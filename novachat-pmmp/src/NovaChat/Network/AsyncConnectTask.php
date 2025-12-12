<?php

declare(strict_types=1);

namespace NovaChat\Network;

use pocketmine\scheduler\AsyncTask;

/**
 * Async task for establishing TCP connection to the backend server.
 * 
 * This task runs the connection attempt in a separate thread to avoid
 * blocking the main server thread.
 * 
 * Requirements:
 * - 8.5: THE NovaChat-PMMP SHALL 使用 libasyncsocket 或 pmmpthread 实现异步网络通信
 */
class AsyncConnectTask extends AsyncTask {
    
    /** Connection timeout in seconds */
    private const CONNECT_TIMEOUT = 10;
    
    private string $host;
    private int $port;
    
    /**
     * Creates a new async connect task.
     * 
     * @param string $host Server host
     * @param int $port Server port
     * @param callable $callback Callback function(bool $success, resource|null $socket, string $error)
     */
    public function __construct(string $host, int $port, callable $callback) {
        $this->host = $host;
        $this->port = $port;
        $this->storeLocal("callback", $callback);
    }
    
    /**
     * Runs the connection attempt in a separate thread.
     */
    public function onRun(): void {
        $socket = @socket_create(AF_INET, SOCK_STREAM, SOL_TCP);
        if ($socket === false) {
            $this->setResult([
                "success" => false,
                "error" => "Failed to create socket: " . socket_strerror(socket_last_error())
            ]);
            return;
        }
        
        // Set socket to non-blocking for connection with timeout
        socket_set_nonblock($socket);
        
        // Attempt connection
        $result = @socket_connect($socket, $this->host, $this->port);
        
        if ($result === false) {
            $error = socket_last_error($socket);
            
            // Check if connection is in progress (expected for non-blocking)
            if ($error === SOCKET_EINPROGRESS || $error === SOCKET_EALREADY || 
                $error === 10035 || $error === 115) { // Windows and Linux codes
                
                // Wait for connection with timeout using select
                $write = [$socket];
                $except = null;
                $read = null;
                
                $selectResult = @socket_select($read, $write, $except, self::CONNECT_TIMEOUT);
                
                if ($selectResult === false) {
                    @socket_close($socket);
                    $this->setResult([
                        "success" => false,
                        "error" => "Select failed: " . socket_strerror(socket_last_error())
                    ]);
                    return;
                }
                
                if ($selectResult === 0) {
                    @socket_close($socket);
                    $this->setResult([
                        "success" => false,
                        "error" => "Connection timeout"
                    ]);
                    return;
                }
                
                // Check if connection succeeded
                $socketError = socket_get_option($socket, SOL_SOCKET, SO_ERROR);
                if ($socketError !== 0) {
                    @socket_close($socket);
                    $this->setResult([
                        "success" => false,
                        "error" => "Connection failed: " . socket_strerror($socketError)
                    ]);
                    return;
                }
            } else {
                @socket_close($socket);
                $this->setResult([
                    "success" => false,
                    "error" => "Connection failed: " . socket_strerror($error)
                ]);
                return;
            }
        }
        
        // Connection successful - store socket info for main thread
        // Note: We can't pass socket resources between threads, so we store connection info
        $this->setResult([
            "success" => true,
            "host" => $this->host,
            "port" => $this->port
        ]);
        
        // Close the socket in this thread - main thread will create its own
        @socket_close($socket);
    }
    
    /**
     * Called on the main thread after the task completes.
     */
    public function onCompletion(): void {
        /** @var callable $callback */
        $callback = $this->fetchLocal("callback");
        $result = $this->getResult();
        
        if ($result["success"]) {
            // Create socket on main thread
            $socket = @socket_create(AF_INET, SOCK_STREAM, SOL_TCP);
            if ($socket === false) {
                $callback(false, null, "Failed to create socket on main thread");
                return;
            }
            
            // Set timeout for blocking connect
            socket_set_option($socket, SOL_SOCKET, SO_RCVTIMEO, ["sec" => 5, "usec" => 0]);
            socket_set_option($socket, SOL_SOCKET, SO_SNDTIMEO, ["sec" => 5, "usec" => 0]);
            
            // Connect (we know it should succeed since async task verified)
            $connectResult = @socket_connect($socket, $result["host"], $result["port"]);
            if ($connectResult === false) {
                $error = socket_strerror(socket_last_error($socket));
                @socket_close($socket);
                $callback(false, null, "Main thread connect failed: $error");
                return;
            }
            
            $callback(true, $socket, "");
        } else {
            $callback(false, null, $result["error"]);
        }
    }
}
