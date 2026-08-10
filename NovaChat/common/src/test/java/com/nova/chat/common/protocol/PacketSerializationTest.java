package com.nova.chat.common.protocol;

import com.nova.chat.common.protocol.packets.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for packet serialization/deserialization.
 * Tests each packet type for correct round-trip behavior.
 * 
 * _Requirements: 21.2_
 */
class PacketSerializationTest {

    private PacketRegistry registry;
    private ByteBuf buf;

    @BeforeEach
    void setUp() {
        registry = NovaProtocol.createRegistry();
        buf = Unpooled.buffer();
    }

    @AfterEach
    void tearDown() {
        if (buf != null) {
            buf.release();
        }
    }

    // ==================== HandshakePacket Tests ====================

    @Nested
    @DisplayName("HandshakePacket Serialization")
    class HandshakePacketTests {

        @Test
        @DisplayName("Should serialize and deserialize basic handshake")
        void shouldSerializeAndDeserializeBasicHandshake() {
            HandshakePacket original = new HandshakePacket(
                    NovaProtocol.PROTOCOL_VERSION,
                    "TestServer",
                    "sha256hash",
                    PlatformType.BUKKIT
            );

            registry.encode(original, buf);
            Packet decoded = registry.decode(buf);

            assertThat(decoded).isInstanceOf(HandshakePacket.class);
            HandshakePacket result = (HandshakePacket) decoded;

            assertThat(result.getProtocolVersion()).isEqualTo(NovaProtocol.PROTOCOL_VERSION);
            assertThat(result.getClientId()).isEqualTo("TestServer");
            assertThat(result.getPasswordHash()).isEqualTo("sha256hash");
            assertThat(result.getPlatform()).isEqualTo(PlatformType.BUKKIT);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should handle all platform types")
        void shouldHandleAllPlatformTypes() {
            for (PlatformType platform : PlatformType.values()) {
                buf.clear();
                HandshakePacket original = new HandshakePacket(1, "client", "hash", platform);

                registry.encode(original, buf);
                HandshakePacket result = (HandshakePacket) registry.decode(buf);

                assertThat(result.getPlatform()).isEqualTo(platform);
            }
        }

        @Test
        @DisplayName("Should preserve request ID")
        void shouldPreserveRequestId() {
            UUID requestId = UUID.randomUUID();
            HandshakePacket original = new HandshakePacket(requestId);
            original.setProtocolVersion(1);
            original.setClientId("test");
            original.setPasswordHash("hash");
            original.setPlatform(PlatformType.FABRIC);

            registry.encode(original, buf);
            HandshakePacket result = (HandshakePacket) registry.decode(buf);

            assertThat(result.getRequestId()).isEqualTo(requestId);
        }
    }

    // ==================== HandshakeResponsePacket Tests ====================

    @Nested
    @DisplayName("HandshakeResponsePacket Serialization")
    class HandshakeResponsePacketTests {

        @Test
        @DisplayName("Should serialize successful response")
        void shouldSerializeSuccessfulResponse() {
            HandshakeResponsePacket original = HandshakeResponsePacket.success("Welcome!");

            registry.encode(original, buf);
            HandshakeResponsePacket result = (HandshakeResponsePacket) registry.decode(buf);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getErrorCode()).isEmpty();
            assertThat(result.getMessage()).isEqualTo("Welcome!");
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should serialize failure response with error code")
        void shouldSerializeFailureResponseWithErrorCode() {
            HandshakeResponsePacket original = HandshakeResponsePacket.failure("NC-401", "Invalid credentials");

            registry.encode(original, buf);
            HandshakeResponsePacket result = (HandshakeResponsePacket) registry.decode(buf);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("NC-401");
            assertThat(result.getMessage()).isEqualTo("Invalid credentials");
        }

        @Test
        @DisplayName("Should handle empty strings")
        void shouldHandleEmptyStrings() {
            HandshakeResponsePacket original = new HandshakeResponsePacket(true, "", "");

            registry.encode(original, buf);
            HandshakeResponsePacket result = (HandshakeResponsePacket) registry.decode(buf);

            assertThat(result.getErrorCode()).isEmpty();
            assertThat(result.getMessage()).isEmpty();
        }
    }

    // ==================== ChatMessagePacket Tests ====================

    @Nested
    @DisplayName("ChatMessagePacket Serialization")
    class ChatMessagePacketTests {

        @Test
        @DisplayName("Should serialize basic chat message")
        void shouldSerializeBasicChatMessage() {
            UUID senderId = UUID.randomUUID();
            ChatMessagePacket original = new ChatMessagePacket(
                    senderId, "Player1", "Server1", "global", "Hello World!"
            );

            registry.encode(original, buf);
            ChatMessagePacket result = (ChatMessagePacket) registry.decode(buf);

            assertThat(result.getSenderId()).isEqualTo(senderId);
            assertThat(result.getSenderName()).isEqualTo("Player1");
            assertThat(result.getClientId()).isEqualTo("Server1");
            assertThat(result.getChannelId()).isEqualTo("global");
            assertThat(result.getContent()).isEqualTo("Hello World!");
            assertThat(result.getPlaceholders()).isEmpty();
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should serialize message with color codes")
        void shouldSerializeMessageWithColorCodes() {
            ChatMessagePacket original = new ChatMessagePacket(
                    UUID.randomUUID(), "Player", "Server", "chat",
                    "&c[PVP] &7Player&f: &aHello!"
            );

            registry.encode(original, buf);
            ChatMessagePacket result = (ChatMessagePacket) registry.decode(buf);

            assertThat(result.getContent()).isEqualTo("&c[PVP] &7Player&f: &aHello!");
        }

        @Test
        @DisplayName("Should serialize message with hex color codes")
        void shouldSerializeMessageWithHexColorCodes() {
            ChatMessagePacket original = new ChatMessagePacket(
                    UUID.randomUUID(), "Player", "Server", "chat",
                    "&#FF5555Red &#00AA00Green &#0000FFBlue"
            );

            registry.encode(original, buf);
            ChatMessagePacket result = (ChatMessagePacket) registry.decode(buf);

            assertThat(result.getContent()).isEqualTo("&#FF5555Red &#00AA00Green &#0000FFBlue");
        }

        @Test
        @DisplayName("Should serialize message with placeholders")
        void shouldSerializeMessageWithPlaceholders() {
            ChatMessagePacket original = new ChatMessagePacket(
                    UUID.randomUUID(), "Player", "Server", "chat", "Hello"
            );
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player_level", "50");
            placeholders.put("player_rank", "VIP");
            placeholders.put("server_name", "Lobby");
            original.setPlaceholders(placeholders);

            registry.encode(original, buf);
            ChatMessagePacket result = (ChatMessagePacket) registry.decode(buf);

            assertThat(result.getPlaceholders()).hasSize(3);
            assertThat(result.getPlaceholders().get("player_level")).isEqualTo("50");
            assertThat(result.getPlaceholders().get("player_rank")).isEqualTo("VIP");
            assertThat(result.getPlaceholders().get("server_name")).isEqualTo("Lobby");
        }

        @Test
        @DisplayName("Should handle UTF-8 content")
        void shouldHandleUtf8Content() {
            ChatMessagePacket original = new ChatMessagePacket(
                    UUID.randomUUID(), "玩家", "服务器", "聊天",
                    "你好世界！🎮"
            );

            registry.encode(original, buf);
            ChatMessagePacket result = (ChatMessagePacket) registry.decode(buf);

            assertThat(result.getSenderName()).isEqualTo("玩家");
            assertThat(result.getClientId()).isEqualTo("服务器");
            assertThat(result.getChannelId()).isEqualTo("聊天");
            assertThat(result.getContent()).isEqualTo("你好世界！🎮");
        }
    }

    // ==================== ChannelActionPacket Tests ====================

    @Nested
    @DisplayName("ChannelActionPacket Serialization")
    class ChannelActionPacketTests {

        @Test
        @DisplayName("Should serialize JOIN action")
        void shouldSerializeJoinAction() {
            ChannelActionPacket original = new ChannelActionPacket(
                    ChannelAction.JOIN, "global"
            );

            registry.encode(original, buf);
            ChannelActionPacket result = (ChannelActionPacket) registry.decode(buf);

            assertThat(result.getAction()).isEqualTo(ChannelAction.JOIN);
            assertThat(result.getChannelId()).isEqualTo("global");
            assertThat(result.getPassword()).isEmpty();
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should serialize JOIN action with password")
        void shouldSerializeJoinActionWithPassword() {
            ChannelActionPacket original = new ChannelActionPacket(
                    ChannelAction.JOIN, "private-channel", "secret123"
            );

            registry.encode(original, buf);
            ChannelActionPacket result = (ChannelActionPacket) registry.decode(buf);

            assertThat(result.getAction()).isEqualTo(ChannelAction.JOIN);
            assertThat(result.getChannelId()).isEqualTo("private-channel");
            assertThat(result.getPassword()).isEqualTo("secret123");
        }

        @Test
        @DisplayName("Should handle all channel actions")
        void shouldHandleAllChannelActions() {
            for (ChannelAction action : ChannelAction.values()) {
                buf.clear();
                ChannelActionPacket original = new ChannelActionPacket(action, "test-channel");

                registry.encode(original, buf);
                ChannelActionPacket result = (ChannelActionPacket) registry.decode(buf);

                assertThat(result.getAction()).isEqualTo(action);
            }
        }

        @Test
        @DisplayName("Should serialize action with extra data")
        void shouldSerializeActionWithExtraData() {
            ChannelActionPacket original = new ChannelActionPacket(
                    ChannelAction.CREATE, "new-channel"
            );
            Map<String, String> extra = new HashMap<>();
            extra.put("display_name", "New Channel");
            extra.put("scope", "SERVER");
            extra.put("max_capacity", "100");
            original.setExtra(extra);

            registry.encode(original, buf);
            ChannelActionPacket result = (ChannelActionPacket) registry.decode(buf);

            assertThat(result.getExtra()).hasSize(3);
            assertThat(result.getExtra("display_name")).isEqualTo("New Channel");
            assertThat(result.getExtra("scope")).isEqualTo("SERVER");
            assertThat(result.getExtra("max_capacity")).isEqualTo("100");
        }
    }

    // ==================== ChannelActionResponsePacket Tests ====================

    @Nested
    @DisplayName("ChannelActionResponsePacket Serialization")
    class ChannelActionResponsePacketTests {

        @Test
        @DisplayName("Should serialize successful response")
        void shouldSerializeSuccessfulResponse() {
            ChannelActionResponsePacket original = new ChannelActionResponsePacket(
                    true, ChannelAction.JOIN, "global", "", "Joined channel"
            );

            registry.encode(original, buf);
            ChannelActionResponsePacket result = (ChannelActionResponsePacket) registry.decode(buf);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getAction()).isEqualTo(ChannelAction.JOIN);
            assertThat(result.getChannelId()).isEqualTo("global");
            assertThat(result.getErrorCode()).isEmpty();
            assertThat(result.getMessage()).isEqualTo("Joined channel");
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should serialize failure response")
        void shouldSerializeFailureResponse() {
            ChannelActionResponsePacket original = new ChannelActionResponsePacket(
                    false, ChannelAction.JOIN, "private", "NC-403", "Permission denied"
            );

            registry.encode(original, buf);
            ChannelActionResponsePacket result = (ChannelActionResponsePacket) registry.decode(buf);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("NC-403");
            assertThat(result.getMessage()).isEqualTo("Permission denied");
        }

        @Test
        @DisplayName("Should serialize response with extra data")
        void shouldSerializeResponseWithExtraData() {
            ChannelActionResponsePacket original = new ChannelActionResponsePacket(
                    true, ChannelAction.JOIN, "global", "", "Success"
            );
            original.addExtra("channel_count", "5");
            original.addExtra("channels", "global,local,pvp,trade,help");

            registry.encode(original, buf);
            ChannelActionResponsePacket result = (ChannelActionResponsePacket) registry.decode(buf);

            assertThat(result.getExtra("channel_count")).isEqualTo("5");
            assertThat(result.getExtra("channels")).isEqualTo("global,local,pvp,trade,help");
        }
    }

    // ==================== KeepAlivePacket Tests ====================

    @Nested
    @DisplayName("KeepAlivePacket Serialization")
    class KeepAlivePacketTests {

        @Test
        @DisplayName("Should serialize keep-alive with timestamp")
        void shouldSerializeKeepAliveWithTimestamp() {
            long timestamp = System.currentTimeMillis();
            KeepAlivePacket original = new KeepAlivePacket(timestamp);

            registry.encode(original, buf);
            KeepAlivePacket result = (KeepAlivePacket) registry.decode(buf);

            assertThat(result.getTimestamp()).isEqualTo(timestamp);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should handle zero timestamp")
        void shouldHandleZeroTimestamp() {
            KeepAlivePacket original = new KeepAlivePacket(0L);

            registry.encode(original, buf);
            KeepAlivePacket result = (KeepAlivePacket) registry.decode(buf);

            assertThat(result.getTimestamp()).isZero();
        }

        @Test
        @DisplayName("Should handle max long timestamp")
        void shouldHandleMaxLongTimestamp() {
            KeepAlivePacket original = new KeepAlivePacket(Long.MAX_VALUE);

            registry.encode(original, buf);
            KeepAlivePacket result = (KeepAlivePacket) registry.decode(buf);

            assertThat(result.getTimestamp()).isEqualTo(Long.MAX_VALUE);
        }
    }

    // ==================== TitlePacket Tests ====================

    @Nested
    @DisplayName("TitlePacket Serialization")
    class TitlePacketTests {

        @Test
        @DisplayName("Should serialize basic title")
        void shouldSerializeBasicTitle() {
            UUID senderId = UUID.randomUUID();
            TitlePacket original = new TitlePacket("global", "Welcome!", "To the server", senderId);

            registry.encode(original, buf);
            TitlePacket result = (TitlePacket) registry.decode(buf);

            assertThat(result.getChannelId()).isEqualTo("global");
            assertThat(result.getTitle()).isEqualTo("Welcome!");
            assertThat(result.getSubtitle()).isEqualTo("To the server");
            assertThat(result.getSenderId()).isEqualTo(senderId);
            assertThat(result.getFadeIn()).isEqualTo(10);
            assertThat(result.getStay()).isEqualTo(70);
            assertThat(result.getFadeOut()).isEqualTo(20);
            assertThat(buf.readableBytes()).isZero();
        }

        @Test
        @DisplayName("Should serialize title with custom timing")
        void shouldSerializeTitleWithCustomTiming() {
            UUID senderId = UUID.randomUUID();
            TitlePacket original = new TitlePacket(
                    "global", "Title", "Subtitle", senderId, 5, 100, 10
            );

            registry.encode(original, buf);
            TitlePacket result = (TitlePacket) registry.decode(buf);

            assertThat(result.getFadeIn()).isEqualTo(5);
            assertThat(result.getStay()).isEqualTo(100);
            assertThat(result.getFadeOut()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should serialize title with color codes")
        void shouldSerializeTitleWithColorCodes() {
            TitlePacket original = new TitlePacket(
                    "global", "&c&lWARNING", "&7Server restart in 5 minutes", UUID.randomUUID()
            );

            registry.encode(original, buf);
            TitlePacket result = (TitlePacket) registry.decode(buf);

            assertThat(result.getTitle()).isEqualTo("&c&lWARNING");
            assertThat(result.getSubtitle()).isEqualTo("&7Server restart in 5 minutes");
        }

        @Test
        @DisplayName("Should handle empty subtitle")
        void shouldHandleEmptySubtitle() {
            TitlePacket original = new TitlePacket("global", "Title Only", "", UUID.randomUUID());

            registry.encode(original, buf);
            TitlePacket result = (TitlePacket) registry.decode(buf);

            assertThat(result.getSubtitle()).isEmpty();
        }
    }

    // ==================== Multiple Packets Tests ====================

    @Nested
    @DisplayName("Multiple Packets Serialization")
    class MultiplePacketsTests {

        @Test
        @DisplayName("Should serialize multiple packets in sequence")
        void shouldSerializeMultiplePacketsInSequence() {
            // Create multiple packets
            HandshakePacket handshake = new HandshakePacket(1, "Server", "hash", PlatformType.BUKKIT);
            ChatMessagePacket chat = new ChatMessagePacket(
                    UUID.randomUUID(), "Player", "Server", "global", "Hello"
            );
            KeepAlivePacket keepAlive = new KeepAlivePacket(System.currentTimeMillis());

            // Encode all
            registry.encode(handshake, buf);
            registry.encode(chat, buf);
            registry.encode(keepAlive, buf);

            // Decode all
            Packet decoded1 = registry.decode(buf);
            Packet decoded2 = registry.decode(buf);
            Packet decoded3 = registry.decode(buf);

            assertThat(decoded1).isInstanceOf(HandshakePacket.class);
            assertThat(decoded2).isInstanceOf(ChatMessagePacket.class);
            assertThat(decoded3).isInstanceOf(KeepAlivePacket.class);
            assertThat(buf.readableBytes()).isZero();
        }
    }
}
