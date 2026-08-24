package com.nova.chat.common.protocol;

import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PROTO-003 field-level string length boundary tests.
 *
 * <p>For a representative set of packets, encodes a bounded string field at
 * {@code max-1}, {@code max} (exact boundary) and {@code max+1}, and asserts
 * that {@code max+1} is rejected by the bounded {@code readString(buf, max)}
 * call while {@code max} and {@code max-1} round-trip successfully.
 *
 * <p>This complements the frame-level {@link codec.FrameLimitBoundaryTest}
 * (PROTO-002): the frame ceiling protects the whole packet; these per-field
 * limits ensure a single field cannot approach the frame ceiling.
 *
 * <p>Strategy: each case writes the packet envelope (packet id + request id) +
 * the payload fields up to and including the target field, then calls
 * {@code packet.decode(buf)} (which reads the envelope) and lets
 * {@code packet.read(buf)} consume the payload. The target field's VarInt
 * length is set to {@code max-1}, {@code max} or {@code max+1} followed by
 * that many bytes, so the {@code max+1} case must throw
 * {@link IllegalArgumentException} containing {@code "exceeds maximum"}.
 *
 * <p>All byte counts are UTF-8 bytes (one byte per {@code 'a'}), so the
 * on-wire length equals the string length.
 */
@DisplayName("PROTO-003 per-field string length limits")
class StringFieldLimitTest {

    private static final byte CONFIG_SYNC_ID = 0x06;
    private static final byte CHANNEL_ACTION_RESPONSE_ID = 0x05;
    private static final byte TITLE_ID = 0x09;
    private static final byte PRIVATE_MESSAGE_ID = 0x14;
    private static final byte ITEM_DISPLAY_ID = 0x10;

    /**
     * Builds an ASCII string of exactly {@code byteLength} UTF-8 bytes (one
     * byte per char), so the on-wire length equals {@code byteLength}.
     */
    private static String bytes(int byteLength) {
        return "a".repeat(byteLength);
    }

    /** Writes the packet envelope (packet id byte + 16-byte request id). */
    private static void writeEnvelope(ByteBuf buf, int packetId) {
        buf.writeByte(packetId);
        UUID id = new UUID(1L, 2L);
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    /** Writes a VarInt-prefixed string of exactly {@code byteLength} 'a' bytes. */
    private static void writeStringField(ByteBuf buf, int byteLength) {
        String s = bytes(byteLength);
        byte[] data = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        VarInt.writeVarInt(buf, data.length);
        buf.writeBytes(data);
    }

    /** Decodes a packet of the given type from {@code buf}, asserting the class. */
    private static <T extends Packet> T decode(ByteBuf buf, Class<T> type) {
        // PacketRegistry.decode reads the packet id byte, instantiates the
        // right Packet subclass, then calls decode() which reads the request
        // id and read() (the payload).
        PacketRegistry registry = NovaProtocol.createRegistry();
        Packet decoded = registry.decode(buf);
        assertThat(decoded).isInstanceOf(type);
        return type.cast(decoded);
    }

    // ========================================================================
    // ConfigSyncPacket — configJson bounded by MAX_CONFIG_SYNC_JSON (2 MiB)
    // ========================================================================

    @Nested
    @DisplayName("ConfigSyncPacket.configJson bounded by MAX_CONFIG_SYNC_JSON")
    class ConfigSyncConfigJson {

        // ConfigSync payload: String configJson, long timestamp.
        private void writePayloadWithConfigJson(ByteBuf buf, int jsonLen) {
            writeStringField(buf, jsonLen);
            buf.writeLong(42L);
        }

        @Test
        @DisplayName("max-1 bytes round-trips")
        void belowMaxSucceeds() {
            int n = ProtocolLimits.MAX_CONFIG_SYNC_JSON - 1;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, CONFIG_SYNC_ID);
                writePayloadWithConfigJson(buf, n);
                ConfigSyncPacket result = decode(buf, ConfigSyncPacket.class);
                assertThat(result.getConfigJson()).hasSize(n);
                assertThat(result.getTimestamp()).isEqualTo(42L);
                assertThat(buf.readableBytes()).isZero();
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("exact MAX_CONFIG_SYNC_JSON bytes round-trips (boundary)")
        void atMaxSucceeds() {
            int n = ProtocolLimits.MAX_CONFIG_SYNC_JSON;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, CONFIG_SYNC_ID);
                writePayloadWithConfigJson(buf, n);
                ConfigSyncPacket result = decode(buf, ConfigSyncPacket.class);
                assertThat(result.getConfigJson()).hasSize(n);
                assertThat(buf.readableBytes()).isZero();
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("MAX_CONFIG_SYNC_JSON + 1 bytes is rejected")
        void aboveMaxRejected() {
            int n = ProtocolLimits.MAX_CONFIG_SYNC_JSON + 1;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, CONFIG_SYNC_ID);
                writePayloadWithConfigJson(buf, n);
                assertThatThrownBy(() -> decode(buf, ConfigSyncPacket.class))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("exceeds maximum")
                        .hasMessageContaining(String.valueOf(ProtocolLimits.MAX_CONFIG_SYNC_JSON));
            } finally {
                buf.release();
            }
        }
    }

    // ========================================================================
    // ChannelActionResponsePacket — channelId bounded by MAX_CHANNEL_ID (64)
    // ========================================================================

    @Nested
    @DisplayName("ChannelActionResponsePacket.channelId bounded by MAX_CHANNEL_ID")
    class ChannelActionResponseChannelId {

        // Payload: boolean success, byte action, String channelId, String errorCode,
        // String message, varint extraMapSize, (key,value)*.
        // channelId is the first string; all trailing fields must be present
        // so read() consumes the whole payload.
        private void writePayloadWithChannelId(ByteBuf buf, int channelIdLen) {
            buf.writeBoolean(true);
            buf.writeByte(ChannelAction.JOIN.getId());
            writeStringField(buf, channelIdLen);
            writeStringField(buf, 0);  // errorCode ""
            writeStringField(buf, 0);  // message ""
            VarInt.writeVarInt(buf, 0); // empty extra map
        }

        @Test
        @DisplayName("max-1 bytes round-trips")
        void belowMaxSucceeds() {
            int n = ProtocolLimits.MAX_CHANNEL_ID - 1;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, CHANNEL_ACTION_RESPONSE_ID);
                writePayloadWithChannelId(buf, n);
                ChannelActionResponsePacket result =
                        decode(buf, ChannelActionResponsePacket.class);
                assertThat(result.getChannelId()).hasSize(n);
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("exact MAX_CHANNEL_ID bytes round-trips (boundary)")
        void atMaxSucceeds() {
            int n = ProtocolLimits.MAX_CHANNEL_ID;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, CHANNEL_ACTION_RESPONSE_ID);
                writePayloadWithChannelId(buf, n);
                ChannelActionResponsePacket result =
                        decode(buf, ChannelActionResponsePacket.class);
                assertThat(result.getChannelId()).hasSize(n);
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("MAX_CHANNEL_ID + 1 bytes is rejected")
        void aboveMaxRejected() {
            int n = ProtocolLimits.MAX_CHANNEL_ID + 1;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, CHANNEL_ACTION_RESPONSE_ID);
                writePayloadWithChannelId(buf, n);
                assertThatThrownBy(() -> decode(buf, ChannelActionResponsePacket.class))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("exceeds maximum")
                        .hasMessageContaining(String.valueOf(ProtocolLimits.MAX_CHANNEL_ID));
            } finally {
                buf.release();
            }
        }
    }

    // ========================================================================
    // TitlePacket — title bounded by MAX_TITLE (512)
    // ========================================================================

    @Nested
    @DisplayName("TitlePacket.title bounded by MAX_TITLE")
    class TitleTitle {

        // Payload: String channelId, String title, String subtitle,
        // int fadeIn, int stay, int fadeOut, uuid senderId.
        // All trailing fields must be present so read() consumes the whole
        // payload and the round-trip assertions can verify the title length.
        private void writePayloadWithTitle(ByteBuf buf, int titleLen) {
            writeStringField(buf, 6);  // channelId "global"
            writeStringField(buf, titleLen);
            writeStringField(buf, 0);  // subtitle ""
            buf.writeInt(10);         // fadeIn
            buf.writeInt(70);          // stay
            buf.writeInt(20);         // fadeOut
            UUID senderId = new UUID(1L, 2L);
            buf.writeLong(senderId.getMostSignificantBits());
            buf.writeLong(senderId.getLeastSignificantBits());
        }

        @Test
        @DisplayName("max-1 bytes round-trips")
        void belowMaxSucceeds() {
            int n = ProtocolLimits.MAX_TITLE - 1;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, TITLE_ID);
                writePayloadWithTitle(buf, n);
                TitlePacket result = decode(buf, TitlePacket.class);
                assertThat(result.getTitle()).hasSize(n);
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("exact MAX_TITLE bytes round-trips (boundary)")
        void atMaxSucceeds() {
            int n = ProtocolLimits.MAX_TITLE;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, TITLE_ID);
                writePayloadWithTitle(buf, n);
                TitlePacket result = decode(buf, TitlePacket.class);
                assertThat(result.getTitle()).hasSize(n);
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("MAX_TITLE + 1 bytes is rejected")
        void aboveMaxRejected() {
            int n = ProtocolLimits.MAX_TITLE + 1;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, TITLE_ID);
                writePayloadWithTitle(buf, n);
                assertThatThrownBy(() -> decode(buf, TitlePacket.class))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("exceeds maximum")
                        .hasMessageContaining(String.valueOf(ProtocolLimits.MAX_TITLE));
            } finally {
                buf.release();
            }
        }
    }

    // ========================================================================
    // PrivateMessagePacket — content bounded by MAX_MESSAGE_CONTENT (2048)
    // ========================================================================

    @Nested
    @DisplayName("PrivateMessagePacket.content bounded by MAX_MESSAGE_CONTENT")
    class PrivateMessageContent {

        // Payload: uuid senderId, String senderName, String senderClientId,
        // String targetName, uuid targetId, String content, long timestamp.
        // content is the 4th string; all preceding fields must be valid.
        private void writePayloadWithContent(ByteBuf buf, int contentLen) {
            UUID senderId = new UUID(1L, 2L);
            buf.writeLong(senderId.getMostSignificantBits());
            buf.writeLong(senderId.getLeastSignificantBits());
            writeStringField(buf, 5);  // senderName "Alice"
            writeStringField(buf, 5);  // senderClientId "srv-1"
            writeStringField(buf, 3);  // targetName "Bob"
            UUID targetId = new UUID(0, 0);
            buf.writeLong(targetId.getMostSignificantBits());
            buf.writeLong(targetId.getLeastSignificantBits());
            writeStringField(buf, contentLen);
            buf.writeLong(0L);
        }

        @Test
        @DisplayName("max-1 bytes round-trips")
        void belowMaxSucceeds() {
            int n = ProtocolLimits.MAX_MESSAGE_CONTENT - 1;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, PRIVATE_MESSAGE_ID);
                writePayloadWithContent(buf, n);
                PrivateMessagePacket result =
                        decode(buf, PrivateMessagePacket.class);
                assertThat(result.getContent()).hasSize(n);
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("exact MAX_MESSAGE_CONTENT bytes round-trips (boundary)")
        void atMaxSucceeds() {
            int n = ProtocolLimits.MAX_MESSAGE_CONTENT;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, PRIVATE_MESSAGE_ID);
                writePayloadWithContent(buf, n);
                PrivateMessagePacket result =
                        decode(buf, PrivateMessagePacket.class);
                assertThat(result.getContent()).hasSize(n);
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("MAX_MESSAGE_CONTENT + 1 bytes is rejected")
        void aboveMaxRejected() {
            int n = ProtocolLimits.MAX_MESSAGE_CONTENT + 1;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, PRIVATE_MESSAGE_ID);
                writePayloadWithContent(buf, n);
                assertThatThrownBy(() -> decode(buf, PrivateMessagePacket.class))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("exceeds maximum")
                        .hasMessageContaining(String.valueOf(ProtocolLimits.MAX_MESSAGE_CONTENT));
            } finally {
                buf.release();
            }
        }
    }

    // ========================================================================
    // ItemDisplayPacket — itemJson bounded by MAX_ITEM_JSON (8192)
    // ========================================================================

    @Nested
    @DisplayName("ItemDisplayPacket.itemJson bounded by MAX_ITEM_JSON")
    class ItemDisplayItemJson {

        // Payload: uuid senderId, String senderName, String channelId,
        // String itemJson, long timestamp. itemJson is the 3rd string.
        private void writePayloadWithItemJson(ByteBuf buf, int itemJsonLen) {
            UUID senderId = new UUID(1L, 2L);
            buf.writeLong(senderId.getMostSignificantBits());
            buf.writeLong(senderId.getLeastSignificantBits());
            writeStringField(buf, 6);  // senderName "Sender"
            writeStringField(buf, 6);  // channelId "global"
            writeStringField(buf, itemJsonLen);
            buf.writeLong(0L);
        }

        @Test
        @DisplayName("max-1 bytes round-trips")
        void belowMaxSucceeds() {
            int n = ProtocolLimits.MAX_ITEM_JSON - 1;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, ITEM_DISPLAY_ID);
                writePayloadWithItemJson(buf, n);
                ItemDisplayPacket result = decode(buf, ItemDisplayPacket.class);
                assertThat(result.getItemJson()).hasSize(n);
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("exact MAX_ITEM_JSON bytes round-trips (boundary)")
        void atMaxSucceeds() {
            int n = ProtocolLimits.MAX_ITEM_JSON;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, ITEM_DISPLAY_ID);
                writePayloadWithItemJson(buf, n);
                ItemDisplayPacket result = decode(buf, ItemDisplayPacket.class);
                assertThat(result.getItemJson()).hasSize(n);
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("MAX_ITEM_JSON + 1 bytes is rejected")
        void aboveMaxRejected() {
            int n = ProtocolLimits.MAX_ITEM_JSON + 1;
            ByteBuf buf = Unpooled.buffer();
            try {
                writeEnvelope(buf, ITEM_DISPLAY_ID);
                writePayloadWithItemJson(buf, n);
                assertThatThrownBy(() -> decode(buf, ItemDisplayPacket.class))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("exceeds maximum")
                        .hasMessageContaining(String.valueOf(ProtocolLimits.MAX_ITEM_JSON));
            } finally {
                buf.release();
            }
        }
    }
}
