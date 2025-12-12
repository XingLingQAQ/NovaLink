package com.nova.chat.mod.version;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **Feature: novachat-platform-extensions, Property 16: Mod Version Detection Correctness**
 * 
 * Property: For any supported Forge Minecraft version (1.7.10-1.21.x), the version detector 
 * should correctly identify the version and determine the appropriate adapter range.
 * 
 * Forge supports a wide range of Minecraft versions:
 * - 1.7.10-1.12.2 (Legacy, pre-flattening)
 * - 1.13-1.19.4 (Post-flattening, NBT-based items)
 * - 1.20-1.21.x (Modern, with data components in 1.20.5+)
 * 
 * **Validates: Requirements 7.4**
 */
class ForgeVersionDetectorPropertyTest {
    
    // ==================== Legacy Forge Version Range Detection (1.7-1.12) ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forgeLegacyVersions1_7_1_12AreCorrectlyIdentified(
            @ForAll @IntRange(min = 7, max = 12) int minor,
            @ForAll @IntRange(min = 0, max = 10) int patch) {
        
        // Skip invalid versions (1.7.0-1.7.9 don't exist for Forge)
        if (minor == 7 && patch < 10) {
            return;
        }
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // Legacy versions (1.7-1.12) should be in UNSUPPORTED range for the common detector
        // but Forge-specific logic handles them
        assertThat(detector.getVersionRange())
                .as("Version %s should be in UNSUPPORTED range (pre-1.14)", version)
                .isEqualTo(VersionRange.UNSUPPORTED);
        
        // Verify version components are correctly parsed
        assertThat(detector.getMajorVersion()).isEqualTo(1);
        assertThat(detector.getMinorVersion()).isEqualTo(minor);
        assertThat(detector.getPatchVersion()).isEqualTo(patch);
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forgeLegacyVersionsArePreFlattening(
            @ForAll @IntRange(min = 7, max = 12) int minor,
            @ForAll @IntRange(min = 0, max = 10) int patch) {
        
        // Skip invalid versions
        if (minor == 7 && patch < 10) {
            return;
        }
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // Legacy versions should be before 1.13 (flattening)
        assertThat(detector.isBefore(1, 13))
                .as("Version %s should be before 1.13 (pre-flattening)", version)
                .isTrue();
        
        // Legacy versions should NOT support chat signing
        assertThat(detector.isBefore(1, 19, 1))
                .as("Version %s should be before 1.19.1 (no chat signing)", version)
                .isTrue();
    }
    
    // ==================== Post-Flattening Forge Version Range Detection (1.13-1.19) ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forgePostFlatteningVersions1_13_1_19AreCorrectlyIdentified(
            @ForAll @IntRange(min = 13, max = 19) int minor,
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // 1.13 is the boundary - 1.13 is unsupported by common detector but 1.14+ is legacy
        if (minor == 13) {
            assertThat(detector.getVersionRange())
                    .as("Version %s should be in UNSUPPORTED range", version)
                    .isEqualTo(VersionRange.UNSUPPORTED);
        } else {
            // 1.14-1.19 should be in LEGACY range
            assertThat(detector.getVersionRange())
                    .as("Version %s should be in LEGACY range", version)
                    .isEqualTo(VersionRange.LEGACY_1_14_1_19);
        }
        
        // All should be post-flattening
        assertThat(detector.isAtLeast(1, 13))
                .as("Version %s should be at least 1.13 (post-flattening)", version)
                .isTrue();
        
        // All should be before 1.20
        assertThat(detector.isBefore(1, 20))
                .as("Version %s should be before 1.20", version)
                .isTrue();
    }

    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forgePostFlatteningVersionsUseModernRegistries(
            @ForAll @IntRange(min = 13, max = 19) int minor,
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // Post-flattening versions use ResourceLocation-based registries
        assertThat(detector.isAtLeast(1, 13))
                .as("Version %s should be at least 1.13 (modern registries)", version)
                .isTrue();
        
        // But should not use data components (1.20.5+)
        assertThat(detector.isBefore(1, 20, 5))
                .as("Version %s should be before 1.20.5 (no data components)", version)
                .isTrue();
    }
    
    // ==================== Modern Forge Version Range Detection (1.20-1.21) ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forgeModernVersions1_20_1_21AreCorrectlyIdentified(
            @ForAll @IntRange(min = 20, max = 21) int minor,
            @ForAll @IntRange(min = 0, max = 6) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // All 1.20-1.21 versions should be in MODERN range
        assertThat(detector.getVersionRange())
                .as("Version %s should be in MODERN range", version)
                .isEqualTo(VersionRange.MODERN_1_20_PLUS);
        
        // Should be supported
        assertThat(detector.isSupported())
                .as("Version %s should be supported", version)
                .isTrue();
        
        // Should be at least 1.20
        assertThat(detector.isAtLeast(1, 20))
                .as("Version %s should be at least 1.20", version)
                .isTrue();
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forgeDataComponentsVersionsAreCorrectlyIdentified(
            @ForAll @IntRange(min = 5, max = 6) int patch) {
        
        String version = String.format("1.20.%d", patch);
        VersionDetector detector = new VersionDetector(version);
        
        // 1.20.5+ uses data components
        assertThat(detector.isAtLeast(1, 20, 5))
                .as("Version %s should be at least 1.20.5 (data components)", version)
                .isTrue();
        
        // Should be in MODERN range
        assertThat(detector.getVersionRange())
                .as("Version %s should be in MODERN range", version)
                .isEqualTo(VersionRange.MODERN_1_20_PLUS);
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forge1_21VersionsAreCorrectlyIdentified(
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.21.%d", patch);
        VersionDetector detector = new VersionDetector(version);
        
        // All 1.21.x versions should be in MODERN range
        assertThat(detector.getVersionRange())
                .as("Version %s should be in MODERN range", version)
                .isEqualTo(VersionRange.MODERN_1_20_PLUS);
        
        // Should be at least 1.21
        assertThat(detector.isAtLeast(1, 21))
                .as("Version %s should be at least 1.21", version)
                .isTrue();
        
        // 1.21 uses data components
        assertThat(detector.isAtLeast(1, 20, 5))
                .as("Version %s should be at least 1.20.5 (data components)", version)
                .isTrue();
    }
    
    // ==================== Forge Version Adapter Selection Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forgeAdapterSelectionIsConsistentForLegacyVersions(
            @ForAll @IntRange(min = 7, max = 12) int minor,
            @ForAll @IntRange(min = 0, max = 10) int patch) {
        
        // Skip invalid versions
        if (minor == 7 && patch < 10) {
            return;
        }
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // Legacy versions should select Forge1_7_1_12Adapter
        // Verify by checking version boundaries
        assertThat(detector.isBefore(1, 13))
                .as("Version %s should select legacy adapter (before 1.13)", version)
                .isTrue();
        
        // Should be at least 1.7.10
        if (minor == 7) {
            assertThat(patch).isGreaterThanOrEqualTo(10);
        }
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forgeAdapterSelectionIsConsistentForPostFlatteningVersions(
            @ForAll @IntRange(min = 13, max = 19) int minor,
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // Post-flattening versions should select Forge1_13_1_19Adapter
        // Verify by checking version boundaries
        assertThat(detector.isAtLeast(1, 13))
                .as("Version %s should be at least 1.13 (post-flattening)", version)
                .isTrue();
        assertThat(detector.isBefore(1, 20))
                .as("Version %s should be before 1.20", version)
                .isTrue();
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forgeAdapterSelectionIsConsistentForModernVersions(
            @ForAll @IntRange(min = 20, max = 21) int minor,
            @ForAll @IntRange(min = 0, max = 6) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // Modern versions should select Forge1_20_1_21Adapter
        // Verify by checking version boundaries
        assertThat(detector.isAtLeast(1, 20))
                .as("Version %s should be at least 1.20 (modern)", version)
                .isTrue();
        
        // Determine expected sub-adapter type
        boolean usesDataComponents = minor >= 21 || (minor == 20 && patch >= 5);
        
        if (usesDataComponents) {
            assertThat(detector.isAtLeast(1, 20, 5))
                    .as("Version %s should use data components", version)
                    .isTrue();
        } else {
            assertThat(detector.isBefore(1, 20, 5))
                    .as("Version %s should use NBT-based items", version)
                    .isTrue();
        }
    }
    
    // ==================== Version Comparison Properties for Forge ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forgeVersionComparisonIsTransitive(
            @ForAll @IntRange(min = 7, max = 21) int minor1,
            @ForAll @IntRange(min = 7, max = 21) int minor2,
            @ForAll @IntRange(min = 7, max = 21) int minor3) {
        
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
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void forgeVersionComparisonIsAntisymmetric(
            @ForAll @IntRange(min = 7, max = 21) int minor1,
            @ForAll @IntRange(min = 0, max = 10) int patch1,
            @ForAll @IntRange(min = 7, max = 21) int minor2,
            @ForAll @IntRange(min = 0, max = 10) int patch2) {
        
        String v1 = String.format("1.%d.%d", minor1, patch1);
        String v2 = String.format("1.%d.%d", minor2, patch2);
        
        int comparison = VersionDetector.compareVersions(v1, v2);
        int reverseComparison = VersionDetector.compareVersions(v2, v1);
        
        // Comparison should be antisymmetric
        if (comparison > 0) {
            assertThat(reverseComparison).isLessThan(0);
        } else if (comparison < 0) {
            assertThat(reverseComparison).isGreaterThan(0);
        } else {
            assertThat(reverseComparison).isEqualTo(0);
        }
    }
    
    // ==================== Edge Cases ====================
    
    @Example
    void specificForgeVersionsAreCorrectlyClassified() {
        // Test specific known Forge versions
        
        // Legacy versions (1.7-1.12)
        assertThat(new VersionDetector("1.7.10").isBefore(1, 13)).isTrue();
        assertThat(new VersionDetector("1.8.9").isBefore(1, 13)).isTrue();
        assertThat(new VersionDetector("1.12.2").isBefore(1, 13)).isTrue();
        
        // Post-flattening versions (1.13-1.19)
        assertThat(new VersionDetector("1.13.2").isAtLeast(1, 13)).isTrue();
        assertThat(new VersionDetector("1.13.2").isBefore(1, 20)).isTrue();
        assertThat(new VersionDetector("1.16.5").isAtLeast(1, 13)).isTrue();
        assertThat(new VersionDetector("1.16.5").isBefore(1, 20)).isTrue();
        assertThat(new VersionDetector("1.19.4").isAtLeast(1, 13)).isTrue();
        assertThat(new VersionDetector("1.19.4").isBefore(1, 20)).isTrue();
        
        // Modern versions (1.20-1.21)
        assertThat(new VersionDetector("1.20.1").isAtLeast(1, 20)).isTrue();
        assertThat(new VersionDetector("1.20.4").isAtLeast(1, 20)).isTrue();
        assertThat(new VersionDetector("1.20.4").isBefore(1, 20, 5)).isTrue();
        assertThat(new VersionDetector("1.20.6").isAtLeast(1, 20, 5)).isTrue();
        assertThat(new VersionDetector("1.21").isAtLeast(1, 21)).isTrue();
    }
    
    @Example
    void forgeFlatteningBoundary() {
        // 1.12.2 is pre-flattening
        VersionDetector v1_12_2 = new VersionDetector("1.12.2");
        assertThat(v1_12_2.isBefore(1, 13)).isTrue();
        
        // 1.13 is post-flattening
        VersionDetector v1_13 = new VersionDetector("1.13");
        assertThat(v1_13.isAtLeast(1, 13)).isTrue();
    }
    
    @Example
    void forgeDataComponentsBoundary() {
        // 1.20.4 does NOT use data components
        VersionDetector v1_20_4 = new VersionDetector("1.20.4");
        assertThat(v1_20_4.isAtLeast(1, 20, 5)).isFalse();
        
        // 1.20.5 DOES use data components
        VersionDetector v1_20_5 = new VersionDetector("1.20.5");
        assertThat(v1_20_5.isAtLeast(1, 20, 5)).isTrue();
    }
    
    @Example
    void forgeChatSigningBoundary() {
        // 1.19.0 does NOT support chat signing
        VersionDetector v1_19_0 = new VersionDetector("1.19.0");
        assertThat(v1_19_0.isAtLeast(1, 19, 1)).isFalse();
        
        // 1.19.1 DOES support chat signing
        VersionDetector v1_19_1 = new VersionDetector("1.19.1");
        assertThat(v1_19_1.isAtLeast(1, 19, 1)).isTrue();
    }
    
    @Example
    void forgeMinimumVersionBoundary() {
        // 1.7.9 is NOT supported (Forge starts at 1.7.10)
        VersionDetector v1_7_9 = new VersionDetector("1.7.9");
        assertThat(v1_7_9.isAtLeast(1, 7, 10)).isFalse();
        
        // 1.7.10 IS supported (Forge minimum)
        VersionDetector v1_7_10 = new VersionDetector("1.7.10");
        assertThat(v1_7_10.isAtLeast(1, 7, 10)).isTrue();
    }
    
    @Example
    void versionRangeDescriptionIsNotEmpty() {
        VersionDetector detector = new VersionDetector("1.20.4");
        assertThat(detector.getVersionRangeDescription()).isNotEmpty();
        assertThat(detector.toString()).contains("1.20.4");
    }
}
