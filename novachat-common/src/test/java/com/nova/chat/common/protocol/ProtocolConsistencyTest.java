package com.nova.chat.common.protocol;

import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.packets.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-cutting protocol consistency tests.
 * Validates registry wiring, packet id stability, and full encode/decode cycles
 * for the critical handshake + chat path used by every MC client.
 */
@DisplayName("NovaProtocol consistency")
class ProtocolConsistencyTest {

    @Test
    @DisplayName("PROTOCOL_VERSION is 2 and shared with NovaConstants")
    void protocolVersionAligned() {
        assertThat(NovaProtocol.PROTOCOL_VERSION).isEqualTo(2);
        assertThat(NovaConstants.PROTOCOL_VERSION).isEqualTo(NovaProtocol.PROTOCOL_VERSION);
    }

    @Test
    @DisplayName("createRegistry registers all core packet types")
    void registryContainsCorePackets() {
        PacketRegistry registry = NovaProtocol.createRegistry();

        int[] expected = {
                PacketIds.HANDSHAKE,
                PacketIds.HANDSHAKE_RESPONSE,
                PacketIds.CHAT_MESSAGE,
                PacketIds.CHANNEL_ACTION,
                PacketIds.CHANNEL_ACTION_RESPONSE,
                PacketIds.CONFIG_SYNC,
                PacketIds.KEEP_ALIVE,
                PacketIds.TITLE,
                PacketIds.ADMIN_ACTION,
                PacketIds.ADMIN_ACTION_RESPONSE,
                PacketIds.ITEM_DISPLAY,
                PacketIds.MENTION
        };

        for (int id : expected) {
            assertThat(registry.isRegistered(id))
                    .as("packet id 0x%02X should be registered", id)
                    .isTrue();
            assertThat(registry.createPacket(id))
                    .as("factory for 0x%02X should produce non-null", id)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("duplicate registration is rejected")
    void duplicateRegistrationRejected() {
        PacketRegistry registry = new PacketRegistry();
        registry.register(PacketIds.HANDSHAKE, HandshakePacket.class, HandshakePacket::new);
        assertThatThrownBy(() ->
                registry.register(PacketIds.HANDSHAKE, HandshakePacket.class, HandshakePacket::new))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already registered");
    }

    @Nested
    @DisplayName("Handshake round-trip for every platform")
    class Handshake {

        @ParameterizedTest(name = "{0}")
        @EnumSource(PlatformType.class)
        @DisplayName("encode/decode preserves platform and credentials")
        void handshakePerPlatform(PlatformType platform) {
            PacketRegistry registry = NovaProtocol.createRegistry();
            HandshakePacket original = new HandshakePacket(
                    NovaProtocol.PROTOCOL_VERSION,
                    "Server_" + platform.name(),
                    "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                    platform
            );
            UUID requestId = UUID.randomUUID();
            original.setRequestId(requestId);

            ByteBuf buf = Unpooled.buffer();
            try {
                registry.encode(original, buf);
                Packet decoded = registry.decode(buf);
                assertThat(decoded).isInstanceOf(HandshakePacket.class);
                HandshakePacket result = (HandshakePacket) decoded;

                assertThat(result.getRequestId()).isEqualTo(requestId);
                assertThat(result.getProtocolVersion()).isEqualTo(NovaProtocol.PROTOCOL_VERSION);
                assertThat(result.getClientId()).isEqualTo(original.getClientId());
                assertThat(result.getPasswordHash()).isEqualTo(original.getPasswordHash());
                assertThat(result.getPlatform()).isEqualTo(platform);
                assertThat(buf.readableBytes()).isZero();
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("null optional fields on handshake do not NPE during encode")
        void nullFieldsDoNotNpe() {
            HandshakePacket packet = new HandshakePacket();
            packet.setProtocolVersion(1);
            // clientId / passwordHash / platform left null
            ByteBuf buf = Unpooled.buffer();
            try {
                assertThatCode(() -> packet.write(buf)).doesNotThrowAnyException();
            } finally {
                buf.release();
            }
        }
    }

    @Nested
    @DisplayName("ChatMessage round-trip")
    class ChatMessage {

        @Test
        @DisplayName("full message with placeholders survives encode/decode")
        void fullMessageWithPlaceholders() {
            PacketRegistry registry = NovaProtocol.createRegistry();
            UUID sender = UUID.randomUUID();
            ChatMessagePacket original = new ChatMessagePacket(
                    sender, "Steve", "Survival_Server", "global", "Hello @Alex"
            );
            original.addPlaceholder("player_level", "42");
            original.addPlaceholder("world", "world_nether");

            ByteBuf buf = Unpooled.buffer();
            try {
                registry.encode(original, buf);
                ChatMessagePacket result = (ChatMessagePacket) registry.decode(buf);

                assertThat(result.getSenderId()).isEqualTo(sender);
                assertThat(result.getSenderName()).isEqualTo("Steve");
                assertThat(result.getClientId()).isEqualTo("Survival_Server");
                assertThat(result.getChannelId()).isEqualTo("global");
                assertThat(result.getContent()).isEqualTo("Hello @Alex");
                assertThat(result.getPlaceholders())
                        .containsEntry("player_level", "42")
                        .containsEntry("world", "world_nether");
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("null content/name fields encode as empty strings")
        void nullFieldsEncodeAsEmpty() {
            ChatMessagePacket packet = new ChatMessagePacket();
            packet.setSenderId(null);
            packet.setSenderName(null);
            packet.setClientId(null);
            packet.setChannelId(null);
            packet.setContent(null);
            packet.setPlaceholders(null);

            ByteBuf buf = Unpooled.buffer();
            try {
                assertThatCode(() -> packet.write(buf)).doesNotThrowAnyException();
                ChatMessagePacket result = new ChatMessagePacket();
                // Skip request id – call read() on payload only after manual header simulation
                buf.resetReaderIndex();
                // write() only wrote payload; read payload directly
                ByteBuf payload = Unpooled.buffer();
                try {
                    packet.write(payload);
                    result.read(payload);
                    assertThat(result.getSenderName()).isEmpty();
                    assertThat(result.getClientId()).isEmpty();
                    assertThat(result.getChannelId()).isEmpty();
                    assertThat(result.getContent()).isEmpty();
                    assertThat(result.getPlaceholders()).isEmpty();
                } finally {
                    payload.release();
                }
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("CJK content round-trips without corruption")
        void cjkContent() {
            PacketRegistry registry = NovaProtocol.createRegistry();
            ChatMessagePacket original = new ChatMessagePacket(
                    UUID.randomUUID(), "玩家甲", "生存服", "全服", "大家好！欢迎来到服务器。"
            );

            ByteBuf buf = Unpooled.buffer();
            try {
                registry.encode(original, buf);
                ChatMessagePacket result = (ChatMessagePacket) registry.decode(buf);
                assertThat(result.getContent()).isEqualTo("大家好！欢迎来到服务器。");
                assertThat(result.getSenderName()).isEqualTo("玩家甲");
            } finally {
                buf.release();
            }
        }

        @Test
        @DisplayName("many placeholders stay within defensive size limit")
        void manyPlaceholders() {
            PacketRegistry registry = NovaProtocol.createRegistry();
            ChatMessagePacket original = new ChatMessagePacket(
                    UUID.randomUUID(), "Steve", "s1", "local", "msg"
            );
            Map<String, String> placeholders = new LinkedHashMap<>();
            for (int i = 0; i < 50; i++) {
                placeholders.put("k" + i, "v" + i);
            }
            original.setPlaceholders(placeholders);

            ByteBuf buf = Unpooled.buffer();
            try {
                registry.encode(original, buf);
                ChatMessagePacket result = (ChatMessagePacket) registry.decode(buf);
                assertThat(result.getPlaceholders()).hasSize(50);
            } finally {
                buf.release();
            }
        }
    }

    @Nested
    @DisplayName("KeepAlive")
    class KeepAlive {

        @Test
        @DisplayName("timestamp round-trips")
        void timestampRoundTrip() {
            PacketRegistry registry = NovaProtocol.createRegistry();
            KeepAlivePacket original = new KeepAlivePacket();
            long ts = System.currentTimeMillis();
            original.setTimestamp(ts);

            ByteBuf buf = Unpooled.buffer();
            try {
                registry.encode(original, buf);
                KeepAlivePacket result = (KeepAlivePacket) registry.decode(buf);
                assertThat(result.getTimestamp()).isEqualTo(ts);
            } finally {
                buf.release();
            }
        }
    }
}
