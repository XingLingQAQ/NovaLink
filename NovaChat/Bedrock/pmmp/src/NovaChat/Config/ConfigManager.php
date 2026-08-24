<?php

declare(strict_types=1);

namespace NovaChat\Config;

use UnexpectedValueException;

/**
 * Configuration manager for NovaChat PMMP plugin.
 * 
 * Handles loading and accessing configuration values from config.yml.
 */
class ConfigManager {
    /** @var array<mixed> Parsed configuration */
    private array $config;
    
    // Backend settings
    private string $backendHost;
    private int $backendPort;
    private string $backendUsername;
    private string $backendPassword;
    private string $serverVersion;
    private int $reconnectDelay;

    // AUTH-002 TLS: transport encryption for the backend connection.
    // enable defaults to false (plaintext compatibility). When enabled the
    // backend certificate is always verified — there is no flag to disable it.
    private bool $tlsEnable;
    private string $tlsCaCertPath;
    private string $tlsClientCertPath;
    private string $tlsClientKeyPath;
    
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
     * @param array<mixed> $config Parsed configuration
     */
    public function __construct(array $config) {
        $this->config = $config;
        $this->loadConfig();
    }

    /**
     * Validates a parsed configuration without changing runtime state.
     *
     * @param array<mixed> $config Parsed configuration
     */
    public static function validate(array $config): void {
        new self($config);
    }
    
    /**
     * Loads configuration values from the config file.
     */
    private function loadConfig(): void {
        $configVersion = $this->requireInt("config-version");
        if ($configVersion <= 0) {
            throw new UnexpectedValueException("Configuration value config-version must be greater than 0");
        }

        // Backend settings
        $this->backendHost = $this->requireNonBlankString("backend.host");
        $this->backendPort = $this->requirePort("backend.port");
        $this->backendUsername = $this->requireNonBlankString("backend.username");
        $this->backendPassword = $this->requireString("backend.password");
        $this->serverVersion = $this->requireNonBlankString("backend.server-version");
        $this->reconnectDelay = $this->requirePositiveInt("backend.reconnect-delay");

        // AUTH-002 TLS: backend transport encryption. Optional mapping; absent
        // keeps the plaintext default (zero regression for existing configs).
        // When enable=true the backend certificate is ALWAYS verified — there
        // is no option to disable verification. The optional client_cert_path
        // / client_key_path are for mutual-TLS (both must be set together).
        $tlsEnable = false;
        $tlsCaCertPath = "";
        $tlsClientCertPath = "";
        $tlsClientKeyPath = "";
        $backendValue = $this->config["backend"] ?? null;
        if (is_array($backendValue) && array_key_exists("tls", $backendValue)) {
            $tls = $backendValue["tls"];
            if (!is_array($tls)) {
                throw new UnexpectedValueException("Configuration value backend.tls must be a mapping");
            }
            $tlsEnable = $this->requireBool("backend.tls.enable");
            $tlsCaCertPath = $this->requireString("backend.tls.ca_cert_path");
            $tlsClientCertPath = $this->requireString("backend.tls.client_cert_path");
            $tlsClientKeyPath = $this->requireString("backend.tls.client_key_path");
            // mTLS pair must be both-set or both-empty (a cert without a key,
            // or vice versa, is a misconfiguration).
            $hasCert = trim($tlsClientCertPath) !== "";
            $hasKey = trim($tlsClientKeyPath) !== "";
            if ($hasCert !== $hasKey) {
                throw new UnexpectedValueException(
                    "Configuration values backend.tls.client_cert_path and backend.tls.client_key_path "
                    . "must both be set or both be empty"
                );
            }
        }
        $this->tlsEnable = $tlsEnable;
        $this->tlsCaCertPath = $tlsCaCertPath;
        $this->tlsClientCertPath = $tlsClientCertPath;
        $this->tlsClientKeyPath = $tlsClientKeyPath;
        
        // Chat settings
        $this->replaceVanilla = $this->requireBool("chat.replace_vanilla");
        $this->defaultChannel = $this->requireNonBlankString("chat.default_channel");
        
        // Format settings
        $format = $this->requireValue("format");
        if (!is_array($format)) {
            throw new UnexpectedValueException("Configuration value format must be a mapping");
        }
        $this->prefix = $this->requireString("format.prefix");
        $this->errorFormat = $this->requireString("format.error");
        $this->successFormat = $this->requireString("format.success");
        $this->defaultFormat = $this->requireString("format.default");
        
        // Channel formats
        $this->channelFormats = [];
        if (array_key_exists("channels", $format)) {
            $channels = $format["channels"];
            if (!is_array($channels)) {
                throw new UnexpectedValueException(
                    "Configuration value format.channels must be a mapping"
                );
            }
            // PHP represents an empty YAML sequence and an empty mapping as
            // the same [] value. Accept the empty case, but reject non-empty
            // sequences instead of treating their numeric indexes as channel
            // IDs.
            if ($channels !== [] && array_is_list($channels)) {
                throw new UnexpectedValueException(
                    "Configuration value format.channels must be a mapping"
                );
            }
        } else {
            $channels = [];
        }
        foreach ($channels as $channelId => $format) {
            if (!is_string($format)) {
                throw new UnexpectedValueException(
                    "Configuration value format.channels." . $channelId . " must be a string"
                );
            }
            $this->channelFormats[(string) $channelId] = $format;
        }
        
        // Debug mode
        $this->debug = $this->requireBool("debug");
    }

    private function requireValue(string $path): mixed {
        $value = $this->config;
        foreach (explode(".", $path) as $segment) {
            if (!is_array($value) || !array_key_exists($segment, $value)) {
                throw new UnexpectedValueException(
                    "Required configuration value " . $path . " is missing"
                );
            }
            $value = $value[$segment];
        }
        return $value;
    }

    private function requireString(string $path): string {
        $value = $this->requireValue($path);
        if (!is_string($value)) {
            throw new UnexpectedValueException("Configuration value " . $path . " must be a string");
        }
        return $value;
    }

    private function requireNonBlankString(string $path): string {
        $value = $this->requireString($path);
        if (trim($value) === "") {
            throw new UnexpectedValueException(
                "Configuration value " . $path . " must not be blank"
            );
        }
        return $value;
    }

    private function requireInt(string $path): int {
        $value = $this->requireValue($path);
        if (!is_int($value)) {
            throw new UnexpectedValueException("Configuration value " . $path . " must be an integer");
        }
        return $value;
    }

    private function requirePort(string $path): int {
        $value = $this->requireInt($path);
        if ($value < 1 || $value > 65535) {
            throw new UnexpectedValueException(
                "Configuration value " . $path . " must be between 1 and 65535"
            );
        }
        return $value;
    }

    private function requirePositiveInt(string $path): int {
        $value = $this->requireInt($path);
        if ($value <= 0) {
            throw new UnexpectedValueException(
                "Configuration value " . $path . " must be greater than 0"
            );
        }
        return $value;
    }

    private function requireBool(string $path): bool {
        $value = $this->requireValue($path);
        if (!is_bool($value)) {
            throw new UnexpectedValueException("Configuration value " . $path . " must be a boolean");
        }
        return $value;
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
     * Gets the server version reported in the protocol v2 handshake.
     *
     * @return string The server version
     */
    public function getServerVersion(): string {
        return $this->serverVersion;
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
     * Whether TLS (AUTH-002) is enabled for the backend transport. False
     * keeps the plaintext path; true wraps the TCP socket in TLS.
     *
     * @return bool True if TLS is enabled
     */
    public function isTlsEnabled(): bool {
        return $this->tlsEnable;
    }

    /**
     * Gets the CA certificate (PEM) path used to verify the backend
     * certificate. Empty string means use the system CA store. Verification
     * is always enforced when isTlsEnabled() is true.
     *
     * @return string The CA certificate path, or "" for the system store
     */
    public function getTlsCaCertPath(): string {
        return $this->tlsCaCertPath;
    }

    /**
     * Gets the optional mTLS client certificate (PEM) path.
     *
     * @return string The client certificate path, or "" when mTLS is not used
     */
    public function getTlsClientCertPath(): string {
        return $this->tlsClientCertPath;
    }

    /**
     * Gets the optional mTLS client private key (PEM) path.
     *
     * @return string The client key path, or "" when mTLS is not used
     */
    public function getTlsClientKeyPath(): string {
        return $this->tlsClientKeyPath;
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
