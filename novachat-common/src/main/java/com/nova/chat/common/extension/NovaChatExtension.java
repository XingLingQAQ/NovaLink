package com.nova.chat.common.extension;

/**
 * Interface that all NovaChat extensions must implement.
 * Extensions can add custom functionality to NovaChat without modifying core code.
 * 
 * <p>Lifecycle:
 * <ol>
 *   <li>Extension JAR is loaded from the extensions directory</li>
 *   <li>Extension metadata is parsed from extension.yml</li>
 *   <li>{@link #onEnable()} is called when the extension is enabled</li>
 *   <li>{@link #onDisable()} is called when the extension is disabled</li>
 * </ol>
 * 
 * @see ExtensionMeta
 * @see ExtensionLoader
 */
public interface NovaChatExtension {
    
    /**
     * Called when the extension is enabled.
     * This is where the extension should initialize its resources,
     * register event listeners, and set up commands.
     */
    void onEnable();
    
    /**
     * Called when the extension is disabled.
     * This is where the extension should clean up resources,
     * unregister listeners, and save any pending data.
     */
    void onDisable();
    
    /**
     * Gets the extension metadata.
     * 
     * @return the extension metadata containing id, name, version, etc.
     */
    ExtensionMeta getMeta();
}
