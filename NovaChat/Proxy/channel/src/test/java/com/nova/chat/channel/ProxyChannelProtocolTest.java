package com.nova.chat.channel;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip and hostile-input coverage for the shared channel envelope.
 *
 * <p>The packet body must stay byte-compatible with the existing NovaProtocol
 * codec, so these tests decode through {@link ProxyChannelProtocol} and compare
 * fields on the real packet classes rather than on a test-only representation.
 */
@DisplayName("ProxyChannelProtocol envelope")
class ProxyChannelProtocolTest {

    private static final String SERVER_ID = "survival-01";

    @Test
    @DisplayName("a chat packet round-trips with its request id, sender id and fields intact")
    void chatRoundTrip() throws Exception {
        UUID senderId = UUID.randomUUID();
        ChatMessagePacket packet = new ChatMessagePacket(
                senderId, "Alice", SERVER_ID, "global", "hello");
        packet.addPlaceholder("server", "survival-01");
        UUID requestId = UUID.randomUUID();
        packet.setRequestId(requestId);

        byte[] payload = ProxyChannelProtocol.encode(SERVER_ID, packet);
        ProxyChannelProtocol.Decoded decoded = ProxyChannelProtocol.decode(payload);

        assertThat(decoded.getSenderId()).isEqualTo(SERVER_ID);
        assertThat(decoded.getExcludedRecipients()).isEmpty();
        assertThat(decoded.getPacket()).isInstanceOf(ChatMessagePacket.class);

        ChatMessagePacket roundTripped = (ChatMessagePacket) decoded.getPacket();
        assertThat(roundTripped.getRequestId()).isEqualTo(requestId);
        assertThat(roundTripped.getSenderId()).isEqualTo(senderId);
        assertThat(roundTripped.getSenderName()).isEqualTo("Alice");
        assertThat(roundTripped.getClientId()).isEqualTo(SERVER_ID);
        assertThat(roundTripped.getChannelId()).isEqualTo("global");
        assertThat(roundTripped.getContent()).isEqualTo("hello");
        assertThat(roundTripped.getPlaceholders()).containsEntry("server", "survival-01");
    }

    @Test
    @DisplayName("downstream exclusions survive the round trip in order-insensitive form")
    void excludedRecipientsRoundTrip() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        PrivateMessagePacket packet = new PrivateMessagePacket(
                UUID.randomUUID(), "Carol", "proxy", "Bob", null, "psst",
                System.currentTimeMillis());

        byte[] payload = ProxyChannelProtocol.encode("proxy", packet, List.of(alice, bob));
        ProxyChannelProtocol.Decoded decoded = ProxyChannelProtocol.decode(payload);

        assertThat(decoded.getSenderId()).isEqualTo("proxy");
        assertThat(decoded.getExcludedRecipients())
                .containsExactlyInAnyOrder(alice, bob);
        assertThat(decoded.getPacket()).isInstanceOf(PrivateMessagePacket.class);
    }

    @Test
    @DisplayName("a payload with the wrong magic byte is rejected")
    void wrongMagicRejected() throws Exception {
        byte[] payload = ProxyChannelProtocol.encode(SERVER_ID, chat("hi"));
        payload[0] = 'X';

        assertThatThrownBy(() -> ProxyChannelProtocol.decode(payload))
                .isInstanceOf(ProxyChannelException.class)
                .hasMessageContaining("magic");
    }

    @Test
    @DisplayName("an unknown envelope version is rejected")
    void unknownVersionRejected() throws Exception {
        byte[] payload = ProxyChannelProtocol.encode(SERVER_ID, chat("hi"));
        payload[1] = 99;

        assertThatThrownBy(() -> ProxyChannelProtocol.decode(payload))
                .isInstanceOf(ProxyChannelException.class)
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("a truncated payload never escapes as an unchecked exception")
    void truncatedPayloadRejected() throws Exception {
        byte[] payload = ProxyChannelProtocol.encode(SERVER_ID, chat("hi"));

        for (int length : new int[]{1, 2, 3, 5, payload.length - 1}) {
            byte[] truncated = java.util.Arrays.copyOf(payload, length);
            assertThatThrownBy(() -> ProxyChannelProtocol.decode(truncated))
                    .as("truncated to %d bytes", length)
                    .isInstanceOf(ProxyChannelException.class);
        }
    }

    @Test
    @DisplayName("trailing bytes after the envelope are rejected instead of ignored")
    void trailingBytesRejected() throws Exception {
        byte[] payload = ProxyChannelProtocol.encode(SERVER_ID, chat("hi"));
        byte[] extended = java.util.Arrays.copyOf(payload, payload.length + 1);
        extended[payload.length] = 0x7F;

        assertThatThrownBy(() -> ProxyChannelProtocol.decode(extended))
                .isInstanceOf(ProxyChannelException.class)
                .hasMessageContaining("trailing");
    }

    @Test
    @DisplayName("an unregistered packet id is rejected")
    void unknownPacketIdRejected() throws Exception {
        byte[] payload = ProxyChannelProtocol.encode(SERVER_ID, chat("hi"));
        // Envelope: magic, version, sender string, packet length, then the id.
        int idIndex = packetIdOffset(payload);
        payload[idIndex] = 0x7E; // not registered by NovaProtocol

        assertThatThrownBy(() -> ProxyChannelProtocol.decode(payload))
                .isInstanceOf(ProxyChannelException.class)
                .hasMessageContaining("unregistered");
    }

    @Test
    @DisplayName("a payload above the channel cap is rejected before parsing")
    void oversizedPayloadRejected() {
        byte[] payload = new byte[ProxyChannelProtocol.MAX_PAYLOAD_BYTES + 1];

        assertThatThrownBy(() -> ProxyChannelProtocol.decode(payload))
                .isInstanceOf(ProxyChannelException.class)
                .hasMessageContaining("at most");
    }

    @Test
    @DisplayName("encoding a packet whose envelope exceeds the cap fails explicitly")
    void encodeOversizedFails() {
        ChatMessagePacket packet = chat("x".repeat(ProxyChannelProtocol.MAX_PAYLOAD_BYTES));

        assertThatThrownBy(() -> ProxyChannelProtocol.encode(SERVER_ID, packet))
                .isInstanceOf(ProxyChannelException.class)
                .hasMessageContaining("at most");
    }

    @Test
    @DisplayName("a sender id above the client-id budget is rejected")
    void oversizedSenderIdRejected() {
        String id = "x".repeat(ProxyChannelProtocol.MAX_SENDER_ID_BYTES + 1);

        assertThatThrownBy(() -> ProxyChannelProtocol.encode(id, chat("hi")))
                .isInstanceOf(ProxyChannelException.class)
                .hasMessageContaining("sender id");
    }

    @Test
    @DisplayName("wrong channel id constant is the documented namespace:path form")
    void channelIdShape() {
        assertThat(ProxyChannelProtocol.CHANNEL_ID).isEqualTo("novachat:main");
        assertThat(ProxyChannelProtocol.CHANNEL_ID.split(":", -1)).hasSize(2);
    }

    private static ChatMessagePacket chat(String content) {
        return new ChatMessagePacket(UUID.randomUUID(), "Alice", SERVER_ID, "global", content);
    }

    /** Offsets past magic (1) + version (1) + sender string + packet length. */
    private static int packetIdOffset(byte[] payload) {
        int index = 2;
        // VarInt sender length
        int shift = 0;
        int senderLength = 0;
        byte current;
        do {
            current = payload[index++];
            senderLength |= (current & 0x7F) << shift;
            shift += 7;
        } while ((current & 0x80) != 0);
        index += senderLength;
        // VarInt packet length
        do {
            current = payload[index++];
        } while ((current & 0x80) != 0);
        return index;
    }
}
