package com.nova.link.integration;

import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for message sending and receiving flow verification.
 * 
 * Requirements: 23.7 - Verify message sending and receiving flow
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MessageFlowTest {

    private static IntegrationTestHelper helper;
    private static final int TEST_PORT = 18895;

    @BeforeAll
    static void setUp() throws Exception {
        helper = new IntegrationTestHelper();
        helper.startServer(TEST_PORT);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (helper != null) {
            helper.stopServer();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Message should be broadcast to all authenticated clients")
    void testMessageBroadcast() throws Exception {
        // Setup clients
        helper.registerClient("Sender", "password");
        helper.registerClient("Receiver1", "password");
        helper.registerClient("Receiver2", "password");
        
        IntegrationTestHelper.TestClient sender = helper.createClient(PlatformType.BUKKIT);
        IntegrationTestHelper.TestClient receiver1 = helper.createClient(PlatformType.VELOCITY);
        IntegrationTestHelper.TestClient receiver2 = helper.createClient(PlatformType.NUKKIT);
        
        sender.connect().get(5, TimeUnit.SECONDS);
        receiver1.connect().get(5, TimeUnit.SECONDS);
        receiver2.connect().get(5, TimeUnit.SECONDS);
        
        sender.authenticate("Sender", "password").get(5, TimeUnit.SECONDS);
        receiver1.authenticate("Receiver1", "password").get(5, TimeUnit.SECONDS);
        receiver2.authenticate("Receiver2", "password").get(5, TimeUnit.SECONDS);
        
        // Send message
        UUID senderId = UUID.randomUUID();
        ChatMessagePacket message = new ChatMessagePacket(
            senderId,
            "TestSender",
            "Sender",
            "global",
            "Broadcast message to all!"
        );
        sender.sendPacket(message);
        
        // Both receivers should get the message
        ChatMessagePacket received1 = receiver1.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
        ChatMessagePacket received2 = receiver2.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
        
        assertThat(received1.getContent()).isEqualTo("Broadcast message to all!");
        assertThat(received2.getContent()).isEqualTo("Broadcast message to all!");
        
        sender.disconnect();
        receiver1.disconnect();
        receiver2.disconnect();
    }

    @Test
    @Order(2)
    @DisplayName("Message should preserve all fields during transmission")
    void testMessageFieldPreservation() throws Exception {
        helper.registerClient("FieldSender", "password");
        helper.registerClient("FieldReceiver", "password");
        
        IntegrationTestHelper.TestClient sender = helper.createClient(PlatformType.BUKKIT);
        IntegrationTestHelper.TestClient receiver = helper.createClient(PlatformType.BUKKIT);
        
        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);
        
        sender.authenticate("FieldSender", "password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("FieldReceiver", "password").get(5, TimeUnit.SECONDS);
        
        // Create message with all fields
        UUID senderId = UUID.randomUUID();
        ChatMessagePacket message = new ChatMessagePacket(
            senderId,
            "PlayerName",
            "FieldSender",
            "test-channel",
            "Test message content"
        );
        message.addPlaceholder("prefix", "[Admin]");
        message.addPlaceholder("suffix", "[VIP]");
        
        sender.sendPacket(message);
        
        // Verify all fields are preserved
        ChatMessagePacket received = receiver.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
        
        assertThat(received.getSenderId()).isEqualTo(senderId);
        assertThat(received.getSenderName()).isEqualTo("PlayerName");
        assertThat(received.getClientId()).isEqualTo("FieldSender");
        assertThat(received.getChannelId()).isEqualTo("test-channel");
        assertThat(received.getContent()).isEqualTo("Test message content");
        assertThat(received.getPlaceholders()).containsEntry("prefix", "[Admin]");
        assertThat(received.getPlaceholders()).containsEntry("suffix", "[VIP]");
        
        sender.disconnect();
        receiver.disconnect();
    }

    @Test
    @Order(3)
    @DisplayName("Message should support Unicode content")
    void testUnicodeMessageContent() throws Exception {
        helper.registerClient("UnicodeSender", "password");
        helper.registerClient("UnicodeReceiver", "password");
        
        IntegrationTestHelper.TestClient sender = helper.createClient(PlatformType.BUKKIT);
        IntegrationTestHelper.TestClient receiver = helper.createClient(PlatformType.BUKKIT);
        
        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);
        
        sender.authenticate("UnicodeSender", "password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("UnicodeReceiver", "password").get(5, TimeUnit.SECONDS);
        
        // Test various Unicode content
        String[] unicodeMessages = {
            "你好世界！",  // Chinese
            "こんにちは",  // Japanese
            "안녕하세요",  // Korean
            "Привет мир",  // Russian
            "مرحبا بالعالم",  // Arabic
            "🎮 Gaming 🎮",  // Emoji
            "Mixed: Hello 你好 🌍"  // Mixed
        };
        
        for (String content : unicodeMessages) {
            ChatMessagePacket message = new ChatMessagePacket(
                UUID.randomUUID(),
                "UnicodePlayer",
                "UnicodeSender",
                "global",
                content
            );
            sender.sendPacket(message);
            
            ChatMessagePacket received = receiver.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
            assertThat(received.getContent())
                .as("Unicode content should be preserved: " + content)
                .isEqualTo(content);
        }
        
        sender.disconnect();
        receiver.disconnect();
    }

    @Test
    @Order(4)
    @DisplayName("Cross-platform message routing should work")
    void testCrossPlatformMessageRouting() throws Exception {
        // Test message routing between different platform types
        helper.registerClient("JavaClient", "password");
        helper.registerClient("BedrockClient", "password");
        helper.registerClient("ProxyClient", "password");
        
        IntegrationTestHelper.TestClient javaClient = helper.createClient(PlatformType.BUKKIT);
        IntegrationTestHelper.TestClient bedrockClient = helper.createClient(PlatformType.NUKKIT);
        IntegrationTestHelper.TestClient proxyClient = helper.createClient(PlatformType.VELOCITY);
        
        javaClient.connect().get(5, TimeUnit.SECONDS);
        bedrockClient.connect().get(5, TimeUnit.SECONDS);
        proxyClient.connect().get(5, TimeUnit.SECONDS);
        
        javaClient.authenticate("JavaClient", "password").get(5, TimeUnit.SECONDS);
        bedrockClient.authenticate("BedrockClient", "password").get(5, TimeUnit.SECONDS);
        proxyClient.authenticate("ProxyClient", "password").get(5, TimeUnit.SECONDS);
        
        // Send from Java Edition
        ChatMessagePacket javaMessage = new ChatMessagePacket(
            UUID.randomUUID(),
            "JavaPlayer",
            "JavaClient",
            "global",
            "Hello from Java Edition!"
        );
        javaClient.sendPacket(javaMessage);
        
        // Both Bedrock and Proxy should receive
        ChatMessagePacket bedrockReceived = bedrockClient.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
        ChatMessagePacket proxyReceived = proxyClient.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
        
        assertThat(bedrockReceived.getContent()).isEqualTo("Hello from Java Edition!");
        assertThat(proxyReceived.getContent()).isEqualTo("Hello from Java Edition!");
        
        javaClient.disconnect();
        bedrockClient.disconnect();
        proxyClient.disconnect();
    }

    @Test
    @Order(5)
    @DisplayName("Unauthenticated client should not receive messages")
    void testUnauthenticatedClientNoMessages() throws Exception {
        helper.registerClient("AuthSender", "password");
        
        IntegrationTestHelper.TestClient sender = helper.createClient(PlatformType.BUKKIT);
        IntegrationTestHelper.TestClient unauthClient = helper.createClient(PlatformType.BUKKIT);
        
        sender.connect().get(5, TimeUnit.SECONDS);
        unauthClient.connect().get(5, TimeUnit.SECONDS);
        
        sender.authenticate("AuthSender", "password").get(5, TimeUnit.SECONDS);
        // unauthClient does NOT authenticate
        
        // Send message
        ChatMessagePacket message = new ChatMessagePacket(
            UUID.randomUUID(),
            "AuthPlayer",
            "AuthSender",
            "global",
            "This should not reach unauthenticated client"
        );
        sender.sendPacket(message);
        
        // Unauthenticated client should not receive the message
        // We expect a timeout here
        assertThatThrownBy(() -> 
            unauthClient.waitForPacket(ChatMessagePacket.class, 2, TimeUnit.SECONDS)
        ).isInstanceOf(java.util.concurrent.TimeoutException.class);
        
        sender.disconnect();
        unauthClient.disconnect();
    }

    @Test
    @Order(6)
    @DisplayName("Message with empty content should be handled")
    void testEmptyMessageContent() throws Exception {
        helper.registerClient("EmptySender", "password");
        helper.registerClient("EmptyReceiver", "password");
        
        IntegrationTestHelper.TestClient sender = helper.createClient(PlatformType.BUKKIT);
        IntegrationTestHelper.TestClient receiver = helper.createClient(PlatformType.BUKKIT);
        
        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);
        
        sender.authenticate("EmptySender", "password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("EmptyReceiver", "password").get(5, TimeUnit.SECONDS);
        
        // Send message with empty content
        ChatMessagePacket message = new ChatMessagePacket(
            UUID.randomUUID(),
            "EmptyPlayer",
            "EmptySender",
            "global",
            ""
        );
        sender.sendPacket(message);
        
        ChatMessagePacket received = receiver.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
        assertThat(received.getContent()).isEmpty();
        
        sender.disconnect();
        receiver.disconnect();
    }

    @Test
    @Order(7)
    @DisplayName("Multiple rapid messages should all be delivered")
    void testRapidMessageDelivery() throws Exception {
        helper.registerClient("RapidSender", "password");
        helper.registerClient("RapidReceiver", "password");
        
        IntegrationTestHelper.TestClient sender = helper.createClient(PlatformType.BUKKIT);
        IntegrationTestHelper.TestClient receiver = helper.createClient(PlatformType.BUKKIT);
        
        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);
        
        sender.authenticate("RapidSender", "password").get(5, TimeUnit.SECONDS);
        receiver.authenticate("RapidReceiver", "password").get(5, TimeUnit.SECONDS);
        
        // Send multiple messages rapidly
        int messageCount = 10;
        List<String> sentContents = new ArrayList<>();
        
        for (int i = 0; i < messageCount; i++) {
            String content = "Rapid message #" + i;
            sentContents.add(content);
            
            ChatMessagePacket message = new ChatMessagePacket(
                UUID.randomUUID(),
                "RapidPlayer",
                "RapidSender",
                "global",
                content
            );
            sender.sendPacket(message);
        }
        
        // Receive all messages
        List<String> receivedContents = new ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            ChatMessagePacket received = receiver.waitForPacket(ChatMessagePacket.class, 5, TimeUnit.SECONDS);
            receivedContents.add(received.getContent());
        }
        
        // All messages should be received (order may vary)
        assertThat(receivedContents).containsExactlyInAnyOrderElementsOf(sentContents);
        
        sender.disconnect();
        receiver.disconnect();
    }
}
