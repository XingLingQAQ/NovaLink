package com.nova.link.websocket;

import io.jsonwebtoken.Claims;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for JwtService consistency.
 * 
 * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
 * 
 * Tests that for any valid payload, generating a token and verifying it
 * should return the original payload.
 * 
 * **Validates: Requirements 22.3**
 */
public class JwtServicePropertyTest {

    private static final String TEST_SECRET = "test-secret-key-for-jwt-signing-minimum-32-chars";
    private static final long TEST_EXPIRATION_MS = 3600000; // 1 hour

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any valid user ID, username, and role, generating a token and validating it
     * should return the original claims.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void tokenRoundTripPreservesUserIdUsernameAndRole(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        JwtService jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
        
        String token = jwtService.generateToken(userId, username, role);
        
        assertThat(token).isNotNull();
        assertThat(token).isNotEmpty();
        
        Claims claims = jwtService.validateToken(token);
        
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo(userId);
        assertThat(claims.get("username", String.class)).isEqualTo(username);
        assertThat(claims.get("role", String.class)).isEqualTo(role);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any valid user ID, getUserId should return the original user ID.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void getUserIdReturnsOriginalUserId(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        JwtService jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
        
        String token = jwtService.generateToken(userId, username, role);
        String extractedUserId = jwtService.getUserId(token);
        
        assertThat(extractedUserId).isEqualTo(userId);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any valid username, getUsername should return the original username.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void getUsernameReturnsOriginalUsername(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        JwtService jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
        
        String token = jwtService.generateToken(userId, username, role);
        String extractedUsername = jwtService.getUsername(token);
        
        assertThat(extractedUsername).isEqualTo(username);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any valid role, getRole should return the original role.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void getRoleReturnsOriginalRole(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        JwtService jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
        
        String token = jwtService.generateToken(userId, username, role);
        String extractedRole = jwtService.getRole(token);
        
        assertThat(extractedRole).isEqualTo(role);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any valid token with sufficient expiration time, isTokenExpired should return false.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void freshTokenIsNotExpired(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        JwtService jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
        
        String token = jwtService.generateToken(userId, username, role);
        
        assertThat(jwtService.isTokenExpired(token)).isFalse();
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any token generated with very short expiration, it should eventually expire.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 10)
    void tokenWithShortExpirationExpires(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) throws InterruptedException {
        // Use 1ms expiration
        JwtService jwtService = new JwtService(TEST_SECRET, 1);
        
        String token = jwtService.generateToken(userId, username, role);
        
        // Wait for token to expire
        Thread.sleep(10);
        
        assertThat(jwtService.isTokenExpired(token)).isTrue();
        assertThat(jwtService.validateToken(token)).isNull();
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any refresh token, isRefreshToken should return true.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void refreshTokenIsIdentifiedCorrectly(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        JwtService jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
        
        String refreshToken = jwtService.generateRefreshToken(userId, username, role);
        
        assertThat(jwtService.isRefreshToken(refreshToken)).isTrue();
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any regular token, isRefreshToken should return false.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void regularTokenIsNotRefreshToken(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        JwtService jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
        
        String token = jwtService.generateToken(userId, username, role);
        
        assertThat(jwtService.isRefreshToken(token)).isFalse();
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any refresh token, the subject should be the original user ID.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void refreshTokenPreservesUserId(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        JwtService jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
        
        String refreshToken = jwtService.generateRefreshToken(userId, username, role);
        String extractedUserId = jwtService.getUserId(refreshToken);
        
        assertThat(extractedUserId).isEqualTo(userId);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any invalid token string, validateToken should return null.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void invalidTokenReturnsNull(
            @ForAll @AlphaChars @StringLength(min = 10, max = 100) String invalidToken
    ) {
        JwtService jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
        
        Claims claims = jwtService.validateToken(invalidToken);
        
        assertThat(claims).isNull();
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any token signed with a different secret, validateToken should return null.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void tokenSignedWithDifferentSecretIsInvalid(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        JwtService jwtService1 = new JwtService("secret-key-one-minimum-32-characters", TEST_EXPIRATION_MS);
        JwtService jwtService2 = new JwtService("secret-key-two-minimum-32-characters", TEST_EXPIRATION_MS);
        
        String token = jwtService1.generateToken(userId, username, role);
        
        // Token signed with different secret should be invalid
        Claims claims = jwtService2.validateToken(token);
        
        assertThat(claims).isNull();
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any valid token, the issued at time should be close to current time.
     * Note: JWT timestamps are in seconds, so we allow 1 second tolerance.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void tokenIssuedAtIsCloseToCurrentTime(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        JwtService jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
        
        long before = System.currentTimeMillis();
        String token = jwtService.generateToken(userId, username, role);
        long after = System.currentTimeMillis();
        
        Claims claims = jwtService.validateToken(token);
        
        assertThat(claims).isNotNull();
        long issuedAt = claims.getIssuedAt().getTime();
        // JWT timestamps are truncated to seconds, so allow 1 second tolerance
        assertThat(issuedAt).isGreaterThanOrEqualTo(before - 1000);
        assertThat(issuedAt).isLessThanOrEqualTo(after + 1000);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any valid token, the expiration time should be issuedAt + expirationMs.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void tokenExpirationIsCorrect(
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        JwtService jwtService = new JwtService(TEST_SECRET, TEST_EXPIRATION_MS);
        
        String token = jwtService.generateToken(userId, username, role);
        Claims claims = jwtService.validateToken(token);
        
        assertThat(claims).isNotNull();
        long issuedAt = claims.getIssuedAt().getTime();
        long expiration = claims.getExpiration().getTime();
        
        assertThat(expiration - issuedAt).isEqualTo(TEST_EXPIRATION_MS);
    }

    /**
     * **Feature: novachat-platform-expansion, Property 15: JWT Service Consistency**
     * 
     * For any short secret key, JwtService should pad it to minimum length.
     * 
     * **Validates: Requirements 22.3**
     */
    @Property(tries = 100)
    void shortSecretKeyIsPadded(
            @ForAll @AlphaChars @StringLength(min = 1, max = 31) String shortSecret,
            @ForAll("userIds") String userId,
            @ForAll("usernames") String username,
            @ForAll("roles") String role
    ) {
        // Should not throw exception even with short secret
        JwtService jwtService = new JwtService(shortSecret, TEST_EXPIRATION_MS);
        
        String token = jwtService.generateToken(userId, username, role);
        Claims claims = jwtService.validateToken(token);
        
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo(userId);
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.oneOf(
                Arbitraries.create(() -> UUID.randomUUID().toString()),
                Arbitraries.strings().alpha().numeric().ofMinLength(8).ofMaxLength(36)
        );
    }

    @Provide
    Arbitrary<String> usernames() {
        return Arbitraries.of(
                "admin",
                "user123",
                "test_user",
                "player_one",
                "moderator",
                "super_admin",
                "guest",
                "operator"
        );
    }

    @Provide
    Arbitrary<String> roles() {
        return Arbitraries.of(
                "SUPER_ADMIN",
                "CLIENT_ADMIN",
                "CHANNEL_ADMIN",
                "PLAYER",
                "GUEST"
        );
    }
}
