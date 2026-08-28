package com.nova.chat.client.network;

import java.security.SecureRandom;
import java.util.Objects;

/**
 * AUTH-002 challenge-response helpers for the client side.
 *
 * <p>Delegates the HMAC computation to
 * {@code com.nova.chat.common.security.ChallengeHmac} in
 * {@code NovaChat:common}, the single source of truth shared with the backend
 * {@code AuthManager.computeChallengeHmac}. The wire format stays
 * byte-for-byte identical with the PHP/Python/C++ clients.
 *
 * <p>HMAC (defined in the common class):
 * <ul>
 *   <li>key = UTF-8 bytes of {@code sha256hex(password)} (the stored credential
 *       hash, computed by {@code PasswordHasher#sha256Hex})</li>
 *   <li>message = UTF-8 bytes of {@code serverNonce + clientNonce}
 *       (string concatenation)</li>
 *   <li>output = lowercase-hex HMAC-SHA-256</li>
 * </ul>
 *
 * <p>This class is plugin-only (Architecture B) and deliberately does not
 * depend on {@code StarLink/core}; it shares the implementation with the
 * backend via {@code NovaChat:common}.
 */
public final class ChallengeHmac {

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
        return com.nova.chat.common.security.ChallengeHmac.compute(
                passwordHash, serverNonce, clientNonce);
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
