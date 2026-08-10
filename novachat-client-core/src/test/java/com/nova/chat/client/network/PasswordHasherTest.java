package com.nova.chat.client.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PasswordHasher#sha256Hex}, verifying against known
 * NIST/FIPS SHA-256 vectors, determinism, hex format, UTF-8 handling, and
 * null rejection.
 */
@DisplayName("PasswordHasher")
class PasswordHasherTest {

    /** NIST / FIPS empty-string SHA-256 vector. */
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** Known vector for the ASCII string "password". */
    private static final String PASSWORD_SHA256 =
            "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8";

    /** Known vector for the ASCII string "abc" (FIPS 180-2). */
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    @DisplayName("sha256Hex matches known empty-string digest")
    void emptyString() {
        assertThat(PasswordHasher.sha256Hex("")).isEqualTo(EMPTY_SHA256);
    }

    @Test
    @DisplayName("sha256Hex matches known 'password' digest")
    void knownPasswordVector() {
        assertThat(PasswordHasher.sha256Hex("password")).isEqualTo(PASSWORD_SHA256);
    }

    @Test
    @DisplayName("sha256Hex matches known 'abc' digest (FIPS 180-2)")
    void knownAbcVector() {
        assertThat(PasswordHasher.sha256Hex("abc")).isEqualTo(ABC_SHA256);
    }

    @Test
    @DisplayName("sha256Hex is deterministic for the same input")
    void deterministic() {
        String first = PasswordHasher.sha256Hex("novalink");
        String second = PasswordHasher.sha256Hex("novalink");
        assertThat(first).isEqualTo(second);
        assertThat(PasswordHasher.sha256Hex("novalink")).isEqualTo(first);
    }

    @Test
    @DisplayName("different inputs produce different digests")
    void differentInputsDiffer() {
        assertThat(PasswordHasher.sha256Hex("password"))
                .isNotEqualTo(PasswordHasher.sha256Hex("Password"));
        assertThat(PasswordHasher.sha256Hex("a"))
                .isNotEqualTo(PasswordHasher.sha256Hex("b"));
    }

    @Test
    @DisplayName("sha256Hex is lowercase hex of fixed length 64")
    void format() {
        String hash = PasswordHasher.sha256Hex("novalink");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(PasswordHasher.sha256Hex("")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(PasswordHasher.sha256Hex("password")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("sha256Hex handles UTF-8 multi-byte characters")
    void utf8Input() {
        // "密码" in UTF-8
        String hash = PasswordHasher.sha256Hex("密码");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        // Must differ from the empty digest and from Latin "password"
        assertThat(hash).isNotEqualTo(EMPTY_SHA256).isNotEqualTo(PASSWORD_SHA256);
        // Deterministic for the same UTF-8 input
        assertThat(PasswordHasher.sha256Hex("密码")).isEqualTo(hash);
    }

    @Test
    @DisplayName("sha256Hex throws NullPointerException on null")
    void nullThrows() {
        assertThatThrownBy(() -> PasswordHasher.sha256Hex(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("password");
    }
}
