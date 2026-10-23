<?php

declare(strict_types=1);

namespace NovaChat\Network;

/**
 * Guard + blocking write for an ext-socket handle.
 *
 * PHP 8.0 changed socket_create() to return a Socket object instead of a
 * resource, so is_resource() is FALSE for every live socket on every PHP this
 * plugin supports (composer.json: ^8.1). A guard written as !is_resource()
 * therefore rejects every real connection -- that is what made PMMP log
 * "Socket is not valid" and never put the handshake on the wire.
 *
 * The check has to stay type-aware in both directions:
 *  - a Socket object must pass, and a closed one must not. A closed Socket is
 *    still an instance of Socket, so instanceof alone is not enough: every
 *    socket_* call on it throws Error "... has already been closed".
 *  - a stream resource (fopen / stream_socket_client -- the TLS path) must not
 *    pass: socket_write()/socket_set_block() raise TypeError on it.
 *
 * The write loop lives here rather than in AsyncSendTask so it can be driven
 * by a unit test without pmmpthread (AsyncTask extends the extension-provided
 * pmmp\thread\Runnable class, which the CI PHP does not install).
 */
final class SocketGuard {

    /**
     * Whether the handle is a live ext-socket socket.
     *
     * @param mixed $socket Socket object (PHP 8), ext-socket resource (PHP 7),
     *                      or anything else that reached this point by mistake
     */
    public static function isUsable(mixed $socket): bool {
        if ($socket instanceof \Socket) {
            try {
                // SO_TYPE is the cheapest probe: it reads a socket property
                // without changing state (unlike a read or a write) and works
                // on connected and unconnected sockets alike. It throws Error
                // on a handle that socket_close() already released.
                return socket_get_option($socket, SOL_SOCKET, SO_TYPE) !== false;
            } catch (\Error) {
                return false;
            }
        }

        // Legacy PHP 7 sockets. The ext-socket resource type is "Socket"; any
        // other resource (notably a "stream") is not writable by socket_write.
        return is_resource($socket) && get_resource_type($socket) === "Socket";
    }

    /**
     * Writes the whole buffer, blocking the socket for the duration.
     *
     * Mirrors the original AsyncSendTask loop: switch to blocking, write until
     * every byte is out, switch back to non-blocking. A short write is retried
     * on the remainder, not treated as failure.
     *
     * @param mixed $socket A handle that passed isUsable()
     * @return string|null Null on success, otherwise the socket error text
     */
    public static function writeAll(mixed $socket, string $data): ?string {
        $length = strlen($data);
        $sent = 0;

        socket_set_block($socket);

        while ($sent < $length) {
            $written = @socket_write($socket, substr($data, $sent), $length - $sent);

            if ($written === false) {
                $error = socket_strerror(socket_last_error($socket));
                socket_set_nonblock($socket);
                return $error;
            }

            $sent += $written;
        }

        socket_set_nonblock($socket);
        return null;
    }
}
