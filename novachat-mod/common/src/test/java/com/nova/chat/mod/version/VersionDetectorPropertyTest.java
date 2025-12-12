package com.nova.chat.mod.version;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **Feature: novachat-platform-extensions, Property 16: Mod Version Detection Correctness**
 * 
 * Property: For any supported Minecraft version, the version detector should correctly 
 * identify the version and load the appropriate adapter.
 * 
 * **Validates: Requirements 4.4**
 */
class VersionDetectorPropertyTest {
    
    // ==================== Version Parsing Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void versionParsingExtractsMajorMinorPatch(
            @ForAll @IntRange(min = 1, max = 2) int major,
            @ForAll @IntRange(min = 0, max = 30) int minor,
            @ForAll @IntRange(min = 0, max = 20) int patch) {
        
        String version = String.format("%d.%d.%d", major, minor, patch);
        int[] parsed = VersionDetector.parseVersion(version);
        
        assertThat(parsed[0]).as("Major version").isEqualTo(major);
        assertThat(parsed[1]).as("Minor version").isEqualTo(minor);
        assertThat(parsed[2]).as("Patch version").isEqualTo(patch);
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void versionParsingHandlesTwoPartVersions(
            @ForAll @IntRange(min = 1, max = 2) int major,
            @ForAll @IntRange(min = 0, max = 30) int minor) {
        
        String version = String.format("%d.%d", major, minor);
        int[] parsed = VersionDetector.parseVersion(version);
        
        assertThat(parsed[0]).as("Major version").isEqualTo(major);
        assertThat(parsed[1]).as("Minor version").isEqualTo(minor);
        assertThat(parsed[2]).as("Patch version (default)").isEqualTo(0);
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void versionDetectorCorrectlyIdentifiesVersionComponents(
            @ForAll @IntRange(min = 1, max = 2) int major,
            @ForAll @IntRange(min = 14, max = 21) int minor,
            @ForAll @IntRange(min = 0, max = 10) int patch) {
        
        String version = String.format("%d.%d.%d", major, minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        assertThat(detector.getMajorVersion()).isEqualTo(major);
        assertThat(detector.getMinorVersion()).isEqualTo(minor);
        assertThat(detector.getPatchVersion()).isEqualTo(patch);
        assertThat(detector.getMinecraftVersion()).isEqualTo(version);
    }
    
    // ==================== Version Range Detection Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void legacyVersionsAreCorrectlyIdentified(
            @ForAll @IntRange(min = 14, max = 19) int minor,
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        assertThat(detector.getVersionRange())
                .as("Version %s should be in LEGACY range", version)
                .isEqualTo(VersionRange.LEGACY_1_14_1_19);
        assertThat(detector.isSupported()).isTrue();
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void modernVersionsAreCorrectlyIdentified(
            @ForAll @IntRange(min = 20, max = 21) int minor,
            @ForAll @IntRange(min = 0, max = 6) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        assertThat(detector.getVersionRange())
                .as("Version %s should be in MODERN range", version)
                .isEqualTo(VersionRange.MODERN_1_20_PLUS);
        assertThat(detector.isSupported()).isTrue();
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void unsupportedVersionsAreCorrectlyIdentified(
            @ForAll @IntRange(min = 7, max = 13) int minor,
            @ForAll @IntRange(min = 0, max = 4) int patch) {
        
        String version = String.format("1.%d.%d", minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        assertThat(detector.getVersionRange())
                .as("Version %s should be UNSUPPORTED", version)
                .isEqualTo(VersionRange.UNSUPPORTED);
        assertThat(detector.isSupported()).isFalse();
    }
    
    // ==================== Version Comparison Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void isAtLeastWorksCorrectlyForSameVersion(
            @ForAll @IntRange(min = 1, max = 2) int major,
            @ForAll @IntRange(min = 14, max = 21) int minor,
            @ForAll @IntRange(min = 0, max = 10) int patch) {
        
        String version = String.format("%d.%d.%d", major, minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // A version should always be at least itself
        assertThat(detector.isAtLeast(major, minor, patch))
                .as("Version %s should be at least %d.%d.%d", version, major, minor, patch)
                .isTrue();
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void isAtLeastWorksCorrectlyForLowerVersions(
            @ForAll @IntRange(min = 1, max = 2) int major,
            @ForAll @IntRange(min = 15, max = 21) int minor,
            @ForAll @IntRange(min = 1, max = 10) int patch) {
        
        String version = String.format("%d.%d.%d", major, minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // A version should be at least any lower version
        assertThat(detector.isAtLeast(major, minor - 1))
                .as("Version %s should be at least %d.%d", version, major, minor - 1)
                .isTrue();
        assertThat(detector.isAtLeast(major, minor, patch - 1))
                .as("Version %s should be at least %d.%d.%d", version, major, minor, patch - 1)
                .isTrue();
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void isBeforeWorksCorrectlyForHigherVersions(
            @ForAll @IntRange(min = 1, max = 2) int major,
            @ForAll @IntRange(min = 14, max = 20) int minor,
            @ForAll @IntRange(min = 0, max = 9) int patch) {
        
        String version = String.format("%d.%d.%d", major, minor, patch);
        VersionDetector detector = new VersionDetector(version);
        
        // A version should be before any higher version
        assertThat(detector.isBefore(major, minor + 1))
                .as("Version %s should be before %d.%d", version, major, minor + 1)
                .isTrue();
        assertThat(detector.isBefore(major, minor, patch + 1))
                .as("Version %s should be before %d.%d.%d", version, major, minor, patch + 1)
                .isTrue();
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void compareVersionsIsConsistent(
            @ForAll @IntRange(min = 1, max = 2) int major1,
            @ForAll @IntRange(min = 14, max = 21) int minor1,
            @ForAll @IntRange(min = 0, max = 10) int patch1,
            @ForAll @IntRange(min = 1, max = 2) int major2,
            @ForAll @IntRange(min = 14, max = 21) int minor2,
            @ForAll @IntRange(min = 0, max = 10) int patch2) {
        
        String version1 = String.format("%d.%d.%d", major1, minor1, patch1);
        String version2 = String.format("%d.%d.%d", major2, minor2, patch2);
        
        int comparison = VersionDetector.compareVersions(version1, version2);
        int reverseComparison = VersionDetector.compareVersions(version2, version1);
        
        // Comparison should be antisymmetric
        if (comparison > 0) {
            assertThat(reverseComparison).isLessThan(0);
        } else if (comparison < 0) {
            assertThat(reverseComparison).isGreaterThan(0);
        } else {
            assertThat(reverseComparison).isEqualTo(0);
        }
    }
    
    // ==================== Version Range Contains Properties ====================
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void versionRangeContainsIsConsistentWithDetector(
            @ForAll @IntRange(min = 14, max = 21) int minor) {
        
        // Minecraft versions are always 1.x
        int major = 1;
        String version = String.format("%d.%d", major, minor);
        VersionDetector detector = new VersionDetector(version);
        VersionRange detectedRange = detector.getVersionRange();
        
        // The detected range should contain the version
        assertThat(detectedRange.contains(major, minor))
                .as("Range %s should contain version %s", detectedRange, version)
                .isTrue();
    }
    
    @Property(tries = 100)
    @Report(Reporting.GENERATED)
    void versionRangeForVersionIsConsistent(
            @ForAll @IntRange(min = 1, max = 2) int major,
            @ForAll @IntRange(min = 14, max = 21) int minor,
            @ForAll @IntRange(min = 0, max = 10) int patch) {
        
        String version = String.format("%d.%d.%d", major, minor, patch);
        
        // Both methods should return the same range
        VersionRange fromInts = VersionRange.forVersion(major, minor);
        VersionRange fromString = VersionRange.forVersion(version);
        
        assertThat(fromInts)
                .as("Range from ints should match range from string for %s", version)
                .isEqualTo(fromString);
    }
    
    // ==================== Edge Cases ====================
    
    @Example
    void nullVersionReturnsZeros() {
        int[] parsed = VersionDetector.parseVersion(null);
        assertThat(parsed).containsExactly(0, 0, 0);
    }
    
    @Example
    void emptyVersionReturnsZeros() {
        int[] parsed = VersionDetector.parseVersion("");
        assertThat(parsed).containsExactly(0, 0, 0);
    }
    
    @Example
    void specificVersionsAreCorrectlyClassified() {
        // Test specific known versions
        assertThat(new VersionDetector("1.14.4").getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
        assertThat(new VersionDetector("1.16.5").getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
        assertThat(new VersionDetector("1.18.2").getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
        assertThat(new VersionDetector("1.19.4").getVersionRange()).isEqualTo(VersionRange.LEGACY_1_14_1_19);
        assertThat(new VersionDetector("1.20.1").getVersionRange()).isEqualTo(VersionRange.MODERN_1_20_PLUS);
        assertThat(new VersionDetector("1.20.4").getVersionRange()).isEqualTo(VersionRange.MODERN_1_20_PLUS);
        assertThat(new VersionDetector("1.21").getVersionRange()).isEqualTo(VersionRange.MODERN_1_20_PLUS);
    }
    
    @Example
    void versionRangeDescriptionIsNotEmpty() {
        VersionDetector detector = new VersionDetector("1.20.4");
        assertThat(detector.getVersionRangeDescription()).isNotEmpty();
        assertThat(detector.toString()).contains("1.20.4");
    }
}
