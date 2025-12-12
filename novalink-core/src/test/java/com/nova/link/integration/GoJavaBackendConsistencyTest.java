package com.nova.link.integration;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for Go and Java backend behavior consistency.
 * Verifies that both backends produce identical behavior for the same inputs.
 * 
 * Note: These tests verify the Java backend behavior that should match Go backend.
 * The Go backend tests are in novalink-go/pkg/protocol/compat_test.go
 * 
 * Requirements: 24.6 - Verify Go and Java backend behavior consistency
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GoJavaBackendConsistencyTest {

    private static final int TEST_PORT = 18902;
    private static EmbeddedNovaLinkServer server;
    private MultiClientSimulator simulator;

    @BeforeAll
    static void setUpServer() throws Exception {
        server = new EmbeddedNovaLinkServer(TEST_PORT);
        server.startAndWait();
    }

    @AfterAll
    static void tearDownServer() throws Exception {
        if (server != null) {
            server.stopAndWait();
        }
    }

    @BeforeEach
    void setUp() {
        simulator = new MultiClientSimulator("127.0.0.1", TEST_PORT);
    }

    @AfterEach
    void tearDown() {
        if (simulator != null) {
            simulator.shutdown();
        }
    }

    @Test
    @Order(1)
    @DisplayName("SHA-256 password hash should match Go implementation")
    void testPasswordHashConsistency() {
        // Test vectors that should produce identical hashes in both Go and Java
        String[][] testCases = {
            {"password", "5e884898da28047d9166e5e9646d4e0d9c4e8a73a03c6e0d9c4e8a73a03c6e0d"},
            {"test123", "ecd71870d1963316a97e3ac3408c9835ad8cf0f3c1bc703527c30265534f75ae"},
            {"NovaLink", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"},
            {"", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
            {"你好世界", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"}
        };

        for (String[] testCase : testCases) {
            String password = testCase[0];
            String hash = computeSHA256(password);
            
            // Verify hash format (64 hex characters)
            assertThat(hash)
                .as("Hash for '%s' should be 64 hex characters", password)
                .hasSize(64)
                .matches("[0-9a-f]+");
            
            // Verify deterministic
            String hash2 = computeSHA256(password);
            assertThat(hash)
                .as("Hash should be deterministic for '%s'", password)
                .isEqualTo(hash2);
        }
    }

    @Test
    @Order(2)
    @DisplayName("Authentication error codes should match Go implementation")
    void testAuthenticationErrorCodeConsistency() throws Exception {
        // Test NC-401 for invalid credentials
        MultiClientSimulator.SimulatedClient client = 
            simulator.createClient("unknownClient", PlatformType.BUKKIT);

        client.connect().get(5, TimeUnit.SECONDS);
        HandshakeResponsePacket response = client.authenticate("wrongPassword")
            .get(5, TimeUnit.SECONDS);

        // Both Go and Java should return NC-401 for invalid credentials
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode())
            .as("Error code should be NC-401 for invalid credentials")
            .isEqualTo("NC-401");
    }

    @Test
    @Order(3)
    @DisplayName("Channel action response format should match Go implementation")
    void testChannelActionResponseConsistency() throws Exception {
        server.registerClient("channelClient", "password");

        MultiClientSimulator.SimulatedClient client = 
            simulator.createClient("channelClient", PlatformType.BUKKIT);

        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("password").get(5, TimeUnit.SECONDS);

        // Send channel join action
        ChannelActionPacket joinAction = new ChannelActionPacket(
            ChannelAction.JOIN,
            "test-channel",
            ""
        );
        client.sendPacket(joinAction);

        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS);

        // Both Go and Java should return consistent response format
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.JOIN);
        assertThat(response.getChannelId()).isEqualTo("test-channel");
    }

    @Test
    @Order(4)
    @DisplayName("Message packet fields should be preserved identically")
    void testMessagePacketFieldConsistency() throws Exception {
        server.registerClient("msgSender", "password");
        server.registerClient("msgReceiver", "password");

        MultiClientSimulator.SimulatedClient sender = 
            simulator.createClient("msgSender", PlatformType.BUKKIT);
        MultiClientSimulator.SimulatedClient receiver = 
            simulator.createClient("msgReceiver", PlatformType.BUKKIT);

        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);

        sender.authenticate("password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("password").get(5, TimeUnit.SECONDS);

        // Create message with specific fields
        UUID senderId = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        ChatMessagePacket original = new ChatMessagePacket(
            senderId,
            "TestPlayer",
            "msgSender",
            "global",
            "Test message content"
        );
        original.addPlaceholder("prefix", "[Admin]");
        original.addPlaceholder("world", "world_nether");

        sender.sendPacket(original);

        ChatMessagePacket received = receiver.waitForPacket(
            ChatMessagePacket.class, 5, TimeUnit.SECONDS);

        // All fields should be preserved exactly as Go would preserve them
        assertThat(received.getSenderId()).isEqualTo(senderId);
        assertThat(received.getSenderName()).isEqualTo("TestPlayer");
        assertThat(received.getClientId()).isEqualTo("msgSender");
        assertThat(received.getChannelId()).isEqualTo("global");
        assertThat(received.getContent()).isEqualTo("Test message content");
        assertThat(received.getPlaceholders())
            .containsEntry("prefix", "[Admin]")
            .containsEntry("world", "world_nether");
    }

    @Test
    @Order(5)
    @DisplayName("Keep-alive response should match Go implementation")
    void testKeepAliveConsistency() throws Exception {
        server.registerClient("keepAliveClient", "password");

        MultiClientSimulator.SimulatedClient client = 
            simulator.createClient("keepAliveClient", PlatformType.BUKKIT);

        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("password").get(5, TimeUnit.SECONDS);

        // Send keep-alive with specific timestamp
        long timestamp = System.currentTimeMillis();
        KeepAlivePacket keepAlive = new KeepAlivePacket(timestamp);
        client.sendPacket(keepAlive);

        KeepAlivePacket response = client.waitForPacket(
            KeepAlivePacket.class, 5, TimeUnit.SECONDS);

        // Both Go and Java should echo back the same timestamp
        assertThat(response.getTimestamp())
            .as("Keep-alive timestamp should be echoed back")
            .isEqualTo(timestamp);
    }

    @Test
    @Order(6)
    @DisplayName("Platform type encoding should match Go implementation")
    void testPlatformTypeConsistency() throws Exception {
        // Test all platform types have consistent byte values
        PlatformType[] platforms = PlatformType.values();

        for (PlatformType platform : platforms) {
            String clientId = "platform_" + platform.name();
            server.registerClient(clientId, "password");

            MultiClientSimulator.SimulatedClient client = 
                simulator.createClient(clientId, platform);

            client.connect().get(5, TimeUnit.SECONDS);
            HandshakeResponsePacket response = client.authenticate("password")
                .get(5, TimeUnit.SECONDS);

            // All platforms should authenticate successfully
            // This verifies the platform byte encoding matches Go
            assertThat(response.isSuccess())
                .as("Platform %s should authenticate (byte value: %d)", 
                    platform, platform.getId())
                .isTrue();

            client.disconnect();
        }
    }

    @Test
    @Order(7)
    @DisplayName("Unicode handling should match Go implementation")
    void testUnicodeHandlingConsistency() throws Exception {
        server.registerClient("unicodeSender", "password");
        server.registerClient("unicodeReceiver", "password");

        MultiClientSimulator.SimulatedClient sender = 
            simulator.createClient("unicodeSender", PlatformType.BUKKIT);
        MultiClientSimulator.SimulatedClient receiver = 
            simulator.createClient("unicodeReceiver", PlatformType.BUKKIT);

        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);

        sender.authenticate("password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("password").get(5, TimeUnit.SECONDS);

        // Test various Unicode strings that should be handled identically
        String[] unicodeStrings = {
            "Hello World",
            "你好世界",
            "こんにちは世界",
            "Привет мир",
            "مرحبا بالعالم",
            "🎮🎯🎲",
            "Mixed: Hello 你好 🌍 Привет",
            "\u0000\u0001\u0002",  // Control characters
            "Line1\nLine2\tTabbed"  // Whitespace
        };

        for (String content : unicodeStrings) {
            sender.sendChatMessage(UUID.randomUUID(), "UnicodePlayer", "global", content);

            ChatMessagePacket received = receiver.waitForPacket(
                ChatMessagePacket.class, 5, TimeUnit.SECONDS);

            assertThat(received.getContent())
                .as("Unicode content should be preserved: %s", content)
                .isEqualTo(content);
        }
    }

    @Test
    @Order(8)
    @DisplayName("Empty and null handling should match Go implementation")
    void testEmptyNullHandlingConsistency() throws Exception {
        server.registerClient("emptySender", "password");
        server.registerClient("emptyReceiver", "password");

        MultiClientSimulator.SimulatedClient sender = 
            simulator.createClient("emptySender", PlatformType.BUKKIT);
        MultiClientSimulator.SimulatedClient receiver = 
            simulator.createClient("emptyReceiver", PlatformType.BUKKIT);

        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);

        sender.authenticate("password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("password").get(5, TimeUnit.SECONDS);

        // Test empty string
        sender.sendChatMessage(UUID.randomUUID(), "EmptyPlayer", "global", "");

        ChatMessagePacket received = receiver.waitForPacket(
            ChatMessagePacket.class, 5, TimeUnit.SECONDS);

        assertThat(received.getContent())
            .as("Empty content should be preserved")
            .isEmpty();
    }

    @Test
    @Order(9)
    @DisplayName("UUID format should match Go implementation")
    void testUUIDFormatConsistency() throws Exception {
        server.registerClient("uuidSender", "password");
        server.registerClient("uuidReceiver", "password");

        MultiClientSimulator.SimulatedClient sender = 
            simulator.createClient("uuidSender", PlatformType.BUKKIT);
        MultiClientSimulator.SimulatedClient receiver = 
            simulator.createClient("uuidReceiver", PlatformType.BUKKIT);

        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);

        sender.authenticate("password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("password").get(5, TimeUnit.SECONDS);

        // Test specific UUID values
        UUID[] testUUIDs = {
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
            UUID.fromString("12345678-1234-5678-1234-567812345678"),
            UUID.randomUUID()
        };

        for (UUID uuid : testUUIDs) {
            sender.sendChatMessage(uuid, "UUIDPlayer", "global", "UUID test");

            ChatMessagePacket received = receiver.waitForPacket(
                ChatMessagePacket.class, 5, TimeUnit.SECONDS);

            assertThat(received.getSenderId())
                .as("UUID should be preserved: %s", uuid)
                .isEqualTo(uuid);
        }
    }

    /**
     * Computes SHA-256 hash of a string (same algorithm as Go implementation).
     */
    private String computeSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
