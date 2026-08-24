package com.nova.link.integration;

import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import com.nova.link.config.TlsConfig;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.junit.jupiter.api.*;

import java.io.File;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AUTH-002: verifies the 3-packet challenge-response handshake runs inside a
 * one-way TLS channel (server presents a cert, client trusts the test CA).
 *
 * <p>The server is started with a {@link TlsConfig} pointing at the self-signed
 * server cert/key under {@code src/test/resources/tls/}; the test client
 * installs an {@link SslContext} that trusts the test CA so the TLS handshake
 * completes, after which the normal AUTH-002 challenge-response dance runs and
 * must authenticate successfully.
 *
 * <p>Cert fixtures are self-signed test-only material generated under
 * {@code StarLink/core/src/test/resources/tls/}. They are NOT production
 * secrets and are safe to commit alongside the test sources.
 */
@DisplayName("AUTH-002 TLS handshake (one-way)")
class TlsHandshakeTest {

    private static final int TEST_PORT = 18910;
    // Resolve the test cert fixtures from the classpath so the tests work
    // regardless of the test worker's working directory (Gradle runs tests
    // from the module dir, so a repo-root-relative path like
    // "StarLink/core/..." would not resolve). The files live under
    // src/test/resources/tls/ and end up on the test classpath at "tls/...".
    private static final String SERVER_CERT = classpathFile("tls/server.crt");
    private static final String SERVER_KEY = classpathFile("tls/server.key");
    private static final String CA_CERT = classpathFile("tls/test-ca.crt");

    private IntegrationTestHelper helper;

    private static String classpathFile(String resourcePath) {
        URL url = TlsHandshakeTest.class.getClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new IllegalStateException(
                    "AUTH-002: test cert fixture not on classpath: " + resourcePath);
        }
        return new File(url.getFile()).getAbsolutePath();
    }

    @BeforeEach
    void setUp() {
        helper = new IntegrationTestHelper();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (helper != null) {
            helper.stopServer();
        }
    }

    @Test
    @DisplayName("challenge-response authenticates over a TLS-wrapped connection")
    void authenticatesOverTls() throws Exception {
        TlsConfig tls = new TlsConfig();
        tls.setCertChainFile(SERVER_CERT);
        tls.setPrivateKeyFile(SERVER_KEY);
        // One-way TLS: no caCertFile, mutualTls left false. The server presents
        // server.crt; the client trusts the test CA so the chain validates.
        helper.startServer(TEST_PORT, tls);

        String clientId = "TlsClient";
        String password = "tls-password";
        helper.registerClient(clientId, password);

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        // Trust the test CA so the server's server.crt (signed by test-ca) validates.
        SslContext clientSsl = SslContextBuilder.forClient()
                .trustManager(new File(CA_CERT))
                .build();
        client.setSslContext(clientSsl);

        Boolean connected = client.connect().get(5, TimeUnit.SECONDS);
        assertThat(connected)
                .as("TLS client must connect to the TLS-wrapped server")
                .isTrue();

        HandshakeResponsePacket response = client.authenticate(clientId, password)
                .get(5, TimeUnit.SECONDS);

        assertThat(response.isSuccess())
                .as("AUTH-002 challenge-response must succeed inside TLS")
                .isTrue();
        assertThat(response.getErrorCode()).isEmpty();
        assertThat(client.isAuthenticated()).isTrue();

        client.disconnect();
    }

    @Test
    @DisplayName("a client that does not trust the test CA cannot complete the TLS handshake")
    void plaintextClientRejectedByTlsServer() throws Exception {
        TlsConfig tls = new TlsConfig();
        tls.setCertChainFile(SERVER_CERT);
        tls.setPrivateKeyFile(SERVER_KEY);
        helper.startServer(TEST_PORT, tls);

        String clientId = "TlsClientNoTrust";
        String password = "tls-password";
        helper.registerClient(clientId, password);

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        // No setSslContext -> plaintext client against a TLS-only server. The
        // TLS handshake can never start (the server expects a ClientHello, the
        // client sends a raw VarInt frame), so connect either fails or the
        // channel goes inactive before any AUTH-002 packet can be exchanged.
        Boolean connected = client.connect().get(5, TimeUnit.SECONDS);
        // connect() completing true only means the TCP socket opened; the TLS
        // handshake failure surfaces as a closed channel + no auth response.
        assertThat(connected).isTrue();

        // The auth future never completes with a success HandshakeResponsePacket:
        // either it times out (server never got a valid ClientHello so never
        // sent a HandshakeChallenge) or the channel closed mid-handshake and the
        // future completes exceptionally on channel close.
        assertThatThrownBy(() -> client.authenticate(clientId, password).get(2, TimeUnit.SECONDS))
                .as("a plaintext client against a TLS server must never receive an auth response")
                // Either a TimeoutException (server ignored the bytes) or an
                // ExecutionException wrapping a closed-channel error — both
                // prove the challenge-response handshake never completed.
                .isInstanceOfAny(TimeoutException.class, java.util.concurrent.ExecutionException.class);

        assertThat(client.isAuthenticated())
                .as("plaintext client must remain unauthenticated against a TLS server")
                .isFalse();

        client.disconnect();
    }
}
