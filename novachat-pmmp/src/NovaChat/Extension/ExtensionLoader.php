<?php

declare(strict_types=1);

namespace NovaChat\Extension;

use NovaChat\NovaChatPlugin;
use pocketmine\utils\Config;

/**
 * Loads and manages NovaChat extensions for PocketMine-MP.
 * 
 * This loader scans the extensions directory for PHP extension files,
 * parses extension.yml metadata, and creates extension instances.
 * 
 * Extensions that fail to load are logged but do not prevent
 * other extensions from loading (isolation property).
 * 
 * Requirements: 10.1 - THE PMMP 扩展 SHALL 使用 PHP 编写并放置在 extensions 目录
 * Requirements: 10.4 - WHEN 扩展加载 THEN 各平台扩展加载器 SHALL 调用对应的初始化方法
 */
class ExtensionLoader {
    
    private const EXTENSION_YML = "extension.yml";
    
    private NovaChatPlugin $plugin;
    
    /** @var array<string, NovaChatExtension> */
    private array $loadedExtensions = [];
    
    /** @var array<string, bool> */
    private array $enabledExtensions = [];
    
    /**
     * Creates a new ExtensionLoader.
     * 
     * @param NovaChatPlugin $plugin the NovaChat plugin instance
     */
    public function __construct(NovaChatPlugin $plugin) {
        $this->plugin = $plugin;
    }
    
    /**
     * Loads all extensions from the extensions directory.
     * 
     * This method will:
     * 1. Scan the directory for subdirectories containing extension.yml
     * 2. Parse extension.yml from each directory
     * 3. Load the main PHP class
     * 4. Create extension instances
     * 
     * Extensions that fail to load will be logged but will not prevent
     * other extensions from loading (isolation property).
     * 
     * @param string $extensionsDir the path to the extensions directory
     * @return NovaChatExtension[] list of successfully loaded extensions
     */
    public function loadExtensions(string $extensionsDir): array {
        $extensions = [];
        
        // Create extensions directory if it doesn't exist
        if (!is_dir($extensionsDir)) {
            @mkdir($extensionsDir, 0755, true);
            return $extensions;
        }
        
        // Scan for extension directories
        $dirs = scandir($extensionsDir);
        if ($dirs === false) {
            $this->plugin->getLogger()->warning("Failed to scan extensions directory");
            return $extensions;
        }
        
        foreach ($dirs as $dir) {
            if ($dir === '.' || $dir === '..') {
                continue;
            }
            
            $extPath = $extensionsDir . DIRECTORY_SEPARATOR . $dir;
            
            // Skip if not a directory
            if (!is_dir($extPath)) {
                continue;
            }
            
            try {
                $extension = $this->loadExtension($extPath);
                if ($extension !== null) {
                    $extensions[] = $extension;
                    $this->loadedExtensions[$extension->getMeta()->getId()] = $extension;
                    $this->plugin->getLogger()->info(
                        "Loaded extension: " . $extension->getMeta()->getName() . 
                        " v" . $extension->getMeta()->getVersion()
                    );
                }
            } catch (ExtensionException $e) {
                // Log error but continue loading other extensions (isolation)
                $this->plugin->getLogger()->warning(
                    "Failed to load extension from $dir: " . $e->getMessage()
                );
            } catch (\Throwable $e) {
                // Catch any other errors to ensure isolation
                $this->plugin->getLogger()->warning(
                    "Unexpected error loading extension from $dir: " . $e->getMessage()
                );
            }
        }
        
        return $extensions;
    }
    
    /**
     * Loads a single extension from a directory.
     * 
     * @param string $extPath path to the extension directory
     * @return NovaChatExtension|null the loaded extension, or null if loading fails
     * @throws ExtensionException if the extension cannot be loaded
     */
    private function loadExtension(string $extPath): ?NovaChatExtension {
        $metaFile = $extPath . DIRECTORY_SEPARATOR . self::EXTENSION_YML;
        
        // Check for extension.yml
        if (!file_exists($metaFile)) {
            return null; // Not an extension directory
        }
        
        // Parse metadata
        $meta = $this->loadMeta($metaFile);
        
        // Check for duplicate ID
        if (isset($this->loadedExtensions[$meta->getId()])) {
            throw new ExtensionException(
                "Duplicate extension ID: " . $meta->getId(),
                $meta->getId()
            );
        }
        
        // Load the main class file
        $mainFile = $extPath . DIRECTORY_SEPARATOR . $meta->getMain() . ".php";
        if (!file_exists($mainFile)) {
            throw new ExtensionException(
                "Main class file not found: " . $meta->getMain() . ".php",
                $meta->getId()
            );
        }
        
        // Include the main class file
        require_once $mainFile;
        
        // Determine the full class name
        $className = $meta->getMain();
        
        // Check if class exists
        if (!class_exists($className)) {
            throw new ExtensionException(
                "Main class not found: $className",
                $meta->getId()
            );
        }
        
        // Check if class implements NovaChatExtension
        if (!is_subclass_of($className, NovaChatExtension::class)) {
            throw new ExtensionException(
                "Main class $className does not implement NovaChatExtension",
                $meta->getId()
            );
        }
        
        // Create instance
        /** @var NovaChatExtension $extension */
        $extension = new $className();
        $extension->setMeta($meta);
        $extension->setPlugin($this->plugin);
        
        return $extension;
    }
    
    /**
     * Loads extension metadata from extension.yml.
     * 
     * @param string $metaFile path to the extension.yml file
     * @return ExtensionMeta the parsed metadata
     * @throws ExtensionException if metadata cannot be loaded or parsed
     */
    private function loadMeta(string $metaFile): ExtensionMeta {
        $config = new Config($metaFile, Config::YAML);
        $data = $config->getAll();
        
        if (empty($data)) {
            throw new ExtensionException("Empty or invalid extension.yml");
        }
        
        return ExtensionMeta::fromArray($data);
    }
    
    /**
     * Enables a specific extension.
     * 
     * @param NovaChatExtension $extension the extension to enable
     * @throws ExtensionException if the extension fails to enable
     */
    public function enableExtension(NovaChatExtension $extension): void {
        $id = $extension->getMeta()->getId();
        
        if (isset($this->enabledExtensions[$id]) && $this->enabledExtensions[$id]) {
            return; // Already enabled
        }
        
        // Check dependencies
        foreach ($extension->getMeta()->getDependencies() as $depId) {
            if (!isset($this->enabledExtensions[$depId]) || !$this->enabledExtensions[$depId]) {
                throw new ExtensionException(
                    "Missing dependency: $depId",
                    $id
                );
            }
        }
        
        try {
            $extension->onEnable();
            $this->enabledExtensions[$id] = true;
            $this->plugin->getLogger()->info("Enabled extension: " . $extension->getMeta()->getName());
        } catch (\Throwable $e) {
            throw new ExtensionException(
                "Failed to enable extension: " . $e->getMessage(),
                $id,
                $e
            );
        }
    }
    
    /**
     * Disables a specific extension.
     * 
     * @param NovaChatExtension $extension the extension to disable
     */
    public function disableExtension(NovaChatExtension $extension): void {
        $id = $extension->getMeta()->getId();
        
        if (!isset($this->enabledExtensions[$id]) || !$this->enabledExtensions[$id]) {
            return; // Not enabled
        }
        
        try {
            $extension->onDisable();
            $this->plugin->getLogger()->info("Disabled extension: " . $extension->getMeta()->getName());
        } catch (\Throwable $e) {
            $this->plugin->getLogger()->warning(
                "Error disabling extension " . $extension->getMeta()->getName() . ": " . $e->getMessage()
            );
        }
        
        $this->enabledExtensions[$id] = false;
    }
    
    /**
     * Enables all loaded extensions.
     * Extensions are enabled in dependency order.
     */
    public function enableAllExtensions(): void {
        // Sort by dependencies (simple topological sort)
        $sorted = $this->sortByDependencies();
        
        foreach ($sorted as $extension) {
            try {
                $this->enableExtension($extension);
            } catch (ExtensionException $e) {
                $this->plugin->getLogger()->warning(
                    "Failed to enable extension: " . $e->getMessage()
                );
            }
        }
    }
    
    /**
     * Disables all enabled extensions.
     * Extensions are disabled in reverse dependency order.
     */
    public function disableAllExtensions(): void {
        $sorted = array_reverse($this->sortByDependencies());
        
        foreach ($sorted as $extension) {
            $this->disableExtension($extension);
        }
    }
    
    /**
     * Sorts extensions by dependencies (topological sort).
     * 
     * @return NovaChatExtension[] sorted extensions
     */
    private function sortByDependencies(): array {
        $sorted = [];
        $visited = [];
        
        foreach ($this->loadedExtensions as $extension) {
            $this->visitExtension($extension, $visited, $sorted);
        }
        
        return $sorted;
    }
    
    /**
     * Helper for topological sort.
     * 
     * @param NovaChatExtension $extension the extension to visit
     * @param array<string, bool> $visited visited map
     * @param NovaChatExtension[] $sorted sorted list
     */
    private function visitExtension(NovaChatExtension $extension, array &$visited, array &$sorted): void {
        $id = $extension->getMeta()->getId();
        
        if (isset($visited[$id])) {
            return;
        }
        
        $visited[$id] = true;
        
        // Visit dependencies first
        foreach ($extension->getMeta()->getDependencies() as $depId) {
            if (isset($this->loadedExtensions[$depId])) {
                $this->visitExtension($this->loadedExtensions[$depId], $visited, $sorted);
            }
        }
        
        $sorted[] = $extension;
    }
    
    /**
     * Gets all currently loaded extensions.
     * 
     * @return NovaChatExtension[] loaded extensions
     */
    public function getLoadedExtensions(): array {
        return array_values($this->loadedExtensions);
    }
    
    /**
     * Gets an extension by its ID.
     * 
     * @param string $id the extension ID
     * @return NovaChatExtension|null the extension, or null if not found
     */
    public function getExtension(string $id): ?NovaChatExtension {
        return $this->loadedExtensions[$id] ?? null;
    }
    
    /**
     * Checks if an extension is enabled.
     * 
     * @param string $id the extension ID
     * @return bool true if enabled
     */
    public function isExtensionEnabled(string $id): bool {
        return isset($this->enabledExtensions[$id]) && $this->enabledExtensions[$id];
    }
}
