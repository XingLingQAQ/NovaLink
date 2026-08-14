package com.nova.link.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * Resolves the effective JWT signing secret at startup.
 *
 * <p>Rules:
 * <ul>
 *   <li>When the configured secret is the well-known default
 *       ({@code change-me-in-production}) or blank, a random 256-bit secret is
 *       generated and persisted to {@code data/.jwt-secret} in the working
 *       directory. Subsequent startups reuse the persisted secret so issued
 *       tokens survive restarts. A strong WARN is logged.</li>
 *   <li>When an explicit secret is configured but is shorter than 32 bytes,
 *       the configured value is kept (backward compatible: {@link JwtService}
 *       pads it) but an explicit security warning is logged.</li>
 *   <li>Otherwise the configured secret is used as-is.</li>
 * </ul>
 */
public final class JwtSecretResolver {

    /** The shipped default secret that must never be used for signing directly. */
    public static final String DEFAULT_SECRET = "change-me-in-production";

    private static final Logger logger = LoggerFactory.getLogger(JwtSecretResolver.class);
    private static final int GENERATED_SECRET_BYTES = 32;

    private JwtSecretResolver() {
    }

    /**
     * Resolves the effective JWT secret.
     *
     * @param configuredSecret the {@code server.secret-key} config value
     * @param secretFile       the persistence path (e.g. {@code data/.jwt-secret})
     * @return the secret to use for JWT signing
     */
    public static String resolve(String configuredSecret, Path secretFile) {
        if (configuredSecret != null && !configuredSecret.isBlank()
                && !DEFAULT_SECRET.equals(configuredSecret)) {
            if (configuredSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
                logger.warn("SECURITY WARNING: server.secret-key is shorter than 32 bytes. "
                        + "Weak JWT secrets can be brute-forced offline; configure a random "
                        + "secret of at least 32 characters.");
            }
            return configuredSecret;
        }

        // Default / blank secret: use (or create) a persisted random secret.
        try {
            if (Files.exists(secretFile)) {
                String persisted = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
                if (!persisted.isBlank()) {
                    logger.warn("SECURITY WARNING: server.secret-key is the default value. "
                            + "Using previously generated secret from {}. Configure a real "
                            + "secret-key in novalink.yml.", secretFile);
                    return persisted;
                }
            }
            byte[] raw = new byte[GENERATED_SECRET_BYTES];
            new SecureRandom().nextBytes(raw);
            String generated = toHex(raw);
            Path parent = secretFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(secretFile, generated, StandardCharsets.UTF_8);
            logger.warn("SECURITY WARNING: server.secret-key is the default value. Generated a "
                    + "random 256-bit JWT secret and persisted it to {}. Configure a real "
                    + "secret-key in novalink.yml.", secretFile);
            return generated;
        } catch (IOException e) {
            // Persistence failed: fall back to an in-memory random secret. Tokens
            // will not survive a restart, but that is safer than the default key.
            logger.error("Failed to persist generated JWT secret to {}: {}. Using an "
                    + "in-memory secret; tokens will be invalidated on restart.",
                    secretFile, e.getMessage());
            byte[] raw = new byte[GENERATED_SECRET_BYTES];
            new SecureRandom().nextBytes(raw);
            return toHex(raw);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }
}
