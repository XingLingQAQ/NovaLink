<?php

declare(strict_types=1);

namespace NovaChat\Tests\Network;

use NovaChat\Config\ConfigManager;
use NovaChat\Network\NetworkClient;
use NovaChat\NovaChatPlugin;
use PHPUnit\Framework\TestCase;
use ReflectionMethod;
use ReflectionProperty;

/**
 * AUTH-002 TLS coverage for PMMP NetworkClient.
 *
 * The production NetworkClient implements TLS via the PHP streams API
 * (stream_socket_client with tls:// + an ssl stream context built by
 * buildTlsContext). These tests verify:
 *
 * 1. buildTlsContext configuration correctness — verify_peer, verify_peer_name,
 *    allow_self_signed=false, cafile, local_cert/local_pk.
 * 2. TLS handshake success — a real TLS handshake against a mock server using
 *    the context from buildTlsContext, plus data round-trip.
 * 3. TLS verification failure — self-signed cert not signed by the trusted CA
 *    → handshake fails.
 * 4. InsecureModeGate — tls_enabled=false → null context (plaintext path);
 *    tls_enabled=true → non-null context with mandatory verification.
 *
 * Self-signed test certificates (test CA, server, client, bad-server) are test
 * fixtures under fixtures/tls/, mirroring the StarLink Java TLS test material.
 * The bad-server cert is self-signed (not signed by the test CA) to exercise
 * the verification-failure path.
 */
final class NetworkClientTlsTest extends TestCase {
    private string $tlsDir;

    protected function setUp(): void {
        $this->tlsDir = __DIR__ . DIRECTORY_SEPARATOR . 'fixtures' . DIRECTORY_SEPARATOR . 'tls';
    }

    protected function tearDown(): void {
        // Drain the OpenSSL error queue so a leftover error from one test
        // does not surface as a spurious warning in the next.
        while (openssl_error_string() !== false) {
            // discard
        }
    }

    // ------------------------------------------------------------------
    // buildTlsContext configuration tests (reflection, no network)
    // ------------------------------------------------------------------

    /**
     * When TLS is enabled, verification is ALWAYS on — verify_peer,
     * verify_peer_name, and allow_self_signed=false are hardcoded. There is
     * no option to disable verification (disabling would re-open the
     * sniff/brute-force window TLS is meant to close).
     */
    public function testBuildTlsContextEnforcesVerificationFlags(): void {
        $client = $this->makeClient(
            tlsEnabled: true,
            caCertPath: $this->tlsDir . '/test-ca.crt'
        );
        $ctx = $this->invokeBuildTlsContext($client);
        self::assertNotNull($ctx);
        $opts = stream_context_get_options($ctx);
        self::assertTrue($opts['ssl']['verify_peer'], 'verify_peer must be true');
        self::assertTrue($opts['ssl']['verify_peer_name'], 'verify_peer_name must be true');
        self::assertFalse($opts['ssl']['allow_self_signed'], 'allow_self_signed must be false');
    }

    /**
     * The configured CA path is passed as cafile in the SSL context so
     * OpenSSL loads the trusted root for backend certificate verification.
     */
    public function testBuildTlsContextIncludesCaFile(): void {
        $caPath = $this->tlsDir . '/test-ca.crt';
        $client = $this->makeClient(tlsEnabled: true, caCertPath: $caPath);
        $ctx = $this->invokeBuildTlsContext($client);
        self::assertNotNull($ctx);
        $opts = stream_context_get_options($ctx);
        self::assertSame($caPath, $opts['ssl']['cafile']);
    }

    /**
     * When mTLS client cert + key are configured, they appear as local_cert
     * and local_pk in the SSL context so the client presents a certificate
     * when the backend requests one (mutual TLS).
     */
    public function testBuildTlsContextIncludesClientCertAndKey(): void {
        $certPath = $this->tlsDir . '/client.crt';
        $keyPath = $this->tlsDir . '/client.key';
        $client = $this->makeClient(
            tlsEnabled: true,
            caCertPath: $this->tlsDir . '/test-ca.crt',
            clientCertPath: $certPath,
            clientKeyPath: $keyPath
        );
        $ctx = $this->invokeBuildTlsContext($client);
        self::assertNotNull($ctx);
        $opts = stream_context_get_options($ctx);
        self::assertSame($certPath, $opts['ssl']['local_cert']);
        self::assertSame($keyPath, $opts['ssl']['local_pk']);
    }

    // ------------------------------------------------------------------
    // InsecureModeGate tests
    // ------------------------------------------------------------------

    /**
     * InsecureModeGate: tls_enabled=false → buildTlsContext returns null.
     * connect() checks tlsContext !== null to branch to the TLS path; when
     * null, it falls through to the plaintext AsyncConnectTask path.
     */
    public function testInsecureModeGatePlaintextWhenDisabled(): void {
        $client = $this->makeClient(tlsEnabled: false);
        self::assertNull($this->invokeBuildTlsContext($client));

        // The tlsContext field (set in the constructor) must also be null
        // so connect() takes the plaintext branch.
        $fieldRef = new ReflectionProperty(NetworkClient::class, 'tlsContext');
        self::assertNull($fieldRef->getValue($client));
    }

    /**
     * InsecureModeGate: tls_enabled=true → buildTlsContext returns a non-null
     * context with verification ALWAYS on. There is no flag to disable it.
     */
    public function testInsecureModeGateForcesTlsWhenEnabled(): void {
        $client = $this->makeClient(
            tlsEnabled: true,
            caCertPath: $this->tlsDir . '/test-ca.crt'
        );
        $ctx = $this->invokeBuildTlsContext($client);
        self::assertNotNull($ctx);

        // The tlsContext field (set in the constructor) must be non-null
        // so connect() takes the TLS branch.
        $fieldRef = new ReflectionProperty(NetworkClient::class, 'tlsContext');
        self::assertNotNull($fieldRef->getValue($client));
    }

    // ------------------------------------------------------------------
    // TLS handshake tests (mock server, real TLS)
    // ------------------------------------------------------------------

    /**
     * TLS handshake succeeds when the server presents a certificate signed by
     * the configured CA. Uses the actual SSL context from buildTlsContext and
     * verifies data can be sent/received after the handshake.
     *
     * The mock server is a plain TCP stream_socket_server that accepts one
     * connection, then both sides call stream_socket_enable_crypto in
     * non-blocking mode, alternating TLS handshake steps until both complete.
     */
    public function testTlsHandshakeSucceedsWithValidCa(): void {
        $caPath = $this->tlsDir . '/test-ca.crt';
        $serverCert = $this->tlsDir . '/server.crt';
        $serverKey = $this->tlsDir . '/server.key';

        // Build the client SSL context via the real production path
        $client = $this->makeClient(tlsEnabled: true, caCertPath: $caPath);
        $clientCtx = $this->invokeBuildTlsContext($client);
        self::assertNotNull($clientCtx);

        $result = $this->performTlsHandshake($serverCert, $serverKey, $clientCtx);
        try {
            self::assertTrue(
                $result['clientDone'],
                'Client TLS handshake should succeed when server cert is signed by the trusted CA'
            );
            self::assertTrue(
                $result['serverDone'],
                'Server TLS handshake should complete'
            );

            // Verify data round-trip over the established TLS channel
            $this->verifyDataRoundTrip($result['clientStream'], $result['serverStream']);
        } finally {
            $this->closeStreams($result);
        }
    }

    /**
     * TLS handshake fails when the server presents a self-signed certificate
     * that is NOT signed by the configured CA. The client's verify_peer
     * rejects the untrusted chain — this is the core AUTH-002 security
     * guarantee: a MITM with an untrusted cert cannot establish a TLS session.
     */
    public function testTlsHandshakeFailsWithUnknownCa(): void {
        $caPath = $this->tlsDir . '/test-ca.crt';
        $badCert = $this->tlsDir . '/bad-server.crt';
        $badKey = $this->tlsDir . '/bad-server.key';

        // Client trusts only the test CA
        $client = $this->makeClient(tlsEnabled: true, caCertPath: $caPath);
        $clientCtx = $this->invokeBuildTlsContext($client);
        self::assertNotNull($clientCtx);

        // Server uses a self-signed cert not signed by the test CA
        $result = $this->performTlsHandshake($badCert, $badKey, $clientCtx);
        try {
            self::assertFalse(
                $result['clientDone'],
                'Client TLS handshake should fail when server cert is not signed by the trusted CA'
            );
        } finally {
            $this->closeStreams($result);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Builds a NetworkClient with the given TLS configuration.
     *
     * The plugin is stubbed — buildTlsContext only reads from ConfigManager,
     * so no plugin calls happen during construction. The debug() method is
     * void-typed; PHPUnit 10 rejects willReturn(null) for void, so we use
     * willReturnCallback with a no-op closure.
     */
    private function makeClient(
        bool $tlsEnabled,
        string $caCertPath = '',
        string $clientCertPath = '',
        string $clientKeyPath = ''
    ): NetworkClient {
        $config = $this->validConfig();
        if ($tlsEnabled) {
            $config['backend']['tls'] = [
                'enable' => true,
                'ca_cert_path' => $caCertPath,
                'client_cert_path' => $clientCertPath,
                'client_key_path' => $clientKeyPath,
            ];
        }
        $configManager = new ConfigManager($config);

        $plugin = $this->createStub(NovaChatPlugin::class);
        $plugin->method('debug')->willReturnCallback(static function (): void {});

        return new NetworkClient($plugin, $configManager);
    }

    /**
     * Invokes the private buildTlsContext method via reflection.
     *
     * @return resource|null The stream context, or null for plaintext
     */
    private function invokeBuildTlsContext(NetworkClient $client) {
        // setAccessible() is a no-op since PHP 8.1 (deprecated in 8.5) —
        // private members are reflectable without it.
        $ref = new ReflectionMethod($client, 'buildTlsContext');
        return $ref->invoke($client);
    }

    /**
     * Starts a mock TLS server and performs the TLS handshake.
     *
     * Uses stream_socket_server (TCP) + stream_socket_accept, then enables
     * TLS on both client and server via stream_socket_enable_crypto in
     * non-blocking mode. The two sides alternate processing TLS handshake
     * steps until both complete, one fails, or the deadline expires.
     *
     * This avoids forking a child process: PHP's non-blocking
     * stream_socket_enable_crypto returns 0 while the handshake is in
     * progress, so alternating calls on client and server advance the
     * TLS state machine step by step.
     *
     * @param resource $clientCtx SSL stream context from buildTlsContext
     * @return array{clientDone: bool, serverDone: bool, clientStream: resource|null, serverStream: resource|null}
     */
    private function performTlsHandshake(
        string $serverCert,
        string $serverKey,
        $clientCtx
    ): array {
        // Server SSL context: present the server cert, don't verify client
        $serverCtx = stream_context_create(['ssl' => [
            'local_cert' => $serverCert,
            'local_pk' => $serverKey,
            'allow_self_signed' => true,
            'verify_peer' => false,
            'verify_peer_name' => false,
        ]]);

        // TCP server on an ephemeral port (with server SSL context attached
        // so the accepted stream inherits the local_cert/local_pk options)
        $server = @stream_socket_server(
            'tcp://127.0.0.1:0',
            $errno,
            $errstr,
            STREAM_SERVER_BIND | STREAM_SERVER_LISTEN,
            $serverCtx
        );
        self::assertNotFalse($server, "Failed to start TCP server: $errstr ($errno)");

        $addr = stream_socket_get_name($server, false);
        $port = (int) substr($addr, strrpos($addr, ':') + 1);

        // Client TCP connect (blocking, with the SSL context attached so
        // stream_socket_enable_crypto can read verify_peer/cafile/peer_name)
        $clientStream = @stream_socket_client(
            'tcp://127.0.0.1:' . $port,
            $errno,
            $errstr,
            5,
            STREAM_CLIENT_CONNECT,
            $clientCtx
        );
        if ($clientStream === false) {
            fclose($server);
            self::fail("Client TCP connect failed: $errstr ($errno)");
        }

        // Set peer_name for verify_peer_name (production uses tls:// URL
        // which sets this automatically from the host part; here we set it
        // explicitly because we used tcp:// + stream_socket_enable_crypto).
        stream_context_set_option($clientStream, 'ssl', 'peer_name', '127.0.0.1');

        // Server accept (blocking, fast — connection is already pending)
        $serverStream = @stream_socket_accept($server, 5);
        fclose($server); // close listener — we only need one connection
        if ($serverStream === false) {
            fclose($clientStream);
            self::fail("Server accept failed");
        }

        // Switch both to non-blocking for the TLS handshake
        stream_set_blocking($clientStream, false);
        stream_set_blocking($serverStream, false);

        $clientDone = false;
        $serverDone = false;
        $clientFailed = false;
        $deadline = time() + 5;

        while (
            !$clientFailed
            && (!$clientDone || !$serverDone)
            && time() < $deadline
        ) {
            if (!$clientDone) {
                $r = @stream_socket_enable_crypto(
                    $clientStream,
                    true,
                    STREAM_CRYPTO_METHOD_TLS_CLIENT
                );
                if ($r === true) {
                    $clientDone = true;
                } elseif ($r === false) {
                    $clientFailed = true;
                }
                // 0 = handshake in progress, continue
            }
            if (!$serverDone) {
                $r = @stream_socket_enable_crypto(
                    $serverStream,
                    true,
                    STREAM_CRYPTO_METHOD_TLS_SERVER
                );
                if ($r === true) {
                    $serverDone = true;
                } elseif ($r === false) {
                    // Server-side failure is expected when the client
                    // aborts (e.g. cert rejected) — stop looping.
                    break;
                }
            }
            if (!$clientDone && !$serverDone && !$clientFailed) {
                usleep(10000); // 10ms — let data propagate between sides
            }
        }

        return [
            'clientDone' => $clientDone,
            'serverDone' => $serverDone,
            'clientStream' => $clientStream,
            'serverStream' => $serverStream,
        ];
    }

    /**
     * Verifies data can be sent and received over the established TLS channel.
     *
     * @param resource $clientStream
     * @param resource $serverStream
     */
    private function verifyDataRoundTrip($clientStream, $serverStream): void {
        stream_set_blocking($clientStream, true);
        stream_set_blocking($serverStream, true);

        $message = "hello TLS";
        $written = fwrite($clientStream, $message);
        self::assertSame(strlen($message), $written, 'Client should write all bytes');

        $received = fread($serverStream, 4096);
        self::assertSame($message, $received, 'Server should receive the message');

        // Echo back
        $written = fwrite($serverStream, $received);
        self::assertSame(strlen($received), $written);

        $echoed = fread($clientStream, 4096);
        self::assertSame($message, $echoed, 'Client should receive the echo');
    }

    /**
     * @param array{clientStream: resource|null, serverStream: resource|null} $result
     */
    private function closeStreams(array $result): void {
        if (isset($result['clientStream']) && is_resource($result['clientStream'])) {
            @fclose($result['clientStream']);
        }
        if (isset($result['serverStream']) && is_resource($result['serverStream'])) {
            @fclose($result['serverStream']);
        }
    }

    /** @return array<string, mixed> */
    private function validConfig(): array {
        return [
            'config-version' => 1,
            'backend' => [
                'host' => '127.0.0.1',
                'port' => 18888,
                'username' => 'PMMP_Server',
                'password' => 'secret',
                'server-version' => '5.0.0',
                'reconnect-delay' => 5,
            ],
            'chat' => [
                'replace_vanilla' => false,
                'default_channel' => 'local',
            ],
            'format' => [
                'prefix' => '[NovaChat] ',
                'error' => 'error: {message}',
                'success' => 'success: {message}',
                'default' => 'default',
                'channels' => ['local' => 'local'],
            ],
            'debug' => false,
        ];
    }
}
