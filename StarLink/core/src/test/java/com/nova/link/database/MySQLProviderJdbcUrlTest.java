package com.nova.link.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H1: verifies that {@link MySQLProvider#buildJdbcUrl()} is no longer hardcoded
 * to {@code useSSL=false} and that a plaintext JDBC link is gated by the same
 * {@code server.insecure-allow-plaintext} opt-in used by AUTH-002.
 *
 * <p>The default {@code ssl-mode=REQUIRED} fails closed — a missing config
 * cannot silently downgrade to plaintext.
 */
@DisplayName("H1 MySQLProvider JDBC URL")
class MySQLProviderJdbcUrlTest {

    private static MySQLProvider provider(String sslMode, boolean insecureAllowPlaintext) {
        return new MySQLProvider(
                "127.0.0.1", 3306, "novalink", "root", "secret", 10,
                sslMode, insecureAllowPlaintext);
    }

    @Nested
    @DisplayName("fail-closed defaults")
    class FailClosedDefaults {

        @Test
        @DisplayName("legacy constructor defaults to sslMode=REQUIRED with useSSL=true")
        void legacyCtorDefaultsToRequired() {
            MySQLProvider p = new MySQLProvider("127.0.0.1", 3306, "novalink", "root", "secret", 10);
            String url = p.buildJdbcUrl();
            assertThat(url).contains("sslMode=REQUIRED");
            assertThat(url).contains("useSSL=true");
            assertThat(url).doesNotContain("useSSL=false");
            assertThat(url).doesNotContain("allowPublicKeyRetrieval");
        }

        @Test
        @DisplayName("null/blank sslMode falls back to REQUIRED (fail-closed)")
        void blankSslModeFallsBackToRequired() {
            MySQLProvider p = provider(null, false);
            assertThat(p.buildJdbcUrl()).contains("sslMode=REQUIRED");
            assertThat(provider("   ", false).buildJdbcUrl()).contains("sslMode=REQUIRED");
        }

        @Test
        @DisplayName("lowercase ssl-mode config is normalised")
        void lowercaseIsNormalised() {
            MySQLProvider p = provider("required", false);
            assertThat(p.buildJdbcUrl()).contains("sslMode=REQUIRED");
        }

        @Test
        @DisplayName("unsupported ssl-mode throws at URL build time")
        void unsupportedSslModeThrows() {
            MySQLProvider p = provider("WEAK_CIPHER_OK", false);
            assertThatThrownBy(p::buildJdbcUrl)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("H1")
                    .hasMessageContaining("WEAK_CIPHER_OK");
        }
    }

    @Nested
    @DisplayName("plaintext gating (AUTH-002/H1)")
    class PlaintextGating {

        @Test
        @DisplayName("ssl-mode=DISABLED without opt-in throws AUTH-002 error")
        void disabledWithoutOptInThrows() {
            MySQLProvider p = provider("DISABLED", false);
            assertThatThrownBy(p::buildJdbcUrl)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUTH-002")
                    .hasMessageContaining("plaintext")
                    .hasMessageContaining("insecure-allow-plaintext");
        }

        @Test
        @DisplayName("ssl-mode=DISABLED + insecure-allow-plaintext=true builds a plaintext URL")
        void disabledWithOptInBuildsPlaintextUrl() {
            MySQLProvider p = provider("DISABLED", true);
            String url = p.buildJdbcUrl();
            assertThat(url).contains("sslMode=DISABLED");
            assertThat(url).contains("useSSL=false");
            assertThat(url).contains("allowPublicKeyRetrieval=true");
        }

        @Test
        @DisplayName("REQUIRED does not consult the insecure flag (always passes)")
        void requiredIgnoresInsecureFlag() {
            assertThatCode(() -> provider("REQUIRED", false).buildJdbcUrl())
                    .doesNotThrowAnyException();
            assertThatCode(() -> provider("REQUIRED", true).buildJdbcUrl())
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("secure modes emit SSL parameters")
    class SecureModesEmitSslParameters {

        @Test
        @DisplayName("VERIFY_CA enables certificate verification and drops the plaintext fallback")
        void verifyCaEnablesCertificateVerification() {
            String url = provider("VERIFY_CA", false).buildJdbcUrl();
            assertThat(url).contains("sslMode=VERIFY_CA");
            assertThat(url).contains("useSSL=true");
            assertThat(url).contains("verifyServerCertificate=true");
            assertThat(url).doesNotContain("allowPublicKeyRetrieval");
        }

        @Test
        @DisplayName("VERIFY_IDENTITY enables certificate verification")
        void verifyIdentityEnablesCertificateVerification() {
            String url = provider("VERIFY_IDENTITY", false).buildJdbcUrl();
            assertThat(url).contains("sslMode=VERIFY_IDENTITY");
            assertThat(url).contains("verifyServerCertificate=true");
        }

        @Test
        @DisplayName("PREFERRED keeps useSSL=true but skips hostname verification")
        void preferredKeepsSslButSkipsVerification() {
            String url = provider("PREFERRED", false).buildJdbcUrl();
            assertThat(url).contains("sslMode=PREFERRED");
            assertThat(url).contains("useSSL=true");
            assertThat(url).doesNotContain("verifyServerCertificate");
        }

        @Test
        @DisplayName("every URL carries serverTimezone=UTC (preserved from the legacy hardcoded URL)")
        void serverTimezoneAlwaysPresent() {
            for (String mode : new String[]{"DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY"}) {
                String url = provider(mode, true).buildJdbcUrl();
                assertThat(url).contains("serverTimezone=UTC");
            }
        }
    }
}
