<?php

declare(strict_types=1);

namespace NovaChat\Config;

use NovaChat\NovaChatPlugin;
use pocketmine\utils\Config;

/**
 * Configuration manager for NovaChat PMMP plugin.
 * 
 * Handles loading and accessing configuration values from config.yml.
 */
class ConfigManager {
    
    /** @var NovaChatPlugin Plugin instance */
    private NovaChatPlugin $plugin;
    
    /** @var Config Configuration instance */
    private Config $config;
    
    // Backend settings
    private string $backendHost;
    private int $backendPort;
    private string $backendUsername;
    private string $backendPassword;
    private int $reconnectDelay;
    
    // Chat settings
    private bool $replaceVanilla;
    private string $defaultChannel;
    
    // Format settings
    private string $prefix;
    private string $errorFormat;
    private string $successFormat;
    private string $defaultFormat;
    
    /** @var array<string, string> Channel-specific formats */
    private array $channelFormats;
    
    // Debug mode
    private bool $debug;
    
    /**
     * Creates a new configuration manager.
     * 
     * @param NovaChatPlugin $plugin Plugin instance
     */
    public function __construct(NovaChatPlugin $plugin) {
        $this->plugin = $plugin;
        $this->config = $plugin->getConfig();
        $this->loadConfig();
    }
    
    /**
     * Loads configuration values from the config file.
     */
    private function loadConfig(): void {
        // Backend settings
        $this->backendHost = $this->config->getNested("backend.host", "127.0.0.1");
        $this->backendPort = (int) $this->config->getNested("backend.port", 8888);
        $this->backendUsername = $this->config->getNested("backend.username", "PMMP_Server");
        $this->backendPassword = $this->config->getNested("backend.password", "");
        $this->reconnectDelay = (int) $this->config->getNested("backend.reconnect-delay", 5);
        
        // Chat settings
        $this->replaceVanilla = (bool) $this->config->getNested("chat.replace_vanilla", false);
        $this->defaultChannel = $this->config->getNested("chat.default_channel", "local");
        
        // Format settings
        $this->prefix = $this->config->getNested("format.prefix", "§8[§bNovaChat§8]§r ");
        $this->errorFormat = $this->config->getNested("format.error", "§c错误: {message}");
        $this->successFormat = $this->config->getNested("format.success", "§a成功: {message}");
        $this->defaultFormat = $this->config->getNested("format.default", "§7[{channel_name}] {player}§f: {message}");
        
        // Channel formats
        $this->channelFormats = [];
        $channels = $this->config->getNested("format.channels", []);
        if (is_array($channels)) {
            foreach ($channels as $channelId => $format) {
                $this->channelFormats[(string) $channelId] = (string) $format;
            }
        }
        
        // Debug mode
        $this->debug = (bool) $this->config->get("debug", false);
    }
    
    /**
     * Gets the backend host.
     * 
     * @return string The backend host
     */
    public function getBackendHost(): string {
        return $this->backendHost;
    }
    
    /**
     * Gets the backend port.
     * 
     * @return int The backend port
     */
    public function getBackendPort(): int {
        return $this->backendPort;
    }
    
    /**
     * Gets the backend username.
     * 
     * @return string The backend username
     */
    public function getBackendUsername(): string {
        return $this->backendUsername;
    }
    
    /**
     * Gets the backend password.
     * 
     * @return string The backend password
     */
    public function getBackendPassword(): string {
        return $this->backendPassword;
    }
    
    /**
     * Gets the reconnect delay in seconds.
     * 
     * @return int The reconnect delay
     */
    public function getReconnectDelay(): int {
        return $this->reconnectDelay;
    }
    
    /**
     * Checks if vanilla chat should be replaced.
     * 
     * @return bool True if vanilla chat should be replaced
     */
    public function shouldReplaceVanilla(): bool {
        return $this->replaceVanilla;
    }
    
    /**
     * Gets the default channel.
     * 
     * @return string The default channel ID
     */
    public function getDefaultChannel(): string {
        return $this->defaultChannel;
    }
    
    /**
     * Gets the message prefix.
     * 
     * @return string The prefix
     */
    public function getPrefix(): string {
        return $this->prefix;
    }
    
    /**
     * Gets the error message format.
     * 
     * @return string The error format
     */
    public function getErrorFormat(): string {
        return $this->errorFormat;
    }
    
    /**
     * Gets the success message format.
     * 
     * @return string The success format
     */
    public function getSuccessFormat(): string {
        return $this->successFormat;
    }
    
    /**
     * Gets the default message format.
     * 
     * @return string The default format
     */
    public function getDefaultFormat(): string {
        return $this->defaultFormat;
    }
    
    /**
     * Gets the format for a specific channel.
     * 
     * @param string $channelId The channel ID
     * @return string The channel format, or default format if not configured
     */
    public function getChannelFormat(string $channelId): string {
        return $this->channelFormats[$channelId] ?? $this->defaultFormat;
    }
    
    /**
     * Gets all channel formats.
     * 
     * @return array<string, string> The channel formats
     */
    public function getChannelFormats(): array {
        return $this->channelFormats;
    }
    
    /**
     * Checks if debug mode is enabled.
     * 
     * @return bool True if debug mode is enabled
     */
    public function isDebug(): bool {
        return $this->debug;
    }
    
    /**
     * Formats an error message.
     * 
     * @param string $message The error message
     * @return string The formatted error message
     */
    public function formatError(string $message): string {
        return $this->prefix . str_replace("{message}", $message, $this->errorFormat);
    }
    
    /**
     * Formats a success message.
     * 
     * @param string $message The success message
     * @return string The formatted success message
     */
    public function formatSuccess(string $message): string {
        return $this->prefix . str_replace("{message}", $message, $this->successFormat);
    }
    
    /**
     * Formats a chat message.
     * 
     * @param string $channelId The channel ID
     * @param string $channelName The channel display name
     * @param string $playerName The player name
     * @param string $message The message content
     * @return string The formatted message
     */
    public function formatChatMessage(string $channelId, string $channelName, string $playerName, string $message): string {
        $format = $this->getChannelFormat($channelId);
        
        return str_replace(
            ["{channel}", "{channel_name}", "{player}", "{message}"],
            [$channelId, $channelName, $playerName, $message],
            $format
        );
    }
}
