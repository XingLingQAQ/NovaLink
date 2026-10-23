<?php

declare(strict_types=1);

namespace NovaChat\Network;

use pocketmine\scheduler\AsyncTask;

/**
 * Async task for sending data to the backend server.
 * 
 * This task handles packet sending in a way that doesn't block the main thread.
 * Note: Due to PHP socket limitations, actual sending happens on main thread,
 * but this task structure allows for future optimization.
 * 
 * Requirements:
 * - 8.5: THE NovaChat-PMMP SHALL 使用 libasyncsocket 或 pmmpthread 实现异步网络通信
 */
class AsyncSendTask extends AsyncTask {
    
    private string $data;
    
    /**
     * Creates a new async send task.
     *
     * @param \Socket|resource $socket Socket handle (PHP 8 object or legacy resource)
     * @param string $data Data to send
     * @param callable $callback Callback function(bool $success, string $error)
     */
    public function __construct($socket, string $data, callable $callback) {
        $this->storeLocal("socket", $socket);
        $this->storeLocal("callback", $callback);
        $this->data = $data;
    }
    
    /**
     * Runs in async thread - prepares data for sending.
     */
    public function onRun(): void {
        // Data is already serialized, just pass it through
        $this->setResult(["data" => $this->data]);
    }
    
    /**
     * Called on the main thread - performs actual send.
     */
    public function onCompletion(): void {
        /** @var \Socket|resource|null $socket */
        $socket = $this->fetchLocal("socket");
        /** @var callable $callback */
        $callback = $this->fetchLocal("callback");
        $result = $this->getResult();

        // PHP 8 returns a Socket object, not a resource (see SocketGuard).
        // The old is_resource() guard here rejected every live socket, so the
        // handshake frame was never written.
        if (!SocketGuard::isUsable($socket)) {
            $callback(false, "Socket is not valid");
            return;
        }

        $error = SocketGuard::writeAll($socket, $result["data"]);
        if ($error !== null) {
            $callback(false, $error);
            return;
        }

        $callback(true, "");
    }
}
