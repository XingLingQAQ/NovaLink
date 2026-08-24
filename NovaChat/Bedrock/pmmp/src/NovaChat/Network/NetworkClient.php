<?php

declare(strict_types=1);

namespace NovaChat\Network;

use NovaChat\Config\ConfigManager;
use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\ChannelActionPacket;
use NovaChat\Protocol\ChatMessagePacket;
use NovaChat\Protocol\HandshakeAuthenticatePacket;
use NovaChat\Protocol\HandshakeChallengePacket;
use NovaChat\Protocol\HandshakeInitPacket;
use NovaChat\Protocol\HandshakePacket;
use NovaChat\Protocol\HandshakeResponsePacket;
use NovaChat\Protocol\KeepAlivePacket;
use NovaChat\Protocol\Packet;
use NovaChat\Protocol\PacketBuffer;
use NovaChat\Protocol\VarInt;
use pocketmine\scheduler\ClosureTask;
use pocketmine\scheduler\TaskHandler;

/**
 * Network client for connecting to NovaLink backend.
 * 
 * This client implements asynchronous TCP communication using PocketMine's
 * scheduler system for non-blocking operations.
 * 
 * Requirements:
 * - 8.3: WHEN 插件启用 THEN NovaChat-PMMP SHALL 建立与后端的 TCP 连接
 * - 8.5: THE NovaChat-PMMP SHALL 使用 libasyncsocket 或 pmmpthread 实现异步网络通信
 * - 9.4: WHEN 连接断开 THEN NovaChat-PMMP SHALL 实现指数退避重连机制
 * - 9.5: THE NovaChat-PMMP SHALL 每 15 秒发送心跳包维持连接
 */
class NetworkClient {
    
    /** Keep-alive interval in seconds */
    private const KEEPALIVE_INTERVAL = 15;
    
    /** Keep-alive interval in ticks (20 ticks = 1 second) */
    private const KEEPALIVE_INTERVAL_TICKS = 300; // 15 seconds * 20 ticks
    
    /** Maximum reconnect delay in seconds */
    private const MAX_RECONNECT_DELAY = 60;
    
    /** Socket read interval in ticks */
    private const READ_INTERVAL_TICKS = 1; // Every tick for responsiveness
    
    /** @var NovaChatPlugin Plugin instance */
    private NovaChatPlugin $plugin;
    
    /** @var ConfigManager Configuration manager */
    private ConfigManager $config;
    
    /** @var resource|null Socket resource */
    private $socket = null;
    
    /** @var bool Connection status */
    private bool $connected = false;
    
    /** @var bool Authentication status */
    private bool $authenticated = false;
    
    /** @var int Current reconnect delay in seconds */
    private int $reconnectDelay;
    
    /** @var string Read buffer for incoming data */
    private string $readBuffer = "";
    
    /** @var int Last keep-alive timestamp */
    private int $lastKeepAlive = 0;
    
    /** @var TaskHandler|null Read task handler */
    private ?TaskHandler $readTaskHandler = null;
    
    /** @var TaskHandler|null Keep-alive task handler */
    private ?TaskHandler $keepAliveTaskHandler = null;
    
    /** @var bool Whether a reconnect is scheduled */
    private bool $reconnectScheduled = false;

    /**
     * AUTH-002 TLS: the stream context (SSL options) built from the config
     * manager. Null when TLS is disabled (plaintext path). Held on the
     * instance so it is built once and reused across reconnect attempts.
     *
     * @var resource|null stream context with ssl options, or null for plaintext
     */
    private $tlsContext = null;

    /**
     * Pending client nonce for the AUTH-002 challenge-response handshake.
     *
     * Generated when HandshakeInitPacket is sent, consumed when the server's
     * HandshakeChallengePacket arrives to compute the authenticate HMAC.
     * Cleared after use. Kept on the instance (not a local) because PMMP
     * drives the flow across scheduler ticks (send in sendHandshake, read in
     * processIncomingData → handlePacket).
     *
     * @var string hex string (32 chars) of the 16 random client nonce bytes
     */
    private string $pendingClientNonce = "";

    /**
     * Pending AdminAction request correlation map.
     *
     * Maps requestId (UUID string, sent on the AdminActionPacket) to the
     * originating player UUID string (or the console sentinel UUID). Populated
     * in sendAdminAction() right before the packet goes on the wire, popped in
     * handleAdminActionResponse() when the backend's AdminActionResponsePacket
     * arrives. This is the ONLY mechanism that routes an admin-action result
     * back to the player who issued /nc auth or /nc announce — without it the
     * response is undeliverable and the command would hang on "progress".
     *
     * The client never tracks super-admin session state here (the backend is
     * the sole arbiter via its NC-403 gate); this map only correlates
     * request/response routing, exactly mirroring the Java NetworkClient
     * pendingAdminRequests.
     *
     * @var array<string, string> requestId => player UUID
     */
    private array $pendingAdminRequests = [];
    
    /**
     * Creates a new network client.
     * 
     * @param NovaChatPlugin $plugin Plugin instance
     * @param ConfigManager $config Configuration manager
     */
    public function __construct(NovaChatPlugin $plugin, ConfigManager $config) {
        $this->plugin = $plugin;
        $this->config = $config;
        $this->reconnectDelay = $config->getReconnectDelay();
        $this->tlsContext = $this->buildTlsContext();
    }

    /**
     * Builds the SSL stream context used to wrap the backend TCP socket when
     * TLS is enabled (AUTH-002). Returns null when TLS is disabled so the
     * plaintext connect path is unchanged.
     *
     * PHP socket extension TLS: the socket is created with stream_socket_*
     * + an ssl stream context (verify_peer / verify_peer_name / cafile). The
     * ext-socket socket_* family does not support TLS, so a TLS connection
     * uses the streams API (stream_socket_client / stream_socket_enable_crypto)
     * rather than socket_create. When TLS is disabled, the existing
     * AsyncConnectTask / ext-socket path is preserved verbatim.
     *
     * Verification is ALWAYS enabled when TLS is on — there is no flag to
     * disable it (disabling would re-open the sniff/brute-force window TLS
     * is meant to close).
     *
     * @return resource|null stream context, or null for plaintext
     */
    private function buildTlsContext() {
        if (!$this->config->isTlsEnabled()) {
            return null;
        }

        $options = [
            // verify_peer: validate the backend certificate chain against the
            // configured CA (or the system CA store when cafile is empty).
            "verify_peer" => true,
            // verify_peer_name: validate that the certificate's CN/SAN matches
            // the configured backend host.
            "verify_peer_name" => true,
            // allow_self_signed: false — a self-signed cert is a trust failure
            // by default, matching the backend's InsecureModeGate posture.
            "allow_self_signed" => false,
        ];

        $caCertPath = $this->config->getTlsCaCertPath();
        if (trim($caCertPath) !== "") {
            $options["cafile"] = $caCertPath;
        }

        $clientCertPath = $this->config->getTlsClientCertPath();
        $clientKeyPath = $this->config->getTlsClientKeyPath();
        if (trim($clientCertPath) !== "" && trim($clientKeyPath) !== "") {
            // mTLS: present a client certificate when the backend requests one.
            $options["local_cert"] = $clientCertPath;
            $options["local_pk"] = $clientKeyPath;
        }

        $context = @stream_context_create(["ssl" => $options]);
        if ($context === false) {
            // stream_context_create can only fail on invalid option types,
            // which the validator above rules out — but guard anyway so a
            // future PHP build cannot silently fall back to plaintext.
            return null;
        }
        return $context;
    }

    /**
     * Whether the active socket is a streams-API resource (TLS path) rather
     * than an ext-socket Socket resource (plaintext path). The two resource
     * types are NOT interchangeable: socket_read/socket_write/socket_close
     * raise TypeError on a stream resource, and fread/fwrite/fclose raise
     * TypeError on an ext-socket Socket. Every I/O call site dispatches on
     * this flag so the plaintext path keeps its original ext-socket calls
     * verbatim and the TLS path uses the streams API.
     */
    private function isStreamSocket(): bool {
        return is_resource($this->socket) && get_resource_type($this->socket) === "stream";
    }

    /**
     * Connects to the backend over TLS (AUTH-002). The streams API performs
     * both the TCP connect and the TLS handshake in one blocking call when
     * the remote scheme is tls://, so by the time stream_socket_client returns
     * the backend certificate has already been verified against the SSL
     * context (verify_peer / verify_peer_name / cafile). The stream is then
     * switched to non-blocking for the tick-driven read loop.
     *
     * The plaintext path (AsyncConnectTask / ext-socket) is untouched when
     * TLS is disabled; this method only runs when buildTlsContext() returned
     * a non-null context (i.e. isTlsEnabled() is true).
     */
    private function connectTls(string $host, int $port): bool {
        $remote = "tls://" . $host . ":" . $port;
        // Match AsyncConnectTask::CONNECT_TIMEOUT (10s). The timeout governs
        // both the TCP connect and the TLS handshake.
        $timeout = 10;
        $errno = 0;
        $errstr = "";

        $socket = @stream_socket_client(
            $remote,
            $errno,
            $errstr,
            $timeout,
            STREAM_CLIENT_CONNECT,
            $this->tlsContext
        );

        if ($socket === false) {
            // stream_socket_client's $errstr is frequently empty for TLS
            // verification failures (the real detail is queued by OpenSSL).
            // Drain the OpenSSL error queue so an operator can diagnose a bad
            // CA, expired cert, or hostname mismatch instead of seeing a bare
            // "Failed to connect (TLS)" with no reason.
            $sslErrors = [];
            do {
                $sslError = openssl_error_string();
                if ($sslError === false) {
                    break;
                }
                $sslErrors[] = $sslError;
            } while (count($sslErrors) < 10);

            $reason = $errstr;
            if ($reason === "" && $sslErrors !== []) {
                $reason = implode("; ", $sslErrors);
            }
            $this->plugin->getLogger()->warning(
                "Failed to connect (TLS) to $host:$port: $reason ($errno)"
            );
            $this->scheduleReconnect($host, $port);
            return true;
        }

        // The TLS handshake completed inside stream_socket_client (tls://
        // remote + ssl context). Switch to non-blocking for the read loop.
        stream_set_blocking($socket, false);

        // Post-connect setup mirrors onConnectComplete, but inlined because
        // onConnectComplete calls socket_set_nonblock (ext-socket only).
        $this->socket = $socket;
        $this->connected = true;
        $this->reconnectDelay = $this->config->getReconnectDelay();
        $this->reconnectScheduled = false;
        $this->lastKeepAlive = time();

        $this->plugin->getLogger()->info("Connected (TLS) to NovaLink backend at $host:$port");
        $this->plugin->debug("TLS connection established, sending handshake...");

        $this->startReadLoop();
        $this->startKeepAliveLoop();
        $this->sendHandshake();
        return true;
    }

    /**
     * Sends a fully serialized packet over the TLS stream synchronously.
     *
     * Mirrors the plaintext AsyncSendTask behavior (blocking socket_write loop
     * on the main thread): the stream is briefly switched to blocking, the
     * whole frame is flushed, then switched back to non-blocking. The main
     * thread blocks only for the duration of the write — typically sub-
     * millisecond for chat-sized frames — which matches the plaintext path's
     * blocking-send posture. A residual-buffer non-blocking state machine
     * would be more complex and is not warranted for the production-only TLS
     * path.
     */
    private function sendStreamSync(string $data): bool {
        $length = strlen($data);
        $sent = 0;

        stream_set_blocking($this->socket, true);
        while ($sent < $length) {
            $written = @fwrite($this->socket, substr($data, $sent));
            if ($written === false || $written === 0) {
                // false = stream error; 0 on a blocking stream = EOF (peer
                // closed). Either way the connection is unusable.
                stream_set_blocking($this->socket, false);
                $this->plugin->debug("Failed to send packet (TLS): stream write error");
                $this->handleDisconnect();
                return false;
            }
            $sent += $written;
        }
        stream_set_blocking($this->socket, false);
        return true;
    }
    
    /**
     * Connects to the backend server asynchronously.
     * 
     * This method initiates a non-blocking connection attempt using
     * PocketMine's async task system.
     * 
     * Requirements:
     * - 8.5: THE NovaChat-PMMP SHALL 使用 libasyncsocket 或 pmmpthread 实现异步网络通信
     * 
     * @param string $host Server host
     * @param int $port Server port
     * @return bool True if connection attempt was initiated
     */
    public function connect(string $host, int $port): bool {
        $this->plugin->debug("Initiating async connection to $host:$port...");

        // AUTH-002 TLS: when TLS is enabled, branch to the streams-based connect
        // path (connectTls). The ext-socket socket_* family used by the plaintext
        // path has no TLS support, so a TLS connection is established via the
        // streams API (stream_socket_client with a tls:// remote + the ssl stream
        // context built in buildTlsContext). The handshake runs in blocking mode
        // (stream_socket_client blocks until the TLS handshake completes or fails
        // when the remote scheme is tls://), then the stream is switched to
        // non-blocking for the tick-driven read loop. The plaintext path below
        // (AsyncConnectTask / ext-socket) is untouched when TLS is disabled.
        if ($this->tlsContext !== null) {
            return $this->connectTls($host, $port);
        }

        // Submit async connection task
        $this->plugin->getServer()->getAsyncPool()->submitTask(
            new AsyncConnectTask($host, $port, function(bool $success, $socket, string $error) use ($host, $port) {
                $this->onConnectComplete($success, $socket, $error, $host, $port);
            })
        );

        return true;
    }
    
    /**
     * Called when async connection completes.
     * 
     * @param bool $success Whether connection was successful
     * @param resource|null $socket Socket resource if successful
     * @param string $error Error message if failed
     * @param string $host Server host
     * @param int $port Server port
     */
    private function onConnectComplete(bool $success, $socket, string $error, string $host, int $port): void {
        if (!$success) {
            $this->plugin->getLogger()->warning("Failed to connect to $host:$port: $error");
            $this->scheduleReconnect($host, $port);
            return;
        }
        
        $this->socket = $socket;
        $this->connected = true;
        $this->reconnectDelay = $this->config->getReconnectDelay();
        $this->reconnectScheduled = false;
        $this->lastKeepAlive = time();
        
        // Set socket to non-blocking for async reads
        socket_set_nonblock($this->socket);
        
        $this->plugin->getLogger()->info("Connected to NovaLink backend at $host:$port");
        $this->plugin->debug("Connection established, sending handshake...");
        
        // Start async read loop
        $this->startReadLoop();
        
        // Start keep-alive loop
        $this->startKeepAliveLoop();
        
        // Send handshake
        $this->sendHandshake();
    }
    
    /**
     * Starts the async read loop using scheduler.
     * 
     * Requirements:
     * - 8.5: THE NovaChat-PMMP SHALL 使用 libasyncsocket 或 pmmpthread 实现异步网络通信
     */
    private function startReadLoop(): void {
        // Cancel existing read task if any
        if ($this->readTaskHandler !== null) {
            $this->readTaskHandler->cancel();
        }
        
        // Schedule repeating read task
        $this->readTaskHandler = $this->plugin->getScheduler()->scheduleRepeatingTask(
            new ClosureTask(function(): void {
                $this->processIncomingData();
            }),
            self::READ_INTERVAL_TICKS
        );
    }
    
    /**
     * Starts the keep-alive loop.
     * 
     * Requirements:
     * - 9.5: THE NovaChat-PMMP SHALL 每 15 秒发送心跳包维持连接
     */
    private function startKeepAliveLoop(): void {
        // Cancel existing keep-alive task if any
        if ($this->keepAliveTaskHandler !== null) {
            $this->keepAliveTaskHandler->cancel();
        }
        
        // Schedule repeating keep-alive task
        $this->keepAliveTaskHandler = $this->plugin->getScheduler()->scheduleRepeatingTask(
            new ClosureTask(function(): void {
                $this->sendKeepAlive();
            }),
            self::KEEPALIVE_INTERVAL_TICKS
        );
    }
    
    /**
     * Processes incoming data from the socket.
     */
    private function processIncomingData(): void {
        if (!$this->connected || $this->socket === null) {
            return;
        }

        // AUTH-002 TLS: the streams-API path uses fread/feof on a stream
        // resource; the ext-socket plaintext path uses socket_read/socket_last_error.
        // The two resource types are not interchangeable, so dispatch on the
        // resource type. (isStreamSocket() returns false for the plaintext
        // ext-socket Socket resource, which is a "Socket" type, not "stream".)
        if ($this->isStreamSocket()) {
            $this->processIncomingDataStream();
            return;
        }

        // Non-blocking read
        $data = @socket_read($this->socket, 4096);

        if ($data === false) {
            $error = socket_last_error($this->socket);
            // EAGAIN/EWOULDBLOCK means no data available (not an error)
            if ($error !== SOCKET_EWOULDBLOCK && $error !== 11 && $error !== 10035) {
                $this->plugin->debug("Socket read error: " . socket_strerror($error));
                $this->handleDisconnect();
            }
            return;
        }

        if ($data === "") {
            // Connection closed by peer
            $this->plugin->debug("Connection closed by server");
            $this->handleDisconnect();
            return;
        }

        // Append to buffer and process packets
        $this->readBuffer .= $data;
        $this->processPackets();
    }

    /**
     * Processes incoming data from a TLS stream resource (AUTH-002).
     *
     * On a non-blocking SSL stream PHP's fread is quirky: it may return ""
     * (no bytes decrypted yet) OR false ("SSL: The operation did not complete"
     * notice) while the connection is still healthy. feof is the reliable
     * signal: it is false on a healthy read that returned no bytes, and true
     * once the peer has half-closed the stream. We therefore treat BOTH the
     * empty-string and false returns as "check feof before disconnecting" so
     * the TLS read loop does not tear down the connection on a transient
     * non-blocking empty read.
     */
    private function processIncomingDataStream(): void {
        $data = @fread($this->socket, 4096);

        if ($data === "" || $data === false) {
            // Distinguish "no data right now" from "peer closed". On a
            // non-blocking SSL stream a healthy read with no pending bytes
            // can return either "" or false (with an SSL operation-in-progress
            // notice); only feof signals a genuine disconnect.
            if (@feof($this->socket)) {
                $this->plugin->debug("Connection closed by server (TLS)");
                $this->handleDisconnect();
            }
            return;
        }

        // Append to buffer and process packets
        $this->readBuffer .= $data;
        $this->processPackets();
    }
    
    /**
     * Processes complete packets from the read buffer.
     */
    private function processPackets(): void {
        while (strlen($this->readBuffer) > 0) {
            // Try to read packet length (VarInt)
            $offset = 0;
            $length = $this->tryReadVarInt($this->readBuffer, $offset);
            
            if ($length === null) {
                // Not enough data for length
                break;
            }
            
            if (strlen($this->readBuffer) < $offset + $length) {
                // Not enough data for packet
                break;
            }
            
            // Extract packet data
            $packetData = substr($this->readBuffer, $offset, $length);
            $this->readBuffer = substr($this->readBuffer, $offset + $length);
            
            // Process the packet
            $this->handlePacket($packetData);
        }
    }
    
    /**
     * Tries to read a VarInt from buffer without modifying offset on failure.
     * 
     * @param string $buffer The buffer
     * @param int &$offset Current offset (updated on success)
     * @return int|null The value or null if not enough data
     */
    private function tryReadVarInt(string $buffer, int &$offset): ?int {
        $value = 0;
        $shift = 0;
        $startOffset = $offset;
        
        while (true) {
            if ($offset >= strlen($buffer)) {
                $offset = $startOffset;
                return null;
            }
            
            $byte = ord($buffer[$offset++]);
            $value |= ($byte & 0x7F) << $shift;
            
            if (($byte & 0x80) === 0) {
                break;
            }
            
            $shift += 7;
            if ($shift >= 32) {
                $offset = $startOffset;
                return null;
            }
        }
        
        return $value;
    }
    
    /**
     * Handles a received packet.
     * 
     * @param string $data Raw packet data
     */
    private function handlePacket(string $data): void {
        $packet = Packet::fromBytes($data);
        if ($packet === null) {
            $this->plugin->debug("Received unknown packet");
            return;
        }
        
        $this->plugin->debug("Received packet: " . get_class($packet));
        
        // Handle specific packet types
        if ($packet instanceof HandshakeResponsePacket) {
            $this->handleHandshakeResponse($packet);
        } elseif ($packet instanceof HandshakeChallengePacket) {
            $this->handleHandshakeChallenge($packet);
        } elseif ($packet instanceof ChatMessagePacket) {
            $this->handleChatMessage($packet);
        } elseif ($packet instanceof \NovaChat\Protocol\ChannelActionResponsePacket) {
            $this->handleChannelActionResponse($packet);
        } elseif ($packet instanceof \NovaChat\Protocol\ConfigSyncPacket) {
            $this->handleConfigSync($packet);
        } elseif ($packet instanceof \NovaChat\Protocol\MentionPacket) {
            $this->handleMention($packet);
        } elseif ($packet instanceof \NovaChat\Protocol\ItemDisplayPacket) {
            $this->handleItemDisplay($packet);
        } elseif ($packet instanceof \NovaChat\Protocol\AdminActionResponsePacket) {
            $this->handleAdminActionResponse($packet);
        } elseif ($packet instanceof \NovaChat\Protocol\TitleMessagePacket) {
            $this->handleTitleMessage($packet);
        } elseif ($packet instanceof \NovaChat\Protocol\ChannelUpdatePacket) {
            $this->handleChannelUpdate($packet);
        } elseif ($packet instanceof \NovaChat\Protocol\PrivateMessagePacket) {
            $this->handlePrivateMessage($packet);
        } elseif ($packet instanceof KeepAlivePacket) {
            // Server responded to keep-alive, connection is healthy
            $this->plugin->debug("Keep-alive response received");
        }
    }
    
    /**
     * Handles handshake response packet.
     * Requirements: 27.3 - Outputs clear error message when version is incompatible
     * 
     * @param HandshakeResponsePacket $packet The packet
     */
    private function handleHandshakeResponse(HandshakeResponsePacket $packet): void {
        if ($packet->success) {
            $this->authenticated = true;
            $this->plugin->getLogger()->info("Successfully authenticated with NovaLink backend");
            if ($packet->message !== "") {
                $this->plugin->debug("Handshake message: " . $packet->message);
            }
        } else {
            $this->plugin->getLogger()->error("Authentication failed: " . $packet->errorCode .
                ($packet->message !== "" ? " - " . $packet->message : ""));
            
            // Handle specific error codes with clear messages
            switch ($packet->errorCode) {
                case "NC-401":
                    $this->plugin->getLogger()->error("Please check your username and password in config.yml");
                    break;
                case "NC-420":
                    $this->plugin->getLogger()->error("=================================================");
                    $this->plugin->getLogger()->error("PROTOCOL VERSION MISMATCH!");
                    $this->plugin->getLogger()->error("Your NovaChat plugin version is incompatible with the NovaLink backend.");
                    $this->plugin->getLogger()->error("Please update your plugin to match the backend protocol version.");
                    $this->plugin->getLogger()->error("Current plugin protocol version: " . HandshakePacket::PROTOCOL_VERSION);
                    $this->plugin->getLogger()->error("=================================================");
                    break;
            }
            
            $this->disconnect();
        }
    }
    
    /**
     * Handles incoming chat message packet.
     * 
     * @param ChatMessagePacket $packet The packet
     */
    private function handleChatMessage(ChatMessagePacket $packet): void {
        $chatHandler = $this->plugin->getChatHandler();
        if ($chatHandler !== null) {
            $chatHandler->handleIncomingMessage($packet);
        }
    }
    
    /**
     * Handles incoming title message packet.
     *
     * @param \NovaChat\Protocol\TitleMessagePacket $packet The packet
     */
    private function handleTitleMessage(\NovaChat\Protocol\TitleMessagePacket $packet): void {
        $chatHandler = $this->plugin->getChatHandler();
        if ($chatHandler !== null) {
            $chatHandler->handleTitleMessage($packet);
        }
    }
    
    /**
     * Handles incoming channel update packet.
     *
     * @param \NovaChat\Protocol\ChannelUpdatePacket $packet The packet
     */
    private function handleChannelUpdate(\NovaChat\Protocol\ChannelUpdatePacket $packet): void {
        $chatHandler = $this->plugin->getChatHandler();
        if ($chatHandler !== null) {
            $chatHandler->handleChannelUpdate($packet);
        }
    }

    /**
     * Handles incoming private message packet.
     *
     * The backend delivers a completed PrivateMessagePacket to BOTH the
     * sender's client (echo) and the target's client. We hand the packet to
     * the ChatHandler, which renders the "sent" line to the local player
     * matching senderId and the "received" line to the local player matching
     * targetId (when distinct). Private chat is per-player directed, so this
     * never broadcasts to a channel.
     *
     * @param \NovaChat\Protocol\PrivateMessagePacket $packet The packet
     */
    private function handlePrivateMessage(\NovaChat\Protocol\PrivateMessagePacket $packet): void {
        $chatHandler = $this->plugin->getChatHandler();
        if ($chatHandler !== null) {
            $chatHandler->handlePrivateMessage($packet);
        }
    }

    /**
     * Handles incoming channel action response — routes kick/mute target
     * notifications and tracks known channels.
     *
     * @param \NovaChat\Protocol\ChannelActionResponsePacket $packet The packet
     */
    private function handleChannelActionResponse(\NovaChat\Protocol\ChannelActionResponsePacket $packet): void {
        $chatHandler = $this->plugin->getChatHandler();
        if ($chatHandler !== null) {
            $chatHandler->handleChannelActionResponse($packet);
        }
    }

    /**
     * Handles incoming config sync packet.
     *
     * @param \NovaChat\Protocol\ConfigSyncPacket $packet The packet
     */
    private function handleConfigSync(\NovaChat\Protocol\ConfigSyncPacket $packet): void {
        $chatHandler = $this->plugin->getChatHandler();
        if ($chatHandler !== null) {
            $chatHandler->handleConfigSync($packet);
        }
    }

    /**
     * Handles incoming mention packet — highlight + title to the mentioned player.
     *
     * @param \NovaChat\Protocol\MentionPacket $packet The packet
     */
    private function handleMention(\NovaChat\Protocol\MentionPacket $packet): void {
        $chatHandler = $this->plugin->getChatHandler();
        if ($chatHandler !== null) {
            $chatHandler->handleMention($packet);
        }
    }

    /**
     * Handles incoming item display packet — [item]/[i] tag display.
     *
     * @param \NovaChat\Protocol\ItemDisplayPacket $packet The packet
     */
    private function handleItemDisplay(\NovaChat\Protocol\ItemDisplayPacket $packet): void {
        $chatHandler = $this->plugin->getChatHandler();
        if ($chatHandler !== null) {
            $chatHandler->handleItemDisplay($packet);
        }
    }

    /**
     * Handles incoming admin action response packet.
     *
     * Pops the pending requestId correlation and routes the result back to the
     * originating player (or console). Mirrors the Java NetworkClient
     * handleAdminActionResponse: the client never tracks super-admin session
     * state locally, so the "run /nc auth" guidance can only be surfaced here,
     * on the async NC-403 + STATUS response path (the backend's
     * AdminActionHandler.handleStatus gate is the sole arbiter).
     *
     * Routing:
     *  - no pending requestId -> debug log + return (stale/duplicate response)
     *  - console sentinel UUID -> log to server console
     *  - player online -> success: chat.action.success (or backend message);
     *    failure: on STATUS + NC-403 show chat.error.super_admin_required +
     *    chat.error.super_admin_required_suggestion; otherwise the localized
     *    error message for the code
     *
     * @param \NovaChat\Protocol\AdminActionResponsePacket $packet The response
     */
    private function handleAdminActionResponse(\NovaChat\Protocol\AdminActionResponsePacket $packet): void {
        $requestId = $packet->getRequestId();
        $playerUuid = $this->pendingAdminRequests[$requestId] ?? null;
        if ($playerUuid !== null) {
            unset($this->pendingAdminRequests[$requestId]);
        }

        if ($playerUuid === null) {
            $this->plugin->debug("Received AdminActionResponse with no pending request: " . $requestId);
            return;
        }

        $consoleSentinel = "00000000-0000-0000-0000-000000000000";

        // Console/RCON-originated admin action: log to the server console.
        if ($playerUuid === $consoleSentinel) {
            if ($packet->success) {
                $msg = $packet->message !== "" ? $packet->message : "Action succeeded";
                $this->plugin->getLogger()->info("[NovaChat console] " . $msg);
            } else {
                if ($this->isSuperAdminRequired($packet)) {
                    $this->plugin->getLogger()->warning("[NovaChat console] Super admin session required. Run /nc auth <password> first.");
                    $this->plugin->getLogger()->warning("[NovaChat console] Authenticate with /nc auth <password>, then retry.");
                    return;
                }
                $text = $packet->errorCode !== ""
                    ? $packet->errorCode . " | " . ($packet->message !== "" ? $packet->message : "Action failed")
                    : ($packet->message !== "" ? $packet->message : "Action failed");
                $this->plugin->getLogger()->warning("[NovaChat console] " . $text);
            }
            return;
        }

        // Locate the originating player.
        $player = null;
        foreach ($this->plugin->getServer()->getOnlinePlayers() as $online) {
            if ($online->getUniqueId()->toString() === $playerUuid) {
                $player = $online;
                break;
            }
        }
        if ($player === null) {
            $this->plugin->debug("AdminActionResponse target player not online: " . $playerUuid);
            return;
        }

        $chatHandler = $this->plugin->getChatHandler();
        $locale = $chatHandler !== null ? $chatHandler->getPlayerLocale($playerUuid) : "zh_CN";
        $i18n = new \NovaChat\I18n\I18n();
        $prefix = $this->plugin->getConfigManager()->getPrefix();

        if ($packet->success) {
            $message = $packet->message !== ""
                ? $packet->message
                : $i18n->get("chat.action.success", $locale);
            $player->sendMessage($prefix . $message);
            return;
        }

        // Failure: surface the super-admin guidance on the NC-403 + STATUS path
        // (ANNOUNCE/AUTH gate), instead of the generic FORBIDDEN text.
        if ($this->isSuperAdminRequired($packet)) {
            $player->sendMessage($prefix . $i18n->get("chat.error.super_admin_required", $locale));
            $player->sendMessage($prefix . $i18n->get("chat.error.super_admin_required_suggestion", $locale));
            return;
        }

        if ($packet->errorCode !== "") {
            $player->sendMessage($prefix . $i18n->errorMessage($packet->errorCode, $locale));
        } else {
            $message = $packet->message !== "" ? $packet->message : $i18n->get("chat.action.failed", $locale);
            $player->sendMessage($prefix . $message);
        }
    }

    /**
     * Returns true when the backend rejected a STATUS (ANNOUNCE/AUTH) request
     * with NC-403, which is how the backend signals that the sender lacks an
     * active super-admin session (see AdminActionHandler.handleStatus). Used to
     * surface the "run /nc auth" guidance in place of the generic FORBIDDEN
     * error text. Mirrors the Java isSuperAdminRequired helper.
     *
     * @param \NovaChat\Protocol\AdminActionResponsePacket $packet the response
     * @return bool true if this is the super-admin-session-required NC-403 path
     */
    private function isSuperAdminRequired(\NovaChat\Protocol\AdminActionResponsePacket $packet): bool {
        if ($packet->success) {
            return false;
        }
        if ($packet->action !== \NovaChat\Protocol\AdminActionPacket::ACTION_STATUS) {
            return false;
        }
        return $packet->errorCode === "NC-403";
    }
    
    /**
     * Disconnects from the backend server.
     */
    public function disconnect(): void {
        // Cancel scheduled tasks
        if ($this->readTaskHandler !== null) {
            $this->readTaskHandler->cancel();
            $this->readTaskHandler = null;
        }
        
        if ($this->keepAliveTaskHandler !== null) {
            $this->keepAliveTaskHandler->cancel();
            $this->keepAliveTaskHandler = null;
        }
        
        // Close socket. AUTH-002 TLS: a stream resource is closed with fclose;
        // an ext-socket Socket is closed with socket_close. The two resource
        // types are not interchangeable.
        if ($this->socket !== null) {
            if ($this->isStreamSocket()) {
                // Suppress the noisy SSL shutdown warnings that a half-closed
                // peer sometimes emits; the read loop already detected the
                // disconnect and the close is best-effort cleanup.
                @fclose($this->socket);
            } else {
                @socket_close($this->socket);
            }
            $this->socket = null;
        }
        
        $this->connected = false;
        $this->authenticated = false;
        $this->readBuffer = "";
    }
    
    /**
     * Handles disconnection and schedules reconnect.
     */
    private function handleDisconnect(): void {
        $host = $this->config->getBackendHost();
        $port = $this->config->getBackendPort();
        
        $this->disconnect();
        $this->scheduleReconnect($host, $port);
    }
    
    /**
     * Sends the handshake init packet to start the AUTH-002 challenge-response
     * flow. The client generates a fresh 16-byte cryptographically-secure random
     * nonce and sends HandshakeInitPacket (0x15); the server replies with
     * HandshakeChallengePacket (0x16), handled by handleHandshakeChallenge,
     * which sends HandshakeAuthenticatePacket (0x17). The final
     * HandshakeResponsePacket (0x02) is handled by handleHandshakeResponse.
     */
    private function sendHandshake(): void {
        // 16 cryptographically-secure random bytes → lowercase hex (32 chars).
        // random_bytes() uses the OS CSPRNG on PHP; never a weak PRNG.
        $this->pendingClientNonce = bin2hex(random_bytes(16));

        $packet = new HandshakeInitPacket();
        $packet->protocolVersion = HandshakePacket::PROTOCOL_VERSION;
        $packet->clientId = $this->config->getBackendUsername();
        $packet->platform = HandshakePacket::PLATFORM_PMMP;
        $packet->serverVersion = $this->config->getServerVersion();
        $packet->clientNonce = $this->pendingClientNonce;

        $this->sendPacket($packet);
    }

    /**
     * Handles the AUTH-002 HandshakeChallengePacket (0x16) from the server.
     *
     * Computes the HMAC-SHA-256 over (serverNonce . clientNonce), keyed by
     * sha256hex(password), and sends HandshakeAuthenticatePacket (0x17)
     * echoing the clientId and clientNonce. The server validates the echoed
     * nonce against the init packet and recomputes the HMAC in constant time.
     *
     * @param HandshakeChallengePacket $packet The server challenge
     */
    private function handleHandshakeChallenge(HandshakeChallengePacket $packet): void {
        $serverNonce = $packet->serverNonce;
        $clientNonce = $this->pendingClientNonce;

        // Defense in depth: a challenge without a pending nonce means the init
        // packet was never sent (or a duplicate challenge arrived). Discard it.
        if ($clientNonce === "") {
            $this->plugin->getLogger()->warning("Received handshake challenge without a pending init nonce; ignoring");
            return;
        }

        // HMAC key = sha256hex(password) (the stored credential hash, 64 ASCII bytes);
        // message = serverNonceHex . clientNonceHex (hex-string concatenation).
        // hash_hmac key accepts a raw byte string; passing the hex hash directly
        // keeps it byte-for-byte identical to the JVM/Python/C++ forks which
        // treat the sha256hex string as the key bytes.
        $password = $this->config->getBackendPassword();
        $key = hash("sha256", $password);
        $message = $serverNonce . $clientNonce;
        $hmac = hash_hmac("sha256", $message, $key);

        $authenticate = new HandshakeAuthenticatePacket();
        $authenticate->clientId = $this->config->getBackendUsername();
        $authenticate->clientNonce = $clientNonce;
        $authenticate->hmac = $hmac;

        // Clear the pending nonce so a replayed/duplicate challenge cannot
        // re-trigger authentication with a stale nonce.
        $this->pendingClientNonce = "";

        $this->sendPacket($authenticate);
    }
    
    /**
     * Sends a packet to the server asynchronously.
     * 
     * @param Packet $packet The packet to send
     * @return bool True if the packet was queued successfully
     */
    public function sendPacket(Packet $packet): bool {
        if (!$this->connected || $this->socket === null) {
            $this->plugin->debug("Cannot send packet: not connected");
            return false;
        }

        $data = $packet->serialize();

        // AUTH-002 TLS: the streams-API path cannot use AsyncSendTask (which
        // calls socket_write/socket_set_block/socket_set_nonblock on the
        // resource — these raise TypeError on a stream resource). Send
        // synchronously via fwrite on the non-blocking stream, matching the
        // blocking posture of the plaintext AsyncSendTask for chat-sized
        // frames. The plaintext path keeps AsyncSendTask verbatim.
        if ($this->isStreamSocket()) {
            return $this->sendStreamSync($data);
        }

        // Use async task for sending to avoid blocking
        $this->plugin->getServer()->getAsyncPool()->submitTask(
            new AsyncSendTask($this->socket, $data, function(bool $success, string $error) {
                if (!$success) {
                    $this->plugin->debug("Failed to send packet: $error");
                    $this->handleDisconnect();
                }
            })
        );

        return true;
    }
    
    /**
     * Sends a packet synchronously (for critical packets like handshake).
     * 
     * @param Packet $packet The packet to send
     * @return bool True if the packet was sent successfully
     */
    public function sendPacketSync(Packet $packet): bool {
        if (!$this->connected || $this->socket === null) {
            return false;
        }

        $data = $packet->serialize();

        // AUTH-002 TLS: dispatch to the streams-API blocking send on a stream
        // resource (socket_set_block/socket_write would raise TypeError).
        if ($this->isStreamSocket()) {
            return $this->sendStreamSync($data);
        }

        $length = strlen($data);
        $sent = 0;

        // Temporarily set to blocking for sync send
        socket_set_block($this->socket);

        while ($sent < $length) {
            $result = @socket_write($this->socket, substr($data, $sent), $length - $sent);
            if ($result === false) {
                $this->plugin->debug("Failed to send packet: " . socket_strerror(socket_last_error($this->socket)));
                socket_set_nonblock($this->socket);
                return false;
            }
            $sent += $result;
        }

        socket_set_nonblock($this->socket);
        return true;
    }
    
    /**
     * Sends a chat message to the server.
     * 
     * @param string $playerId Player UUID
     * @param string $playerName Player name
     * @param string $channelId Channel ID
     * @param string $message Message content
     */
    public function sendChatMessage(string $playerId, string $playerName, string $channelId, string $message): void {
        if (!$this->authenticated) {
            $this->plugin->debug("Cannot send chat message: not authenticated");
            return;
        }
        
        $packet = new ChatMessagePacket();
        $packet->senderId = $playerId;
        $packet->senderName = $playerName;
        $packet->clientId = $this->config->getBackendUsername();
        $packet->channelId = $channelId;
        $packet->content = $message;
        
        $this->sendPacket($packet);
    }
    
    /**
     * Sends a channel action to the server.
     * 
     * @param int $action Action type
     * @param string $channelId Channel ID
     * @param string $password Optional password
     * @param array<string,string> $extra Optional extra key-value data
     */
    public function sendChannelAction(int $action, string $channelId, string $password = "", array $extra = []): void {
        if (!$this->authenticated) {
            return;
        }
        
        $packet = new ChannelActionPacket();
        $packet->action = $action;
        $packet->channelId = $channelId;
        $packet->password = $password;
        $packet->extra = $extra;

        $this->sendPacket($packet);
    }

    /**
     * Sends an AdminAction packet and registers the requestId -> player UUID
     * correlation so the asynchronous AdminActionResponse can be routed back.
     *
     * Mirrors the Java NetworkClient.sendPacket AdminActionPacket branch: the
     * correlation MUST be recorded BEFORE the packet goes on the wire,
     * otherwise a fast backend response could arrive before the map is
     * populated and be dropped as "no pending request". The client never
     * tracks super-admin session state here — only request/response routing.
     *
     * @param \NovaChat\Protocol\AdminActionPacket $packet the admin action packet
     * @param string $playerUuid the originating player UUID (or console sentinel)
     */
    public function sendAdminAction(\NovaChat\Protocol\AdminActionPacket $packet, string $playerUuid): void {
        if (!$this->authenticated) {
            $this->plugin->debug("Cannot send admin action: not authenticated");
            return;
        }

        $requestId = $packet->getRequestId();
        if ($requestId !== "" && $playerUuid !== "") {
            $this->pendingAdminRequests[$requestId] = $playerUuid;
        }

        $this->sendPacket($packet);
    }
    
    /**
     * Sends a keep-alive packet.
     * 
     * Requirements:
     * - 9.5: THE NovaChat-PMMP SHALL 每 15 秒发送心跳包维持连接
     */
    public function sendKeepAlive(): void {
        if (!$this->connected) {
            return;
        }
        
        $this->plugin->debug("Sending keep-alive packet");
        $this->sendPacket(KeepAlivePacket::create());
        $this->lastKeepAlive = time();
    }
    
    /**
     * Schedules a reconnection attempt with exponential backoff.
     * 
     * Requirements:
     * - 9.4: WHEN 连接断开 THEN NovaChat-PMMP SHALL 实现指数退避重连机制
     * 
     * @param string $host Server host
     * @param int $port Server port
     */
    private function scheduleReconnect(string $host, int $port): void {
        if ($this->reconnectScheduled) {
            return;
        }
        
        $this->reconnectScheduled = true;
        $delay = $this->reconnectDelay;
        
        $this->plugin->getLogger()->info("Reconnecting in {$delay} seconds...");
        
        // Schedule reconnect task
        $this->plugin->getScheduler()->scheduleDelayedTask(
            new ClosureTask(function() use ($host, $port): void {
                $this->reconnectScheduled = false;
                $this->connect($host, $port);
            }),
            $delay * 20 // Convert seconds to ticks
        );
        
        // Exponential backoff: double the delay for next time
        $this->reconnectDelay = min($this->reconnectDelay * 2, self::MAX_RECONNECT_DELAY);
    }
    
    /**
     * Resets the reconnect delay (called after successful connection).
     */
    public function resetReconnectDelay(): void {
        $this->reconnectDelay = $this->config->getReconnectDelay();
    }
    
    /**
     * Gets the current reconnect delay.
     * 
     * @return int Current delay in seconds
     */
    public function getReconnectDelay(): int {
        return $this->reconnectDelay;
    }
    
    /**
     * Checks if the client is connected.
     * 
     * @return bool True if connected
     */
    public function isConnected(): bool {
        return $this->connected;
    }
    
    /**
     * Checks if the client is authenticated.
     * 
     * @return bool True if authenticated
     */
    public function isAuthenticated(): bool {
        return $this->authenticated;
    }
    
    /**
     * Sets the authentication status.
     * 
     * @param bool $authenticated Authentication status
     */
    public function setAuthenticated(bool $authenticated): void {
        $this->authenticated = $authenticated;
    }
    
    /**
     * Gets the socket resource.
     * 
     * @return resource|null The socket resource
     */
    public function getSocket() {
        return $this->socket;
    }
    
    /**
     * Gets the read buffer.
     * 
     * @return string The read buffer
     */
    public function getReadBuffer(): string {
        return $this->readBuffer;
    }
    
    /**
     * Sets the read buffer.
     * 
     * @param string $buffer The new buffer
     */
    public function setReadBuffer(string $buffer): void {
        $this->readBuffer = $buffer;
    }
    
    /**
     * Appends data to the read buffer.
     * 
     * @param string $data Data to append
     */
    public function appendToBuffer(string $data): void {
        $this->readBuffer .= $data;
    }
}
