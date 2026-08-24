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
 * AUTH-002: verifies mutual TLS (mTLS) — the server requires + verifies a
 * client certificate, and only clients presenting a cert signed by the
 * trusted CA can complete the TLS handshake and run the challenge-response
 * flow.
 *
 * <p>The server is started with {@code mutualTls=true} and a {@code caCertFile}
 * pointing at the test CA. {@link NettyServer} wires this as
 * {@link io.netty.handler.ssl.ClientAuth#REQUIRE}, so the SSLEngine aborts the
 * handshake before any application bytes are exchanged if the client presents
 * no cert (or an untrusted cert). A client with a cert signed by the test CA
 * must complete TLS and then authenticate normally.
 *
 * <p>Cert fixtures are self-signed test-only material under
 * {@code StarLink/core/src/test/resources/tls/}. They are NOT production
 * secrets.
 */
@DisplayName("AUTH-002 mutual TLS")
class MutualTlsTest {

    private static final int TEST_PORT = 18911;
    // Resolve the test cert fixtures from the classpath so the tests work
    // regardless of the test worker's working directory (Gradle runs tests
    // from the module dir, so a repo-root-relative path like
    // "StarLink/core/..." would not resolve). The files live under
    // src/test/resources/tls/ and end up on the test classpath at "tls/...".
    private static final String SERVER_CERT = classpathFile("tls/server.crt");
    private static final String SERVER_KEY = classpathFile("tls/server.key");
    private static final String CA_CERT = classpathFile("tls/test-ca.crt");
    private static final String CLIENT_CERT = classpathFile("tls/client.crt");
    private static final String CLIENT_KEY = classpathFile("tls/client.key");

    private IntegrationTestHelper helper;

    private static String classpathFile(String resourcePath) {
        URL url = MutualTlsTest.class.getClassLoader().getResource(resourcePath);
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
    @DisplayName("client presenting a CA-signed cert authenticates over mTLS")
    void clientWithCertAuthenticates() throws Exception {
        TlsConfig tls = new TlsConfig();
        tls.setCertChainFile(SERVER_CERT);
        tls.setPrivateKeyFile(SERVER_KEY);
        tls.setCaCertFile(CA_CERT);
        tls.setMutualTls(true);
        helper.startServer(TEST_PORT, tls);

        String clientId = "MtlsClient";
        String password = "mtls-password";
        helper.registerClient(clientId, password);

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        // Client trusts the test CA (to validate server.crt) AND presents its
        // own client.crt/key (signed by the same CA) so the server's
        // ClientAuth.REQUIRE check passes.
        SslContext clientSsl = SslContextBuilder.forClient()
                .trustManager(new File(CA_CERT))
                .keyManager(new File(CLIENT_CERT), new File(CLIENT_KEY))
                .build();
        client.setSslContext(clientSsl);

        Boolean connected = client.connect().get(5, TimeUnit.SECONDS);
        assertThat(connected)
                .as("mTLS client with a valid cert must complete the TLS handshake")
                .isTrue();

        HandshakeResponsePacket response = client.authenticate(clientId, password)
                .get(5, TimeUnit.SECONDS);

        assertThat(response.isSuccess())
                .as("AUTH-002 challenge-response must succeed inside mTLS")
                .isTrue();
        assertThat(client.isAuthenticated()).isTrue();

        client.disconnect();
    }

    @Test
    @DisplayName("client without a cert is rejected by the mTLS server")
    void clientWithoutCertRejected() throws Exception {
        TlsConfig tls = new TlsConfig();
        tls.setCertChainFile(SERVER_CERT);
        tls.setPrivateKeyFile(SERVER_KEY);
        tls.setCaCertFile(CA_CERT);
        tls.setMutualTls(true);
        helper.startServer(TEST_PORT, tls);

        String clientId = "MtlsNoCert";
        String password = "mtls-password";
        helper.registerClient(clientId, password);

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        // Trusts the CA (so server.crt validates) but presents NO client cert.
        // The server's ClientAuth.REQUIRE must abort the TLS handshake.
        SslContext clientSsl = SslContextBuilder.forClient()
                .trustManager(new File(CA_CERT))
                .build();
        client.setSslContext(clientSsl);

        Boolean connected = client.connect().get(5, TimeUnit.SECONDS);
        // TCP connect may report success, but the TLS handshake fails because
        // the server requires a client cert. The auth future must never
        // complete successfully.
        assertThat(connected).isTrue();

        assertThatThrownBy(() -> client.authenticate(clientId, password).get(2, TimeUnit.SECONDS))
                .as("a client without a cert must never authenticate against an mTLS server")
                .isInstanceOfAny(TimeoutException.class, java.util.concurrent.ExecutionException.class);

        assertThat(client.isAuthenticated())
                .as("client without a cert must remain unauthenticated")
                .isFalse();

        client.disconnect();
    }
}
