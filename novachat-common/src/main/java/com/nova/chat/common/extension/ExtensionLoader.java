package com.nova.chat.common.extension;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for loading NovaChat extensions from the extensions directory.
 * 
 * <p>The extension loader is responsible for:
 * <ul>
 *   <li>Scanning the extensions directory for JAR files</li>
 *   <li>Loading and parsing extension.yml metadata</li>
 *   <li>Creating extension instances</li>
 *   <li>Managing extension lifecycle (enable/disable)</li>
 * </ul>
 * 
 * @see NovaChatExtension
 * @see ExtensionMeta
 */
public interface ExtensionLoader {
    
    /**
     * Loads all extensions from the specified extensions directory.
     * 
     * <p>This method will:
     * <ol>
     *   <li>Scan the directory for JAR files</li>
     *   <li>Parse extension.yml from each JAR</li>
     *   <li>Create extension instances</li>
     * </ol>
     * 
     * <p>Extensions that fail to load will be logged but will not prevent
     * other extensions from loading (isolation property).
     * 
     * @param extensionsDir the path to the extensions directory
     * @return list of successfully loaded extensions
     */
    List<NovaChatExtension> loadExtensions(Path extensionsDir);
    
    /**
     * Enables a specific extension.
     * 
     * @param extension the extension to enable
     * @throws ExtensionException if the extension fails to enable
     */
    void enableExtension(NovaChatExtension extension) throws ExtensionException;
    
    /**
     * Disables a specific extension.
     * 
     * @param extension the extension to disable
     */
    void disableExtension(NovaChatExtension extension);
    
    /**
     * Gets all currently loaded extensions.
     * 
     * @return unmodifiable list of loaded extensions
     */
    List<NovaChatExtension> getLoadedExtensions();
    
    /**
     * Gets an extension by its ID.
     * 
     * @param id the extension ID
     * @return the extension, or null if not found
     */
    NovaChatExtension getExtension(String id);
}
