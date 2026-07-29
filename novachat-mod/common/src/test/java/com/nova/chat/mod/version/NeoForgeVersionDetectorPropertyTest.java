package com.nova.chat.mod.version;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **Feature: novachat-platform-extensions, Property 16: Mod Version Detection Correctness**
 * 
 * Property: For any supported NeoForge Minecraft version (1.20.2+), the version detector 
 * should correctly identify the version and determine the appropriate adapter range.
 * 
 * NeoForge only supports Minecraft 1.20.2 and above, so this test focuses on:
 * - 1.20.2-1.20.4 (NBT-based items)
 * - 1.20.5-1.20.6 (Data Components)
 * - 1.21.x (Latest)
 * 
 * **Validates: Requirements 5.4**
 */
class NeoForgeVersionDetectorPropertyTest {
    
    // ==================== NeoForge Version Range Detection Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void neoForge1_20_2to1_20_4VersionsAreCorrectlyIdentified(
            @ForAll @IntRange(min = 2, max = 4) int patch) {
        
        String version = String.format("1.20.%d", patch);
        VersionDetector detector = new VersionDetector(version);
        
        // All 1.20.2-1.20.4 versions should be in MODERN range
        assertThat(detector.getVersionRange())
                .as("Version %s should be in MODERN range", version)
                .isEqualTo(VersionRange.MODERN_1_20_PLUS);
        
        // Should be supported
        assertThat(detector.isSupported())
                .as("Version %s should be supported", version)
                .isTrue();
        
        // Should be at least 1.20.2 (NeoForge minimum)
        assertThat(detector.isAtLeast(1, 20, 2))
                .as("Version %s should be at least 1.20.2", version)
                .isTrue();
        
        // Should be before 1.20.5 (data components)
        assertThat(detector.isBefore(1, 20, 5))
                .as("Version %s should be before 1.20.5", version)
                .isTrue();
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void neoForge1_20_5to1_20_6VersionsAreCorrectlyIdentified(
            @ForAll @IntRange(min = 5, max = 6) int patch) {
        
        String version = String.format("1.20.%d", patch);
        VersionDetector detector = new VersionDetector(version);
        
        // All 1.20.5-1.20.6 versions should be in MODERN range
        assertThat(detector.getVersionRange())
                .as("Version %s should be in MODERN range", version)
                .isEqualTo(VersionRange.MODERN_1_20_PLUS);
        
        // Should be supported
        assertThat(detector.isSupported())
                .as("Version %s should be supported", version)
                .isTrue();
        
        // Should be at least 1.20.5 (data components)
        assertThat(detector.isAtLeast(1, 20, 5))
                .as("Version %s should be at least 1.20.5", version)
                .isTrue();
        
        // Should be before 1.21
        assertThat(detector.isBefore(1, 21))
                .as("Version %s should be before 1.21", version)
                .isTrue();
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void neoForge1_21VersionsAreCorrectlyIdentified(
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.21.%d", patch);
        VersionDetector detector = new VersionDetector(version);
        
        // All 1.21.x versions should be in MODERN range
        assertThat(detector.getVersionRange())
                .as("Version %s should be in MODERN range", version)
                .isEqualTo(VersionRange.MODERN_1_20_PLUS);
        
        // Should be supported
        assertThat(detector.isSupported())
                .as("Version %s should be supported", version)
                .isTrue();
        
        // Should be at least 1.21
        assertThat(detector.isAtLeast(1, 21))
                .as("Version %s should be at least 1.21", version)
                .isTrue();
    }
    
    // ==================== NeoForge Unsupported Version Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void versionsBelow1_20_2AreNotNeoForgeCompatible(
            @ForAll @IntRange(min = 14, max = 19) int minor,
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // Versions below 1.20.2 are not NeoForge compatible
        assertThat(detector.isAtLeast(1, 20, 2))
                .as("Version %s should NOT be at least 1.20.2 (NeoForge minimum)", version)
                .isFalse();
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void versions1_20_0and1_20_1AreNotNeoForgeCompatible(
            @ForAll @IntRange(min = 0, max = 1) int patch) {
        
        String version = String.format("1.20.%d", patch);
        VersionDetector detector = new VersionDetector(version);
        
        // 1.20.0 and 1.20.1 are not NeoForge compatible (NeoForge starts at 1.20.2)
        assertThat(detector.isAtLeast(1, 20, 2))
                .as("Version %s should NOT be at least 1.20.2 (NeoForge minimum)", version)
                .isFalse();
    }
    
    // ==================== NeoForge Version Range Boundary Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void neoForgeVersionRangeBoundariesAreCorrect(
            @ForAll @IntRange(min = 2, max = 6) int patch) {
        
        String version = String.format("1.20.%d", patch);
        VersionDetector detector = new VersionDetector(version);
        
        // Determine expected range based on patch version
        boolean isDataComponentsVersion = patch >= 5;
        
        if (isDataComponentsVersion) {
            // 1.20.5+ uses data components
            assertThat(detector.isAtLeast(1, 20, 5))
                    .as("Version %s should be at least 1.20.5 (data components)", version)
                    .isTrue();
        } else {
            // 1.20.2-1.20.4 uses NBT
            assertThat(detector.isBefore(1, 20, 5))
                    .as("Version %s should be before 1.20.5 (NBT-based)", version)
                    .isTrue();
        }
    }
    
    // ==================== Version Comparison Properties for NeoForge ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void neoForgeVersionComparisonIsTransitive(
            @ForAll @IntRange(min = 2, max = 6) int patch1,
            @ForAll @IntRange(min = 2, max = 6) int patch2,
            @ForAll @IntRange(min = 2, max = 6) int patch3) {
        
        String v1 = String.format("1.20.%d", patch1);
        String v2 = String.format("1.20.%d", patch2);
        String v3 = String.format("1.20.%d", patch3);
        
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
    
    // ==================== NeoForge Adapter Selection Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void neoForgeAdapterSelectionIsConsistent(
            @ForAll @IntRange(min = 20, max = 21) int minor,
            @ForAll @IntRange(min = 0, max = 6) int patch) {
        
        // Skip invalid combinations (1.20.0, 1.20.1 not supported by NeoForge)
        if (minor == 20 && patch < 2) {
            return;
        }
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // Determine expected adapter type
        String expectedAdapterType;
        if (minor == 21) {
            expectedAdapterType = "1.21.x";
        } else if (patch >= 5) {
            expectedAdapterType = "1.20.5-1.20.6";
        } else {
            expectedAdapterType = "1.20.2-1.20.4";
        }
        
        // Verify version is in modern range (all NeoForge versions are modern)
        assertThat(detector.getVersionRange())
                .as("Version %s should be in MODERN range for adapter type %s", version, expectedAdapterType)
                .isEqualTo(VersionRange.MODERN_1_20_PLUS);
    }
    
    // ==================== Edge Cases ====================
    
    @Example
    void specificNeoForgeVersionsAreCorrectlyClassified() {
        // Test specific known NeoForge versions
        
        // 1.20.2-1.20.4 (NBT-based)
        assertThat(new VersionDetector("1.20.2").isAtLeast(1, 20, 2)).isTrue();
        assertThat(new VersionDetector("1.20.2").isBefore(1, 20, 5)).isTrue();
        assertThat(new VersionDetector("1.20.4").isAtLeast(1, 20, 2)).isTrue();
        assertThat(new VersionDetector("1.20.4").isBefore(1, 20, 5)).isTrue();
        
        // 1.20.5-1.20.6 (Data Components)
        assertThat(new VersionDetector("1.20.5").isAtLeast(1, 20, 5)).isTrue();
        assertThat(new VersionDetector("1.20.5").isBefore(1, 21)).isTrue();
        assertThat(new VersionDetector("1.20.6").isAtLeast(1, 20, 5)).isTrue();
        assertThat(new VersionDetector("1.20.6").isBefore(1, 21)).isTrue();
        
        // 1.21.x
        assertThat(new VersionDetector("1.21").isAtLeast(1, 21)).isTrue();
        assertThat(new VersionDetector("1.21.1").isAtLeast(1, 21)).isTrue();
        assertThat(new VersionDetector("1.21.4").isAtLeast(1, 21)).isTrue();
    }
    
    @Example
    void neoForgeMinimumVersionBoundary() {
        // 1.20.1 is NOT NeoForge compatible
        VersionDetector v1_20_1 = new VersionDetector("1.20.1");
        assertThat(v1_20_1.isAtLeast(1, 20, 2)).isFalse();
        
        // 1.20.2 IS NeoForge compatible (minimum version)
        VersionDetector v1_20_2 = new VersionDetector("1.20.2");
        assertThat(v1_20_2.isAtLeast(1, 20, 2)).isTrue();
    }
    
    @Example
    void dataComponentsBoundary() {
        // 1.20.4 does NOT use data components
        VersionDetector v1_20_4 = new VersionDetector("1.20.4");
        assertThat(v1_20_4.isAtLeast(1, 20, 5)).isFalse();
        
        // 1.20.5 DOES use data components
        VersionDetector v1_20_5 = new VersionDetector("1.20.5");
        assertThat(v1_20_5.isAtLeast(1, 20, 5)).isTrue();
    }
}
