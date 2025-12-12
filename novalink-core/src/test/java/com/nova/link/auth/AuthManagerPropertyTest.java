package com.nova.link.auth;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for AuthManager.
 * 
 * Tests correctness properties defined in the design document.
 */
public class AuthManagerPropertyTest {

    /**
     * **Feature: starchat-starlink, Property 1: Authentication Hash Consistency**
     * 
     * For any username and password combination, computing the SHA-256 hash twice
     * should produce identical results.
     * 
     * **Validates: Requirements 1.1**
     */
    @Property(tries = 100)
    void hashPasswordProducesConsistentResults(
            @ForAll @StringLength(min = 1, max = 100) String password
    ) {
        // Compute hash twice
        String hash1 = AuthManager.hashPassword(password);
        String hash2 = AuthManager.hashPassword(password);

        // Both hashes should be identical
        assertThat(hash1).isEqualTo(hash2);
        
        // Hash should be 64 characters (256 bits = 32 bytes = 64 hex chars)
        assertThat(hash1).hasSize(64);
        
        // Hash should only contain hex characters
        assertThat(hash1).matches("[0-9a-f]{64}");
    }

    /**
     * Additional property: Different passwords should produce different hashes
     * (with extremely high probability due to SHA-256 collision resistance).
     */
    @Property(tries = 100)
    void differentPasswordsProduceDifferentHashes(
            @ForAll @StringLength(min = 1, max = 50) String password1,
            @ForAll @StringLength(min = 1, max = 50) String password2
    ) {
        Assume.that(!password1.equals(password2));

        String hash1 = AuthManager.hashPassword(password1);
        String hash2 = AuthManager.hashPassword(password2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    /**
     * Property: Hash is deterministic across multiple AuthManager instances.
     */
    @Property(tries = 100)
    void hashIsDeterministicAcrossInstances(
            @ForAll @StringLength(min = 1, max = 100) String password
    ) {
        // hashPassword is static, but verify it's truly deterministic
        String hash1 = AuthManager.hashPassword(password);
        
        // Simulate "different instance" by calling again
        String hash2 = AuthManager.hashPassword(password);
        
        assertThat(hash1).isEqualTo(hash2);
    }

    /**
     * **Feature: starchat-starlink, Property 2: Authentication Success/Failure Determinism**
     * 
     * For any client credentials, if the credentials match the backend configuration,
     * authentication should succeed; otherwise, it should fail with NC-401.
     * 
     * **Validates: Requirements 1.2, 1.3**
     */
    @Property(tries = 100)
    void authenticationSucceedsWithMatchingCredentials(
            @ForAll @StringLength(min = 1, max = 50) String username,
            @ForAll @StringLength(min = 1, max = 50) String password,
            @ForAll @StringLength(min = 1, max = 50) String displayName
    ) {
        // Setup
        AuthManager authManager = new AuthManager();
        String passwordHash = AuthManager.hashPassword(password);
        ClientCredentials credentials = new ClientCredentials(username, passwordHash, displayName);
        authManager.registerClient(credentials);

        // Test with correct credentials
        AuthResult result = authManager.authenticate(username, passwordHash, "127.0.0.1");

        // Should succeed
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCredentials()).isNotNull();
        assertThat(result.getCredentials().getUsername()).isEqualTo(username);
    }

    /**
     * Property 2 (continued): Authentication fails with wrong password.
     * 
     * **Validates: Requirements 1.2, 1.3**
     */
    @Property(tries = 100)
    void authenticationFailsWithWrongPassword(
            @ForAll @StringLength(min = 1, max = 50) String username,
            @ForAll @StringLength(min = 1, max = 50) String correctPassword,
            @ForAll @StringLength(min = 1, max = 50) String wrongPassword,
            @ForAll @StringLength(min = 1, max = 50) String displayName
    ) {
        Assume.that(!correctPassword.equals(wrongPassword));

        // Setup
        AuthManager authManager = new AuthManager();
        String correctHash = AuthManager.hashPassword(correctPassword);
        String wrongHash = AuthManager.hashPassword(wrongPassword);
        ClientCredentials credentials = new ClientCredentials(username, correctHash, displayName);
        authManager.registerClient(credentials);

        // Test with wrong password
        AuthResult result = authManager.authenticate(username, wrongHash, "127.0.0.1");

        // Should fail with NC-401
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-401");
    }

    /**
     * Property 2 (continued): Authentication fails with unknown username.
     * 
     * **Validates: Requirements 1.2, 1.3**
     */
    @Property(tries = 100)
    void authenticationFailsWithUnknownUsername(
            @ForAll @StringLength(min = 1, max = 50) String registeredUsername,
            @ForAll @StringLength(min = 1, max = 50) String unknownUsername,
            @ForAll @StringLength(min = 1, max = 50) String password,
            @ForAll @StringLength(min = 1, max = 50) String displayName
    ) {
        Assume.that(!registeredUsername.equals(unknownUsername));

        // Setup
        AuthManager authManager = new AuthManager();
        String passwordHash = AuthManager.hashPassword(password);
        ClientCredentials credentials = new ClientCredentials(registeredUsername, passwordHash, displayName);
        authManager.registerClient(credentials);

        // Test with unknown username
        AuthResult result = authManager.authenticate(unknownUsername, passwordHash, "127.0.0.1");

        // Should fail with NC-401
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-401");
    }

    /**
     * Property: Authentication is deterministic - same inputs always produce same result.
     */
    @Property(tries = 100)
    void authenticationIsDeterministic(
            @ForAll @StringLength(min = 1, max = 50) String username,
            @ForAll @StringLength(min = 1, max = 50) String password,
            @ForAll @StringLength(min = 1, max = 50) String displayName
    ) {
        // Setup
        AuthManager authManager = new AuthManager();
        String passwordHash = AuthManager.hashPassword(password);
        ClientCredentials credentials = new ClientCredentials(username, passwordHash, displayName);
        authManager.registerClient(credentials);

        // Authenticate twice with same credentials
        AuthResult result1 = authManager.authenticate(username, passwordHash, "127.0.0.1");
        AuthResult result2 = authManager.authenticate(username, passwordHash, "127.0.0.1");

        // Both should succeed
        assertThat(result1.isSuccess()).isEqualTo(result2.isSuccess());
    }
}
