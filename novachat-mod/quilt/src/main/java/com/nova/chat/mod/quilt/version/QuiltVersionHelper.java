package com.nova.chat.mod.quilt.version;

import com.nova.chat.mod.version.VersionAdapter;
import com.nova.chat.mod.version.VersionDetector;
import com.nova.chat.mod.version.VersionRange;
import com.nova.chat.mod.version.UnsupportedVersionException;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Helper class for Quilt-specific version detection and adapter initialization.
 * Uses Fabric Loader API (via Quilted Fabric API compatibility) to detect the Minecraft version at runtime.
 * 
 * Requirements: 6.1, 6.4
 */
public class QuiltVersionHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuiltVersionHelper.class);
    
    private static final String MINECRAFT_MOD_ID = "minecraft";
    
    private static VersionAdapter adapter;
    private static VersionDetector detector;
    private static boolean initialized = false;
    
    /**
     * Initializes the version adapter for the current Quilt environment.
     * Detects the Minecraft version using Fabric Loader (Quilt compatible) and creates the appropriate adapter.
     * 
     * @return the initialized VersionAdapter
     * @throws UnsupportedVersionException if the version is not supported
     */
    public static VersionAdapter initialize() throws UnsupportedVersionException {
        if (initialized) {
            return adapter;
        }
        
        String minecraftVersion = detectMinecraftVersion();
        LOGGER.info("Detected Minecraft version via Quilt/Fabric Loader: {}", minecraftVersion);
        
        detector = new VersionDetector(minecraftVersion);
        
        if (!detector.isSupported()) {
            throw new UnsupportedVersionException(
                    minecraftVersion,
                    "1.14.x - 1.21.x"
            );
        }
        
        adapter = createAdapter(minecraftVersion, detector.getVersionRange());
        initialized = true;
        
        LOGGER.info("Quilt version adapter initialized: {} for range {}",
                adapter.getClass().getSimpleName(),
                adapter.getSupportedVersionRange());
        
        return adapter;
    }

    /**
     * Detects the Minecraft version using Fabric Loader (Quilt compatible).
     * @return the Minecraft version string
     */
    public static String detectMinecraftVersion() {
        Optional<ModContainer> minecraft = FabricLoader.getInstance().getModContainer(MINECRAFT_MOD_ID);
        
        if (minecraft.isPresent()) {
            String version = minecraft.get().getMetadata().getVersion().getFriendlyString();
            LOGGER.debug("Found Minecraft version from Quilt/Fabric Loader: {}", version);
            return version;
        }
        
        // Fallback: try to detect from game version
        LOGGER.warn("Could not detect Minecraft version from Quilt/Fabric Loader, using fallback");
        return "1.20.4"; // Default fallback
    }
    
    /**
     * Creates the appropriate version adapter based on the detected version.
     * @param minecraftVersion the Minecraft version
     * @param range the version range
     * @return the version adapter
     */
    private static VersionAdapter createAdapter(String minecraftVersion, VersionRange range) {
        VersionAdapter versionAdapter = switch (range) {
            case LEGACY_1_14_1_19 -> {
                QuiltLegacyVersionAdapter legacy = new QuiltLegacyVersionAdapter();
                legacy.setMinecraftVersion(minecraftVersion);
                yield legacy;
            }
            case MODERN_1_20_PLUS -> {
                QuiltModernVersionAdapter modern = new QuiltModernVersionAdapter();
                modern.setMinecraftVersion(minecraftVersion);
                yield modern;
            }
            default -> throw new IllegalStateException("Unsupported version range: " + range);
        };
        
        return versionAdapter;
    }
    
    /**
     * Gets the current version adapter.
     * @return the adapter
     * @throws IllegalStateException if not initialized
     */
    public static VersionAdapter getAdapter() {
        if (!initialized) {
            throw new IllegalStateException("QuiltVersionHelper not initialized. Call initialize() first.");
        }
        return adapter;
    }
    
    /**
     * Gets the version detector.
     * @return the detector
     * @throws IllegalStateException if not initialized
     */
    public static VersionDetector getDetector() {
        if (!initialized) {
            throw new IllegalStateException("QuiltVersionHelper not initialized. Call initialize() first.");
        }
        return detector;
    }
    
    /**
     * Checks if the helper has been initialized.
     * @return true if initialized
     */
    public static boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Gets the detected Minecraft version.
     * @return the version string, or null if not initialized
     */
    public static String getMinecraftVersion() {
        return detector != null ? detector.getMinecraftVersion() : null;
    }
    
    /**
     * Gets the version range for the current environment.
     * @return the version range, or null if not initialized
     */
    public static VersionRange getVersionRange() {
        return detector != null ? detector.getVersionRange() : null;
    }
    
    /**
     * Checks if the current version is in the legacy range (1.14-1.19).
     * @return true if legacy version
     */
    public static boolean isLegacyVersion() {
        return detector != null && detector.getVersionRange() == VersionRange.LEGACY_1_14_1_19;
    }
    
    /**
     * Checks if the current version is in the modern range (1.20+).
     * @return true if modern version
     */
    public static boolean isModernVersion() {
        return detector != null && detector.getVersionRange() == VersionRange.MODERN_1_20_PLUS;
    }
    
    /**
     * Resets the helper (mainly for testing).
     */
    public static void reset() {
        adapter = null;
        detector = null;
        initialized = false;
    }
}
