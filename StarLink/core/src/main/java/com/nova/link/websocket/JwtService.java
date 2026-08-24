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
     * Token-family records for refresh-token epoch revocation
     * (PANEL-012). Each login creates a family; each refresh rotates the
     * epoch; reuse of an older refresh token in the same family triggers
     * family-wide revocation. Family records are in-memory (like
     * {@link #revokedJtis}) and do NOT survive a server restart — after a
     * restart every refresh token's family is absent, so refresh fails and
     * the user must re-login. This is acceptable per the audit spec.
     */
    private final ConcurrentHashMap<String, FamilyRecord> tokenFamilies = new ConcurrentHashMap<>();

    /** Epoch value used as a tombstone marking a family as revoked. */
    private static final long FAMILY_REVOKED = Long.MAX_VALUE;

    /**
     * Result of validating a refresh token against its family's current epoch.
     */
    public enum FamilyValidation {
        /** Token epoch matches family epoch — proceed with rotation. */
        VALID,
        /** Token epoch is older (stolen/replayed) or family is revoked — reject + revoke family. */
        REJECT,
        /** Family record not found (server restart) — reject, cannot validate. */
        UNKNOWN
    }

    /** In-memory record of a token family's current epoch. */
    private static final class FamilyRecord {
        // volatile: read/written across threads; mutations guarded by synchronizing on the record.
        volatile long currentEpoch;

        FamilyRecord(long initialEpoch) {
            this.currentEpoch = initialEpoch;
        }
    }

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
        return generateRefreshToken(userId, username, role, null, 0L);
    }

    /**
     * Generates a refresh token bound to a token family (PANEL-012).
     *
     * <p>When {@code familyId} is non-null, the token carries {@code fid} and
     * {@code fep} (family epoch) claims. The first refresh token in a family
     * is issued with epoch {@code 0}; each successful rotation issues a new
     * token with the incremented epoch.
     *
     * @param userId   the user ID (JWT subject)
     * @param username the username claim
     * @param role     the role claim
     * @param familyId the token-family id (nullable for legacy callers)
     * @param epoch    the family epoch this token belongs to
     * @return the generated refresh token
     */
    public String generateRefreshToken(String userId, String username, String role,
                                       String familyId, long epoch) {
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
        if (familyId != null && !familyId.isBlank()) {
            builder.claim("fid", familyId);
            builder.claim("fep", epoch);
        }

        return builder
                .signWith(secretKey)
                .compact();
    }

    /**
     * Creates a new token family and returns its id. Used on login.
     *
     * @return the new family id (UUID)
     */
    public String createFamily() {
        String familyId = UUID.randomUUID().toString();
        tokenFamilies.put(familyId, new FamilyRecord(0L));
        return familyId;
    }

    /**
     * Validates a refresh token's family epoch (PANEL-012).
     *
     * <p>Must be called with the parsed claims of the refresh token presented
     * for rotation. When the token's epoch is older than the family's current
     * epoch, or the family has been revoked, this returns {@link FamilyValidation#REJECT}
     * and the family is revoked (so the legitimate holder is also forced
     * re-login — stolen-token reuse invalidates the whole family).
     *
     * @param claims the refresh token claims (must carry {@code fid} and {@code fep})
     * @return VALID when the token is the current family epoch, REJECT when
     *         stolen/replayed/revoked, UNKNOWN when the family record is absent
     */
    public FamilyValidation validateRefreshFamily(Claims claims) {
        if (claims == null) {
            return FamilyValidation.UNKNOWN;
        }
        String familyId = claims.get("fid", String.class);
        Long tokenEpoch = claims.get("fep", Long.class);
        if (familyId == null || tokenEpoch == null) {
            // Legacy refresh token without a family: cannot validate.
            return FamilyValidation.UNKNOWN;
        }
        FamilyRecord record = tokenFamilies.get(familyId);
        if (record == null) {
            return FamilyValidation.UNKNOWN;
        }
        synchronized (record) {
            if (record.currentEpoch == FAMILY_REVOKED) {
                return FamilyValidation.REJECT;
            }
            if (tokenEpoch < record.currentEpoch) {
                // Stolen/replayed older refresh token — revoke the family.
                record.currentEpoch = FAMILY_REVOKED;
                logger.warn("Refresh token family {} revoked: presented epoch {} < current {} (possible token theft)",
                        familyId, tokenEpoch, record.currentEpoch);
                return FamilyValidation.REJECT;
            }
            return FamilyValidation.VALID;
        }
    }

    /**
     * Increments the family epoch and returns the new epoch value to stamp on
     * the freshly issued refresh token. The caller must have already validated
     * the presented token via {@link #validateRefreshFamily}.
     *
     * @param familyId the family id
     * @return the new epoch, or {@code -1} when the family is unknown/revoked
     */
    public long rotateFamilyEpoch(String familyId) {
        if (familyId == null) {
            return -1;
        }
        FamilyRecord record = tokenFamilies.get(familyId);
        if (record == null) {
            return -1;
        }
        synchronized (record) {
            if (record.currentEpoch == FAMILY_REVOKED) {
                return -1;
            }
            record.currentEpoch += 1;
            return record.currentEpoch;
        }
    }

    /**
     * Revokes an entire token family (PANEL-012). All refresh tokens in the
     * family become invalid for rotation: {@link #validateRefreshFamily} will
     * return REJECT for any token carrying this family id. Used on logout to
     * kill every token in the family, and on stolen-token detection.
     *
     * @param familyId the family id to revoke
     */
    public void revokeFamily(String familyId) {
        if (familyId == null) {
            return;
        }
        FamilyRecord record = tokenFamilies.get(familyId);
        if (record == null) {
            // Record may be absent after restart; install a tombstone so a
            // later-presented token from this family is also rejected.
            tokenFamilies.put(familyId, new FamilyRecord(FAMILY_REVOKED));
            return;
        }
        synchronized (record) {
            record.currentEpoch = FAMILY_REVOKED;
        }
        logger.info("Refresh token family {} revoked", familyId);
    }

    /**
     * Extracts the family id from a token's claims (without validating the
     * family). Returns null for legacy tokens without a family.
     *
     * @param claims the parsed token claims
     * @return the family id or null
     */
    public String extractFamilyId(Claims claims) {
        if (claims == null) {
            return null;
        }
        return claims.get("fid", String.class);
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
     * Parses and cryptographically verifies a token WITHOUT consulting the
     * revocation blacklist. Used by the refresh/logout flows (PANEL-012) so
     * that family-epoch validation can run on a token whose jti was already
     * revoked by a prior rotation — detecting stolen older tokens requires
     * reading the family/epoch claims even after the jti is blacklisted.
     *
     * @param token the JWT to parse
     * @return the verified claims, or null if the signature/expiry is invalid
     */
    public Claims parseClaimsUnchecked(String token) {
        return parseClaims(token);
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
