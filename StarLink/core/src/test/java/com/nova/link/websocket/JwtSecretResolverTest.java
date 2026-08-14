package com.nova.link.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for weak-secret handling: the default secret triggers generation of a
 * persisted random 256-bit secret; explicit secrets are kept as configured.
 */
@DisplayName("JwtSecretResolver: weak secret handling")
class JwtSecretResolverTest {

    @TempDir
    Path tempDir;

    private Path secretFile() {
        return tempDir.resolve("data").resolve(".jwt-secret");
    }

    @Test
    @DisplayName("default secret generates a random 256-bit secret and persists it")
    void defaultSecretGeneratesAndPersists() throws Exception {
        String resolved = JwtSecretResolver.resolve(JwtSecretResolver.DEFAULT_SECRET, secretFile());

        assertThat(resolved).isNotEqualTo(JwtSecretResolver.DEFAULT_SECRET);
        // 32 random bytes hex-encoded = 64 chars.
        assertThat(resolved).hasSize(64).matches("[0-9a-f]+");
        assertThat(Files.readString(secretFile()).trim()).isEqualTo(resolved);
    }

    @Test
    @DisplayName("subsequent startups reuse the persisted secret")
    void persistedSecretIsReused() {
        String first = JwtSecretResolver.resolve(JwtSecretResolver.DEFAULT_SECRET, secretFile());
        String second = JwtSecretResolver.resolve(JwtSecretResolver.DEFAULT_SECRET, secretFile());

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("blank secret is treated like the default (generate + persist)")
    void blankSecretGenerates() {
        String resolved = JwtSecretResolver.resolve("   ", secretFile());
        assertThat(resolved).hasSize(64).matches("[0-9a-f]+");
        assertThat(Files.exists(secretFile())).isTrue();
    }

    @Test
    @DisplayName("explicit strong secret is used as-is (no file written)")
    void explicitStrongSecretKept() {
        String configured = "an-explicitly-configured-secret-key-over-32-bytes";
        String resolved = JwtSecretResolver.resolve(configured, secretFile());

        assertThat(resolved).isEqualTo(configured);
        assertThat(Files.exists(secretFile())).isFalse();
    }

    @Test
    @DisplayName("explicit short secret is kept for compatibility (warned, not replaced)")
    void explicitShortSecretKept() {
        String configured = "short-secret";
        String resolved = JwtSecretResolver.resolve(configured, secretFile());

        assertThat(resolved).isEqualTo(configured);
        assertThat(Files.exists(secretFile())).isFalse();
    }
}
