<?php

declare(strict_types=1);

namespace NovaChat\Extension;

use NovaChat\NovaChatPlugin;

/**
 * Base class for NovaChat extensions.
 * Provides default implementations for common extension functionality.
 * 
 * Extensions can extend this class instead of implementing NovaChatExtension directly.
 * 
 * Requirements: 10.1 - THE PMMP 扩展 SHALL 使用 PHP 编写并放置在 extensions 目录
 */
abstract class BaseExtension implements NovaChatExtension {
    
    private ?ExtensionMeta $meta = null;
    private ?NovaChatPlugin $plugin = null;
    
    /**
     * {@inheritdoc}
     */
    public function getMeta(): ExtensionMeta {
        if ($this->meta === null) {
            throw new ExtensionException("Extension metadata not set");
        }
        return $this->meta;
    }
    
    /**
     * {@inheritdoc}
     */
    public function setMeta(ExtensionMeta $meta): void {
        $this->meta = $meta;
    }
    
    /**
     * {@inheritdoc}
     */
    public function getPlugin(): ?NovaChatPlugin {
        return $this->plugin;
    }
    
    /**
     * {@inheritdoc}
     */
    public function setPlugin(NovaChatPlugin $plugin): void {
        $this->plugin = $plugin;
    }
    
    /**
     * Gets the extension's logger.
     * Logs messages with the extension name prefix.
     * 
     * @return \Logger|null the logger, or null if plugin not set
     */
    protected function getLogger(): ?\Logger {
        return $this->plugin?->getLogger();
    }
    
    /**
     * Logs an info message.
     * 
     * @param string $message the message to log
     */
    protected function info(string $message): void {
        $prefix = $this->meta !== null ? "[{$this->meta->getName()}] " : "";
        $this->getLogger()?->info($prefix . $message);
    }
    
    /**
     * Logs a warning message.
     * 
     * @param string $message the message to log
     */
    protected function warning(string $message): void {
        $prefix = $this->meta !== null ? "[{$this->meta->getName()}] " : "";
        $this->getLogger()?->warning($prefix . $message);
    }
    
    /**
     * Logs an error message.
     * 
     * @param string $message the message to log
     */
    protected function error(string $message): void {
        $prefix = $this->meta !== null ? "[{$this->meta->getName()}] " : "";
        $this->getLogger()?->error($prefix . $message);
    }
    
    /**
     * Logs a debug message if debug mode is enabled.
     * 
     * @param string $message the message to log
     */
    protected function debug(string $message): void {
        if ($this->plugin !== null && $this->plugin->isDebugMode()) {
            $prefix = $this->meta !== null ? "[{$this->meta->getName()}] " : "";
            $this->getLogger()?->info("[Debug] " . $prefix . $message);
        }
    }
}
