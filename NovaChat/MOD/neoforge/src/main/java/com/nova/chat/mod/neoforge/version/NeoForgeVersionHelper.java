package com.nova.chat.mod.neoforge.version;

import com.nova.chat.mod.version.VersionAdapter;
import com.nova.chat.mod.version.VersionDetector;
import com.nova.chat.mod.version.VersionRange;
import com.nova.chat.mod.version.UnsupportedVersionException;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for NeoForge-specific version detection and adapter initialization.
 * Uses NeoForge/FML API to detect the Minecraft version at runtime.
 * 
 * NeoForge only supports Minecraft 1.20.2 and above, so this helper
 * only needs to handle modern versions.
 * 
 * Requirements: 5.1, 5.4
 */
public class NeoForgeVersionHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgeVersionHelper.class);
    
    private static final String MINECRAFT_MOD_ID = "minecraft";
    
    // NeoForge minimum supported version
    private static final int MIN_MAJOR = 1;
    private static final int MIN_MINOR = 20;
    private static final int MIN_PATCH = 2;
    
    private static VersionAdapter adapter;
    private static VersionDetector detector;
    private static boolean initialized = false;
    
    /**
     * Initializes the version adapter for the current NeoForge environment.
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
        LOGGER.info("Detected Minecraft version via NeoForge/FML: {}", minecraftVersion);
        
        detector = new VersionDetector(minecraftVersion);
        
        // NeoForge requires 1.20.2+
        if (!isNeoForgeCompatible(detector)) {
            throw new UnsupportedVersionException(
                    minecraftVersion,
                    "1.20.2 - 1.21.x / 26.x (NeoForge)"
            );
        }
        
        adapter = createAdapter(minecraftVersion);
        initialized = true;
        
        LOGGER.info("NeoForge version adapter initialized: {} for range {}",
                adapter.getClass().getSimpleName(),
                adapter.getSupportedVersionRange());
        
        return adapter;
    }
    
    /**
     * Checks if the detected version is compatible with NeoForge.
     * NeoForge only supports 1.20.2 and above.
     * 
     * @param detector the version detector
     * @return true if compatible with NeoForge
     */
    private static boolean isNeoForgeCompatible(VersionDetector detector) {
        // Calendar-line 26.x+ is always NeoForge-compatible modern
        if (detector.getMajorVersion() >= 26) {
            return true;
        }
        return detector.isAtLeast(MIN_MAJOR, MIN_MINOR, MIN_PATCH);
    }
    
    /**
     * Detects the Minecraft version using NeoForge/FML.
     * @return the Minecraft version string
     */
    public static String detectMinecraftVersion() {
        // NeoForge's FMLLoader.versionInfo() was removed in recent FML versions.
        // Detect the Minecraft version from the loaded mod container instead,
        // which is stable across NeoForge 1.20.2+ / 26.x.
        try {
            return ModList.get().getModContainerById(MINECRAFT_MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("1.21.11");
        } catch (Exception e) {
            LOGGER.debug("Could not get version from ModList: {}", e.getMessage());
        }

        // Final fallback
        LOGGER.warn("Could not detect Minecraft version from NeoForge, using fallback 1.21.11");
        return "1.21.11";
    }
    
    /**
     * Creates the appropriate version adapter based on the detected version.
     * For NeoForge, we select the adapter based on the specific version range.
     * 
     * @param minecraftVersion the Minecraft version
     * @return the version adapter
     */
    private static VersionAdapter createAdapter(String minecraftVersion) {
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        int major = parsed[0];
        int minor = parsed[1];
        int patch = parsed[2];

        NeoForgeVersionAdapter versionAdapter;

        if (major >= 26 || minor >= 21) {
            // 1.21.x and calendar-line 26.x+
            versionAdapter = new NeoForge1_21Adapter();
        } else if (minor == 20 && patch >= 5) {
            // 1.20.5-1.20.6
            versionAdapter = new NeoForge1_20_5Adapter();
        } else {
            // 1.20.2-1.20.4 (default for NeoForge)
            versionAdapter = new NeoForge1_20_2Adapter();
        }

        versionAdapter.setMinecraftVersion(minecraftVersion);
        LOGGER.info("Selected NeoForge adapter: {} for version {}",
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
            throw new IllegalStateException("NeoForgeVersionHelper not initialized. Call initialize() first.");
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
            throw new IllegalStateException("NeoForgeVersionHelper not initialized. Call initialize() first.");
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
     * For NeoForge, this is always MODERN_1_20_PLUS.
     * @return the version range, or null if not initialized
     */
    public static VersionRange getVersionRange() {
        return detector != null ? detector.getVersionRange() : null;
    }
    
    /**
     * Gets the NeoForge-specific version range description.
     * @return the version range description
     */
    public static String getNeoForgeVersionRange() {
        if (detector == null) {
            return "Not initialized";
        }
        
        int minor = detector.getMinorVersion();
        int patch = detector.getPatchVersion();
        
        // NeoForge has different API versions for different MC versions
        if (minor == 20) {
            if (patch >= 2 && patch <= 4) {
                return "1.20.2-1.20.4";
            } else if (patch >= 5) {
                return "1.20.5-1.20.6";
            }
        } else if (minor == 21) {
            return "1.21.x";
        }
        
        return "1.20.2+";
    }
    
    /**
     * Checks if the current version is 1.20.2-1.20.4.
     * These versions share similar NeoForge API.
     * @return true if in 1.20.2-1.20.4 range
     */
    public static boolean is1_20_2_to_1_20_4() {
        if (detector == null) return false;
        return detector.getMinorVersion() == 20 && 
               detector.getPatchVersion() >= 2 && 
               detector.getPatchVersion() <= 4;
    }
    
    /**
     * Checks if the current version is 1.20.5-1.20.6.
     * These versions have updated NeoForge API.
     * @return true if in 1.20.5-1.20.6 range
     */
    public static boolean is1_20_5_to_1_20_6() {
        if (detector == null) return false;
        return detector.getMinorVersion() == 20 && 
               detector.getPatchVersion() >= 5;
    }
    
    /**
     * Checks if the current version is 1.21.x.
     * @return true if 1.21.x
     */
    public static boolean is1_21() {
        if (detector == null) return false;
        return detector.getMinorVersion() == 21;
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
