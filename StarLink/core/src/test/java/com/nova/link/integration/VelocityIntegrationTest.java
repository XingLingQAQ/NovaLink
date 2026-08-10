package com.nova.link.integration;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.*;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for NovaChat-Velocity plugin communication with NovaLink backend.
 * 
 * Requirements: 23.2 - Verify NovaChat-Velocity complete communication flow with NovaLink
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VelocityIntegrationTest {

    private static IntegrationTestHelper helper;
    private static final String CLIENT_ID = "VelocityProxy";
    private static final String PASSWORD = "velocity-test-123";
    private static final int TEST_PORT = 18890;

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
    @DisplayName("Velocity client should connect to NovaLink server")
    void testVelocityClientConnection() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.VELOCITY);
        
        boolean connected = client.connect().get(5, TimeUnit.SECONDS);
        
        assertThat(connected).isTrue();
        assertThat(client.isConnected()).isTrue();
        assertThat(client.getPlatform()).isEqualTo(PlatformType.VELOCITY);
        
        client.disconnect();
    }

    @Test
    @Order(2)
    @DisplayName("Velocity client should authenticate successfully")
    void testVelocityClientAuthentication() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.VELOCITY);
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
    @DisplayName("Velocity client should fail authentication with invalid credentials")
    void testVelocityClientAuthenticationFailure() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.VELOCITY);
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
    @DisplayName("Velocity proxy should route chat messages between backend servers")
    void testVelocityChatMessageRouting() throws Exception {
        // Velocity acts as a proxy, routing messages between backend servers
        IntegrationTestHelper.TestClient velocityClient = helper.createClient(PlatformType.VELOCITY);
        IntegrationTestHelper.TestClient backendClient = helper.createClient(PlatformType.BUKKIT);
        
        velocityClient.connect().get(5, TimeUnit.SECONDS);
        backendClient.connect().get(5, TimeUnit.SECONDS);
        
        helper.registerClient("BackendServer", PASSWORD);
        
        velocityClient.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        backendClient.authenticate("BackendServer", PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Send chat message from Velocity
        UUID senderId = UUID.randomUUID();
        ChatMessagePacket chatMessage = new ChatMessagePacket(
            senderId,
            "ProxyPlayer",
            CLIENT_ID,
            "global",
            "Message from Velocity proxy"
        );
        velocityClient.sendPacket(chatMessage);
        
        // Backend server should receive the message
        ChatMessagePacket received = backendClient.waitForPacket(
            ChatMessagePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(received.getSenderId()).isEqualTo(senderId);
        assertThat(received.getSenderName()).isEqualTo("ProxyPlayer");
        assertThat(received.getContent()).isEqualTo("Message from Velocity proxy");
        
        velocityClient.disconnect();
        backendClient.disconnect();
    }

    @Test
    @Order(5)
    @DisplayName("Velocity client should handle channel operations")
    void testVelocityChannelOperations() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.VELOCITY);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Test JOIN
        ChannelActionPacket joinAction = new ChannelActionPacket(ChannelAction.JOIN, "staff");
        client.sendPacket(joinAction);
        
        ChannelActionResponsePacket joinResponse = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        assertThat(joinResponse.isSuccess()).isTrue();
        assertThat(joinResponse.getAction()).isEqualTo(ChannelAction.JOIN);
        
        // Test LEAVE
        ChannelActionPacket leaveAction = new ChannelActionPacket(ChannelAction.LEAVE, "staff");
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
    @DisplayName("Velocity client should handle keep-alive packets")
    void testVelocityKeepAlive() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.VELOCITY);
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
