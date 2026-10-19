package com.nova.chat.channel;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketBuffer;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.ProtocolLimits;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Wire format of the {@code novachat:main} plugin-messaging channel shared by
 * the proxy plugins and the Bukkit sub-server plugin.
 *
 * <p><b>Why an envelope.</b> Plugin messaging hands each side a raw
 * {@code byte[]}, while NovaProtocol already has a complete, audited packet
 * codec ({@link PacketRegistry#decode(ByteBuf)} /
 * {@link PacketRegistry#encode(Packet, ByteBuf)}). The payload therefore wraps
 * the <em>unchanged</em> NovaProtocol bytes in a minimal routing envelope — the
 * packet body is never re-invented:
 *
 * <pre>
 * | magic 'N' (1) | version (1) |
 * | senderId (VarInt byte-length + UTF-8) |
 * | packetLength (VarInt) | packet bytes (PacketRegistry#encode) |
 * | excludedCount (VarInt) | excluded UUIDs (16 bytes each) |
 * </pre>
 *
 * <p><b>{@code senderId}.</b> Upstream (sub-server → proxy) it is the
 * sub-server's own id and the proxy MUST verify it against the server the
 * message actually arrived from, so one downstream cannot impersonate another.
 * Downstream (proxy → sub-server) it is the proxy's own identity; sub-servers
 * currently only log it.
 *
 * <p><b>{@code excludedRecipients}.</b> Downstream only. The proxy lists the
 * players it has already rendered the packet to (its own online roster on that
 * server), so the sub-server renders exclusively to players the proxy cannot
 * see — players connected straight to the sub-server, bypassing the proxy.
 * Without it every proxied player would see the line twice. Upstream senders
 * always write an empty list.
 *
 * <p><b>Size limit.</b> Minecraft caps server-bound custom payloads at 32767
 * bytes and some versions are stricter, so {@link #MAX_PAYLOAD_BYTES} is that
 * value for the whole envelope. Oversized payloads are rejected with a
 * {@link ProxyChannelException} and MUST be dropped with a log line; they must
 * never escape as an unchecked exception (a malicious client can send on this
 * channel before the proxy consumes it).
 *
 * <p><b>Ordering / duplicates.</b> Plugin messaging rides the player's
 * connection, which is TCP: messages on one connection are ordered and are not
 * retransmitted by the game layer, so this format carries no sequence number.
 * Duplicate suppression for fan-out is handled by
 * {@link #getExcludedRecipients()}, not by the transport.
 */
public final class ProxyChannelProtocol {

    /**
     * Channel identifier used on both proxies and on the sub-server.
     * Velocity and BungeeCord both accept the {@code namespace:path} form.
     */
    public static final String CHANNEL_ID = "novachat:main";

    /**
     * Hard cap for one envelope, in bytes.
     *
     * <p>32767 (0x7FFF) is the server-bound Minecraft custom-payload limit; the
     * proxy and the sub-server both enforce it before parsing so a hostile peer
     * cannot make either side allocate or parse beyond that. There is no
     * matching constant in {@link ProtocolLimits} (that class covers the Netty
     * backend transport, whose frame ceiling is 4 MiB), so the channel defines
     * its own — and every implementation of this channel must use this one.
     */
    public static final int MAX_PAYLOAD_BYTES = 32767;

    /** Envelope magic; a cheap "is this even ours" check before packet decode. */
    public static final int MAGIC = 0x4E; // 'N'

    /** Envelope version; bump only with a coordinated change on both sides. */
    public static final int VERSION = 1;

    /** Upper bound for the {@code senderId} string (same budget as a client id). */
    public static final int MAX_SENDER_ID_BYTES = ProtocolLimits.MAX_CLIENT_ID;

    /**
     * Upper bound for the downstream exclusion list. The proxy only ever lists
     * players connected through itself on one downstream server, so this is far
     * above any realistic roster while still bounding a hostile count field.
     */
    public static final int MAX_EXCLUDED_RECIPIENTS = 4096;

    /**
     * Shared registry. {@link PacketRegistry} is read-only after construction
     * (all packet classes are registered by {@link NovaProtocol#createRegistry()}),
     * so one instance is safe for concurrent decode/encode and avoids rebuilding
     * the id maps on every plugin message.
     */
    private static final PacketRegistry REGISTRY = NovaProtocol.createRegistry();

    private ProxyChannelProtocol() {
        // Constants + static codec
    }

    /**
     * Encodes one packet with no downstream exclusions.
     *
     * @param senderId the sender's identity
     * @param packet the packet to carry
     * @return the payload to hand to plugin messaging
     * @throws ProxyChannelException when the envelope would exceed the cap
     */
    public static byte[] encode(String senderId, Packet packet) throws ProxyChannelException {
        return encode(senderId, packet, Set.of());
    }

    /**
     * Encodes one packet into the channel envelope.
     *
     * @param senderId the sender's identity (upstream: the sub-server id;
     *                 downstream: the proxy identity)
     * @param packet the packet to carry
     * @param excludedRecipients players the receiver must not render to
     *                           (downstream only; null behaves like empty)
     * @return the payload to hand to plugin messaging
     * @throws ProxyChannelException when a field is invalid or the envelope
     *                               exceeds {@link #MAX_PAYLOAD_BYTES}
     */
    public static byte[] encode(String senderId, Packet packet,
                                Collection<UUID> excludedRecipients)
            throws ProxyChannelException {
        Objects.requireNonNull(packet, "packet");
        String id = senderId != null ? senderId : "";
        int idBytes = id.getBytes(StandardCharsets.UTF_8).length;
        if (idBytes > MAX_SENDER_ID_BYTES) {
            throw new ProxyChannelException("sender id is " + idBytes
                    + " bytes; the channel allows at most " + MAX_SENDER_ID_BYTES);
        }

        Collection<UUID> excluded = excludedRecipients != null ? excludedRecipients : Set.of();
        if (excluded.size() > MAX_EXCLUDED_RECIPIENTS) {
            throw new ProxyChannelException("exclusion list has " + excluded.size()
                    + " entries; the channel allows at most " + MAX_EXCLUDED_RECIPIENTS);
        }

        ByteBuf envelope = Unpooled.buffer();
        ByteBuf body = Unpooled.buffer();
        try {
            REGISTRY.encode(packet, body);
            int packetLength = body.readableBytes();
            if (packetLength <= 0) {
                throw new ProxyChannelException("encoded packet is empty");
            }

            envelope.writeByte(MAGIC);
            envelope.writeByte(VERSION);
            PacketBuffer.writeString(envelope, id);
            PacketBuffer.writeVarInt(envelope, packetLength);
            envelope.writeBytes(body);
            PacketBuffer.writeVarInt(envelope, excluded.size());
            for (UUID recipient : excluded) {
                PacketBuffer.writeUUID(envelope, recipient);
            }

            int total = envelope.readableBytes();
            if (total > MAX_PAYLOAD_BYTES) {
                throw new ProxyChannelException("encoded payload is " + total
                        + " bytes; the channel allows at most " + MAX_PAYLOAD_BYTES);
            }
            byte[] out = new byte[total];
            envelope.readBytes(out);
            return out;
        } finally {
            body.release();
            envelope.release();
        }
    }

    /**
     * Decodes a channel payload. Accepts untrusted bytes: every failure mode
     * (truncation, bad magic, unknown packet id, oversized list, trailing
     * garbage) surfaces as a {@link ProxyChannelException}, never as an
     * unchecked exception.
     *
     * @param payload the raw plugin-message data
     * @return the decoded envelope
     * @throws ProxyChannelException when the payload is not a valid envelope
     */
    public static Decoded decode(byte[] payload) throws ProxyChannelException {
        if (payload == null || payload.length == 0) {
            throw new ProxyChannelException("empty payload");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new ProxyChannelException("payload is " + payload.length
                    + " bytes; the channel allows at most " + MAX_PAYLOAD_BYTES);
        }

        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        try {
            int magic = buf.readUnsignedByte();
            if (magic != MAGIC) {
                throw new ProxyChannelException("bad magic 0x"
                        + Integer.toHexString(magic));
            }
            int version = buf.readUnsignedByte();
            if (version != VERSION) {
                throw new ProxyChannelException("unsupported envelope version " + version);
            }

            String senderId = PacketBuffer.readString(buf, MAX_SENDER_ID_BYTES);

            int packetLength = PacketBuffer.readVarInt(buf);
            if (packetLength <= 0) {
                throw new ProxyChannelException("invalid packet length " + packetLength);
            }
            if (packetLength > buf.readableBytes()) {
                throw new ProxyChannelException("packet length " + packetLength
                        + " exceeds remaining " + buf.readableBytes() + " bytes");
            }

            Packet packet;
            try {
                packet = REGISTRY.decode(buf.readSlice(packetLength));
            } catch (RuntimeException e) {
                throw new ProxyChannelException("malformed packet body: " + e.getMessage(), e);
            }
            if (packet == null) {
                // The registry returned null: the id is not part of NovaProtocol.
                throw new ProxyChannelException("unregistered packet id in channel payload");
            }

            int excludedCount = PacketBuffer.readVarInt(buf);
            if (excludedCount < 0 || excludedCount > MAX_EXCLUDED_RECIPIENTS) {
                throw new ProxyChannelException("invalid exclusion count " + excludedCount);
            }
            Set<UUID> excluded = new LinkedHashSet<>(
                    Math.min(excludedCount, 64));
            for (int i = 0; i < excludedCount; i++) {
                excluded.add(PacketBuffer.readUUID(buf));
            }

            if (buf.isReadable()) {
                throw new ProxyChannelException("trailing bytes after envelope: "
                        + buf.readableBytes());
            }
            return new Decoded(senderId, packet, excluded);
        } catch (ProxyChannelException e) {
            throw e;
        } catch (RuntimeException e) {
            // Netty's index checks and PacketBuffer's length checks both throw
            // unchecked exceptions on truncated input.
            throw new ProxyChannelException("truncated or malformed payload: "
                    + e.getMessage(), e);
        } finally {
            buf.release();
        }
    }

    /** One decoded envelope. */
    public static final class Decoded {

        private final String senderId;
        private final Packet packet;
        private final Set<UUID> excludedRecipients;

        private Decoded(String senderId, Packet packet, Set<UUID> excludedRecipients) {
            this.senderId = senderId;
            this.packet = packet;
            this.excludedRecipients = Set.copyOf(excludedRecipients);
        }

        /**
         * @return the declared sender identity; never null
         */
        public String getSenderId() {
            return senderId;
        }

        /**
         * @return the carried NovaProtocol packet; never null
         */
        public Packet getPacket() {
            return packet;
        }

        /**
         * @return players the receiver must not render to (downstream only;
         *         empty for upstream messages)
         */
        public Set<UUID> getExcludedRecipients() {
            return excludedRecipients;
        }
    }
}
