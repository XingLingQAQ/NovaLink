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

/**
 * JWT Service for WebSocket authentication.
 * Handles token generation and validation for web panel connections.
 * 
 * Requirements: 24.4 - JWT authentication for web panel
 */
public class JwtService {

    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);
    
    /** Default token expiration time: 24 hours */
    private static final long DEFAULT_EXPIRATION_MS = 24 * 60 * 60 * 1000;
    
    /** Refresh token expiration time: 7 days */
    private static final long REFRESH_EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000;
    
    private final SecretKey secretKey;
    private final long expirationMs;

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
     * Validates a JWT token and returns the claims.
     *
     * @param token the JWT token to validate
     * @return the token claims, or null if invalid
     */
    public Claims validateToken(String token) {
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
