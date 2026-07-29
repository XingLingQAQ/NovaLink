package com.nova.chat.mod.forge.version;

import com.nova.chat.mod.version.VersionAdapter;
import com.nova.chat.mod.version.VersionDetector;
import com.nova.chat.mod.version.VersionRange;
import com.nova.chat.mod.version.UnsupportedVersionException;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for Forge-specific version detection and adapter initialization.
 * Uses Forge/FML API to detect the Minecraft version at runtime.
 * 
 * Forge supports Minecraft versions from 1.7.10 to 1.21.x, with different
 * API generations requiring different adapters:
 * - 1.7.10-1.12.2: Legacy Forge (pre-flattening)
 * - 1.13-1.19.4: Modern Forge (post-flattening, pre-data-components)
 * - 1.20-1.21.x: Latest Forge (with data components in 1.20.5+)
 * 
 * Requirements: 7.1, 7.4
 */
public class ForgeVersionHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForgeVersionHelper.class);
    
    private static final String MINECRAFT_MOD_ID = "minecraft";
    
    // Forge minimum supported version for this implementation
    private static final int MIN_MAJOR = 1;
    private static final int MIN_MINOR = 7;
    private static final int MIN_PATCH = 10;
    
    private static VersionAdapter adapter;
    private static VersionDetector detector;
    private static boolean initialized = false;
    
    /**
     * Initializes the version adapter for the current Forge environment.
     * Detects the Minecraft version using FML and creates the appropriate adapter.
     * 
     * @return the initialized VersionAdapter
     * @throws UnsupportedVersionException if the version is not supported
     */
    public static VersionAdapter initialize() throws UnsupportedVersionException {
        if (initialized) {
            return adapter;
        }
        
        String minecraftVersion = detectMinecraftVersion();
        LOGGER.info("Detected Minecraft version via Forge/FML: {}", minecraftVersion);
        
        detector = new VersionDetector(minecraftVersion);
        
        // Check if version is supported
        if (!isForgeCompatible(detector)) {
            throw new UnsupportedVersionException(
                    minecraftVersion,
                    "1.7.10 - 1.21.x / 26.x (Forge)"
            );
        }
        
        adapter = createAdapter(minecraftVersion);
        initialized = true;
        
        LOGGER.info("Forge version adapter initialized: {} for range {}",
                adapter.getClass().getSimpleName(),
                adapter.getSupportedVersionRange());
        
        return adapter;
    }

    
    /**
     * Checks if the detected version is compatible with Forge.
     * Forge supports 1.7.10 and above.
     * 
     * @param detector the version detector
     * @return true if compatible with Forge
     */
    private static boolean isForgeCompatible(VersionDetector detector) {
        // Forge supports 1.7.10+
        if (detector.getMajorVersion() < MIN_MAJOR) {
            return false;
        }
        if (detector.getMajorVersion() == MIN_MAJOR) {
            if (detector.getMinorVersion() < MIN_MINOR) {
                return false;
            }
            if (detector.getMinorVersion() == MIN_MINOR && detector.getPatchVersion() < MIN_PATCH) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Detects the Minecraft version using Forge/FML.
     * @return the Minecraft version string
     */
    public static String detectMinecraftVersion() {
        try {
            // Try to get version from FMLLoader
            String mcVersion = FMLLoader.versionInfo().mcVersion();
            if (mcVersion != null && !mcVersion.isEmpty()) {
                LOGGER.debug("Found Minecraft version from FMLLoader: {}", mcVersion);
                return mcVersion;
            }
        } catch (Exception e) {
            LOGGER.debug("Could not get version from FMLLoader: {}", e.getMessage());
        }
        
        try {
            // Fallback: try ModList
            return ModList.get().getModContainerById(MINECRAFT_MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("1.21.11");
        } catch (Exception e) {
            LOGGER.debug("Could not get version from ModList: {}", e.getMessage());
        }

        // Final fallback
        LOGGER.warn("Could not detect Minecraft version from Forge, using fallback 1.21.11");
        return "1.21.11";
    }

    /**
     * Creates the appropriate version adapter based on the detected version.
     * For Forge, we select the adapter based on the specific version range.
     *
     * @param minecraftVersion the Minecraft version
     * @return the version adapter
     */
    private static VersionAdapter createAdapter(String minecraftVersion) {
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        int major = parsed[0];
        int minor = parsed[1];
        int patch = parsed[2];

        ForgeVersionAdapter versionAdapter;

        if (major >= 26 || minor >= 20) {
            // 1.20.x - 1.21.x / 26.x (Modern Forge with potential data components)
            versionAdapter = new Forge1_20_1_21Adapter();
        } else if (minor >= 13) {
            // 1.13.x - 1.19.x (Post-flattening Forge)
            versionAdapter = new Forge1_13_1_19Adapter();
        } else {
            // 1.7.10 - 1.12.2 (Legacy Forge)
            versionAdapter = new Forge1_7_1_12Adapter();
        }

        versionAdapter.setMinecraftVersion(minecraftVersion);
        LOGGER.info("Selected Forge adapter: {} for version {}",
                versionAdapter.getClass().getSimpleName(), minecraftVersion);

        return versionAdapter;
    }
    
    /**
     * Gets the current version adapter.
     * @return the adapter
     * @throws IllegalStateException if not initialized
     */
    public static VersionAdapter getAdapter() {
        if (!initialized) {
            throw new IllegalStateException("ForgeVersionHelper not initialized. Call initialize() first.");
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
            throw new IllegalStateException("ForgeVersionHelper not initialized. Call initialize() first.");
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
     * Gets the Forge-specific version range description.
     * @return the version range description
     */
    public static String getForgeVersionRange() {
        if (detector == null) {
            return "Not initialized";
        }
        
        int minor = detector.getMinorVersion();
        int patch = detector.getPatchVersion();
        
        if (minor >= 20) {
            if (minor == 21) {
                return "1.21.x";
            } else if (patch >= 5) {
                return "1.20.5-1.20.6";
            } else {
                return "1.20.0-1.20.4";
            }
        } else if (minor >= 13) {
            return "1.13.x-1.19.x";
        } else {
            return "1.7.10-1.12.2";
        }
    }
    
    /**
     * Checks if the current version is legacy (1.7.10-1.12.2).
     * @return true if legacy version
     */
    public static boolean isLegacyVersion() {
        if (detector == null) return false;
        return detector.getMinorVersion() <= 12;
    }
    
    /**
     * Checks if the current version is post-flattening (1.13+).
     * @return true if post-flattening
     */
    public static boolean isPostFlattening() {
        if (detector == null) return false;
        return detector.getMinorVersion() >= 13;
    }
    
    /**
     * Checks if the current version is modern (1.20+).
     * @return true if modern version
     */
    public static boolean isModernVersion() {
        if (detector == null) return false;
        return detector.getMinorVersion() >= 20;
    }
    
    /**
     * Checks if the current version uses data components (1.20.5+).
     * @return true if data components are used
     */
    public static boolean usesDataComponents() {
        if (detector == null) return false;
        int minor = detector.getMinorVersion();
        int patch = detector.getPatchVersion();
        return minor >= 21 || (minor == 20 && patch >= 5);
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
