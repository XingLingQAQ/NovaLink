<?php

declare(strict_types=1);

namespace NovaChat\Network;

use NovaChat\Config\ConfigManager;
use NovaChat\NovaChatPlugin;
use NovaChat\Protocol\ChannelActionPacket;
use NovaChat\Protocol\ChatMessagePacket;
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
    private int $reconnectDelay = 1;
    
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
     * Creates a new network client.
     * 
     * @param NovaChatPlugin $plugin Plugin instance
     * @param ConfigManager $config Configuration manager
     */
    public function __construct(NovaChatPlugin $plugin, ConfigManager $config) {
        $this->plugin = $plugin;
        $this->config = $config;
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
        $this->reconnectDelay = 1;
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
        } elseif ($packet instanceof \NovaChat\Protocol\AnnouncementPacket) {
            $this->handleAnnouncement($packet);
        } elseif ($packet instanceof \NovaChat\Protocol\TitleMessagePacket) {
            $this->handleTitleMessage($packet);
        } elseif ($packet instanceof \NovaChat\Protocol\ChannelUpdatePacket) {
            $this->handleChannelUpdate($packet);
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
     * Handles incoming announcement packet.
     * 
     * @param \NovaChat\Protocol\AnnouncementPacket $packet The packet
     */
    private function handleAnnouncement(\NovaChat\Protocol\AnnouncementPacket $packet): void {
        $chatHandler = $this->plugin->getChatHandler();
        if ($chatHandler !== null) {
            $chatHandler->handleAnnouncement($packet);
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
     * @param \NovaChat\Protocol\AdminActionResponsePacket $packet The packet
     */
    private function handleAdminActionResponse(\NovaChat\Protocol\AdminActionResponsePacket $packet): void {
        if ($packet->success) {
            $this->plugin->debug("Admin action succeeded: " . $packet->message);
        } else {
            $this->plugin->getLogger()->warning("Admin action failed: " . $packet->errorCode .
                ($packet->message !== "" ? " - " . $packet->message : ""));
        }
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
        
        // Close socket
        if ($this->socket !== null) {
            @socket_close($this->socket);
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
     * Sends the handshake packet to authenticate.
     */
    private function sendHandshake(): void {
        $packet = new HandshakePacket();
        $packet->clientId = $this->config->getBackendUsername();
        $packet->passwordHash = hash("sha256", $this->config->getBackendPassword());
        $packet->platform = HandshakePacket::PLATFORM_PMMP;
        $packet->serverVersion = $this->config->getServerVersion();

        $this->sendPacket($packet);
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
        $this->reconnectDelay = 1;
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
