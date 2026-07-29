package com.nova.chat.client.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Shared SHA-256 hex password hashing used by platform handshake clients.
 *
 * <p>Matches the historical per-platform {@code hashPassword} implementations
 * so handshake credentials remain compatible across plugins.
 */
public final class PasswordHasher {

    private PasswordHasher() {
    }

    /**
     * Hashes a password using SHA-256 and returns a lowercase hex string.
     *
     * @param password the password to hash (must not be null)
     * @return hex-encoded SHA-256 digest (64 lowercase hex chars)
     * @throws NullPointerException if {@code password} is null
     */
    public static String sha256Hex(String password) {
        Objects.requireNonNull(password, "password");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
