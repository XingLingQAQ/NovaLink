package com.nova.link.integration;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.*;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for NovaChat-PNX (PowerNukkitX) plugin communication with NovaLink backend.
 * 
 * Requirements: 23.5 - Verify NovaChat-PNX complete communication flow with NovaLink
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PowerNukkitXIntegrationTest {

    private static IntegrationTestHelper helper;
    private static final String CLIENT_ID = "PNXServer";
    private static final String PASSWORD = "pnx-test-123";
    private static final int TEST_PORT = 18893;

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
    @DisplayName("PowerNukkitX client should connect to NovaLink server")
    void testPNXClientConnection() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.POWERNUKKITX);
        
        boolean connected = client.connect().get(5, TimeUnit.SECONDS);
        
        assertThat(connected).isTrue();
        assertThat(client.isConnected()).isTrue();
        assertThat(client.getPlatform()).isEqualTo(PlatformType.POWERNUKKITX);
        
        client.disconnect();
    }

    @Test
    @Order(2)
    @DisplayName("PowerNukkitX client should authenticate successfully")
    void testPNXClientAuthentication() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.POWERNUKKITX);
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
    @DisplayName("PowerNukkitX client should fail authentication with invalid credentials")
    void testPNXClientAuthenticationFailure() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.POWERNUKKITX);
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
    @DisplayName("PowerNukkitX should communicate with other Bedrock platforms")
    void testPNXBedrockCommunication() throws Exception {
        // Test communication between PowerNukkitX and Nukkit (both Bedrock)
        IntegrationTestHelper.TestClient pnxClient = helper.createClient(PlatformType.POWERNUKKITX);
        IntegrationTestHelper.TestClient nukkitClient = helper.createClient(PlatformType.NUKKIT);
        
        pnxClient.connect().get(5, TimeUnit.SECONDS);
        nukkitClient.connect().get(5, TimeUnit.SECONDS);
        
        helper.registerClient("NukkitServer", PASSWORD);
        
        pnxClient.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        nukkitClient.authenticate("NukkitServer", PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Send chat message from PowerNukkitX
        UUID senderId = UUID.randomUUID();
        ChatMessagePacket chatMessage = new ChatMessagePacket(
            senderId,
            "PNXPlayer",
            CLIENT_ID,
            "global",
            "Hello from PowerNukkitX!"
        );
        pnxClient.sendPacket(chatMessage);
        
        // Nukkit server should receive the message
        ChatMessagePacket received = nukkitClient.waitForPacket(
            ChatMessagePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(received.getSenderId()).isEqualTo(senderId);
        assertThat(received.getSenderName()).isEqualTo("PNXPlayer");
        assertThat(received.getContent()).isEqualTo("Hello from PowerNukkitX!");
        
        pnxClient.disconnect();
        nukkitClient.disconnect();
    }

    @Test
    @Order(5)
    @DisplayName("PowerNukkitX should communicate with Java Edition platforms")
    void testPNXCrossEditionCommunication() throws Exception {
        // Test cross-edition communication between PowerNukkitX (Bedrock) and Bukkit (Java)
        IntegrationTestHelper.TestClient pnxClient = helper.createClient(PlatformType.POWERNUKKITX);
        IntegrationTestHelper.TestClient bukkitClient = helper.createClient(PlatformType.BUKKIT);
        
        pnxClient.connect().get(5, TimeUnit.SECONDS);
        bukkitClient.connect().get(5, TimeUnit.SECONDS);
        
        helper.registerClient("JavaServer", PASSWORD);
        
        pnxClient.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        bukkitClient.authenticate("JavaServer", PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Send chat message from PowerNukkitX (Bedrock)
        UUID senderId = UUID.randomUUID();
        ChatMessagePacket chatMessage = new ChatMessagePacket(
            senderId,
            "BedrockPNXPlayer",
            CLIENT_ID,
            "global",
            "Cross-edition message from PNX!"
        );
        pnxClient.sendPacket(chatMessage);
        
        // Java Edition server should receive the message
        ChatMessagePacket received = bukkitClient.waitForPacket(
            ChatMessagePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(received.getSenderId()).isEqualTo(senderId);
        assertThat(received.getSenderName()).isEqualTo("BedrockPNXPlayer");
        assertThat(received.getContent()).isEqualTo("Cross-edition message from PNX!");
        
        pnxClient.disconnect();
        bukkitClient.disconnect();
    }

    @Test
    @Order(6)
    @DisplayName("PowerNukkitX client should handle channel operations")
    void testPNXChannelOperations() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.POWERNUKKITX);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate(CLIENT_ID, PASSWORD).get(5, TimeUnit.SECONDS);
        
        // Test JOIN
        ChannelActionPacket joinAction = new ChannelActionPacket(ChannelAction.JOIN, "pnx-channel");
        client.sendPacket(joinAction);
        
        ChannelActionResponsePacket joinResponse = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        assertThat(joinResponse.isSuccess()).isTrue();
        assertThat(joinResponse.getAction()).isEqualTo(ChannelAction.JOIN);
        
        // Test LEAVE
        ChannelActionPacket leaveAction = new ChannelActionPacket(ChannelAction.LEAVE, "pnx-channel");
        client.sendPacket(leaveAction);
        
        ChannelActionResponsePacket leaveResponse = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        assertThat(leaveResponse.isSuccess()).isTrue();
        assertThat(leaveResponse.getAction()).isEqualTo(ChannelAction.LEAVE);
        
        client.disconnect();
    }

    @Test
    @Order(7)
    @DisplayName("PowerNukkitX client should handle keep-alive packets")
    void testPNXKeepAlive() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.POWERNUKKITX);
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
