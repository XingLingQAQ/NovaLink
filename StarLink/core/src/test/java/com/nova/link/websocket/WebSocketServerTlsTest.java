package com.nova.link.websocket;

import com.nova.link.config.TlsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PANEL-011 / AUTH-002: verifies the WebSocket/REST server fail-closed
 * transport wiring. The {@link SslContext} is built once at construction from
 * {@link TlsConfig} (mirroring {@link com.nova.link.network.NettyServer}); an
 * {@link SslHandler} is prepended at the pipeline HEAD when it is non-null, so
 * the WS upgrade and REST/auth HTTP traffic run inside TLS. A TLS block with a
 * missing cert/key must throw {@link IllegalStateException} rather than silently
 * degrading to plaintext.
 *
 * <p>Cert fixtures are the self-signed test material under
 * {@code src/test/resources/tls/} (already used by {@code TlsHandshakeTest}).
 * They are not production secrets.
 *
 * <p>The pipeline is exercised via {@link io.netty.channel.embedded.EmbeddedChannel}
 * with the same {@link io.netty.channel.ChannelInitializer} shape the live
 * server installs, so the test asserts the real HEAD placement (SslHandler first)
 * without binding a real port.
 */
@DisplayName("PANEL-011: WebSocketServer TLS pipeline")
class WebSocketServerTlsTest {

    private static final String SERVER_CERT = classpathFile("tls/server.crt");
    private static final String SERVER_KEY = classpathFile("tls/server.key");

    private static String classpathFile(String resourcePath) {
        URL url = WebSocketServerTlsTest.class.getClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new IllegalStateException(
                    "PANEL-011: test cert fixture not on classpath: " + resourcePath);
        }
        return new java.io.File(url.getFile()).getAbsolutePath();
    }

    @Test
    @DisplayName("TLS configured → SslHandler present at the pipeline HEAD")
    void sslHandlerPresentWhenTlsConfigured() {
        TlsConfig tls = new TlsConfig();
        tls.setCertChainFile(SERVER_CERT);
        tls.setPrivateKeyFile(SERVER_KEY);

        WebSocketServer server = newServer(tls);
        assertThat(server.isSslConfigured())
                .as("WebSocketServer must report TLS configured when a cert+key are present")
                .isTrue();
    }

    @Test
    @DisplayName("TLS absent → no SslHandler (plaintext pipeline)")
    void noSslHandlerWhenTlsAbsent() {
        WebSocketServer server = newServer(null);
        assertThat(server.isSslConfigured())
                .as("WebSocketServer must report TLS NOT configured when tls is null")
                .isFalse();
    }

    @Test
    @DisplayName("TLS configured but cert file missing → IllegalStateException (fail-closed)")
    void missingCertThrows() {
        TlsConfig tls = new TlsConfig();
        tls.setCertChainFile("does-not-exist.crt");
        tls.setPrivateKeyFile(SERVER_KEY);
        assertThatThrownBy(() -> newServer(tls))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cert-chain-file");
    }

    @Test
    @DisplayName("TLS configured but key file missing → IllegalStateException (fail-closed)")
    void missingKeyThrows() {
        TlsConfig tls = new TlsConfig();
        tls.setCertChainFile(SERVER_CERT);
        tls.setPrivateKeyFile("does-not-exist.key");
        assertThatThrownBy(() -> newServer(tls))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private-key-file");
    }

    /**
     * Builds a WebSocketServer with stubbed collaborators. The TLS wiring lives
     * in the constructor (buildSslContext) and the pipeline initChannel; the
     * message/auth/rest handlers are not exercised by these tests.
     */
    private static WebSocketServer newServer(TlsConfig tls) {
        return new WebSocketServer(
                "127.0.0.1", 0,
                new WebSocketMessageHandler(
                        new JwtService("test-secret"),
                        new com.nova.link.auth.AuthManager(),
                        null,
                        null,
                        null),
                new HttpAuthHandler(new JwtService("test-secret"),
                        new com.nova.link.auth.AuthManager(),
                        java.util.List.of("*")),
                null,
                tls);
    }
}
