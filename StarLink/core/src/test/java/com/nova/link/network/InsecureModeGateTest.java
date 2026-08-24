package com.nova.link.network;

import com.nova.link.config.ServerConfig;
import com.nova.link.config.TlsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUTH-002: verifies the fail-closed gate that blocks a plaintext TCP
 * listener from starting unless the operator has explicitly opted in.
 *
 * <p>The gate is a pure check — no side effects, no network. It throws
 * {@link IllegalStateException} when neither TLS nor
 * {@code insecure-allow-plaintext} is configured, and returns silently
 * otherwise. Production startup ({@code NovaLinkMain}) invokes it before
 * {@link NettyServer#start()} so a misconfigured deployment aborts before
 * the listener binds.
 */
@DisplayName("AUTH-002 InsecureModeGate")
class InsecureModeGateTest {

    @Nested
    @DisplayName("requireTlsOrExplicitInsecure")
    class RequireTlsOrExplicitInsecure {

        @Test
        @DisplayName("throws when neither TLS nor insecure flag is set (fail-closed)")
        void throwsWhenPlaintextAndNotAcknowledged() {
            ServerConfig server = new ServerConfig();
            // tls == null and insecureAllowPlaintext == false (defaults)
            assertThatThrownBy(() ->
                    InsecureModeGate.requireTlsOrExplicitInsecure(server, "TCP listener (port 8888)"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUTH-002")
                    .hasMessageContaining("plaintext");
        }

        @Test
        @DisplayName("passes when TLS is configured")
        void passesWhenTlsConfigured() {
            ServerConfig server = new ServerConfig();
            TlsConfig tls = new TlsConfig();
            tls.setCertChainFile("server.crt");
            tls.setPrivateKeyFile("server.key");
            server.setTls(tls);
            assertThatCode(() ->
                    InsecureModeGate.requireTlsOrExplicitInsecure(server, "TCP listener (port 8888)"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("passes when insecure-allow-plaintext is explicitly true")
        void passesWhenInsecureAllowed() {
            ServerConfig server = new ServerConfig();
            server.setInsecureAllowPlaintext(true);
            assertThatCode(() ->
                    InsecureModeGate.requireTlsOrExplicitInsecure(server, "TCP listener (port 8888)"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("throws when TLS block is present but not configured (missing key)")
        void throwsWhenTlsBlockIncomplete() {
            ServerConfig server = new ServerConfig();
            TlsConfig tls = new TlsConfig();
            // cert set but key blank -> isConfigured() == false
            tls.setCertChainFile("server.crt");
            tls.setPrivateKeyFile("");
            server.setTls(tls);
            assertThatThrownBy(() ->
                    InsecureModeGate.requireTlsOrExplicitInsecure(server, "TCP listener (port 8888)"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUTH-002");
        }

        @Test
        @DisplayName("throws when server config is null")
        void throwsWhenServerConfigNull() {
            assertThatThrownBy(() ->
                    InsecureModeGate.requireTlsOrExplicitInsecure(null, "TCP listener (port 8888)"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("server config is null");
        }

        @Test
        @DisplayName("insecure flag takes precedence: passes even with incomplete TLS")
        void insecureFlagWinsOverIncompleteTls() {
            ServerConfig server = new ServerConfig();
            TlsConfig tls = new TlsConfig();
            tls.setCertChainFile("server.crt");
            tls.setPrivateKeyFile("");
            server.setTls(tls);
            server.setInsecureAllowPlaintext(true);
            assertThatCode(() ->
                    InsecureModeGate.requireTlsOrExplicitInsecure(server, "TCP listener (port 8888)"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("error message names the listener label so the operator knows which bind failed")
        void errorMessageIncludesLabel() {
            ServerConfig server = new ServerConfig();
            String label = "TCP listener (port 8888)";
            assertThatThrownBy(() ->
                    InsecureModeGate.requireTlsOrExplicitInsecure(server, label))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(label);
        }
    }
}
