<?php

declare(strict_types=1);

namespace NovaChat\Tests\Network;

// AsyncSendTask extends pocketmine\scheduler\AsyncTask, which extends the
// pmmpthread-provided pmmp\thread\Runnable -- absent on the CI PHP. The shim
// declares the type shells so the real class can be loaded and driven.
require_once __DIR__ . '/support/pmmpthread_shim.php';

use NovaChat\Network\AsyncSendTask;
use NovaChat\Network\NetworkClient;
use NovaChat\Network\SocketGuard;
use NovaChat\Config\ConfigManager;
use NovaChat\NovaChatPlugin;
use PHPUnit\Framework\TestCase;
use ReflectionMethod;
use ReflectionProperty;

/**
 * Regression coverage for the PHP 8 socket handle type.
 *
 * socket_create() has returned a Socket OBJECT since PHP 8.0, never a
 * resource. The old AsyncSendTask guard was
 *
 *     if ($socket === null || !is_resource($socket)) {
 *         $callback(false, "Socket is not valid");
 *
 * so on every PHP this plugin supports it rejected the live connection and the
 * handshake frame was never written: PocketMine-MP logged "Failed to send
 * packet: Socket is not valid" on a loop and never authenticated. These tests
 * pin the replacement (SocketGuard) in both directions -- a live Socket must
 * pass, and a closed one, a stream, and null must still be rejected.
 *
 * A live socket here is a real loopback socket_create + socket_connect pair,
 * not a stub: the point of the bug is that the object's runtime type and its
 * post-close behaviour are what the guard has to get right.
 */
final class AsyncSendTaskSocketGuardTest extends TestCase {

    /** @var list<\Socket> Sockets opened by a test, closed even on failure. */
    private array $openSockets = [];

    protected function tearDown(): void {
        foreach ($this->openSockets as $socket) {
            try {
                @socket_close($socket);
            } catch (\Error) {
                // already closed by the test itself
            }
        }
        $this->openSockets = [];
    }

    // ------------------------------------------------------------------
    // The guard: live handles pass, everything else does not
    // ------------------------------------------------------------------

    /**
     * The exact regression: a connected PHP 8 socket_create() handle is a
     * Socket object, is_resource() is false for it, and it must still count
     * as valid. Before the fix this returned false and every send failed.
     */
    public function testConnectedSocketObjectIsUsable(): void {
        [$client] = $this->connectedPair();

        // Pin the premise the bug depended on, so this test fails loudly if a
        // future PHP ever goes back to resources (the guard would still work,
        // but this assertion documents why the branch exists).
        self::assertInstanceOf(\Socket::class, $client, 'socket_create() must return a Socket object on PHP 8+');
        self::assertFalse(is_resource($client), 'a PHP 8 Socket is not a resource');

        self::assertTrue(
            SocketGuard::isUsable($client),
            'A live PHP 8 Socket object must not be rejected as "Socket is not valid"'
        );
    }

    /**
     * A fresh, unconnected socket is a valid handle too: the guard must reject
     * released handles, not merely not-yet-connected ones.
     */
    public function testUnconnectedSocketObjectIsUsable(): void {
        $socket = $this->track(@socket_create(AF_INET, SOCK_STREAM, SOL_TCP));
        self::assertInstanceOf(\Socket::class, $socket);
        self::assertTrue(SocketGuard::isUsable($socket));
    }

    /**
     * Closed is the case the is_resource() check was nominally there for, and
     * it has to keep working. A closed Socket is still a Socket instance, so
     * the guard has to probe the handle, not just its type.
     */
    public function testClosedSocketObjectIsNotUsable(): void {
        [$client] = $this->connectedPair();
        socket_close($client);

        self::assertFalse(
            SocketGuard::isUsable($client),
            'A socket_close()d handle must be rejected'
        );
    }

    /**
     * Never deleting the check: a stream socket (what the TLS path produces)
     * must be rejected here, because socket_write/socket_set_block raise
     * TypeError on a stream. The TLS path has its own fwrite branch and
     * dispatches through NetworkClient::isStreamSocket().
     */
    public function testStreamResourceIsNotUsable(): void {
        [$server] = $this->listener();
        $stream = @stream_socket_client('tcp://127.0.0.1:' . $this->portOf($server), $errno, $errstr, 5);
        self::assertIsResource($stream, "stream_socket_client failed: $errstr ($errno)");
        try {
            self::assertFalse(
                SocketGuard::isUsable($stream),
                'A streams-API handle must not be handed to socket_write()'
            );
        } finally {
            fclose($stream);
        }
    }

    /** Anything that is not a socket handle at all must be rejected. */
    public function testNonSocketValuesAreNotUsable(): void {
        self::assertFalse(SocketGuard::isUsable(null), 'null (the not-connected sentinel) must be rejected');
        self::assertFalse(SocketGuard::isUsable(false), 'false (socket_create failure) must be rejected');
        self::assertFalse(SocketGuard::isUsable('socket'), 'a string must be rejected');
        self::assertFalse(SocketGuard::isUsable(new \stdClass()), 'an arbitrary object must be rejected');
    }

    // ------------------------------------------------------------------
    // The send path: the guard is wired into the real AsyncTask
    // ------------------------------------------------------------------

    /**
     * End-to-end through AsyncSendTask::onCompletion(): a live Socket must
     * reach the write and report success with the bytes arriving on the peer.
     * This is the assertion that would have caught the shipped bug -- the old
     * guard short-circuited with "Socket is not valid" before any write.
     */
    public function testOnCompletionWritesThroughALiveSocketObject(): void {
        [$client, $peer] = $this->connectedPair();
        $payload = "\x01\x02handshake-frame";

        $calls = [];
        $task = $this->makeTask($client, $payload, $calls);
        $task->onRun();
        $task->onCompletion();

        self::assertCount(1, $calls, 'onCompletion must invoke the callback exactly once');
        self::assertTrue($calls[0]['success'], 'Send must succeed; error was: ' . $calls[0]['error']);
        self::assertSame('', $calls[0]['error'], 'A successful send reports an empty error string');
        self::assertSame($payload, $this->readExactly($peer, strlen($payload)), 'The frame must reach the peer');
    }

    /**
     * The closed-socket path must still fail the send (and report rather than
     * throw), so a dead connection triggers handleDisconnect() instead of
     * silently swallowing the packet.
     */
    public function testOnCompletionRejectsAClosedSocketObject(): void {
        [$client] = $this->connectedPair();
        socket_close($client);

        $calls = [];
        $task = $this->makeTask($client, 'payload', $calls);
        $task->onRun();
        $task->onCompletion();

        self::assertCount(1, $calls);
        self::assertFalse($calls[0]['success'], 'A closed socket must fail the send');
        self::assertSame('Socket is not valid', $calls[0]['error']);
    }

    // ------------------------------------------------------------------
    // NetworkClient::isStreamSocket() -- the sibling guard
    // ------------------------------------------------------------------

    /**
     * isStreamSocket() picks the I/O family. A plaintext Socket object must
     * route to the socket_* branch, and a stream must route to the streams
     * branch. Before the fix the Socket case was only right by accident of
     * is_resource() returning false; the comment claimed the handle was "a
     * Socket type, not stream", which described a resource type that a PHP 8
     * Socket object does not have.
     */
    public function testIsStreamSocketDiscriminatesObjectsFromStreams(): void {
        $client = $this->makeNetworkClient();
        $prop = new ReflectionProperty(NetworkClient::class, 'socket');
        $method = new ReflectionMethod($client, 'isStreamSocket');

        [$socket, $peer] = $this->connectedPair();
        $prop->setValue($client, $socket);
        self::assertFalse($method->invoke($client), 'A plaintext Socket object is not a stream');

        $stream = @fopen('php://memory', 'r+');
        self::assertIsResource($stream);
        try {
            $prop->setValue($client, $stream);
            self::assertTrue($method->invoke($client), 'A streams-API resource is a stream');
        } finally {
            fclose($stream);
        }

        $prop->setValue($client, null);
        self::assertFalse($method->invoke($client), 'No socket is not a stream');
    }

    /**
     * writeAll() must not lose a short write: it retries the remainder and
     * returns null only after every byte is out.
     */
    public function testWriteAllDeliversTheWholePayload(): void {
        [$client, $peer] = $this->connectedPair();
        $payload = str_repeat('NovaLink-frame-', 1024); // larger than one socket buffer is fine either way

        self::assertNull(SocketGuard::writeAll($client, $payload));
        self::assertSame($payload, $this->readExactly($peer, strlen($payload)));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Opens a connected loopback TCP pair.
     *
     * @return array{0: \Socket, 1: \Socket} [client, accepted-peer]
     */
    private function connectedPair(): array {
        [$listener, $port] = $this->listener();
        try {
            $client = $this->track(@socket_create(AF_INET, SOCK_STREAM, SOL_TCP));
            self::assertInstanceOf(\Socket::class, $client, 'socket_create failed');
            self::assertTrue(@socket_connect($client, '127.0.0.1', $port), 'client connect failed');

            $peer = $this->track(@socket_accept($listener));
            self::assertInstanceOf(\Socket::class, $peer, 'accept failed');

            return [$client, $peer];
        } finally {
            socket_close($listener);
        }
    }

    /**
     * Binds a loopback listener on an ephemeral port.
     *
     * @return array{0: \Socket, 1: int} [listener, bound port]
     */
    private function listener(): array {
        $listener = $this->track(@socket_create(AF_INET, SOCK_STREAM, SOL_TCP));
        self::assertInstanceOf(\Socket::class, $listener, 'listener create failed');
        self::assertTrue(@socket_bind($listener, '127.0.0.1', 0), 'listener bind failed');
        self::assertTrue(@socket_listen($listener, 1), 'listener listen failed');

        return [$listener, $this->portOf($listener)];
    }

    private function portOf(\Socket $socket): int {
        $addr = '';
        $port = 0;
        self::assertTrue(@socket_getsockname($socket, $addr, $port), 'getsockname failed');
        return $port;
    }

    /** Reads exactly $length bytes, failing the test on EOF or timeout. */
    private function readExactly(\Socket $socket, int $length): string {
        $buffer = '';
        $deadline = microtime(true) + 5.0;
        socket_set_block($socket);
        socket_set_option($socket, SOL_SOCKET, SO_RCVTIMEO, ['sec' => 5, 'usec' => 0]);

        while (strlen($buffer) < $length && microtime(true) < $deadline) {
            $chunk = @socket_read($socket, $length - strlen($buffer));
            if ($chunk === false) {
                $err = socket_last_error($socket);
                if ($err === SOCKET_EAGAIN || $err === SOCKET_EWOULDBLOCK) {
                    continue;
                }
                break;
            }
            if ($chunk === '') {
                break;
            }
            $buffer .= $chunk;
        }

        return $buffer;
    }

    /**
     * Builds the real AsyncSendTask (not a double) with callback capture, so
     * these tests fail if the guard is ever unwired from the production task.
     *
     * @param list<array{success: bool, error: string}> $calls
     */
    private function makeTask(\Socket $socket, string $payload, array &$calls): AsyncSendTask {
        return new AsyncSendTask(
            $socket,
            $payload,
            static function (bool $success, string $error) use (&$calls): void {
                $calls[] = ['success' => $success, 'error' => $error];
            }
        );
    }

    private function makeNetworkClient(): NetworkClient {
        $config = [
            'config-version' => 1,
            'backend' => [
                'host' => '127.0.0.1',
                'port' => 18888,
                'username' => 'PMMP_Server',
                'password' => 'secret',
                'server-version' => '5.0.0',
                'reconnect-delay' => 5,
            ],
            'chat' => ['replace_vanilla' => false, 'default_channel' => 'local'],
            'format' => [
                'prefix' => '[NovaChat] ',
                'error' => 'error: {message}',
                'success' => 'success: {message}',
                'default' => 'default',
                'channels' => ['local' => 'local'],
            ],
            'debug' => false,
        ];

        $plugin = $this->createStub(NovaChatPlugin::class);
        $plugin->method('debug')->willReturnCallback(static function (): void {});

        return new NetworkClient($plugin, new ConfigManager($config));
    }

    /** @template T @param T $socket @return T */
    private function track($socket) {
        if ($socket instanceof \Socket) {
            $this->openSockets[] = $socket;
        }
        return $socket;
    }
}
