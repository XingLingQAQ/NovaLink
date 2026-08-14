package com.nova.link.websocket;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JWT Service for WebSocket authentication.
 * Handles token generation and validation for web panel connections.
 *
 * <p>Tokens carry a {@code jti} id so individual tokens can be revoked
 * (logout / refresh rotation). Revoked ids live in an in-memory blacklist
 * that is purged automatically once the underlying token would have expired
 * anyway.
 *
 * Requirements: 24.4 - JWT authentication for web panel
 */
public class JwtService {

    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);
    
    /** Default access-token expiration time: 2 hours */
    private static final long DEFAULT_EXPIRATION_MS = 2 * 60 * 60 * 1000;
    
    /** Refresh token expiration time: 7 days */
    private static final long REFRESH_EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000;

    /** Minimum interval between blacklist purge sweeps. */
    private static final long PURGE_INTERVAL_MS = 60 * 1000;

    private final SecretKey secretKey;
    private final long expirationMs;

    /** Revoked token ids (jti) -> token expiration epoch millis. */
    private final ConcurrentHashMap<String, Long> revokedJtis = new ConcurrentHashMap<>();
    private volatile long lastPurgeAt = 0L;

    /**
     * Creates a new JwtService with the given secret key.
     *
     * @param secretKey the secret key for signing tokens (min 32 characters)
     */
    public JwtService(String secretKey) {
        this(secretKey, DEFAULT_EXPIRATION_MS);
    }

    /**
     * Creates a new JwtService with the given secret key and expiration time.
     *
     * @param secretKey    the secret key for signing tokens
     * @param expirationMs token expiration time in milliseconds
     */
    public JwtService(String secretKey, long expirationMs) {
        // Ensure key is at least 256 bits (32 bytes) for HS256
        String paddedKey = secretKey;
        while (paddedKey.length() < 32) {
            paddedKey = paddedKey + secretKey;
        }
        this.secretKey = Keys.hmacShaKeyFor(paddedKey.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * Generates a JWT token for a user.
     *
     * @param userId   the user ID (UUID)
     * @param username the username
     * @param role     the user role (e.g., "SUPER_ADMIN", "CLIENT_ADMIN")
     * @return the generated JWT token
     */
    public String generateToken(String userId, String username, String role) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("role", role)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    /**
     * Generates a refresh token for a user.
     *
     * @param userId the user ID
     * @param username the username (optional, but recommended for refresh flow)
     * @param role the user role (optional, but recommended for refresh flow)
     * @return the generated refresh token
     */
    public String generateRefreshToken(String userId, String username, String role) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + REFRESH_EXPIRATION_MS);

        JwtBuilder builder = Jwts.builder()
                .subject(userId)
                .claim("type", "refresh")
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiration);

        // IMPORTANT: add claims BEFORE signWith(). Some JJWT builder implementations finalize payload on signWith().
        // Carry forward identity claims to allow stateless refresh.
        if (username != null) {
            builder.claim("username", username);
        }
        if (role != null) {
            builder.claim("role", role);
        }

        return builder
                .signWith(secretKey)
                .compact();
    }

    /**
     * Validates a JWT token and returns the claims. Revoked tokens
     * (logout / rotated refresh tokens) are treated as invalid.
     *
     * @param token the JWT token to validate
     * @return the token claims, or null if invalid or revoked
     */
    public Claims validateToken(String token) {
        Claims claims = parseClaims(token);
        if (claims == null) {
            return null;
        }
        String jti = claims.getId();
        if (jti != null && revokedJtis.containsKey(jti)) {
            logger.debug("Token revoked: jti={}", jti);
            return null;
        }
        maybePurgeRevocations();
        return claims;
    }

    /**
     * Parses and cryptographically verifies a token WITHOUT consulting the
     * revocation blacklist. Used internally by {@link #revokeToken} so that an
     * already-revoked token can still be re-revoked idempotently.
     */
    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            logger.debug("Token expired: {}", e.getMessage());
            return null;
        } catch (JwtException e) {
            logger.debug("Invalid token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Revokes a token by adding its {@code jti} to the in-memory blacklist.
     * The blacklist entry expires automatically once the token itself would
     * have expired.
     *
     * @param token the token to revoke (access or refresh)
     * @return true when the token was valid and is now revoked
     */
    public boolean revokeToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Claims claims = parseClaims(token);
        if (claims == null) {
            // Invalid or already expired: nothing to revoke.
            return false;
        }
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            // Legacy tokens without a jti cannot be individually revoked.
            logger.debug("Cannot revoke token without jti (subject={})", claims.getSubject());
            return false;
        }
        long expiry = claims.getExpiration() != null
                ? claims.getExpiration().getTime()
                : System.currentTimeMillis();
        revokedJtis.put(jti, expiry);
        purgeExpiredRevocations();
        return true;
    }

    /**
     * @param jti the token id to check
     * @return true when this token id has been revoked (and not yet purged)
     */
    public boolean isRevoked(String jti) {
        return jti != null && revokedJtis.containsKey(jti);
    }

    /** Removes blacklist entries whose tokens have expired anyway. */
    private void purgeExpiredRevocations() {
        long now = System.currentTimeMillis();
        lastPurgeAt = now;
        revokedJtis.entrySet().removeIf(e -> e.getValue() < now);
    }

    /** Throttled purge, invoked opportunistically from the validation path. */
    private void maybePurgeRevocations() {
        if (!revokedJtis.isEmpty()
                && System.currentTimeMillis() - lastPurgeAt > PURGE_INTERVAL_MS) {
            purgeExpiredRevocations();
        }
    }

    /**
     * Extracts the user ID from a token.
     *
     * @param token the JWT token
     * @return the user ID, or null if invalid
     */
    public String getUserId(String token) {
        Claims claims = validateToken(token);
        return claims != null ? claims.getSubject() : null;
    }

    /**
     * Extracts the username from a token.
     *
     * @param token the JWT token
     * @return the username, or null if invalid
     */
    public String getUsername(String token) {
        Claims claims = validateToken(token);
        return claims != null ? claims.get("username", String.class) : null;
    }

    /**
     * Extracts the role from a token.
     *
     * @param token the JWT token
     * @return the role, or null if invalid
     */
    public String getRole(String token) {
        Claims claims = validateToken(token);
        return claims != null ? claims.get("role", String.class) : null;
    }

    /**
     * Checks if a token is expired.
     *
     * @param token the JWT token
     * @return true if expired or invalid
     */
    public boolean isTokenExpired(String token) {
        Claims claims = validateToken(token);
        if (claims == null) {
            return true;
        }
        return claims.getExpiration().before(new Date());
    }

    /**
     * Checks if a token is a refresh token.
     *
     * @param token the JWT token
     * @return true if it's a refresh token
     */
    public boolean isRefreshToken(String token) {
        Claims claims = validateToken(token);
        if (claims == null) {
            return false;
        }
        return "refresh".equals(claims.get("type", String.class));
    }
}
