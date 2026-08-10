package com.nova.link.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for AuthManager.
 * 
 * Requirements: 20.2, 20.5
 */
@DisplayName("AuthManager Unit Tests")
class AuthManagerTest {

    private AuthManager authManager;
    private IpBanManager ipBanManager;

    @BeforeEach
    void setUp() {
        ipBanManager = new IpBanManager();
        authManager = new AuthManager(ipBanManager);
    }

    // ==================== hashPassword tests ====================

    @Test
    @DisplayName("hashPassword - produces consistent SHA-256 hash")
    void hashPassword_sameInput_producesConsistentHash() {
        String password = "testPassword123";
        
        String hash1 = AuthManager.hashPassword(password);
        String hash2 = AuthManager.hashPassword(password);
        
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 produces 64 hex chars
    }

    @Test
    @DisplayName("hashPassword - different inputs produce different hashes")
    void hashPassword_differentInputs_produceDifferentHashes() {
        String hash1 = AuthManager.hashPassword("password1");
        String hash2 = AuthManager.hashPassword("password2");
        
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    @DisplayName("hashPassword - throws exception for null input")
    void hashPassword_nullInput_throwsException() {
        assertThatThrownBy(() -> AuthManager.hashPassword(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    // ==================== registerClient tests ====================

    @Test
    @DisplayName("registerClient - registers client successfully")
    void registerClient_validCredentials_registersClient() {
        String passwordHash = AuthManager.hashPassword("secret");
        ClientCredentials credentials = new ClientCredentials("testClient", passwordHash);
        
        authManager.registerClient(credentials);
        
        assertThat(authManager.getCredentials("testClient")).isNotNull();
        assertThat(authManager.getClientCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("registerClient - throws exception for null credentials")
    void registerClient_nullCredentials_throwsException() {
        assertThatThrownBy(() -> authManager.registerClient(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== unregisterClient tests ====================

    @Test
    @DisplayName("unregisterClient - removes registered client")
    void unregisterClient_existingClient_removesClient() {
        String passwordHash = AuthManager.hashPassword("secret");
        ClientCredentials credentials = new ClientCredentials("testClient", passwordHash);
        authManager.registerClient(credentials);
        
        authManager.unregisterClient("testClient");
        
        assertThat(authManager.getCredentials("testClient")).isNull();
        assertThat(authManager.getClientCount()).isEqualTo(0);
    }

    // ==================== authenticate tests ====================

    @Test
    @DisplayName("authenticate - succeeds with valid credentials")
    void authenticate_validCredentials_succeeds() {
        String password = "secretPassword";
        String passwordHash = AuthManager.hashPassword(password);
        ClientCredentials credentials = new ClientCredentials("client1", passwordHash);
        authManager.registerClient(credentials);
        
        AuthResult result = authManager.authenticate("client1", passwordHash, "192.168.1.1");
        
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCredentials()).isNotNull();
        assertThat(result.getCredentials().getUsername()).isEqualTo("client1");
    }

    @Test
    @DisplayName("authenticate - fails with wrong password")
    void authenticate_wrongPassword_fails() {
        String passwordHash = AuthManager.hashPassword("correctPassword");
        ClientCredentials credentials = new ClientCredentials("client1", passwordHash);
        authManager.registerClient(credentials);
        
        String wrongHash = AuthManager.hashPassword("wrongPassword");
        AuthResult result = authManager.authenticate("client1", wrongHash, "192.168.1.1");
        
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-401");
    }

    @Test
    @DisplayName("authenticate - fails with unknown username")
    void authenticate_unknownUsername_fails() {
        String passwordHash = AuthManager.hashPassword("password");
        
        AuthResult result = authManager.authenticate("unknownClient", passwordHash, "192.168.1.1");
        
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-401");
    }

    @Test
    @DisplayName("authenticate - fails with empty username")
    void authenticate_emptyUsername_fails() {
        AuthResult result = authManager.authenticate("", "someHash", "192.168.1.1");
        
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-401");
    }

    @Test
    @DisplayName("authenticate - fails with null password hash")
    void authenticate_nullPasswordHash_fails() {
        String passwordHash = AuthManager.hashPassword("password");
        ClientCredentials credentials = new ClientCredentials("client1", passwordHash);
        authManager.registerClient(credentials);
        
        AuthResult result = authManager.authenticate("client1", null, "192.168.1.1");
        
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-401");
    }

    @Test
    @DisplayName("authenticate - case insensitive hash comparison")
    void authenticate_caseInsensitiveHash_succeeds() {
        String passwordHash = AuthManager.hashPassword("password").toLowerCase();
        ClientCredentials credentials = new ClientCredentials("client1", passwordHash);
        authManager.registerClient(credentials);
        
        String upperCaseHash = passwordHash.toUpperCase();
        AuthResult result = authManager.authenticate("client1", upperCaseHash, "192.168.1.1");
        
        assertThat(result.isSuccess()).isTrue();
    }

    // ==================== authenticateWithPlainPassword tests ====================

    @Test
    @DisplayName("authenticateWithPlainPassword - succeeds with correct password")
    void authenticateWithPlainPassword_correctPassword_succeeds() {
        String password = "myPassword";
        String passwordHash = AuthManager.hashPassword(password);
        ClientCredentials credentials = new ClientCredentials("client1", passwordHash);
        authManager.registerClient(credentials);
        
        AuthResult result = authManager.authenticateWithPlainPassword("client1", password, "192.168.1.1");
        
        assertThat(result.isSuccess()).isTrue();
    }

    // ==================== IP ban integration tests ====================

    @Test
    @DisplayName("authenticate - blocks banned IP")
    void authenticate_bannedIp_fails() {
        String ip = "10.0.0.1";
        // Trigger 3 failures to get banned
        for (int i = 0; i < 3; i++) {
            authManager.authenticate("unknown", "hash", ip);
        }
        
        // Now register a valid client
        String passwordHash = AuthManager.hashPassword("password");
        ClientCredentials credentials = new ClientCredentials("validClient", passwordHash);
        authManager.registerClient(credentials);
        
        // Even with valid credentials, should be blocked
        AuthResult result = authManager.authenticate("validClient", passwordHash, ip);
        
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-429");
    }

    // ==================== super admin tests ====================

    @Test
    @DisplayName("registerSuperAdmin - registers super admin")
    void registerSuperAdmin_validCredentials_registersSuperAdmin() {
        String passwordHash = AuthManager.hashPassword("adminPassword");
        
        authManager.registerSuperAdmin("superAdmin", passwordHash);
        
        assertThat(authManager.isSuperAdmin("superAdmin")).isTrue();
    }

    @Test
    @DisplayName("isSuperAdmin - returns false for regular client")
    void isSuperAdmin_regularClient_returnsFalse() {
        String passwordHash = AuthManager.hashPassword("password");
        ClientCredentials credentials = new ClientCredentials("regularClient", passwordHash);
        authManager.registerClient(credentials);
        
        assertThat(authManager.isSuperAdmin("regularClient")).isFalse();
    }

    @Test
    @DisplayName("isSuperAdmin - returns false for unknown user")
    void isSuperAdmin_unknownUser_returnsFalse() {
        assertThat(authManager.isSuperAdmin("unknownUser")).isFalse();
    }

    // ==================== clearClients tests ====================

    @Test
    @DisplayName("clearClients - removes all clients")
    void clearClients_withClients_removesAll() {
        authManager.registerClient(new ClientCredentials("c1", "hash1"));
        authManager.registerClient(new ClientCredentials("c2", "hash2"));
        
        authManager.clearClients();
        
        assertThat(authManager.getClientCount()).isEqualTo(0);
    }

    // ==================== getIpBanManager tests ====================

    @Test
    @DisplayName("getIpBanManager - returns the IP ban manager")
    void getIpBanManager_returnsIpBanManager() {
        assertThat(authManager.getIpBanManager()).isSameAs(ipBanManager);
    }
}
