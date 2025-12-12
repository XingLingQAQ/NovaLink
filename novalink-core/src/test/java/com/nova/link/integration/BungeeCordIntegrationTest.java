package com.nova.link.integration;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.*;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for NovaChat-BungeeCord plugin communication with NovaLink backend.
 * 
 * Requirements: 23.3 - Verify NovaChat-BungeeCord complete communication flow with NovaLink
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BungeeCordIntegrationTest {

    private static IntegrationTestHelper helper;
    private static final String CLIENT_ID = "BungeeProxy";
    private static final String PASSWORD = "bungee-test-123";
    private static final int TEST_PORT = 18891;

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
    @DisplayName("BungeeCord client should connect to NovaLink server")
    void testBungeeCordClientConnection() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUNGEECORD);
        
        boolean connected = client.connect().get(5, TimeUnit.SECONDS);
        
        assertThat(connected).isTrue();
        assertThat(client.isConnected()).isTrue();
        assertThat(client.getPlatform()).isEqualTo(PlatformType.BUNGEECORD);
        
        client.disconnect();
    }

    @Test
    @Order(2)
    @DisplayName("BungeeCord client should authenticate successfully")
    void testBungeeCordClientAuthentication() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUNGEECORD);
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
    @DisplayName("BungeeCord client should fail authentication with invalid credentials")
    void testBungeeCordClientAuthenticationFailure() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUNGEECORD);
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
    @DisplayName("BungeeCord proxy should route chat messages")
    void testBungeeCordChatMessageRouting() throws Exception {
        IntegrationTestHelper.TestClient bungeeClient = helper.createClient(PlatformType.BUNGEECORD);
        IntegrationTestHelper.TestClient backendClient = helper.createClient(PlatformType.BUKKIT);
        
        bungeeClient.connect().get(5, TimeUnit.SECONDS);
        backendClient.connect().get(5, TimeUnit.SECONDS);
        
        helper.registerClient("BungeeBackend", PASSWORD);
        
        bungeeClient.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        backendClient.authenticate("BungeeBackend", PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Send chat message from BungeeCord
        UUID senderId = UUID.randomUUID();
        ChatMessagePacket chatMessage = new ChatMessagePacket(
            senderId,
            "BungeePlayer",
            CLIENT_ID,
            "global",
            "Message from BungeeCord proxy"
        );
        bungeeClient.sendPacket(chatMessage);
        
        // Backend server should receive the message
        ChatMessagePacket received = backendClient.waitForPacket(
            ChatMessagePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(received.getSenderId()).isEqualTo(senderId);
        assertThat(received.getSenderName()).isEqualTo("BungeePlayer");
        assertThat(received.getContent()).isEqualTo("Message from BungeeCord proxy");
        
        bungeeClient.disconnect();
        backendClient.disconnect();
    }

    @Test
    @Order(5)
    @DisplayName("BungeeCord client should handle channel operations")
    void testBungeeCordChannelOperations() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUNGEECORD);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Test JOIN
        ChannelActionPacket joinAction = new ChannelActionPacket(ChannelAction.JOIN, "admin");
        client.sendPacket(joinAction);
        
        ChannelActionResponsePacket joinResponse = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        assertThat(joinResponse.isSuccess()).isTrue();
        assertThat(joinResponse.getAction()).isEqualTo(ChannelAction.JOIN);
        
        // Test LEAVE
        ChannelActionPacket leaveAction = new ChannelActionPacket(ChannelAction.LEAVE, "admin");
        client.sendPacket(leaveAction);
        
        ChannelActionResponsePacket leaveResponse = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        assertThat(leaveResponse.isSuccess()).isTrue();
        assertThat(leaveResponse.getAction()).isEqualTo(ChannelAction.LEAVE);
        
        client.disconnect();
    }

    @Test
    @Order(6)
    @DisplayName("BungeeCord client should handle keep-alive packets")
    void testBungeeCordKeepAlive() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUNGEECORD);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        
        long timestamp = System.currentTimeMillis();
        KeepAlivePacket keepAlive = new KeepAlivePacket(timestamp);
        client.sendPacket(keepAlive);
        
        KeepAlivePacket response = client.waitForPacket(
            KeepAlivePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.getTimestamp()).isEqualTo(timestamp);
        
        client.disconnect();
    }
}
