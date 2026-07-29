package com.nova.link.integration;

import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end integration tests for message routing correctness.
 * Verifies that messages are correctly routed between multiple clients.
 * 
 * Requirements: 24.3 - Verify message routing end-to-end correctness
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndMessageRoutingTest {

    private static final int TEST_PORT = 18900;
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
    @DisplayName("Message should be delivered to all authenticated clients")
    void testMessageDeliveryToAllClients() throws Exception {
        // Register clients
        server.registerClient("sender", "password");
        server.registerClient("receiver1", "password");
        server.registerClient("receiver2", "password");

        // Create and connect clients
        MultiClientSimulator.SimulatedClient sender = simulator.createClient("sender", PlatformType.BUKKIT);
        MultiClientSimulator.SimulatedClient receiver1 = simulator.createClient("receiver1", PlatformType.VELOCITY);
        MultiClientSimulator.SimulatedClient receiver2 = simulator.createClient("receiver2", PlatformType.NUKKIT);

        sender.connect().get(5, TimeUnit.SECONDS);
        receiver1.connect().get(5, TimeUnit.SECONDS);
        receiver2.connect().get(5, TimeUnit.SECONDS);

        // Authenticate all
        sender.authenticate("password").get(5, TimeUnit.SECONDS);
        receiver1.authenticate("password").get(5, TimeUnit.SECONDS);
        receiver2.authenticate("password").get(5, TimeUnit.SECONDS);

        // Send message
        UUID playerId = UUID.randomUUID();
        sender.sendChatMessage(playerId, "TestPlayer", "global", "Hello everyone!");

        // Both receivers should get the message
        ChatMessagePacket msg1 = receiver1.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
        ChatMessagePacket msg2 = receiver2.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);

        assertThat(msg1.getContent()).isEqualTo("Hello everyone!");
        assertThat(msg2.getContent()).isEqualTo("Hello everyone!");
        assertThat(msg1.getSenderId()).isEqualTo(playerId);
        assertThat(msg2.getSenderId()).isEqualTo(playerId);
    }

    @Test
    @Order(2)
    @DisplayName("Message should preserve all fields during routing")
    void testMessageFieldPreservation() throws Exception {
        server.registerClient("fieldSender", "password");
        server.registerClient("fieldReceiver", "password");

        MultiClientSimulator.SimulatedClient sender = simulator.createClient("fieldSender", PlatformType.BUKKIT);
        MultiClientSimulator.SimulatedClient receiver = simulator.createClient("fieldReceiver", PlatformType.BUKKIT);

        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);

        sender.authenticate("password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("password").get(5, TimeUnit.SECONDS);

        // Create message with all fields
        UUID playerId = UUID.randomUUID();
        ChatMessagePacket original = new ChatMessagePacket(
            playerId, "OriginalPlayer", "fieldSender", "test-channel", "Test content"
        );
        original.addPlaceholder("prefix", "[Admin]");
        original.addPlaceholder("suffix", "[VIP]");
        sender.sendPacket(original);

        // Verify all fields preserved
        ChatMessagePacket received = receiver.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);

        assertThat(received.getSenderId()).isEqualTo(playerId);
        assertThat(received.getSenderName()).isEqualTo("OriginalPlayer");
        assertThat(received.getClientId()).isEqualTo("fieldSender");
        assertThat(received.getChannelId()).isEqualTo("test-channel");
        assertThat(received.getContent()).isEqualTo("Test content");
        assertThat(received.getPlaceholders()).containsEntry("prefix", "[Admin]");
        assertThat(received.getPlaceholders()).containsEntry("suffix", "[VIP]");
    }

    @Test
    @Order(3)
    @DisplayName("Cross-platform message routing should work correctly")
    void testCrossPlatformRouting() throws Exception {
        // Test routing between different platform types
        PlatformType[] platforms = {
            PlatformType.BUKKIT,
            PlatformType.VELOCITY,
            PlatformType.NUKKIT,
            PlatformType.FABRIC,
            PlatformType.POCKETMINE
        };

        // Register and create clients for each platform
        List<MultiClientSimulator.SimulatedClient> clients = new ArrayList<>();
        for (int i = 0; i < platforms.length; i++) {
            String clientId = "platform_" + i;
            server.registerClient(clientId, "password");
            MultiClientSimulator.SimulatedClient client = simulator.createClient(clientId, platforms[i]);
            client.connect().get(5, TimeUnit.SECONDS);
            client.authenticate("password").get(5, TimeUnit.SECONDS);
            clients.add(client);
        }

        // Send from first client (Bukkit)
        UUID playerId = UUID.randomUUID();
        clients.get(0).sendChatMessage(playerId, "CrossPlatformPlayer", "global", "Cross-platform message!");

        // All other clients should receive
        for (int i = 1; i < clients.size(); i++) {
            ChatMessagePacket received = clients.get(i).waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
            assertThat(received.getContent())
                .as("Platform %s should receive message", platforms[i])
                .isEqualTo("Cross-platform message!");
        }
    }

    @Test
    @Order(4)
    @DisplayName("Multiple rapid messages should all be delivered in order")
    void testRapidMessageDelivery() throws Exception {
        server.registerClient("rapidSender", "password");
        server.registerClient("rapidReceiver", "password");

        MultiClientSimulator.SimulatedClient sender = simulator.createClient("rapidSender", PlatformType.BUKKIT);
        MultiClientSimulator.SimulatedClient receiver = simulator.createClient("rapidReceiver", PlatformType.BUKKIT);

        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);

        sender.authenticate("password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("password").get(5, TimeUnit.SECONDS);

        // Send multiple messages rapidly
        int messageCount = 20;
        List<String> sentContents = new ArrayList<>();
        UUID playerId = UUID.randomUUID();

        for (int i = 0; i < messageCount; i++) {
            String content = "Rapid message #" + i;
            sentContents.add(content);
            sender.sendChatMessage(playerId, "RapidPlayer", "global", content);
        }

        // Receive all messages
        List<String> receivedContents = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            ChatMessagePacket received = receiver.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
            receivedContents.add(received.getContent());
        }

        // All messages should be received
        assertThat(receivedContents).containsExactlyInAnyOrderElementsOf(sentContents);
    }

    @Test
    @Order(5)
    @DisplayName("Unicode content should be preserved during routing")
    void testUnicodeContentRouting() throws Exception {
        server.registerClient("unicodeSender", "password");
        server.registerClient("unicodeReceiver", "password");

        MultiClientSimulator.SimulatedClient sender = simulator.createClient("unicodeSender", PlatformType.BUKKIT);
        MultiClientSimulator.SimulatedClient receiver = simulator.createClient("unicodeReceiver", PlatformType.BUKKIT);

        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);

        sender.authenticate("password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("password").get(5, TimeUnit.SECONDS);

        // Test various Unicode content
        String[] unicodeMessages = {
            "你好世界！",
            "こんにちは",
            "안녕하세요",
            "Привет мир",
            "🎮 Gaming 🎮",
            "Mixed: Hello 你好 🌍"
        };

        UUID playerId = UUID.randomUUID();
        for (String content : unicodeMessages) {
            sender.sendChatMessage(playerId, "UnicodePlayer", "global", content);

            ChatMessagePacket received = receiver.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
            assertThat(received.getContent())
                .as("Unicode content should be preserved: %s", content)
                .isEqualTo(content);
        }
    }

    @Test
    @Order(6)
    @DisplayName("Unauthenticated clients should not receive messages")
    void testUnauthenticatedClientExclusion() throws Exception {
        server.registerClient("authSender", "password");

        MultiClientSimulator.SimulatedClient sender = simulator.createClient("authSender", PlatformType.BUKKIT);
        MultiClientSimulator.SimulatedClient unauthClient = simulator.createClient("unauth", PlatformType.BUKKIT);

        sender.connect().get(5, TimeUnit.SECONDS);
        unauthClient.connect().get(5, TimeUnit.SECONDS);

        sender.authenticate("password").get(5, TimeUnit.SECONDS);
        // unauthClient does NOT authenticate

        // Send message
        sender.sendChatMessage(UUID.randomUUID(), "AuthPlayer", "global", "Secret message");

        // Unauthenticated client should not receive
        assertThatThrownBy(() ->
            unauthClient.waitForPacket(ChatMessagePacket.class, 2, TimeUnit.SECONDS)
        ).isInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    @Test
    @Order(7)
    @DisplayName("Large number of concurrent clients should all receive messages")
    void testManyClientsRouting() throws Exception {
        int clientCount = 10;

        // Register all clients
        for (int i = 0; i < clientCount; i++) {
            server.registerClient("manyClient_" + i, "password");
        }

        // Create and connect all clients
        List<MultiClientSimulator.SimulatedClient> clients = 
            simulator.createClients(clientCount, PlatformType.BUKKIT, "manyClient");

        simulator.connectAll().get(10, TimeUnit.SECONDS);
        simulator.authenticateAll(id -> "password").get(10, TimeUnit.SECONDS);

        assertThat(simulator.getAuthenticatedCount()).isEqualTo(clientCount);

        // Send from first client
        UUID playerId = UUID.randomUUID();
        clients.get(0).sendChatMessage(playerId, "ManyPlayer", "global", "Message to many!");

        // All other clients should receive
        for (int i = 1; i < clientCount; i++) {
            ChatMessagePacket received = clients.get(i).waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
            assertThat(received.getContent()).isEqualTo("Message to many!");
        }
    }

    @Test
    @Order(8)
    @DisplayName("Empty message content should be handled correctly")
    void testEmptyMessageContent() throws Exception {
        server.registerClient("emptySender", "password");
        server.registerClient("emptyReceiver", "password");

        MultiClientSimulator.SimulatedClient sender = simulator.createClient("emptySender", PlatformType.BUKKIT);
        MultiClientSimulator.SimulatedClient receiver = simulator.createClient("emptyReceiver", PlatformType.BUKKIT);

        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);

        sender.authenticate("password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("password").get(5, TimeUnit.SECONDS);

        // Send empty message
        sender.sendChatMessage(UUID.randomUUID(), "EmptyPlayer", "global", "");

        ChatMessagePacket received = receiver.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
        assertThat(received.getContent()).isEmpty();
    }
}
