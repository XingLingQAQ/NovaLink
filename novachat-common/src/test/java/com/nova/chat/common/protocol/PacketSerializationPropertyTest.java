package com.nova.chat.common.protocol;

import com.nova.chat.common.protocol.packets.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.jqwik.api.*;
import net.jqwik.api.arbitraries.StringArbitrary;
import net.jqwik.api.constraints.Size;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for packet serialization round-trip.
 * 
 * **Feature: starchat-starlink, Property 13: Color Code Parsing Round-Trip**
 * **Validates: Requirements 10.2, 10.3**
 */
class PacketSerializationPropertyTest {

    private final PacketRegistry registry = NovaProtocol.createRegistry();

    // ==================== Arbitraries ====================

    @Provide
    Arbitrary<String> validStrings() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('_', '-', '.')
                .ofMinLength(1)
                .ofMaxLength(100);
    }

    /**
     * Strings capped at 64 chars, matching the encoder's maxLength for
     * short identifier fields (clientId, senderName, channelId). See
     * {@code ChatMessagePacket} ({@code readString(buf, 64)}) and
     * {@code HandshakePacket} ({@code readString(buf, 64)}).
     */
    @Provide
    Arbitrary<String> shortStrings() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('_', '-', '.')
                .ofMinLength(1)
                .ofMaxLength(64);
    }

    @Provide
    Arbitrary<String> colorCodeStrings() {
        // Generate strings that may contain Minecraft color codes
        return Arbitraries.oneOf(
                // Plain text
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50),
                // Text with & color codes
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                        .map(s -> "&a" + s + "&r"),
                // Text with hex color codes
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                        .map(s -> "&#FFA500" + s + "&r"),
                // Mixed color codes
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                        .map(s -> "&c[PVP] &7" + s + "&f: Hello")
        );
    }

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.longs().tuple2()
                .map(t -> new UUID(t.get1(), t.get2()));
    }

    @Provide
    Arbitrary<PlatformType> platformTypes() {
        return Arbitraries.of(PlatformType.values());
    }

    @Provide
    Arbitrary<ChannelAction> channelActions() {
        return Arbitraries.of(ChannelAction.values());
    }


    // ==================== HandshakePacket Tests ====================

    /**
     * Property: HandshakePacket Round-Trip
     * 
     * For any valid HandshakePacket, encoding and decoding should produce
     * an equivalent packet.
     * 
     * **Feature: starchat-starlink, Property 13: Color Code Parsing Round-Trip**
     * **Validates: Requirements 10.2, 10.3**
     */
    @Property(tries = 100)
    void handshakePacketRoundTrip(
            @ForAll @From("shortStrings") String clientId,
            @ForAll @From("validStrings") String passwordHash,
            @ForAll @From("platformTypes") PlatformType platform,
            @ForAll int protocolVersion) {
        
        HandshakePacket original = new HandshakePacket(protocolVersion, clientId, passwordHash, platform);
        
        ByteBuf buf = Unpooled.buffer();
        try {
            // Encode
            registry.encode(original, buf);
            
            // Decode
            Packet decoded = registry.decode(buf);
            
            // Verify
            assertThat(decoded).isInstanceOf(HandshakePacket.class);
            HandshakePacket result = (HandshakePacket) decoded;
            
            assertThat(result.getProtocolVersion()).isEqualTo(protocolVersion);
            assertThat(result.getClientId()).isEqualTo(clientId);
            assertThat(result.getPasswordHash()).isEqualTo(passwordHash);
            assertThat(result.getPlatform()).isEqualTo(platform);
            assertThat(result.getRequestId()).isEqualTo(original.getRequestId());
            
            // Buffer should be fully consumed
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }

    // ==================== HandshakeResponsePacket Tests ====================

    /**
     * Property: HandshakeResponsePacket Round-Trip
     * 
     * **Feature: starchat-starlink, Property 13: Color Code Parsing Round-Trip**
     * **Validates: Requirements 10.2, 10.3**
     */
    @Property(tries = 100)
    void handshakeResponsePacketRoundTrip(
            @ForAll boolean success,
            @ForAll @From("validStrings") String errorCode,
            @ForAll @From("validStrings") String message) {
        
        HandshakeResponsePacket original = new HandshakeResponsePacket(success, errorCode, message);
        
        ByteBuf buf = Unpooled.buffer();
        try {
            registry.encode(original, buf);
            Packet decoded = registry.decode(buf);
            
            assertThat(decoded).isInstanceOf(HandshakeResponsePacket.class);
            HandshakeResponsePacket result = (HandshakeResponsePacket) decoded;
            
            assertThat(result.isSuccess()).isEqualTo(success);
            assertThat(result.getErrorCode()).isEqualTo(errorCode);
            assertThat(result.getMessage()).isEqualTo(message);
            assertThat(result.getRequestId()).isEqualTo(original.getRequestId());
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }


    // ==================== ChatMessagePacket Tests ====================

    /**
     * Property: ChatMessagePacket Round-Trip with Color Codes
     * 
     * For any chat message containing color codes, encoding and decoding
     * should preserve the color information.
     * 
     * **Feature: starchat-starlink, Property 13: Color Code Parsing Round-Trip**
     * **Validates: Requirements 10.2, 10.3**
     */
    @Property(tries = 100)
    void chatMessagePacketRoundTrip(
            @ForAll @From("uuids") UUID senderId,
            @ForAll @From("shortStrings") String senderName,
            @ForAll @From("shortStrings") String clientId,
            @ForAll @From("shortStrings") String channelId,
            @ForAll @From("colorCodeStrings") String content) {
        
        ChatMessagePacket original = new ChatMessagePacket(senderId, senderName, clientId, channelId, content);
        
        ByteBuf buf = Unpooled.buffer();
        try {
            registry.encode(original, buf);
            Packet decoded = registry.decode(buf);
            
            assertThat(decoded).isInstanceOf(ChatMessagePacket.class);
            ChatMessagePacket result = (ChatMessagePacket) decoded;
            
            assertThat(result.getSenderId()).isEqualTo(senderId);
            assertThat(result.getSenderName()).isEqualTo(senderName);
            assertThat(result.getClientId()).isEqualTo(clientId);
            assertThat(result.getChannelId()).isEqualTo(channelId);
            assertThat(result.getContent()).isEqualTo(content);
            assertThat(result.getRequestId()).isEqualTo(original.getRequestId());
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }

    /**
     * Property: ChatMessagePacket with Placeholders Round-Trip
     * 
     * **Feature: starchat-starlink, Property 13: Color Code Parsing Round-Trip**
     * **Validates: Requirements 10.2, 10.3**
     */
    @Property(tries = 100)
    void chatMessagePacketWithPlaceholdersRoundTrip(
            @ForAll @From("uuids") UUID senderId,
            @ForAll @From("shortStrings") String senderName,
            @ForAll @From("shortStrings") String clientId,
            @ForAll @From("shortStrings") String channelId,
            @ForAll @From("colorCodeStrings") String content,
            @ForAll @Size(max = 5) Map<@From("validStrings") String, @From("validStrings") String> placeholders) {
        
        ChatMessagePacket original = new ChatMessagePacket(senderId, senderName, clientId, channelId, content);
        original.setPlaceholders(new HashMap<>(placeholders));
        
        ByteBuf buf = Unpooled.buffer();
        try {
            registry.encode(original, buf);
            Packet decoded = registry.decode(buf);
            
            assertThat(decoded).isInstanceOf(ChatMessagePacket.class);
            ChatMessagePacket result = (ChatMessagePacket) decoded;
            
            assertThat(result.getContent()).isEqualTo(content);
            assertThat(result.getPlaceholders()).isEqualTo(placeholders);
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }


    // ==================== ChannelActionPacket Tests ====================

    /**
     * Property: ChannelActionPacket Round-Trip
     * 
     * **Feature: starchat-starlink, Property 13: Color Code Parsing Round-Trip**
     * **Validates: Requirements 10.2, 10.3**
     */
    @Property(tries = 100)
    void channelActionPacketRoundTrip(
            @ForAll @From("channelActions") ChannelAction action,
            @ForAll @From("validStrings") String channelId,
            @ForAll @From("validStrings") String password) {
        
        ChannelActionPacket original = new ChannelActionPacket(action, channelId, password);
        
        ByteBuf buf = Unpooled.buffer();
        try {
            registry.encode(original, buf);
            Packet decoded = registry.decode(buf);
            
            assertThat(decoded).isInstanceOf(ChannelActionPacket.class);
            ChannelActionPacket result = (ChannelActionPacket) decoded;
            
            assertThat(result.getAction()).isEqualTo(action);
            assertThat(result.getChannelId()).isEqualTo(channelId);
            assertThat(result.getPassword()).isEqualTo(password);
            assertThat(result.getRequestId()).isEqualTo(original.getRequestId());
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }

    /**
     * Property: ChannelActionPacket with Extra Data Round-Trip
     * 
     * **Feature: starchat-starlink, Property 13: Color Code Parsing Round-Trip**
     * **Validates: Requirements 10.2, 10.3**
     */
    @Property(tries = 100)
    void channelActionPacketWithExtraRoundTrip(
            @ForAll @From("channelActions") ChannelAction action,
            @ForAll @From("validStrings") String channelId,
            @ForAll @Size(max = 5) Map<@From("validStrings") String, @From("validStrings") String> extra) {
        
        ChannelActionPacket original = new ChannelActionPacket(action, channelId);
        original.setExtra(new HashMap<>(extra));
        
        ByteBuf buf = Unpooled.buffer();
        try {
            registry.encode(original, buf);
            Packet decoded = registry.decode(buf);
            
            assertThat(decoded).isInstanceOf(ChannelActionPacket.class);
            ChannelActionPacket result = (ChannelActionPacket) decoded;
            
            assertThat(result.getAction()).isEqualTo(action);
            assertThat(result.getChannelId()).isEqualTo(channelId);
            assertThat(result.getExtra()).isEqualTo(extra);
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }


    // ==================== KeepAlivePacket Tests ====================

    /**
     * Property: KeepAlivePacket Round-Trip
     * 
     * **Feature: starchat-starlink, Property 13: Color Code Parsing Round-Trip**
     * **Validates: Requirements 10.2, 10.3**
     */
    @Property(tries = 100)
    void keepAlivePacketRoundTrip(@ForAll long timestamp) {
        KeepAlivePacket original = new KeepAlivePacket(timestamp);
        
        ByteBuf buf = Unpooled.buffer();
        try {
            registry.encode(original, buf);
            Packet decoded = registry.decode(buf);
            
            assertThat(decoded).isInstanceOf(KeepAlivePacket.class);
            KeepAlivePacket result = (KeepAlivePacket) decoded;
            
            assertThat(result.getTimestamp()).isEqualTo(timestamp);
            assertThat(result.getRequestId()).isEqualTo(original.getRequestId());
            assertThat(buf.readableBytes()).isZero();
        } finally {
            buf.release();
        }
    }

    // ==================== Color Code Specific Tests ====================

    /**
     * Property: Color codes in messages are preserved exactly
     * 
     * For any message with Minecraft color codes (& format), the codes
     * should be preserved exactly after serialization round-trip.
     * 
     * **Feature: starchat-starlink, Property 13: Color Code Parsing Round-Trip**
     * **Validates: Requirements 10.2, 10.3**
     */
    @Property(tries = 100)
    void colorCodesPreservedInMessages(
            @ForAll @From("uuids") UUID senderId,
            @ForAll("colorCodeMessages") String content) {
        
        ChatMessagePacket original = new ChatMessagePacket(
                senderId, "TestPlayer", "TestClient", "global", content);
        
        ByteBuf buf = Unpooled.buffer();
        try {
            registry.encode(original, buf);
            Packet decoded = registry.decode(buf);
            
            assertThat(decoded).isInstanceOf(ChatMessagePacket.class);
            ChatMessagePacket result = (ChatMessagePacket) decoded;
            
            // Color codes must be preserved exactly
            assertThat(result.getContent()).isEqualTo(content);
            
            // Verify specific color code patterns are intact
            if (content.contains("&")) {
                assertThat(result.getContent()).contains("&");
            }
            if (content.contains("&#")) {
                assertThat(result.getContent()).contains("&#");
            }
        } finally {
            buf.release();
        }
    }

    @Provide
    Arbitrary<String> colorCodeMessages() {
        return Arbitraries.oneOf(
                // Standard color codes
                Arbitraries.of(
                        "&aGreen text",
                        "&cRed &bBlue &eYellow",
                        "&l&nBold and underline",
                        "&r Reset formatting"
                ),
                // Hex color codes
                Arbitraries.of(
                        "&#FF5555Red hex",
                        "&#00AA00Green &#0000FFBlue",
                        "&#FFA500Orange text&#FFFFFF white"
                ),
                // Mixed formats
                Arbitraries.of(
                        "&c[PVP] &7Player&f: Hello world",
                        "&#FFA500[VIP] &7Player&f: &#00FF00Welcome!",
                        "&8[&bNovaChat&8]&r System message"
                ),
                // Edge cases
                Arbitraries.of(
                        "&&double ampersand",
                        "&",
                        "&#",
                        "&#FFF short hex",
                        "No color codes here"
                )
        );
    }
}
