package com.nova.chat.mod.version;

/**
 * Enum representing supported Minecraft version ranges.
 * Used to categorize versions for adapter selection.
 * 
 * Requirements: 4.1, 4.4
 */
public enum VersionRange {
    /**
     * Legacy versions: 1.14.x through 1.19.x
     * These versions use older Text API and chat event systems.
     */
    LEGACY_1_14_1_19("1.14-1.19", 1, 14, 1, 19),
    
    /**
     * Modern versions: 1.20.x through 1.21.x
     * These versions use Component API and modern chat events.
     */
    MODERN_1_20_PLUS("1.20-1.21", 1, 20, 1, 21),
    
    /**
     * Unsupported versions (below 1.14)
     */
    UNSUPPORTED("unsupported", 0, 0, 0, 0);
    
    private final String displayName;
    private final int minMajor;
    private final int minMinor;
    private final int maxMajor;
    private final int maxMinor;
    
    VersionRange(String displayName, int minMajor, int minMinor, int maxMajor, int maxMinor) {
        this.displayName = displayName;
        this.minMajor = minMajor;
        this.minMinor = minMinor;
        this.maxMajor = maxMajor;
        this.maxMinor = maxMinor;
    }
    
    /**
     * Gets the display name for this version range.
     * @return the display name
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * Gets the minimum major version.
     * @return the minimum major version
     */
    public int getMinMajor() {
        return minMajor;
    }
    
    /**
     * Gets the minimum minor version.
     * @return the minimum minor version
     */
    public int getMinMinor() {
        return minMinor;
    }
    
    /**
     * Gets the maximum major version.
     * @return the maximum major version
     */
    public int getMaxMajor() {
        return maxMajor;
    }
    
    /**
     * Gets the maximum minor version.
     * @return the maximum minor version
     */
    public int getMaxMinor() {
        return maxMinor;
    }
    
    /**
     * Checks if a version is within this range.
     * @param major the major version
     * @param minor the minor version
     * @return true if the version is within this range
     */
    public boolean contains(int major, int minor) {
        if (this == UNSUPPORTED) {
            return major < 1 || (major == 1 && minor < 14);
        }
        
        // Check if version is at least minimum
        if (major < minMajor || (major == minMajor && minor < minMinor)) {
            return false;
        }
        
        // Check if version is at most maximum (inclusive of max minor + any patch)
        if (major > maxMajor) {
            return false;
        }
        if (major == maxMajor && minor > maxMinor) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Gets the version range for a given version.
     * @param major the major version
     * @param minor the minor version
     * @return the matching version range
     */
    public static VersionRange forVersion(int major, int minor) {
        if (MODERN_1_20_PLUS.contains(major, minor)) {
            return MODERN_1_20_PLUS;
        }
        if (LEGACY_1_14_1_19.contains(major, minor)) {
            return LEGACY_1_14_1_19;
        }
        return UNSUPPORTED;
    }
    
    /**
     * Gets the version range for a given version string.
     * @param version the version string (e.g., "1.20.4")
     * @return the matching version range
     */
    public static VersionRange forVersion(String version) {
        int[] parsed = VersionDetector.parseVersion(version);
        return forVersion(parsed[0], parsed[1]);
    }
}
