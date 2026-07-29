package com.nova.link.integration;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.*;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for NovaChat-Bukkit plugin communication with NovaLink backend.
 * 
 * Requirements: 23.1 - Verify NovaChat-Bukkit complete communication flow with NovaLink
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BukkitIntegrationTest {

    private static IntegrationTestHelper helper;
    private static final String CLIENT_ID = "BukkitTestServer";
    private static final String PASSWORD = "test-password-123";
    private static final int TEST_PORT = 18889;

    @BeforeAll
    static void setUp() throws Exception {
        helper = new IntegrationTestHelper();
        helper.startServer(TEST_PORT);
        helper.registerClient(CLIENT_ID, PASSWORD);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (helper != null) {
            helper.stopServer();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Bukkit client should connect to NovaLink server")
    void testBukkitClientConnection() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        
        boolean connected = client.connect().get(5, TimeUnit.SECONDS);
        
        assertThat(connected).isTrue();
        assertThat(client.isConnected()).isTrue();
        assertThat(client.getPlatform()).isEqualTo(PlatformType.BUKKIT);
        
        client.disconnect();
    }

    @Test
    @Order(2)
    @DisplayName("Bukkit client should authenticate successfully with valid credentials")
    void testBukkitClientAuthentication() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        
        HandshakeResponsePacket response = client.authenticate(CLIENT_ID, PASSWORD)
            .get(5, TimeUnit.SECONDS);
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getErrorCode()).isEmpty();
        assertThat(client.isAuthenticated()).isTrue();
        
        client.disconnect();
    }

    @Test
    @Order(3)
    @DisplayName("Bukkit client should fail authentication with invalid credentials")
    void testBukkitClientAuthenticationFailure() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        
        HandshakeResponsePacket response = client.authenticate(CLIENT_ID, "wrong-password")
            .get(5, TimeUnit.SECONDS);
        
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-401");
        assertThat(client.isAuthenticated()).isFalse();
        
        client.disconnect();
    }

    @Test
    @Order(4)
    @DisplayName("Bukkit client should send and receive chat messages")
    void testBukkitChatMessageFlow() throws Exception {
        // Create and authenticate two clients
        IntegrationTestHelper.TestClient sender = helper.createClient(PlatformType.BUKKIT);
        IntegrationTestHelper.TestClient receiver = helper.createClient(PlatformType.BUKKIT);
        
        sender.connect().get(5, TimeUnit.SECONDS);
        receiver.connect().get(5, TimeUnit.SECONDS);
        
        // Register second client
        helper.registerClient("BukkitReceiver", PASSWORD);
        
        sender.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        receiver.authenticate("BukkitReceiver", PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Send chat message
        UUID senderId = UUID.randomUUID();
        ChatMessagePacket chatMessage = new ChatMessagePacket(
            senderId,
            "TestPlayer",
            CLIENT_ID,
            "global",
            "Hello from Bukkit!"
        );
        sender.sendPacket(chatMessage);
        
        // Receiver should get the message
        ChatMessagePacket received = receiver.waitForPacket(
            ChatMessagePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(received.getSenderId()).isEqualTo(senderId);
        assertThat(received.getSenderName()).isEqualTo("TestPlayer");
        assertThat(received.getContent()).isEqualTo("Hello from Bukkit!");
        assertThat(received.getChannelId()).isEqualTo("global");
        
        sender.disconnect();
        receiver.disconnect();
    }

    @Test
    @Order(5)
    @DisplayName("Bukkit client should handle channel join action")
    void testBukkitChannelJoin() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Send channel join action
        ChannelActionPacket joinAction = new ChannelActionPacket(
            ChannelAction.JOIN,
            "global"
        );
        client.sendPacket(joinAction);
        
        // Wait for response
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.JOIN);
        assertThat(response.getChannelId()).isEqualTo("global");
        
        client.disconnect();
    }

    @Test
    @Order(6)
    @DisplayName("Bukkit client should handle channel leave action")
    void testBukkitChannelLeave() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Send channel leave action
        ChannelActionPacket leaveAction = new ChannelActionPacket(
            ChannelAction.LEAVE,
            "global"
        );
        client.sendPacket(leaveAction);
        
        // Wait for response
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.LEAVE);
        assertThat(response.getChannelId()).isEqualTo("global");
        
        client.disconnect();
    }

    @Test
    @Order(7)
    @DisplayName("Bukkit client should handle keep-alive packets")
    void testBukkitKeepAlive() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Send keep-alive
        long timestamp = System.currentTimeMillis();
        KeepAlivePacket keepAlive = new KeepAlivePacket(timestamp);
        client.sendPacket(keepAlive);
        
        // Wait for response
        KeepAlivePacket response = client.waitForPacket(
            KeepAlivePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.getTimestamp()).isEqualTo(timestamp);
        
        client.disconnect();
    }
}
