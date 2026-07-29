package com.nova.chat.common.extension;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for ExtensionMeta parsing and serialization.
 * 
 * **Feature: novachat-platform-extensions, Property 10: Extension Metadata Parsing Round-Trip**
 * **Validates: Requirements 8.4**
 * 
 * This test verifies that for any valid extension metadata, serializing to YAML
 * and parsing back produces an equivalent metadata object.
 */
class ExtensionMetaPropertyTest {

    private final ExtensionMetaParser parser = new ExtensionMetaParser();

    // ==================== Round-Trip Property ====================

    /**
     * Property 10: Extension Metadata Parsing Round-Trip
     * 
     * For any valid ExtensionMeta, serializing to YAML and parsing back
     * should produce an equivalent metadata object.
     * 
     * **Feature: novachat-platform-extensions, Property 10: Extension Metadata Parsing Round-Trip**
     * **Validates: Requirements 8.4**
     */
    @Property(tries = 100)
    void extensionMetaRoundTrip(@ForAll("validExtensionMeta") ExtensionMeta original) 
            throws ExtensionException {
        // Serialize to YAML
        String yaml = parser.toYaml(original);
        
        // Parse back from YAML
        ExtensionMeta parsed = parser.parse(yaml);
        
        // Verify all fields match
        assertThat(parsed.getId()).isEqualTo(original.getId());
        assertThat(parsed.getName()).isEqualTo(original.getName());
        assertThat(parsed.getVersion()).isEqualTo(original.getVersion());
        assertThat(parsed.getAuthor()).isEqualTo(original.getAuthor());
        assertThat(parsed.getDescription()).isEqualTo(original.getDescription());
        assertThat(parsed.getMain()).isEqualTo(original.getMain());
        assertThat(parsed.getDependencies()).isEqualTo(original.getDependencies());
    }


    /**
     * Property 10: Extension Metadata Parsing Round-Trip - Equality
     * 
     * For any valid ExtensionMeta, the round-tripped object should be
     * equal to the original using equals().
     * 
     * **Feature: novachat-platform-extensions, Property 10: Extension Metadata Parsing Round-Trip**
     * **Validates: Requirements 8.4**
     */
    @Property(tries = 100)
    void extensionMetaRoundTripEquality(@ForAll("validExtensionMeta") ExtensionMeta original) 
            throws ExtensionException {
        // Serialize to YAML
        String yaml = parser.toYaml(original);
        
        // Parse back from YAML
        ExtensionMeta parsed = parser.parse(yaml);
        
        // Verify equality
        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.hashCode()).isEqualTo(original.hashCode());
    }

    /**
     * Property 10: Extension Metadata Parsing Round-Trip - Multiple Iterations
     * 
     * For any valid ExtensionMeta, multiple round-trips should produce
     * identical results (idempotence).
     * 
     * **Feature: novachat-platform-extensions, Property 10: Extension Metadata Parsing Round-Trip**
     * **Validates: Requirements 8.4**
     */
    @Property(tries = 100)
    void extensionMetaMultipleRoundTrips(@ForAll("validExtensionMeta") ExtensionMeta original) 
            throws ExtensionException {
        // First round-trip
        String yaml1 = parser.toYaml(original);
        ExtensionMeta parsed1 = parser.parse(yaml1);
        
        // Second round-trip
        String yaml2 = parser.toYaml(parsed1);
        ExtensionMeta parsed2 = parser.parse(yaml2);
        
        // Third round-trip
        String yaml3 = parser.toYaml(parsed2);
        ExtensionMeta parsed3 = parser.parse(yaml3);
        
        // All should be equal
        assertThat(parsed1).isEqualTo(original);
        assertThat(parsed2).isEqualTo(original);
        assertThat(parsed3).isEqualTo(original);
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<ExtensionMeta> validExtensionMeta() {
        Arbitrary<String> ids = validIdentifiers();
        Arbitrary<String> names = validNames();
        Arbitrary<String> versions = validVersions();
        Arbitrary<String> authors = validAuthors();
        Arbitrary<String> descriptions = validDescriptions();
        Arbitrary<String> mainClasses = validMainClasses();
        Arbitrary<List<String>> dependencies = validDependencies();
        
        return Combinators.combine(ids, names, versions, authors, descriptions, mainClasses, dependencies)
            .as(ExtensionMeta::new);
    }

    /**
     * Generates valid extension IDs (lowercase alphanumeric with hyphens).
     */
    private Arbitrary<String> validIdentifiers() {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .ofMinLength(1)
            .ofMaxLength(32)
            .map(s -> s.replaceAll("[^a-z0-9]", ""))
            .filter(s -> !s.isEmpty())
            .map(s -> s.length() > 1 && Math.random() > 0.5 
                ? s.substring(0, s.length()/2) + "-" + s.substring(s.length()/2) 
                : s);
    }

    /**
     * Generates valid extension names.
     */
    private Arbitrary<String> validNames() {
        return Arbitraries.strings()
            .withCharRange('A', 'Z')
            .withCharRange('a', 'z')
            .withCharRange('0', '9')
            .withChars(' ')
            .ofMinLength(1)
            .ofMaxLength(64)
            .map(String::trim)
            .filter(s -> !s.isEmpty());
    }

    /**
     * Generates valid semantic versions.
     */
    private Arbitrary<String> validVersions() {
        Arbitrary<Integer> major = Arbitraries.integers().between(0, 99);
        Arbitrary<Integer> minor = Arbitraries.integers().between(0, 99);
        Arbitrary<Integer> patch = Arbitraries.integers().between(0, 99);
        
        return Combinators.combine(major, minor, patch)
            .as((ma, mi, pa) -> ma + "." + mi + "." + pa);
    }

    /**
     * Generates valid author names (can be empty).
     */
    private Arbitrary<String> validAuthors() {
        return Arbitraries.oneOf(
            Arbitraries.just(""),
            Arbitraries.strings()
                .withCharRange('A', 'Z')
                .withCharRange('a', 'z')
                .withChars(' ', '_')
                .ofMinLength(1)
                .ofMaxLength(32)
                .map(String::trim)
        );
    }

    /**
     * Generates valid descriptions (can be empty).
     */
    private Arbitrary<String> validDescriptions() {
        return Arbitraries.oneOf(
            Arbitraries.just(""),
            Arbitraries.strings()
                .withCharRange('A', 'Z')
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .withChars(' ', '.', ',')
                .ofMinLength(1)
                .ofMaxLength(128)
                .map(String::trim)
        );
    }

    /**
     * Generates valid fully qualified class names.
     */
    private Arbitrary<String> validMainClasses() {
        Arbitrary<String> packagePart = Arbitraries.strings()
            .withCharRange('a', 'z')
            .ofMinLength(2)
            .ofMaxLength(10);
        
        Arbitrary<String> className = Arbitraries.strings()
            .withCharRange('A', 'Z')
            .ofLength(1)
            .flatMap(first -> Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .ofMinLength(2)
                .ofMaxLength(15)
                .map(rest -> first + rest));
        
        return Combinators.combine(packagePart, packagePart, className)
            .as((p1, p2, c) -> "com." + p1 + "." + p2 + "." + c);
    }

    /**
     * Generates valid dependency lists.
     */
    private Arbitrary<List<String>> validDependencies() {
        return Arbitraries.oneOf(
            Arbitraries.just(new ArrayList<>()),
            validIdentifiers().list().ofMaxSize(3)
        );
    }
}
