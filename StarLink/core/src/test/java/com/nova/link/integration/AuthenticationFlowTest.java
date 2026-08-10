package com.nova.link.integration;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for authentication flow completeness.
 * Verifies the complete authentication process including edge cases.
 * 
 * Requirements: 24.4 - Verify authentication flow completeness
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthenticationFlowTest {

    private static final int TEST_PORT = 18901;
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
    @DisplayName("Valid credentials should authenticate successfully")
    void testValidCredentialsAuthentication() throws Exception {
        server.registerClient("validClient", "validPassword");

        MultiClientSimulator.SimulatedClient client = 
            simulator.createClient("validClient", PlatformType.BUKKIT);

        client.connect().get(5, TimeUnit.SECONDS);
        assertThat(client.isConnected()).isTrue();

        HandshakeResponsePacket response = client.authenticate("validPassword")
            .get(5, TimeUnit.SECONDS);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getErrorCode()).isEmpty();
        assertThat(client.isAuthenticated()).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("Invalid password should fail authentication")
    void testInvalidPasswordAuthentication() throws Exception {
        server.registerClient("pwdClient", "correctPassword");

        MultiClientSimulator.SimulatedClient client = 
            simulator.createClient("pwdClient", PlatformType.BUKKIT);

        client.connect().get(5, TimeUnit.SECONDS);

        HandshakeResponsePacket response = client.authenticate("wrongPassword")
            .get(5, TimeUnit.SECONDS);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-401");
        assertThat(client.isAuthenticated()).isFalse();
    }

    @Test
    @Order(3)
    @DisplayName("Unregistered client should fail authentication")
    void testUnregisteredClientAuthentication() throws Exception {
        MultiClientSimulator.SimulatedClient client = 
            simulator.createClient("unknownClient", PlatformType.BUKKIT);

        client.connect().get(5, TimeUnit.SECONDS);

        HandshakeResponsePacket response = client.authenticate("anyPassword")
            .get(5, TimeUnit.SECONDS);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-401");
        assertThat(client.isAuthenticated()).isFalse();
    }

    @Test
    @Order(4)
    @DisplayName("All platform types should authenticate successfully")
    void testAllPlatformTypesAuthentication() throws Exception {
        PlatformType[] platforms = PlatformType.values();

        for (PlatformType platform : platforms) {
            String clientId = "platform_" + platform.name();
            server.registerClient(clientId, "password");

            MultiClientSimulator.SimulatedClient client = 
                simulator.createClient(clientId, platform);

            client.connect().get(5, TimeUnit.SECONDS);
            HandshakeResponsePacket response = client.authenticate("password")
                .get(5, TimeUnit.SECONDS);

            assertThat(response.isSuccess())
                .as("Platform %s should authenticate successfully", platform)
                .isTrue();
            assertThat(client.getPlatform()).isEqualTo(platform);

            client.disconnect();
        }
    }

    @Test
    @Order(5)
    @DisplayName("Multiple clients should authenticate independently")
    void testMultipleClientsIndependentAuthentication() throws Exception {
        int clientCount = 5;

        // Register clients
        for (int i = 0; i < clientCount; i++) {
            server.registerClient("multiClient_" + i, "password_" + i);
        }

        // Create clients
        List<MultiClientSimulator.SimulatedClient> clients = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            MultiClientSimulator.SimulatedClient client = 
                simulator.createClient("multiClient_" + i, PlatformType.BUKKIT);
            clients.add(client);
        }

        // Connect all
        for (MultiClientSimulator.SimulatedClient client : clients) {
            client.connect().get(5, TimeUnit.SECONDS);
        }

        // Authenticate all with correct passwords
        List<HandshakeResponsePacket> responses = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            HandshakeResponsePacket response = clients.get(i)
                .authenticate("password_" + i)
                .get(5, TimeUnit.SECONDS);
            responses.add(response);
        }

        // All should succeed
        for (int i = 0; i < clientCount; i++) {
            assertThat(responses.get(i).isSuccess())
                .as("Client %d should authenticate successfully", i)
                .isTrue();
            assertThat(clients.get(i).isAuthenticated()).isTrue();
        }
    }

    @Test
    @Order(6)
    @DisplayName("Password hash should be SHA-256 format")
    void testPasswordHashFormat() {
        String password = "testPassword123";
        String hash = EmbeddedNovaLinkServer.hashPassword(password);

        // SHA-256 produces 64 hex characters
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]+");

        // Same password should produce same hash
        String hash2 = EmbeddedNovaLinkServer.hashPassword(password);
        assertThat(hash).isEqualTo(hash2);

        // Different password should produce different hash
        String differentHash = EmbeddedNovaLinkServer.hashPassword("differentPassword");
        assertThat(hash).isNotEqualTo(differentHash);
    }

    @Test
    @Order(7)
    @DisplayName("Protocol version should be included in handshake")
    void testProtocolVersionInHandshake() throws Exception {
        server.registerClient("protoClient", "password");

        MultiClientSimulator.SimulatedClient client = 
            simulator.createClient("protoClient", PlatformType.BUKKIT);

        client.connect().get(5, TimeUnit.SECONDS);

        // The handshake includes protocol version
        HandshakeResponsePacket response = client.authenticate("password")
            .get(5, TimeUnit.SECONDS);

        // If protocol version was wrong, server would reject
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    @Order(8)
    @DisplayName("Concurrent authentication requests should all be handled")
    void testConcurrentAuthentication() throws Exception {
        int clientCount = 10;

        // Register all clients
        for (int i = 0; i < clientCount; i++) {
            server.registerClient("concurrent_" + i, "password");
        }

        // Create and connect all clients
        List<MultiClientSimulator.SimulatedClient> clients = 
            simulator.createClients(clientCount, PlatformType.BUKKIT, "concurrent");

        simulator.connectAll().get(10, TimeUnit.SECONDS);

        // Authenticate all concurrently
        List<java.util.concurrent.CompletableFuture<HandshakeResponsePacket>> futures = new ArrayList<>();
        for (MultiClientSimulator.SimulatedClient client : clients) {
            futures.add(client.authenticate("password"));
        }

        // Wait for all and verify
        for (int i = 0; i < clientCount; i++) {
            HandshakeResponsePacket response = futures.get(i).get(5, TimeUnit.SECONDS);
            assertThat(response.isSuccess())
                .as("Client %d should authenticate successfully", i)
                .isTrue();
        }

        assertThat(simulator.getAuthenticatedCount()).isEqualTo(clientCount);
    }

    @Test
    @Order(9)
    @DisplayName("Re-authentication should work after disconnect")
    void testReauthenticationAfterDisconnect() throws Exception {
        server.registerClient("reconnectClient", "password");

        MultiClientSimulator.SimulatedClient client = 
            simulator.createClient("reconnectClient", PlatformType.BUKKIT);

        // First connection
        client.connect().get(5, TimeUnit.SECONDS);
        HandshakeResponsePacket response1 = client.authenticate("password")
            .get(5, TimeUnit.SECONDS);
        assertThat(response1.isSuccess()).isTrue();

        // Disconnect
        client.disconnect();
        assertThat(client.isConnected()).isFalse();
        assertThat(client.isAuthenticated()).isFalse();

        // Create new client (simulating reconnection)
        MultiClientSimulator.SimulatedClient newClient = 
            simulator.createClient("reconnectClient", PlatformType.BUKKIT);

        // Reconnect and re-authenticate
        newClient.connect().get(5, TimeUnit.SECONDS);
        HandshakeResponsePacket response2 = newClient.authenticate("password")
            .get(5, TimeUnit.SECONDS);

        assertThat(response2.isSuccess()).isTrue();
        assertThat(newClient.isAuthenticated()).isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("Empty client ID should fail authentication")
    void testEmptyClientIdAuthentication() throws Exception {
        MultiClientSimulator.SimulatedClient client = 
            simulator.createClient("", PlatformType.BUKKIT);

        client.connect().get(5, TimeUnit.SECONDS);

        HandshakeResponsePacket response = client.authenticate("password")
            .get(5, TimeUnit.SECONDS);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-401");
    }

    @Test
    @Order(11)
    @DisplayName("Special characters in password should work")
    void testSpecialCharactersInPassword() throws Exception {
        String specialPassword = "p@$$w0rd!#%^&*()_+-=[]{}|;':\",./<>?";
        server.registerClient("specialPwdClient", specialPassword);

        MultiClientSimulator.SimulatedClient client = 
            simulator.createClient("specialPwdClient", PlatformType.BUKKIT);

        client.connect().get(5, TimeUnit.SECONDS);

        HandshakeResponsePacket response = client.authenticate(specialPassword)
            .get(5, TimeUnit.SECONDS);

        assertThat(response.isSuccess()).isTrue();
        assertThat(client.isAuthenticated()).isTrue();
    }

    @Test
    @Order(12)
    @DisplayName("Unicode in client ID should work")
    void testUnicodeClientId() throws Exception {
        String unicodeClientId = "客户端_クライアント_клиент";
        server.registerClient(unicodeClientId, "password");

        MultiClientSimulator.SimulatedClient client = 
            simulator.createClient(unicodeClientId, PlatformType.BUKKIT);

        client.connect().get(5, TimeUnit.SECONDS);

        HandshakeResponsePacket response = client.authenticate("password")
            .get(5, TimeUnit.SECONDS);

        assertThat(response.isSuccess()).isTrue();
        assertThat(client.isAuthenticated()).isTrue();
    }
}
