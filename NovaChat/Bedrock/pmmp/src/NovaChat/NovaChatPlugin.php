<?php

declare(strict_types=1);

namespace NovaChat;

use NovaChat\Chat\ChatHandler;
use NovaChat\Command\NovaChatCommand;
use NovaChat\Config\ConfigManager;
use NovaChat\Extension\ExtensionLoader;
use NovaChat\Network\NetworkClient;
use pocketmine\plugin\PluginBase;
use pocketmine\utils\TextFormat;

/**
 * NovaChat PocketMine-MP Plugin - Main class
 * 
 * This plugin provides chat channel functionality for PocketMine-MP Bedrock servers,
 * connecting to the NovaLink backend for cross-server communication.
 * 
 * Requirements:
 * - 8.1: THE NovaChat-PMMP SHALL 使用 PHP 8.1+ 编写
 * - 8.2: THE NovaChat-PMMP SHALL 兼容 PocketMine-MP 5.x API
 * - 8.3: WHEN 插件启用 THEN NovaChat-PMMP SHALL 建立与后端的 TCP 连接
 * - 8.6: THE NovaChat-PMMP SHALL 在 plugin.yml 中声明正确的 API 版本和依赖
 */
class NovaChatPlugin extends PluginBase {
    
    /** @var NovaChatPlugin|null Plugin instance */
    private static ?NovaChatPlugin $instance = null;
    
    /** @var ConfigManager Configuration manager */
    private ConfigManager $configManager;
    
    /** @var NetworkClient|null Network client for backend connection */
    private ?NetworkClient $networkClient = null;
    
    /** @var ChatHandler|null Chat handler for message processing */
    private ?ChatHandler $chatHandler = null;
    
    /** @var ExtensionLoader|null Extension loader for custom extensions */
    private ?ExtensionLoader $extensionLoader = null;
    
    /** @var bool Debug mode flag */
    private bool $debugMode = false;
    
    /**
     * Called when the plugin is enabled.
     */
    protected function onEnable(): void {
        self::$instance = $this;
        
        // Save default config if not exists
        $this->savePluginDefaultConfig();
        
        // Initialize configuration manager
        $this->configManager = new ConfigManager($this);
        $this->debugMode = $this->configManager->isDebug();
        
        // Initialize chat handler
        $this->chatHandler = new ChatHandler($this);
        $this->getServer()->getPluginManager()->registerEvents($this->chatHandler, $this);
        
        // Initialize network client and connect to backend
        // Requirements: 8.3 - WHEN 插件启用 THEN NovaChat-PMMP SHALL 建立与后端的 TCP 连接
        $this->initializeNetworkClient();
        
        // Register commands
        $this->registerCommands();
        
        // Load and enable extensions
        // Requirements: 10.1 - THE PMMP 扩展 SHALL 使用 PHP 编写并放置在 extensions 目录
        $this->loadExtensions();
        
        $this->getLogger()->info(TextFormat::GREEN . "NovaChat PMMP plugin enabled!");
    }
    
    /**
     * Called when the plugin is disabled.
     */
    protected function onDisable(): void {
        $this->getLogger()->info("NovaChat PMMP plugin disabling...");
        
        // Disable all extensions first
        if ($this->extensionLoader !== null) {
            $this->extensionLoader->disableAllExtensions();
            $this->extensionLoader = null;
        }
        
        // Disconnect from backend
        if ($this->networkClient !== null) {
            $this->networkClient->disconnect();
            $this->networkClient = null;
        }
        
        self::$instance = null;
        $this->getLogger()->info("NovaChat PMMP plugin disabled!");
    }
    
    /**
     * Saves the default configuration file if it doesn't exist.
     *
     * Renamed from saveDefaultConfig() because PluginBase::saveDefaultConfig()
     * is declared public in pocketmine\plugin\PluginBase; a private override in
     * a subclass narrows visibility, which PHP rejects as a fatal error at
     * class load time ("Access level to NovaChat\NovaChatPlugin::saveDefaultConfig()
     * must be public"). This only surfaces on a REAL PocketMine-MP server (the
     * unit tests never load the PocketMine runtime), found by pmmp E2E 2026-08-11.
     */
    private function savePluginDefaultConfig(): void {
        $this->saveResource("config.yml", false);
    }
    
    /**
     * Initializes the network client and connects to the backend.
     * 
     * Requirements:
     * - 8.3: WHEN 插件启用 THEN NovaChat-PMMP SHALL 建立与后端的 TCP 连接
     * - 8.5: THE NovaChat-PMMP SHALL 使用 libasyncsocket 或 pmmpthread 实现异步网络通信
     */
    private function initializeNetworkClient(): void {
        $this->networkClient = new NetworkClient($this, $this->configManager);
        
        // Connect asynchronously using the new async connection system
        $host = $this->configManager->getBackendHost();
        $port = $this->configManager->getBackendPort();
        
        $this->networkClient->connect($host, $port);
    }
    
    /**
     * Registers plugin commands.
     *
     * PocketMine's PluginManager::parseYamlCommands pre-registers a PluginCommand
     * for every entry under `commands:` in plugin.yml (here `novachat` with
     * alias `nc`). That descriptor PluginCommand OWNS the "novachat"/"nc"
     * command slots. A plain `$commandMap->register("novachat", new ...)` here
     * SILENTLY returns false (no log, no exception) because the slot is already
     * taken -- so every `/nc <sub>` echoed the plugin.yml `usage:` string
     * ("Usage: /novachat <subcommand> [args]") instead of dispatching.
     *
     * Fix (mirrors the nukkit/pnx registerCommands fix,
     * real-e2e-product-findings-2026-08 Finding B2): look up the descriptor's
     * pre-registered PluginCommand via getCommand() and call setExecutor() on
     * it. NovaChatCommand implements CommandExecutor, so PMMP dispatches
     * /novachat (and /nc alias) by calling PluginCommand::execute() which
     * delegates to NovaChatCommand::onCommand() -> execute() (the full
     * subcommand match dispatch).
     */
    private function registerCommands(): void {
        $commandHandler = new NovaChatCommand($this);
        $preRegistered = $this->getCommand("novachat");
        if ($preRegistered !== null) {
            // Attach our executor to the descriptor-pre-registered PluginCommand
            // so /novachat + /nc dispatch through onCommand().
            $preRegistered->setExecutor($commandHandler);
        } else {
            // Fallback: no descriptor command (plugin.yml changed to remove it).
            // Register a plain command so /novachat still works.
            $this->getServer()->getCommandMap()->register("novachat", $commandHandler);
        }
    }
    
    /**
     * Loads and enables extensions from the extensions directory.
     * 
     * Requirements: 10.1 - THE PMMP 扩展 SHALL 使用 PHP 编写并放置在 extensions 目录
     * Requirements: 10.4 - WHEN 扩展加载 THEN 各平台扩展加载器 SHALL 调用对应的初始化方法
     */
    private function loadExtensions(): void {
        $extensionsDir = $this->getDataFolder() . "extensions";
        
        $this->extensionLoader = new ExtensionLoader($this);
        $extensions = $this->extensionLoader->loadExtensions($extensionsDir);
        
        if (count($extensions) > 0) {
            $this->getLogger()->info("Found " . count($extensions) . " extension(s)");
            $this->extensionLoader->enableAllExtensions();
        }
    }
    
    /**
     * Reloads the plugin configuration.
     */
    public function reload(): void {
        $this->reloadConfig();
        $this->configManager = new ConfigManager($this);
        $this->debugMode = $this->configManager->isDebug();
        
        // Reconnect to backend
        if ($this->networkClient !== null) {
            $this->networkClient->disconnect();
        }
        $this->initializeNetworkClient();
        
        $this->getLogger()->info("NovaChat configuration reloaded");
    }
    
    /**
     * Logs a debug message if debug mode is enabled.
     * 
     * @param string $message The message to log
     */
    public function debug(string $message): void {
        if ($this->debugMode) {
            $this->getLogger()->info("[Debug] " . $message);
        }
    }
    
    /**
     * Sets the debug mode.
     * 
     * @param bool $enabled True to enable debug mode
     */
    public function setDebugMode(bool $enabled): void {
        $this->debugMode = $enabled;
    }
    
    /**
     * Checks if debug mode is enabled.
     * 
     * @return bool True if debug mode is enabled
     */
    public function isDebugMode(): bool {
        return $this->debugMode;
    }
    
    /**
     * Gets the configuration manager.
     * 
     * @return ConfigManager The configuration manager
     */
    public function getConfigManager(): ConfigManager {
        return $this->configManager;
    }
    
    /**
     * Gets the network client.
     * 
     * @return NetworkClient|null The network client
     */
    public function getNetworkClient(): ?NetworkClient {
        return $this->networkClient;
    }
    
    /**
     * Gets the chat handler.
     * 
     * @return ChatHandler|null The chat handler
     */
    public function getChatHandler(): ?ChatHandler {
        return $this->chatHandler;
    }
    
    /**
     * Gets the extension loader.
     * 
     * @return ExtensionLoader|null The extension loader
     */
    public function getExtensionLoader(): ?ExtensionLoader {
        return $this->extensionLoader;
    }
    
    /**
     * Gets the plugin instance.
     * 
     * @return NovaChatPlugin|null The plugin instance
     */
    public static function getInstance(): ?NovaChatPlugin {
        return self::$instance;
    }
}
