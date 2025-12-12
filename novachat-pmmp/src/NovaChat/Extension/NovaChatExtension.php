<?php

declare(strict_types=1);

namespace NovaChat\Extension;

/**
 * Interface that all NovaChat extensions must implement.
 * Extensions can add custom functionality to NovaChat without modifying core code.
 * 
 * Lifecycle:
 * 1. Extension file is loaded from the extensions directory
 * 2. Extension metadata is parsed from extension.yml
 * 3. onEnable() is called when the extension is enabled
 * 4. onDisable() is called when the extension is disabled
 * 
 * Requirements: 10.1 - THE PMMP 扩展 SHALL 使用 PHP 编写并放置在 extensions 目录
 */
interface NovaChatExtension {
    
    /**
     * Called when the extension is enabled.
     * This is where the extension should initialize its resources,
     * register event listeners, and set up commands.
     */
    public function onEnable(): void;
    
    /**
     * Called when the extension is disabled.
     * This is where the extension should clean up resources,
     * unregister listeners, and save any pending data.
     */
    public function onDisable(): void;
    
    /**
     * Gets the extension metadata.
     * 
     * @return ExtensionMeta the extension metadata containing id, name, version, etc.
     */
    public function getMeta(): ExtensionMeta;
    
    /**
     * Sets the extension metadata.
     * Called by the extension loader after parsing extension.yml.
     * 
     * @param ExtensionMeta $meta the extension metadata
     */
    public function setMeta(ExtensionMeta $meta): void;
    
    /**
     * Gets the NovaChat plugin instance.
     * 
     * @return \NovaChat\NovaChatPlugin|null the plugin instance
     */
    public function getPlugin(): ?\NovaChat\NovaChatPlugin;
    
    /**
     * Sets the NovaChat plugin instance.
     * Called by the extension loader during initialization.
     * 
     * @param \NovaChat\NovaChatPlugin $plugin the plugin instance
     */
    public function setPlugin(\NovaChat\NovaChatPlugin $plugin): void;
}
