package com.nova.chat.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AUTH-002 challenge-response HMAC — single source of truth.
 *
 * <p>Shared by the backend ({@code AuthManager.computeChallengeHmac}) and the
 * client ({@code com.nova.chat.client.network.ChallengeHmac.compute}) so the
 * wire format stays byte-for-byte identical across the Java backend and every
 * Java client plugin. Non-JVM clients (PHP/Python/C++) keep their own copies;
 * this class is the Java reference implementation.
 *
 * <p>HMAC construction (do NOT change — this is the AUTH-002 wire format):
 * <ul>
 *   <li>key = UTF-8 bytes of {@code sha256hex(password)}
 *       (the stored credential hash)</li>
 *   <li>message = UTF-8 bytes of {@code serverNonce + clientNonce}
 *       (string concatenation)</li>
 *   <li>output = lowercase-hex HMAC-SHA-256</li>
 * </ul>
 *
 * <p>Null inputs return {@code null} (matching the backend
 * {@code AuthManager} semantics). A missing JCA algorithm or a bad key
 * (wrong-length hash) logs at {@link Level#SEVERE} and returns {@code null}.
 */
public final class ChallengeHmac {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Logger logger = Logger.getLogger(ChallengeHmac.class.getName());
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 16 random bytes -> 32 lowercase-hex characters. */
    private static final int NONCE_BYTES = 16;

    private ChallengeHmac() {
    }

    /**
     * Generates a fresh client nonce: 16 cryptographically-random bytes,
     * lowercase-hex-encoded (32 characters).
     *
     * @return a 32-character lowercase-hex nonce
     */
    public static String generateNonceHex() {
        byte[] bytes = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return toLowerHex(bytes);
    }

    /**
     * Computes the AUTH-002 challenge-response HMAC.
     *
     * @param passwordHash the stored credential hash (sha256hex of the password)
     * @param serverNonce  the server nonce (hex) received in HandshakeChallenge
     * @param clientNonce  the client nonce (hex) sent in HandshakeInit
     * @return the lowercase-hex HMAC-SHA-256, or {@code null} if any argument
     *         is {@code null} or the JCA algorithm/key is unavailable
     */
    public static String compute(String passwordHash, String serverNonce, String clientNonce) {
        if (passwordHash == null || serverNonce == null || clientNonce == null) {
            return null;
        }
        try {
            // key construction — UTF-8 bytes of the stored password hash
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(passwordHash.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            // message — UTF-8 bytes of (serverNonce + clientNonce) concatenation
            byte[] hmacBytes = mac.doFinal((serverNonce + clientNonce).getBytes(StandardCharsets.UTF_8));
            // output — lowercase-hex
            return toLowerHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is guaranteed by the JCA; a bad key only happens if the
            // stored hash is somehow the wrong length, which is a config bug.
            logger.log(Level.SEVERE, "Failed to compute challenge HMAC", e);
            return null;
        }
    }

    private static String toLowerHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String h = Integer.toHexString(0xff & b);
            if (h.length() == 1) {
                hex.append('0');
            }
            hex.append(h);
        }
        return hex.toString();
    }
}
