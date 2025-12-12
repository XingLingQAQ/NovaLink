package com.nova.link.integration;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.*;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for NovaChat-Nukkit plugin communication with NovaLink backend.
 * 
 * Requirements: 23.4 - Verify NovaChat-Nukkit complete communication flow with NovaLink
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NukkitIntegrationTest {

    private static IntegrationTestHelper helper;
    private static final String CLIENT_ID = "NukkitServer";
    private static final String PASSWORD = "nukkit-test-123";
    private static final int TEST_PORT = 18892;

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
    @DisplayName("Nukkit client should connect to NovaLink server")
    void testNukkitClientConnection() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.NUKKIT);
        
        boolean connected = client.connect().get(5, TimeUnit.SECONDS);
        
        assertThat(connected).isTrue();
        assertThat(client.isConnected()).isTrue();
        assertThat(client.getPlatform()).isEqualTo(PlatformType.NUKKIT);
        
        client.disconnect();
    }

    @Test
    @Order(2)
    @DisplayName("Nukkit client should authenticate successfully")
    void testNukkitClientAuthentication() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.NUKKIT);
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
    @DisplayName("Nukkit client should fail authentication with invalid credentials")
    void testNukkitClientAuthenticationFailure() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.NUKKIT);
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
    @DisplayName("Nukkit Bedrock client should communicate with Java Edition clients")
    void testNukkitCrossEditionCommunication() throws Exception {
        // Test cross-edition communication between Nukkit (Bedrock) and Bukkit (Java)
        IntegrationTestHelper.TestClient nukkitClient = helper.createClient(PlatformType.NUKKIT);
        IntegrationTestHelper.TestClient bukkitClient = helper.createClient(PlatformType.BUKKIT);
        
        nukkitClient.connect().get(5, TimeUnit.SECONDS);
        bukkitClient.connect().get(5, TimeUnit.SECONDS);
        
        helper.registerClient("JavaServer", PASSWORD);
        
        nukkitClient.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        bukkitClient.authenticate("JavaServer", PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Send chat message from Nukkit (Bedrock)
        UUID senderId = UUID.randomUUID();
        ChatMessagePacket chatMessage = new ChatMessagePacket(
            senderId,
            "BedrockPlayer",
            CLIENT_ID,
            "global",
            "Hello from Bedrock Edition!"
        );
        nukkitClient.sendPacket(chatMessage);
        
        // Java Edition server should receive the message
        ChatMessagePacket received = bukkitClient.waitForPacket(
            ChatMessagePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(received.getSenderId()).isEqualTo(senderId);
        assertThat(received.getSenderName()).isEqualTo("BedrockPlayer");
        assertThat(received.getContent()).isEqualTo("Hello from Bedrock Edition!");
        
        nukkitClient.disconnect();
        bukkitClient.disconnect();
    }

    @Test
    @Order(5)
    @DisplayName("Nukkit client should handle channel operations")
    void testNukkitChannelOperations() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.NUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Test JOIN
        ChannelActionPacket joinAction = new ChannelActionPacket(ChannelAction.JOIN, "bedrock");
        client.sendPacket(joinAction);
        
        ChannelActionResponsePacket joinResponse = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        assertThat(joinResponse.isSuccess()).isTrue();
        assertThat(joinResponse.getAction()).isEqualTo(ChannelAction.JOIN);
        
        // Test LEAVE
        ChannelActionPacket leaveAction = new ChannelActionPacket(ChannelAction.LEAVE, "bedrock");
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
    @DisplayName("Nukkit client should handle keep-alive packets")
    void testNukkitKeepAlive() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.NUKKIT);
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
