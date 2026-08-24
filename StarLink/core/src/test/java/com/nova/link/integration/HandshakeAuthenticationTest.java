package com.nova.link.integration;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for handshake authentication flow verification.
 * 
 * Requirements: 23.6 - Verify handshake authentication flow
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HandshakeAuthenticationTest {

    private static IntegrationTestHelper helper;
    private static final int TEST_PORT = 18894;

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
    @DisplayName("Handshake should succeed with valid credentials")
    void testHandshakeWithValidCredentials() throws Exception {
        String clientId = "ValidClient";
        String password = "valid-password";
        helper.registerClient(clientId, password);
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        
        HandshakeResponsePacket response = client.authenticate(clientId, password)
            .get(5, TimeUnit.SECONDS);
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getErrorCode()).isEmpty();
        assertThat(response.getMessage()).isNotEmpty();
        assertThat(client.isAuthenticated()).isTrue();
        
        client.disconnect();
    }

    @Test
    @Order(2)
    @DisplayName("Handshake should fail with invalid password")
    void testHandshakeWithInvalidPassword() throws Exception {
        String clientId = "InvalidPwdClient";
        String password = "correct-password";
        helper.registerClient(clientId, password);
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        
        HandshakeResponsePacket response = client.authenticate(clientId, "wrong-password")
            .get(5, TimeUnit.SECONDS);
        
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-401");
        assertThat(client.isAuthenticated()).isFalse();
        assertThat(awaitDisconnected(client, 2, TimeUnit.SECONDS))
                .as("server must close the channel after writing NC-401")
                .isTrue();
        
        client.disconnect();
    }

    @Test
    @Order(3)
    @DisplayName("Handshake should fail with unregistered client")
    void testHandshakeWithUnregisteredClient() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        
        HandshakeResponsePacket response = client.authenticate("UnknownClient", "any-password")
            .get(5, TimeUnit.SECONDS);
        
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-401");
        assertThat(client.isAuthenticated()).isFalse();
        
        client.disconnect();
    }

    @Test
    @Order(4)
    @DisplayName("Handshake should work for all platform types")
    void testHandshakeForAllPlatforms() throws Exception {
        PlatformType[] platforms = {
            PlatformType.BUKKIT,
            PlatformType.VELOCITY,
            PlatformType.BUNGEECORD,
            PlatformType.NUKKIT,
            PlatformType.FABRIC,
            PlatformType.NEOFORGE,
            PlatformType.QUILT,
            PlatformType.FORGE,
            PlatformType.POCKETMINE,
            PlatformType.ENDSTONE,
            PlatformType.POWERNUKKITX
        };
        
        for (PlatformType platform : platforms) {
            String clientId = "Client_" + platform.name();
            String password = "password_" + platform.name();
            helper.registerClient(clientId, password);
            
            IntegrationTestHelper.TestClient client = helper.createClient(platform);
            client.connect().get(5, TimeUnit.SECONDS);
            
            HandshakeResponsePacket response = client.authenticate(clientId, password)
                .get(5, TimeUnit.SECONDS);
            
            assertThat(response.isSuccess())
                .as("Authentication should succeed for platform: " + platform)
                .isTrue();
            assertThat(client.getPlatform()).isEqualTo(platform);
            
            client.disconnect();
        }
    }

    @Test
    @Order(5)
    @DisplayName("Handshake should include correct protocol version")
    void testHandshakeProtocolVersion() throws Exception {
        String clientId = "ProtocolClient";
        String password = "protocol-password";
        helper.registerClient(clientId, password);
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        
        // The handshake packet includes protocol version
        HandshakeResponsePacket response = client.authenticate(clientId, password)
            .get(5, TimeUnit.SECONDS);
        
        // If protocol version mismatch, server would reject
        assertThat(response.isSuccess()).isTrue();
        
        client.disconnect();
    }

    @Test
    @Order(6)
    @DisplayName("Multiple clients should authenticate independently")
    void testMultipleClientAuthentication() throws Exception {
        // Register multiple clients
        helper.registerClient("Client1", "password1");
        helper.registerClient("Client2", "password2");
        helper.registerClient("Client3", "password3");
        
        IntegrationTestHelper.TestClient client1 = helper.createClient(PlatformType.BUKKIT);
        IntegrationTestHelper.TestClient client2 = helper.createClient(PlatformType.VELOCITY);
        IntegrationTestHelper.TestClient client3 = helper.createClient(PlatformType.NUKKIT);
        
        // Connect all clients
        client1.connect().get(5, TimeUnit.SECONDS);
        client2.connect().get(5, TimeUnit.SECONDS);
        client3.connect().get(5, TimeUnit.SECONDS);
        
        // Authenticate all clients
        HandshakeResponsePacket response1 = client1.authenticate("Client1", "password1")
            .get(5, TimeUnit.SECONDS);
        HandshakeResponsePacket response2 = client2.authenticate("Client2", "password2")
            .get(5, TimeUnit.SECONDS);
        HandshakeResponsePacket response3 = client3.authenticate("Client3", "password3")
            .get(5, TimeUnit.SECONDS);
        
        // All should succeed
        assertThat(response1.isSuccess()).isTrue();
        assertThat(response2.isSuccess()).isTrue();
        assertThat(response3.isSuccess()).isTrue();
        
        assertThat(client1.isAuthenticated()).isTrue();
        assertThat(client2.isAuthenticated()).isTrue();
        assertThat(client3.isAuthenticated()).isTrue();
        
        client1.disconnect();
        client2.disconnect();
        client3.disconnect();
    }

    @Test
    @Order(7)
    @DisplayName("Password hash should be SHA-256")
    void testPasswordHashFormat() throws Exception {
        String password = "test-password";
        String hash = IntegrationTestHelper.hashPassword(password);
        
        // SHA-256 produces 64 hex characters
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]+");
        
        // Same password should produce same hash
        String hash2 = IntegrationTestHelper.hashPassword(password);
        assertThat(hash).isEqualTo(hash2);
        
        // Different password should produce different hash
        String differentHash = IntegrationTestHelper.hashPassword("different-password");
        assertThat(hash).isNotEqualTo(differentHash);
    }

    private static boolean awaitDisconnected(IntegrationTestHelper.TestClient client,
                                             long timeout,
                                             TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (client.isConnected() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        return !client.isConnected();
    }
}
