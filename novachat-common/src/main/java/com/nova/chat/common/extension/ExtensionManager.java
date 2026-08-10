package com.nova.chat.common.extension;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of NovaChat extensions.
 * 
 * <p>The ExtensionManager is responsible for:
 * <ul>
 *   <li>Loading extensions from the extensions directory</li>
 *   <li>Resolving and validating extension dependencies</li>
 *   <li>Enabling extensions in dependency order</li>
 *   <li>Disabling extensions in reverse dependency order</li>
 *   <li>Managing extension events and commands</li>
 * </ul>
 * 
 * <p>Extensions that fail to load do not prevent other extensions from loading
 * (isolation property - Requirements 8.5).
 * 
 * @see NovaChatExtension
 * @see ExtensionLoader
 */
public class ExtensionManager {
    
    private static final Logger LOGGER = Logger.getLogger(ExtensionManager.class.getName());
    
    private final ExtensionLoader loader;
    private final ExtensionEventBus eventBus;
    private final ExtensionCommandRegistry commandRegistry;
    private final Map<String, NovaChatExtension> extensions;
    private final Map<String, ExtensionState> extensionStates;
    private final Set<String> enabledExtensions;
    
    /**
     * Extension lifecycle states.
     */
    public enum ExtensionState {
        /** Extension class loaded and instantiated, but not yet enabled. */
        LOADED,
        /** Extension {@code onEnable} has completed successfully. */
        ENABLED,
        /** Extension has been disabled (via {@code onDisable} or shutdown). */
        DISABLED,
        /** Extension failed to load or enable; isolated from other extensions. */
        FAILED
    }
    
    /**
     * Creates a new ExtensionManager with the default loader.
     */
    public ExtensionManager() {
        this(new DefaultExtensionLoader());
    }
    
    /**
     * Creates a new ExtensionManager with a custom loader.
     * 
     * @param loader the extension loader to use
     */
    public ExtensionManager(ExtensionLoader loader) {
        this.loader = loader;
        this.eventBus = new ExtensionEventBus();
        this.commandRegistry = new ExtensionCommandRegistry();
        this.extensions = new ConcurrentHashMap<>();
        this.extensionStates = new ConcurrentHashMap<>();
        this.enabledExtensions = ConcurrentHashMap.newKeySet();
    }

    
    /**
     * Loads all extensions from the specified directory.
     * 
     * <p>This method scans the directory for JAR files, parses their metadata,
     * and creates extension instances. Extensions that fail to load are logged
     * but do not prevent other extensions from loading.
     * 
     * @param extensionsDir the path to the extensions directory
     * @return the number of successfully loaded extensions
     */
    public int loadExtensions(Path extensionsDir) {
        List<NovaChatExtension> loaded = loader.loadExtensions(extensionsDir);
        
        for (NovaChatExtension extension : loaded) {
            String id = extension.getMeta().getId();
            extensions.put(id, extension);
            extensionStates.put(id, ExtensionState.LOADED);
        }
        
        LOGGER.info("Loaded " + loaded.size() + " extension(s)");
        return loaded.size();
    }
    
    /**
     * Enables all loaded extensions in dependency order.
     * 
     * <p>Extensions are enabled in topological order based on their dependencies.
     * If an extension's dependencies cannot be satisfied, it will not be enabled.
     * 
     * @return the number of successfully enabled extensions
     */
    public int enableAllExtensions() {
        List<String> sortedIds = resolveDependencyOrder();
        int enabled = 0;
        
        for (String id : sortedIds) {
            try {
                enableExtension(id);
                enabled++;
            } catch (ExtensionException e) {
                LOGGER.log(Level.WARNING, "Failed to enable extension: " + id, e);
                extensionStates.put(id, ExtensionState.FAILED);
            }
        }
        
        LOGGER.info("Enabled " + enabled + " extension(s)");
        return enabled;
    }
    
    /**
     * Enables a specific extension by ID.
     * 
     * <p>This method checks that all dependencies are satisfied before enabling.
     * 
     * @param extensionId the ID of the extension to enable
     * @throws ExtensionException if the extension cannot be enabled
     */
    public void enableExtension(String extensionId) throws ExtensionException {
        NovaChatExtension extension = extensions.get(extensionId);
        if (extension == null) {
            throw new ExtensionException(extensionId, "Extension not found: " + extensionId);
        }
        
        if (enabledExtensions.contains(extensionId)) {
            return; // Already enabled
        }
        
        // Check dependencies
        List<String> missingDeps = checkDependencies(extension);
        if (!missingDeps.isEmpty()) {
            throw new ExtensionException(extensionId, 
                "Missing dependencies: " + String.join(", ", missingDeps));
        }
        
        // Enable dependencies first
        for (String depId : extension.getMeta().getDependencies()) {
            if (!enabledExtensions.contains(depId)) {
                enableExtension(depId);
            }
        }
        
        // Enable the extension
        loader.enableExtension(extension);
        enabledExtensions.add(extensionId);
        extensionStates.put(extensionId, ExtensionState.ENABLED);
        
        LOGGER.info("Enabled extension: " + extension.getMeta().getName());
    }
    
    /**
     * Disables all enabled extensions in reverse dependency order.
     */
    public void disableAllExtensions() {
        List<String> sortedIds = resolveDependencyOrder();
        Collections.reverse(sortedIds);
        
        for (String id : sortedIds) {
            if (enabledExtensions.contains(id)) {
                disableExtension(id);
            }
        }
        
        LOGGER.info("Disabled all extensions");
    }
    
    /**
     * Disables a specific extension by ID.
     * 
     * <p>Extensions that depend on this extension will be disabled first.
     * 
     * @param extensionId the ID of the extension to disable
     */
    public void disableExtension(String extensionId) {
        NovaChatExtension extension = extensions.get(extensionId);
        if (extension == null || !enabledExtensions.contains(extensionId)) {
            return;
        }
        
        // Disable dependents first
        for (String dependentId : getDependents(extensionId)) {
            if (enabledExtensions.contains(dependentId)) {
                disableExtension(dependentId);
            }
        }
        
        // Unregister commands and events
        commandRegistry.unregisterAll(extensionId);
        eventBus.unregisterAll(extensionId);
        
        // Disable the extension
        loader.disableExtension(extension);
        enabledExtensions.remove(extensionId);
        extensionStates.put(extensionId, ExtensionState.DISABLED);
        
        LOGGER.info("Disabled extension: " + extension.getMeta().getName());
    }

    
    /**
     * Gets an extension by its ID.
     * 
     * @param extensionId the extension ID
     * @return the extension, or null if not found
     */
    public NovaChatExtension getExtension(String extensionId) {
        return extensions.get(extensionId);
    }
    
    /**
     * Gets all loaded extensions.
     * 
     * @return unmodifiable collection of loaded extensions
     */
    public Collection<NovaChatExtension> getExtensions() {
        return Collections.unmodifiableCollection(extensions.values());
    }
    
    /**
     * Gets the state of an extension.
     * 
     * @param extensionId the extension ID
     * @return the extension state, or null if not found
     */
    public ExtensionState getExtensionState(String extensionId) {
        return extensionStates.get(extensionId);
    }
    
    /**
     * Checks if an extension is enabled.
     * 
     * @param extensionId the extension ID
     * @return true if the extension is enabled
     */
    public boolean isEnabled(String extensionId) {
        return enabledExtensions.contains(extensionId);
    }
    
    /**
     * Gets the event bus for extension events.
     * 
     * @return the extension event bus
     */
    public ExtensionEventBus getEventBus() {
        return eventBus;
    }
    
    /**
     * Gets the command registry for extension commands.
     * 
     * @return the extension command registry
     */
    public ExtensionCommandRegistry getCommandRegistry() {
        return commandRegistry;
    }
    
    /**
     * Checks if all dependencies of an extension are available.
     * 
     * @param extension the extension to check
     * @return list of missing dependency IDs (empty if all satisfied)
     */
    private List<String> checkDependencies(NovaChatExtension extension) {
        List<String> missing = new ArrayList<>();
        for (String depId : extension.getMeta().getDependencies()) {
            if (!extensions.containsKey(depId)) {
                missing.add(depId);
            }
        }
        return missing;
    }
    
    /**
     * Gets all extensions that depend on the specified extension.
     * 
     * @param extensionId the extension ID
     * @return list of dependent extension IDs
     */
    private List<String> getDependents(String extensionId) {
        List<String> dependents = new ArrayList<>();
        for (NovaChatExtension ext : extensions.values()) {
            if (ext.getMeta().getDependencies().contains(extensionId)) {
                dependents.add(ext.getMeta().getId());
            }
        }
        return dependents;
    }
    
    /**
     * Resolves the dependency order for enabling extensions.
     * Uses topological sort to ensure dependencies are enabled first.
     * 
     * @return list of extension IDs in dependency order
     */
    private List<String> resolveDependencyOrder() {
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        
        for (String id : extensions.keySet()) {
            if (!visited.contains(id)) {
                topologicalSort(id, visited, visiting, sorted);
            }
        }
        
        return sorted;
    }
    
    /**
     * Performs topological sort for dependency resolution.
     */
    private void topologicalSort(String id, Set<String> visited, 
                                  Set<String> visiting, List<String> sorted) {
        if (visiting.contains(id)) {
            LOGGER.warning("Circular dependency detected involving: " + id);
            return;
        }
        
        if (visited.contains(id)) {
            return;
        }
        
        visiting.add(id);
        
        NovaChatExtension extension = extensions.get(id);
        if (extension != null) {
            for (String depId : extension.getMeta().getDependencies()) {
                if (extensions.containsKey(depId)) {
                    topologicalSort(depId, visited, visiting, sorted);
                }
            }
        }
        
        visiting.remove(id);
        visited.add(id);
        sorted.add(id);
    }
    
    /**
     * Reloads a specific extension.
     * 
     * @param extensionId the extension ID
     * @throws ExtensionException if reload fails
     */
    public void reloadExtension(String extensionId) throws ExtensionException {
        boolean wasEnabled = enabledExtensions.contains(extensionId);
        
        if (wasEnabled) {
            disableExtension(extensionId);
        }
        
        // Note: Full reload would require re-loading from JAR
        // For now, just re-enable if it was enabled
        if (wasEnabled) {
            enableExtension(extensionId);
        }
    }
    
    /**
     * Gets extension statistics.
     * 
     * @return map of state to count
     */
    public Map<ExtensionState, Integer> getStatistics() {
        Map<ExtensionState, Integer> stats = new EnumMap<>(ExtensionState.class);
        for (ExtensionState state : ExtensionState.values()) {
            stats.put(state, 0);
        }
        for (ExtensionState state : extensionStates.values()) {
            stats.merge(state, 1, Integer::sum);
        }
        return stats;
    }
}
