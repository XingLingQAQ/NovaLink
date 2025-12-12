package com.nova.chat.mod.version;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the Minecraft version at runtime and provides version information.
 * Used to select the appropriate VersionAdapter for the current environment.
 * 
 * Requirements: 4.1, 4.4
 */
public class VersionDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersionDetector.class);
    
    // Version pattern: major.minor.patch (patch is optional)
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
    
    private final String minecraftVersion;
    private final int majorVersion;
    private final int minorVersion;
    private final int patchVersion;
    
    /**
     * Creates a VersionDetector with the specified Minecraft version.
     * @param minecraftVersion the Minecraft version string (e.g., "1.20.4")
     */
    public VersionDetector(String minecraftVersion) {
        this.minecraftVersion = minecraftVersion;
        
        int[] parsed = parseVersion(minecraftVersion);
        this.majorVersion = parsed[0];
        this.minorVersion = parsed[1];
        this.patchVersion = parsed[2];
        
        LOGGER.info("Detected Minecraft version: {} (major={}, minor={}, patch={})",
                minecraftVersion, majorVersion, minorVersion, patchVersion);
    }
    
    /**
     * Parses a version string into major, minor, and patch components.
     * @param version the version string
     * @return array of [major, minor, patch]
     */
    public static int[] parseVersion(String version) {
        if (version == null || version.isEmpty()) {
            return new int[]{0, 0, 0};
        }
        
        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (matcher.find()) {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
            return new int[]{major, minor, patch};
        }
        
        return new int[]{0, 0, 0};
    }
    
    /**
     * Gets the detected Minecraft version.
     * @return the version string
     */
    public String getMinecraftVersion() {
        return minecraftVersion;
    }
    
    /**
     * Gets the major version number (e.g., 1 for "1.20.4").
     * @return the major version
     */
    public int getMajorVersion() {
        return majorVersion;
    }
    
    /**
     * Gets the minor version number (e.g., 20 for "1.20.4").
     * @return the minor version
     */
    public int getMinorVersion() {
        return minorVersion;
    }
    
    /**
     * Gets the patch version number (e.g., 4 for "1.20.4").
     * @return the patch version
     */
    public int getPatchVersion() {
        return patchVersion;
    }
    
    /**
     * Checks if the current version is at least the specified version.
     * @param major the minimum major version
     * @param minor the minimum minor version
     * @return true if current version >= specified version
     */
    public boolean isAtLeast(int major, int minor) {
        return isAtLeast(major, minor, 0);
    }
    
    /**
     * Checks if the current version is at least the specified version.
     * @param major the minimum major version
     * @param minor the minimum minor version
     * @param patch the minimum patch version
     * @return true if current version >= specified version
     */
    public boolean isAtLeast(int major, int minor, int patch) {
        if (majorVersion > major) return true;
        if (majorVersion < major) return false;
        if (minorVersion > minor) return true;
        if (minorVersion < minor) return false;
        return patchVersion >= patch;
    }
    
    /**
     * Checks if the current version is before the specified version.
     * @param major the version major
     * @param minor the version minor
     * @return true if current version < specified version
     */
    public boolean isBefore(int major, int minor) {
        return isBefore(major, minor, 0);
    }
    
    /**
     * Checks if the current version is before the specified version.
     * @param major the version major
     * @param minor the version minor
     * @param patch the version patch
     * @return true if current version < specified version
     */
    public boolean isBefore(int major, int minor, int patch) {
        return !isAtLeast(major, minor, patch);
    }
    
    /**
     * Checks if the current version is within the specified range (inclusive).
     * @param minMajor minimum major version
     * @param minMinor minimum minor version
     * @param maxMajor maximum major version
     * @param maxMinor maximum minor version
     * @return true if version is within range
     */
    public boolean isInRange(int minMajor, int minMinor, int maxMajor, int maxMinor) {
        return isAtLeast(minMajor, minMinor) && !isAtLeast(maxMajor, maxMinor + 1);
    }
    
    /**
     * Determines the version range category for adapter selection.
     * @return the version range (LEGACY_1_14_1_19 or MODERN_1_20_PLUS)
     */
    public VersionRange getVersionRange() {
        if (isAtLeast(1, 20)) {
            return VersionRange.MODERN_1_20_PLUS;
        } else if (isAtLeast(1, 14)) {
            return VersionRange.LEGACY_1_14_1_19;
        } else {
            return VersionRange.UNSUPPORTED;
        }
    }
    
    /**
     * Checks if the current version is supported.
     * @return true if version is supported (1.14+)
     */
    public boolean isSupported() {
        return isAtLeast(1, 14);
    }
    
    /**
     * Gets a human-readable description of the version range.
     * @return the version range description
     */
    public String getVersionRangeDescription() {
        VersionRange range = getVersionRange();
        return switch (range) {
            case LEGACY_1_14_1_19 -> "1.14.x - 1.19.x";
            case MODERN_1_20_PLUS -> "1.20.x - 1.21.x";
            case UNSUPPORTED -> "Unsupported (< 1.14)";
        };
    }
    
    /**
     * Compares two version strings.
     * @param version1 first version
     * @param version2 second version
     * @return negative if version1 < version2, 0 if equal, positive if version1 > version2
     */
    public static int compareVersions(String version1, String version2) {
        int[] v1 = parseVersion(version1);
        int[] v2 = parseVersion(version2);
        
        for (int i = 0; i < 3; i++) {
            if (v1[i] != v2[i]) {
                return v1[i] - v2[i];
            }
        }
        return 0;
    }
    
    @Override
    public String toString() {
        return String.format("VersionDetector[version=%s, range=%s]", 
                minecraftVersion, getVersionRangeDescription());
    }
}
