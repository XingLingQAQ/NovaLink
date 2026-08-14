package com.nova.link.websocket;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for JWT hardening: jti-based revocation blacklist and the
 * shortened default access-token TTL (2 hours; refresh stays 7 days).
 */
@DisplayName("JwtService revocation + TTL hardening")
class JwtServiceRevocationTest {

    private static final String SECRET = "test-secret-key-for-jwt-signing-minimum-32-chars";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET);
    }

    @Test
    @DisplayName("every token carries a unique jti claim")
    void tokensCarryUniqueJti() {
        String t1 = jwtService.generateToken("root", "root", "SUPER_ADMIN");
        String t2 = jwtService.generateToken("root", "root", "SUPER_ADMIN");

        Claims c1 = jwtService.validateToken(t1);
        Claims c2 = jwtService.validateToken(t2);
        assertThat(c1.getId()).isNotBlank();
        assertThat(c2.getId()).isNotBlank();
        assertThat(c1.getId()).isNotEqualTo(c2.getId());
    }

    @Test
    @DisplayName("revoked access token fails validation")
    void revokedAccessTokenIsInvalid() {
        String token = jwtService.generateToken("root", "root", "SUPER_ADMIN");
        Claims claims = jwtService.validateToken(token);
        assertThat(claims).isNotNull();

        assertThat(jwtService.revokeToken(token)).isTrue();

        assertThat(jwtService.validateToken(token)).isNull();
        assertThat(jwtService.isRevoked(claims.getId())).isTrue();
    }

    @Test
    @DisplayName("revoked refresh token fails validation")
    void revokedRefreshTokenIsInvalid() {
        String refresh = jwtService.generateRefreshToken("root", "root", "SUPER_ADMIN");
        assertThat(jwtService.validateToken(refresh)).isNotNull();

        assertThat(jwtService.revokeToken(refresh)).isTrue();
        assertThat(jwtService.validateToken(refresh)).isNull();
    }

    @Test
    @DisplayName("revoking one token does not affect other tokens of the same user")
    void revocationIsPerToken() {
        String t1 = jwtService.generateToken("root", "root", "SUPER_ADMIN");
        String t2 = jwtService.generateToken("root", "root", "SUPER_ADMIN");

        jwtService.revokeToken(t1);

        assertThat(jwtService.validateToken(t1)).isNull();
        assertThat(jwtService.validateToken(t2)).isNotNull();
    }

    @Test
    @DisplayName("revoking is idempotent and tolerates garbage input")
    void revokeIsIdempotentAndSafe() {
        String token = jwtService.generateToken("root", "root", "SUPER_ADMIN");
        assertThat(jwtService.revokeToken(token)).isTrue();
        assertThat(jwtService.revokeToken(token)).isTrue();

        assertThat(jwtService.revokeToken(null)).isFalse();
        assertThat(jwtService.revokeToken("")).isFalse();
        assertThat(jwtService.revokeToken("not-a-jwt")).isFalse();
    }

    @Test
    @DisplayName("revoking an already-expired token is a no-op")
    void revokeExpiredTokenIsNoOp() throws InterruptedException {
        JwtService shortLived = new JwtService(SECRET, 1);
        String token = shortLived.generateToken("root", "root", "SUPER_ADMIN");
        Thread.sleep(10);

        assertThat(shortLived.revokeToken(token)).isFalse();
    }

    @Test
    @DisplayName("default access-token TTL is 2 hours")
    void defaultAccessTtlIsTwoHours() {
        String token = jwtService.generateToken("root", "root", "SUPER_ADMIN");
        Claims claims = jwtService.validateToken(token);

        long ttlMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertThat(ttlMs).isEqualTo(2 * 60 * 60 * 1000L);
    }

    @Test
    @DisplayName("refresh-token TTL stays 7 days")
    void refreshTtlIsSevenDays() {
        String refresh = jwtService.generateRefreshToken("root", "root", "SUPER_ADMIN");
        Claims claims = jwtService.validateToken(refresh);

        long ttlMs = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertThat(ttlMs).isEqualTo(7 * 24 * 60 * 60 * 1000L);
    }
}
