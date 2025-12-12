package com.nova.chat.mod.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * Factory for creating version-specific adapters.
 * Uses ServiceLoader to discover and load the appropriate adapter implementation.
 * 
 * Requirements: 4.1, 4.4
 */
public class VersionAdapterFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersionAdapterFactory.class);
    
    private static VersionAdapter currentAdapter;
    private static VersionDetector detector;
    
    /**
     * Initializes the version adapter for the current Minecraft version.
     * @param minecraftVersion the detected Minecraft version
     * @return the appropriate VersionAdapter
     * @throws UnsupportedVersionException if the version is not supported
     */
    public static VersionAdapter initialize(String minecraftVersion) throws UnsupportedVersionException {
        detector = new VersionDetector(minecraftVersion);
        
        if (!detector.isSupported()) {
            String message = String.format(
                    "Minecraft version %s is not supported. NovaChat requires Minecraft 1.14 or higher.",
                    minecraftVersion);
            LOGGER.error(message);
            throw new UnsupportedVersionException(message);
        }
        
        VersionRange range = detector.getVersionRange();
        LOGGER.info("Detected version range: {} for Minecraft {}", range.getDisplayName(), minecraftVersion);
        
        // Try to load adapter via ServiceLoader
        currentAdapter = loadAdapter(minecraftVersion, range);
        
        if (currentAdapter == null) {
            String message = String.format(
                    "No version adapter found for Minecraft %s (range: %s). " +
                    "Please ensure you are using the correct mod version.",
                    minecraftVersion, range.getDisplayName());
            LOGGER.error(message);
            throw new UnsupportedVersionException(message);
        }
        
        LOGGER.info("Loaded version adapter: {} for range {}", 
                currentAdapter.getClass().getSimpleName(), 
                currentAdapter.getSupportedVersionRange());
        
        return currentAdapter;
    }
    
    /**
     * Loads the appropriate adapter for the given version.
     * @param minecraftVersion the Minecraft version
     * @param range the version range
     * @return the adapter, or null if not found
     */
    private static VersionAdapter loadAdapter(String minecraftVersion, VersionRange range) {
        // Try ServiceLoader first
        ServiceLoader<VersionAdapter> loader = ServiceLoader.load(VersionAdapter.class);
        
        for (VersionAdapter adapter : loader) {
            if (adapter.supportsVersion(minecraftVersion)) {
                return adapter;
            }
        }
        
        // If ServiceLoader doesn't find anything, try direct class loading
        return loadAdapterByClassName(range);
    }
    
    /**
     * Attempts to load an adapter by class name based on version range.
     * @param range the version range
     * @return the adapter, or null if not found
     */
    private static VersionAdapter loadAdapterByClassName(VersionRange range) {
        String className = switch (range) {
            case LEGACY_1_14_1_19 -> "com.nova.chat.mod.fabric.version.LegacyVersionAdapter";
            case MODERN_1_20_PLUS -> "com.nova.chat.mod.fabric.version.ModernVersionAdapter";
            default -> null;
        };
        
        if (className == null) {
            return null;
        }
        
        try {
            Class<?> clazz = Class.forName(className);
            return (VersionAdapter) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            LOGGER.debug("Could not load adapter class {}: {}", className, e.getMessage());
            return null;
        }
    }
    
    /**
     * Gets the current version adapter.
     * @return the current adapter
     * @throws IllegalStateException if not initialized
     */
    public static VersionAdapter getAdapter() {
        if (currentAdapter == null) {
            throw new IllegalStateException("VersionAdapterFactory not initialized. Call initialize() first.");
        }
        return currentAdapter;
    }
    
    /**
     * Gets the version detector.
     * @return the version detector
     * @throws IllegalStateException if not initialized
     */
    public static VersionDetector getDetector() {
        if (detector == null) {
            throw new IllegalStateException("VersionAdapterFactory not initialized. Call initialize() first.");
        }
        return detector;
    }
    
    /**
     * Checks if the factory has been initialized.
     * @return true if initialized
     */
    public static boolean isInitialized() {
        return currentAdapter != null && detector != null;
    }
    
    /**
     * Resets the factory (mainly for testing).
     */
    public static void reset() {
        currentAdapter = null;
        detector = null;
    }
}
