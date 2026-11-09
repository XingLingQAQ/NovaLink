package com.nova.chat.channel;

import com.nova.chat.common.protocol.ProtocolLimits;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class RenderCoordinationProtocol {
    public static final String CHANNEL_ID = "novachat:render";
    public static final int VERSION = 1;

    private static final int BODY_BYTES = 2 + 1 + 1 + 4 * 16 + Integer.BYTES + 1;
    private static final int MAC_BYTES = 32;
    private static final int FRAME_BYTES = BODY_BYTES + MAC_BYTES;
    private static final int OFFER_OVERHEAD_BYTES = FRAME_BYTES + Integer.BYTES;
    private static final int PART_OVERHEAD_BYTES = FRAME_BYTES + 3 * Integer.BYTES;
    private static final int MAX_PAYLOAD_BYTES = 32767;
    private static final UUID ZERO_UUID = new UUID(0, 0);

    public static final int MAX_PART_DATA_BYTES = MAX_PAYLOAD_BYTES - PART_OVERHEAD_BYTES;

    public enum Kind { PROBE, HELLO, CLAIM, DECISION, CONFIRM, CONFIRMED, OFFER, SKIP, OFFER_PART }

    public record Frame(Kind kind, UUID playerId, UUID proxySession, UUID bukkitSession,
                        UUID requestId, int packetId, boolean proxyOwner) {}

    public record Offer(Frame frame, byte[] envelope) {
        public Offer {
            envelope = envelope.clone();
        }

        @Override
        public byte[] envelope() {
            return envelope.clone();
        }
    }

    public record OfferPart(Frame frame, int totalLength, int offset, byte[] data) {
        public OfferPart {
            data = data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    private RenderCoordinationProtocol() {}

    public static byte[] encode(Frame frame, String secret) throws ProxyChannelException {
        byte[] key = keyBytes(secret);
        validate(frame);
        if (frame.kind() == Kind.OFFER) {
            throw new ProxyChannelException("offer requires an envelope");
        }
        if (frame.kind() == Kind.OFFER_PART) {
            throw new ProxyChannelException("offer part requires encodeOfferPart");
        }
        ByteBuffer buffer = ByteBuffer.allocate(FRAME_BYTES);
        putFrame(buffer, frame);
        buffer.put(mac(buffer.array(), key));
        return buffer.array();
    }

    public static Frame decode(byte[] payload, String secret) throws ProxyChannelException {
        if (payload == null || payload.length != FRAME_BYTES) {
            throw new ProxyChannelException("invalid render frame length");
        }
        Frame frame = getFrame(authenticated(payload, secret));
        if (frame.kind() == Kind.OFFER) {
            throw new ProxyChannelException("offer requires decodeOffer");
        }
        if (frame.kind() == Kind.OFFER_PART) {
            throw new ProxyChannelException("offer part requires decodeOfferPart");
        }
        return frame;
    }

    public static byte[] encodeOffer(Frame frame, byte[] envelope, String secret) throws ProxyChannelException {
        if (envelope == null || envelope.length == 0 || envelope.length > MAX_PAYLOAD_BYTES - OFFER_OVERHEAD_BYTES) {
            throw new ProxyChannelException("invalid render offer length");
        }
        byte[] key = keyBytes(secret);
        validate(frame);
        if (frame.kind() != Kind.OFFER) {
            throw new ProxyChannelException("render offer requires offer kind");
        }
        ByteBuffer buffer = ByteBuffer.allocate(OFFER_OVERHEAD_BYTES + envelope.length);
        putFrame(buffer, frame);
        buffer.putInt(envelope.length).put(envelope);
        buffer.put(mac(buffer.array(), key));
        return buffer.array();
    }

    public static Offer decodeOffer(byte[] payload, String secret) throws ProxyChannelException {
        if (payload == null || payload.length <= OFFER_OVERHEAD_BYTES || payload.length > MAX_PAYLOAD_BYTES) {
            throw new ProxyChannelException("invalid render offer length");
        }
        ByteBuffer buffer = authenticated(payload, secret);
        Frame frame = getFrame(buffer);
        if (frame.kind() != Kind.OFFER) {
            throw new ProxyChannelException("render offer requires offer kind");
        }
        int length = buffer.getInt();
        if (length <= 0 || length != buffer.remaining()) {
            throw new ProxyChannelException("invalid render offer envelope length");
        }
        byte[] envelope = new byte[length];
        buffer.get(envelope);
        return new Offer(frame, envelope);
    }

    public static byte[] encodeOfferPart(Frame frame, int totalLength, int offset, byte[] data, String secret)
            throws ProxyChannelException {
        if (data == null) {
            throw new ProxyChannelException("invalid render offer part data");
        }
        validatePartBounds(totalLength, offset, data.length);
        byte[] key = keyBytes(secret);
        validate(frame);
        if (frame.kind() != Kind.OFFER_PART) {
            throw new ProxyChannelException("render offer part requires offer part kind");
        }
        ByteBuffer buffer = ByteBuffer.allocate(PART_OVERHEAD_BYTES + data.length);
        putFrame(buffer, frame);
        buffer.putInt(totalLength).putInt(offset).putInt(data.length).put(data);
        buffer.put(mac(buffer.array(), key));
        return buffer.array();
    }

    public static OfferPart decodeOfferPart(byte[] payload, String secret) throws ProxyChannelException {
        if (payload == null || payload.length <= PART_OVERHEAD_BYTES || payload.length > MAX_PAYLOAD_BYTES) {
            throw new ProxyChannelException("invalid render offer part length");
        }
        ByteBuffer buffer = authenticated(payload, secret);
        Frame frame = getFrame(buffer);
        if (frame.kind() != Kind.OFFER_PART) {
            throw new ProxyChannelException("render offer part requires offer part kind");
        }
        int totalLength = buffer.getInt();
        int offset = buffer.getInt();
        int length = buffer.getInt();
        if (length != buffer.remaining()) {
            throw new ProxyChannelException("invalid render offer part data length");
        }
        validatePartBounds(totalLength, offset, length);
        byte[] data = new byte[length];
        buffer.get(data);
        return new OfferPart(frame, totalLength, offset, data);
    }

    private static void validatePartBounds(int totalLength, int offset, int length) throws ProxyChannelException {
        if (totalLength < 1 || totalLength > ProtocolLimits.MAX_FRAME_LENGTH || offset < 0
                || length < 1 || length > MAX_PART_DATA_BYTES || offset > totalLength - length) {
            throw new ProxyChannelException("invalid render offer part bounds");
        }
    }

    private static void putFrame(ByteBuffer buffer, Frame frame) {
        buffer.put((byte) 'N').put((byte) 'R').put((byte) VERSION).put((byte) frame.kind().ordinal());
        putUuid(buffer, frame.playerId());
        putUuid(buffer, frame.proxySession());
        putUuid(buffer, frame.bukkitSession());
        putUuid(buffer, frame.requestId());
        buffer.putInt(frame.packetId()).put((byte) (frame.proxyOwner() ? 1 : 0));
    }

    private static Frame getFrame(ByteBuffer buffer) throws ProxyChannelException {
        if (buffer.get() != 'N' || buffer.get() != 'R') {
            throw new ProxyChannelException("invalid render frame magic");
        }
        if (Byte.toUnsignedInt(buffer.get()) != VERSION) {
            throw new ProxyChannelException("unsupported render frame version");
        }
        int kind = Byte.toUnsignedInt(buffer.get());
        if (kind >= Kind.values().length) {
            throw new ProxyChannelException("invalid render frame kind");
        }
        UUID playerId = getUuid(buffer);
        UUID proxySession = getUuid(buffer);
        UUID bukkitSession = getUuid(buffer);
        UUID requestId = getUuid(buffer);
        int packetId = buffer.getInt();
        byte owner = buffer.get();
        if (owner != 0 && owner != 1) {
            throw new ProxyChannelException("invalid render frame owner flag");
        }
        Frame frame = new Frame(Kind.values()[kind], playerId, proxySession, bukkitSession,
                requestId, packetId, owner == 1);
        validate(frame);
        return frame;
    }

    private static void validate(Frame frame) throws ProxyChannelException {
        if (frame == null || frame.kind() == null || isZeroOrNull(frame.playerId())
                || isZeroOrNull(frame.proxySession()) || frame.bukkitSession() == null
                || frame.requestId() == null) {
            throw new ProxyChannelException("invalid render frame identifiers");
        }
        if (frame.kind() != Kind.DECISION && frame.proxyOwner()) {
            throw new ProxyChannelException("invalid render frame owner flag");
        }
        switch (frame.kind()) {
            case PROBE -> {
                if (!ZERO_UUID.equals(frame.bukkitSession()) || !ZERO_UUID.equals(frame.requestId())
                        || frame.packetId() != 0) {
                    throw new ProxyChannelException("invalid probe frame");
                }
            }
            case HELLO, CONFIRM, CONFIRMED -> {
                if (isZeroOrNull(frame.bukkitSession()) || !ZERO_UUID.equals(frame.requestId())
                        || frame.packetId() != 0) {
                    throw new ProxyChannelException("invalid hello frame");
                }
            }
            case CLAIM, DECISION, OFFER, SKIP, OFFER_PART -> {
                if (isZeroOrNull(frame.bukkitSession()) || isZeroOrNull(frame.requestId())
                        || !supportedPacket(frame.packetId())) {
                    throw new ProxyChannelException("invalid render request frame");
                }
            }
        }
    }

    private static boolean supportedPacket(int packetId) {
        return packetId == 3 || packetId == 9 || packetId == 16 || packetId == 18 || packetId == 20 || packetId == 24;
    }

    private static boolean isZeroOrNull(UUID id) {
        return id == null || ZERO_UUID.equals(id);
    }

    private static void putUuid(ByteBuffer buffer, UUID id) {
        buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static byte[] keyBytes(String secret) throws ProxyChannelException {
        if (secret == null) {
            throw new ProxyChannelException("invalid render secret length");
        }
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32 || key.length > 4096) {
            throw new ProxyChannelException("invalid render secret length");
        }
        return key;
    }

    private static byte[] mac(byte[] bytes, byte[] key) throws ProxyChannelException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(bytes, 0, bytes.length - MAC_BYTES);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new ProxyChannelException("render frame authentication unavailable");
        }
    }

    private static ByteBuffer authenticated(byte[] payload, String secret) throws ProxyChannelException {
        byte[] key = keyBytes(secret);
        byte[] frameBytes = payload.clone();
        int bodyBytes = frameBytes.length - MAC_BYTES;
        byte[] expected = mac(frameBytes, key);
        byte[] actual = Arrays.copyOfRange(frameBytes, bodyBytes, frameBytes.length);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ProxyChannelException("render frame authentication failed");
        }
        return ByteBuffer.wrap(frameBytes, 0, bodyBytes);
    }
}
