package com.nova.chat.client.network;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Objects;

/**
 * AUTH-002 challenge-response helpers for the client side.
 *
 * <p>Mirrors {@code AuthManager.computeChallengeHmac} on the backend so the
 * wire format stays byte-for-byte identical with the PHP/Python/C++ clients.
 *
 * <p>HMAC:
 * <ul>
 *   <li>key = UTF-8 bytes of {@code sha256hex(password)} (the stored credential
 *       hash, computed by {@link PasswordHasher#sha256Hex})</li>
 *   <li>message = UTF-8 bytes of {@code serverNonce + clientNonce}
 *       (string concatenation)</li>
 *   <li>output = lowercase-hex HMAC-SHA-256</li>
 * </ul>
 *
 * <p>This class is plugin-only (Architecture B) and deliberately does not
 * depend on {@code StarLink/core}; the backend has its own copy in
 * {@code AuthManager.computeChallengeHmac}.
 */
public final class ChallengeHmac {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 16 random bytes → 32 lowercase-hex characters. */
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
     * @return the lowercase-hex HMAC-SHA-256
     */
    public static String compute(String passwordHash, String serverNonce, String clientNonce) {
        Objects.requireNonNull(passwordHash, "passwordHash");
        Objects.requireNonNull(serverNonce, "serverNonce");
        Objects.requireNonNull(clientNonce, "clientNonce");
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(passwordHash.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hmacBytes = mac.doFinal((serverNonce + clientNonce).getBytes(StandardCharsets.UTF_8));
            return toLowerHex(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is guaranteed by the JCA; a bad key only happens if the
            // stored hash is somehow the wrong length, which is a config bug.
            throw new RuntimeException("Failed to compute challenge HMAC", e);
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
