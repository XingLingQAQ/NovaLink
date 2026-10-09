package com.nova.link.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H1b: verifies that {@link PostgreSQLProvider#buildJdbcUrl()} applies an
 * explicit {@code sslmode} instead of silently inheriting the PostgreSQL
 * driver's plaintext-tolerant default ({@code prefer}), and that a plaintext
 * JDBC link ({@code sslmode=disable}) is gated by the same
 * {@code server.insecure-allow-plaintext} opt-in used by AUTH-002.
 *
 * <p>This is the PostgreSQL mirror of {@link MySQLProviderJdbcUrlTest}: no
 * hardcoded {@code ssl=false} existed here, but the pre-H1b bare URL opened
 * the door to silent ssl-off downgrade, so the fail-closed knobs are added
 * in the same shape.
 */
@DisplayName("H1b PostgreSQLProvider JDBC URL")
class PostgreSQLProviderJdbcUrlTest {

    private static PostgreSQLProvider provider(String sslMode, boolean insecureAllowPlaintext) {
        return new PostgreSQLProvider(
                "127.0.0.1", 5432, "novalink", "postgres", "secret", 10,
                sslMode, insecureAllowPlaintext);
    }

    @Nested
    @DisplayName("fail-closed defaults")
    class FailClosedDefaults {

        @Test
        @DisplayName("legacy constructor defaults to sslmode=verify-ca with ssl=true")
        void legacyCtorDefaultsToVerifyCa() {
            PostgreSQLProvider p = new PostgreSQLProvider("127.0.0.1", 5432, "novalink", "postgres", "secret", 10);
            String url = p.buildJdbcUrl();
            assertThat(url).contains("sslmode=verify-ca");
            assertThat(url).contains("ssl=true");
            assertThat(url).doesNotContain("ssl=false");
            assertThat(url).doesNotContain("sslmode=disable");
        }

        @Test
        @DisplayName("null/blank sslMode falls back to verify-ca (fail-closed)")
        void blankSslModeFallsBackToVerifyCa() {
            PostgreSQLProvider p = provider(null, false);
            assertThat(p.buildJdbcUrl()).contains("sslmode=verify-ca");
            assertThat(provider("   ", false).buildJdbcUrl()).contains("sslmode=verify-ca");
        }

        @Test
        @DisplayName("uppercase ssl-mode config is normalised to lowercase")
        void uppercaseIsNormalised() {
            PostgreSQLProvider p = provider("VERIFY-CA", false);
            assertThat(p.buildJdbcUrl()).contains("sslmode=verify-ca");
        }

        @Test
        @DisplayName("unsupported ssl-mode throws at URL build time")
        void unsupportedSslModeThrows() {
            PostgreSQLProvider p = provider("WEAK_CIPHER_OK", false);
            assertThatThrownBy(p::buildJdbcUrl)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("H1b")
                    // PostgreSQL normalises sslmode to lowercase before validation
                    .hasMessageContaining("weak_cipher_ok");
        }

        @Test
        @DisplayName("never falls back to driver default 'prefer' (silent plaintext downgrade)")
        void neverFallsBackToPreferDefault() {
            // The bug this guards: pre-H1b the URL had no sslmode at all, which
            // makes the PG JDBC driver use sslmode=prefer. prefer tries SSL
            // first but silently falls back to plaintext if the server lacks
            // SSL support — that's a silent downgrade, not fail-closed.
            String url = new PostgreSQLProvider(
                    "127.0.0.1", 5432, "novalink", "postgres", "secret", 10
            ).buildJdbcUrl();
            assertThat(url).doesNotContain("sslmode=prefer");
            assertThat(url).doesNotContain("sslmode=allow");
        }
    }

    @Nested
    @DisplayName("plaintext gating (AUTH-002/H1b)")
    class PlaintextGating {

        @Test
        @DisplayName("ssl-mode=disable without opt-in throws AUTH-002 error")
        void disabledWithoutOptInThrows() {
            PostgreSQLProvider p = provider("disable", false);
            assertThatThrownBy(p::buildJdbcUrl)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUTH-002")
                    .hasMessageContaining("plaintext")
                    .hasMessageContaining("insecure-allow-plaintext");
        }

        @Test
        @DisplayName("ssl-mode=disable + insecure-allow-plaintext=true builds a plaintext URL")
        void disabledWithOptInBuildsPlaintextUrl() {
            PostgreSQLProvider p = provider("disable", true);
            String url = p.buildJdbcUrl();
            assertThat(url).contains("sslmode=disable");
            assertThat(url).contains("ssl=false");
        }

        @Test
        @DisplayName("verify-ca does not consult the insecure flag (always passes)")
        void verifyCaIgnoresInsecureFlag() {
            assertThatCode(() -> provider("verify-ca", false).buildJdbcUrl())
                    .doesNotThrowAnyException();
            assertThatCode(() -> provider("verify-ca", true).buildJdbcUrl())
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("secure modes emit ssl=true")
    class SecureModesEmitSslParameters {

        @Test
        @DisplayName("verify-ca enables ssl=true")
        void verifyCaEnablesSsl() {
            String url = provider("verify-ca", false).buildJdbcUrl();
            assertThat(url).contains("sslmode=verify-ca");
            assertThat(url).contains("ssl=true");
            assertThat(url).doesNotContain("ssl=false");
        }

        @Test
        @DisplayName("verify-full enables ssl=true")
        void verifyFullEnablesSsl() {
            String url = provider("verify-full", false).buildJdbcUrl();
            assertThat(url).contains("sslmode=verify-full");
            assertThat(url).contains("ssl=true");
        }

        @Test
        @DisplayName("require keeps ssl=true without certificate verification")
        void requireKeepsSslButSkipsVerification() {
            String url = provider("require", false).buildJdbcUrl();
            assertThat(url).contains("sslmode=require");
            assertThat(url).contains("ssl=true");
            assertThat(url).doesNotContain("ssl=false");
        }

        @Test
        @DisplayName("explicit prefer is allowed but only when the operator opts in by name")
        void explicitPreferIsAllowed() {
            // prefer/allow are NOT fail-closed but the operator has to write
            // them out by name — they cannot be the silent default.
            assertThatCode(() -> provider("prefer", false).buildJdbcUrl())
                    .doesNotThrowAnyException();
            assertThat(provider("prefer", false).buildJdbcUrl()).contains("sslmode=prefer");
            assertThatCode(() -> provider("allow", false).buildJdbcUrl())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("jdbc prefix and database segment preserved")
        void jdbcPrefixPreserved() {
            String url = provider("verify-ca", false).buildJdbcUrl();
            assertThat(url).startsWith("jdbc:postgresql://127.0.0.1:5432/novalink?");
        }
    }
}
