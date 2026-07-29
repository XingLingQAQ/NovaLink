package com.nova.chat.mod.version;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **Feature: novachat-platform-extensions, Property 16: Mod Version Detection Correctness**
 * 
 * Property: For any supported Quilt Minecraft version (1.14.x - 1.21.x), the version detector 
 * should correctly identify the version and determine the appropriate adapter range.
 * 
 * Quilt supports Minecraft 1.14.x through 1.21.x via Quilted Fabric API:
 * - 1.14.x - 1.19.x (Legacy range)
 * - 1.20.x - 1.21.x (Modern range)
 * 
 * **Validates: Requirements 6.4**
 */
class QuiltVersionDetectorPropertyTest {
    
    // ==================== Quilt Legacy Version Range Detection Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void quiltLegacyVersionsAreCorrectlyIdentified(
            @ForAll @IntRange(min = 14, max = 19) int minor,
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // All 1.14.x - 1.19.x versions should be in LEGACY range
        assertThat(detector.getVersionRange())
                .as("Version %s should be in LEGACY range", version)
                .isEqualTo(VersionRange.LEGACY_1_14_1_19);
        
        // Should be supported
        assertThat(detector.isSupported())
                .as("Version %s should be supported", version)
                .isTrue();
        
        // Should be at least 1.14 (Quilt minimum)
        assertThat(detector.isAtLeast(1, 14))
                .as("Version %s should be at least 1.14", version)
                .isTrue();
        
        // Should be before 1.20 (modern range)
        assertThat(detector.isBefore(1, 20))
                .as("Version %s should be before 1.20", version)
                .isTrue();
    }

    // ==================== Quilt Modern Version Range Detection Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void quiltModernVersionsAreCorrectlyIdentified(
            @ForAll @IntRange(min = 20, max = 21) int minor,
            @ForAll @IntRange(min = 0, max = 6) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // All 1.20.x - 1.21.x versions should be in MODERN range
        assertThat(detector.getVersionRange())
                .as("Version %s should be in MODERN range", version)
                .isEqualTo(VersionRange.MODERN_1_20_PLUS);
        
        // Should be supported
        assertThat(detector.isSupported())
                .as("Version %s should be supported", version)
                .isTrue();
        
        // Should be at least 1.20 (modern range)
        assertThat(detector.isAtLeast(1, 20))
                .as("Version %s should be at least 1.20", version)
                .isTrue();
    }
    
    // ==================== Quilt Unsupported Version Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void versionsBelow1_14AreNotQuiltCompatible(
            @ForAll @IntRange(min = 7, max = 13) int minor,
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // Versions below 1.14 are not Quilt compatible
        assertThat(detector.getVersionRange())
                .as("Version %s should be UNSUPPORTED", version)
                .isEqualTo(VersionRange.UNSUPPORTED);
        
        assertThat(detector.isSupported())
                .as("Version %s should NOT be supported", version)
                .isFalse();
    }
    
    // ==================== Quilt Version Range Boundary Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void quiltVersionRangeBoundariesAreCorrect(
            @ForAll @IntRange(min = 14, max = 21) int minor) {
        
        String version = String.format("1.%d", minor);
        VersionDetector detector = new VersionDetector(version);
        
        // Determine expected range based on minor version
        boolean isModernVersion = minor >= 20;
        
        if (isModernVersion) {
            assertThat(detector.getVersionRange())
                    .as("Version %s should be in MODERN range", version)
                    .isEqualTo(VersionRange.MODERN_1_20_PLUS);
        } else {
            assertThat(detector.getVersionRange())
                    .as("Version %s should be in LEGACY range", version)
                    .isEqualTo(VersionRange.LEGACY_1_14_1_19);
        }
    }
    
    // ==================== Version Comparison Properties for Quilt ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void quiltVersionComparisonIsTransitive(
            @ForAll @IntRange(min = 14, max = 21) int minor1,
            @ForAll @IntRange(min = 14, max = 21) int minor2,
            @ForAll @IntRange(min = 14, max = 21) int minor3) {
        
        String v1 = String.format("1.%d", minor1);
        String v2 = String.format("1.%d", minor2);
        String v3 = String.format("1.%d", minor3);
        
        int cmp12 = VersionDetector.compareVersions(v1, v2);
        int cmp23 = VersionDetector.compareVersions(v2, v3);
        int cmp13 = VersionDetector.compareVersions(v1, v3);
        
        // If v1 <= v2 and v2 <= v3, then v1 <= v3 (transitivity)
        if (cmp12 <= 0 && cmp23 <= 0) {
            assertThat(cmp13)
                    .as("Transitivity: if %s <= %s and %s <= %s, then %s <= %s", v1, v2, v2, v3, v1, v3)
                    .isLessThanOrEqualTo(0);
        }
        
        // If v1 >= v2 and v2 >= v3, then v1 >= v3 (transitivity)
        if (cmp12 >= 0 && cmp23 >= 0) {
            assertThat(cmp13)
                    .as("Transitivity: if %s >= %s and %s >= %s, then %s >= %s", v1, v2, v2, v3, v1, v3)
                    .isGreaterThanOrEqualTo(0);
        }
    }

    // ==================== Quilt Adapter Selection Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void quiltAdapterSelectionIsConsistent(
            @ForAll @IntRange(min = 14, max = 21) int minor,
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // Determine expected adapter type
        String expectedAdapterType;
        if (minor >= 20) {
            expectedAdapterType = "QuiltModernVersionAdapter (1.20-1.21)";
        } else {
            expectedAdapterType = "QuiltLegacyVersionAdapter (1.14-1.19)";
        }
        
        // Verify version range matches expected adapter
        VersionRange range = detector.getVersionRange();
        if (minor >= 20) {
            assertThat(range)
                    .as("Version %s should use %s", version, expectedAdapterType)
                    .isEqualTo(VersionRange.MODERN_1_20_PLUS);
        } else {
            assertThat(range)
                    .as("Version %s should use %s", version, expectedAdapterType)
                    .isEqualTo(VersionRange.LEGACY_1_14_1_19);
        }
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void quiltVersionRangeContainsIsConsistentWithDetector(
            @ForAll @IntRange(min = 14, max = 21) int minor,
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        VersionRange detectedRange = detector.getVersionRange();
        
        // The detected range should contain the version
        assertThat(detectedRange.contains(1, minor))
                .as("Range %s should contain version %s", detectedRange, version)
                .isTrue();
    }
    
    // ==================== Quilted Fabric API Compatibility Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void allSupportedQuiltVersionsAreQfapiCompatible(
            @ForAll @IntRange(min = 14, max = 21) int minor,
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // All supported Quilt versions should be compatible with Quilted Fabric API
        assertThat(detector.isSupported())
                .as("Version %s should be supported by Quilted Fabric API", version)
                .isTrue();
        
        // All supported versions should be at least 1.14
        assertThat(detector.isAtLeast(1, 14))
                .as("Version %s should be at least 1.14 for QFAPI", version)
                .isTrue();
    }
    
    // ==================== Edge Cases ====================
    
    @Example
    void specificQuiltVersionsAreCorrectlyClassified() {
        // Test specific known Quilt versions
        
        // Legacy versions (1.14-1.19)
        assertThat(new VersionDetector("1.14.4").getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
        assertThat(new VersionDetector("1.15.2").getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
        assertThat(new VersionDetector("1.16.5").getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
        assertThat(new VersionDetector("1.17.1").getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
        assertThat(new VersionDetector("1.18.2").getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
        assertThat(new VersionDetector("1.19.4").getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
        
        // Modern versions (1.20-1.21)
        assertThat(new VersionDetector("1.20.1").getVersionRange()).isEqualTo(VersionRange.MODERN_1_20_PLUS);
        assertThat(new VersionDetector("1.20.4").getVersionRange()).isEqualTo(VersionRange.MODERN_1_20_PLUS);
        assertThat(new VersionDetector("1.20.6").getVersionRange()).isEqualTo(VersionRange.MODERN_1_20_PLUS);
        assertThat(new VersionDetector("1.21").getVersionRange()).isEqualTo(VersionRange.MODERN_1_20_PLUS);
        assertThat(new VersionDetector("1.21.1").getVersionRange()).isEqualTo(VersionRange.MODERN_1_20_PLUS);
    }
    
    @Example
    void quiltMinimumVersionBoundary() {
        // 1.13.2 is NOT Quilt compatible
        VersionDetector v1_13_2 = new VersionDetector("1.13.2");
        assertThat(v1_13_2.isSupported()).isFalse();
        assertThat(v1_13_2.getVersionRange()).isEqualTo(VersionRange.UNSUPPORTED);
        
        // 1.14 IS Quilt compatible (minimum version)
        VersionDetector v1_14 = new VersionDetector("1.14");
        assertThat(v1_14.isSupported()).isTrue();
        assertThat(v1_14.getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
    }
    
    @Example
    void legacyToModernBoundary() {
        // 1.19.4 is in LEGACY range
        VersionDetector v1_19_4 = new VersionDetector("1.19.4");
        assertThat(v1_19_4.getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
        assertThat(v1_19_4.isBefore(1, 20)).isTrue();
        
        // 1.20 is in MODERN range
        VersionDetector v1_20 = new VersionDetector("1.20");
        assertThat(v1_20.getVersionRange()).isEqualTo(VersionRange.MODERN_1_20_PLUS);
        assertThat(v1_20.isAtLeast(1, 20)).isTrue();
    }
    
    @Example
    void versionRangeDescriptionIsNotEmpty() {
        VersionDetector legacyDetector = new VersionDetector("1.16.5");
        assertThat(legacyDetector.getVersionRangeDescription()).isNotEmpty();
        assertThat(legacyDetector.toString()).contains("1.16.5");
        
        VersionDetector modernDetector = new VersionDetector("1.20.4");
        assertThat(modernDetector.getVersionRangeDescription()).isNotEmpty();
        assertThat(modernDetector.toString()).contains("1.20.4");
    }
}
