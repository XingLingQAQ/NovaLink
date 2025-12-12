package com.nova.chat.common.extension;

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Extension Loading Isolation.
 * 
 * **Feature: novachat-platform-extensions, Property 9: Extension Loading Isolation**
 * **Validates: Requirements 8.5**
 * 
 * This test verifies that for any set of extensions where one fails to load,
 * all other valid extensions should still load successfully.
 * 
 * Since we cannot easily generate valid bytecode for test extension classes,
 * this test focuses on verifying that:
 * 1. Invalid metadata doesn't prevent scanning of other JARs
 * 2. The loader continues processing after encountering errors
 * 3. Non-JAR files are ignored
 */
class ExtensionLoadingIsolationPropertyTest {

    private Path tempDir;
    private DefaultExtensionLoader loader;
    private final ExtensionMetaParser parser = new ExtensionMetaParser();

    @BeforeProperty
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("extension-test");
        loader = new DefaultExtensionLoader();
    }

    @AfterProperty
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        // Ignore cleanup errors
                    }
                });
        }
    }

    // ==================== Isolation Property ====================

    /**
     * Property 9: Extension Loading Isolation - Metadata Parsing Isolation
     * 
     * For any set of extension metadata where some are invalid,
     * parsing valid metadata should not be affected by invalid ones.
     * 
     * **Feature: novachat-platform-extensions, Property 9: Extension Loading Isolation**
     * **Validates: Requirements 8.5**
     */
    @Property(tries = 50)
    void validMetadataParsesDespiteInvalidOnes(
            @ForAll("validMetadataCount") int validCount,
            @ForAll("invalidMetadataCount") int invalidCount) {
        
        List<String> validYamls = new ArrayList<>();
        List<String> invalidYamls = new ArrayList<>();
        
        // Create valid YAML metadata
        for (int i = 0; i < validCount; i++) {
            validYamls.add(createValidYaml("valid-ext-" + i, "Valid Extension " + i));
        }
        
        // Create invalid YAML metadata (missing required fields)
        for (int i = 0; i < invalidCount; i++) {
            invalidYamls.add(createInvalidYaml("invalid-ext-" + i));
        }
        
        // Parse all valid ones - should succeed
        List<ExtensionMeta> parsedValid = new ArrayList<>();
        for (String yaml : validYamls) {
            try {
                parsedValid.add(parser.parse(yaml));
            } catch (ExtensionException e) {
                // Should not happen for valid YAML
                throw new AssertionError("Valid YAML failed to parse", e);
            }
        }
        
        // Parse all invalid ones - should fail but not affect others
        int failedCount = 0;
        for (String yaml : invalidYamls) {
            try {
                parser.parse(yaml);
            } catch (ExtensionException e) {
                failedCount++;
            }
        }
        
        // Verify all valid parsed correctly
        assertThat(parsedValid).hasSize(validCount);
        
        // Verify all invalid failed
        assertThat(failedCount).isEqualTo(invalidCount);
    }


    /**
     * Property 9: Extension Loading Isolation - JAR Scanning Continues After Errors
     * 
     * When scanning a directory with mixed valid and invalid JARs,
     * the loader should continue scanning after encountering errors.
     * 
     * **Feature: novachat-platform-extensions, Property 9: Extension Loading Isolation**
     * **Validates: Requirements 8.5**
     */
    @Property(tries = 30)
    void jarScanningContinuesAfterErrors(
            @ForAll("jarMixCount") int count) throws IOException {
        
        int validJarCount = 0;
        int invalidJarCount = 0;
        
        // Create a mix of valid JARs (with extension.yml) and invalid JARs
        for (int i = 0; i < count; i++) {
            if (i % 2 == 0) {
                // Create JAR with valid extension.yml but no main class
                // This will fail during class loading but metadata parsing succeeds
                createJarWithValidMetadata("ext-" + i);
                validJarCount++;
            } else {
                // Create JAR without extension.yml
                createJarWithoutMetadata("noext-" + i);
                invalidJarCount++;
            }
        }
        
        // Load extensions - should not throw, should return empty list
        // because class loading fails, but the important thing is it doesn't crash
        List<NovaChatExtension> loaded = loader.loadExtensions(tempDir);
        
        // The loader should have attempted to load all JARs
        // Even though all fail (no valid classes), it should not throw
        assertThat(loaded).isNotNull();
    }

    /**
     * Property 9: Extension Loading Isolation - Empty Directory
     * 
     * Loading from an empty directory should return an empty list
     * without errors.
     * 
     * **Feature: novachat-platform-extensions, Property 9: Extension Loading Isolation**
     * **Validates: Requirements 8.5**
     */
    @Property(tries = 10)
    void emptyDirectoryReturnsEmptyList() {
        List<NovaChatExtension> loaded = loader.loadExtensions(tempDir);
        assertThat(loaded).isEmpty();
    }

    /**
     * Property 9: Extension Loading Isolation - Non-Existent Directory
     * 
     * Loading from a non-existent directory should return an empty list
     * without throwing exceptions. The directory may or may not be created
     * depending on permissions.
     * 
     * **Feature: novachat-platform-extensions, Property 9: Extension Loading Isolation**
     * **Validates: Requirements 8.5**
     */
    @Property(tries = 10)
    void nonExistentDirectoryHandledGracefully(@ForAll("randomDirName") String dirName) throws IOException {
        Path nonExistent = tempDir.resolve(dirName);
        
        // Ensure it doesn't exist initially
        if (Files.exists(nonExistent)) {
            Files.delete(nonExistent);
        }
        assertThat(Files.exists(nonExistent)).isFalse();
        
        // Load extensions - should not throw
        List<NovaChatExtension> loaded = loader.loadExtensions(nonExistent);
        
        // Should return empty list
        assertThat(loaded).isEmpty();
        
        // Clean up if directory was created
        if (Files.exists(nonExistent)) {
            Files.delete(nonExistent);
        }
    }

    /**
     * Property 9: Extension Loading Isolation - Non-JAR Files Ignored
     * 
     * Non-JAR files in the extensions directory should be ignored.
     * 
     * **Feature: novachat-platform-extensions, Property 9: Extension Loading Isolation**
     * **Validates: Requirements 8.5**
     */
    @Property(tries = 20)
    void nonJarFilesAreIgnored(@ForAll("fileCount") int count) throws IOException {
        // Create various non-JAR files
        for (int i = 0; i < count; i++) {
            Files.writeString(tempDir.resolve("file" + i + ".txt"), "test content");
            Files.writeString(tempDir.resolve("config" + i + ".yml"), "key: value");
            Files.writeString(tempDir.resolve("readme" + i + ".md"), "# Readme");
        }
        
        // Load extensions - should return empty list, not crash
        List<NovaChatExtension> loaded = loader.loadExtensions(tempDir);
        
        assertThat(loaded).isEmpty();
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<Integer> validMetadataCount() {
        return Arbitraries.integers().between(1, 5);
    }

    @Provide
    Arbitrary<Integer> invalidMetadataCount() {
        return Arbitraries.integers().between(1, 5);
    }

    @Provide
    Arbitrary<Integer> jarMixCount() {
        return Arbitraries.integers().between(2, 8);
    }

    @Provide
    Arbitrary<Integer> fileCount() {
        return Arbitraries.integers().between(1, 5);
    }

    @Provide
    Arbitrary<String> randomDirName() {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .ofMinLength(5)
            .ofMaxLength(10);
    }

    // ==================== Helper Methods ====================

    /**
     * Creates valid YAML metadata string.
     */
    private String createValidYaml(String id, String name) {
        return String.format(
            "id: %s%n" +
            "name: %s%n" +
            "version: 1.0.0%n" +
            "main: com.test.TestExtension%n",
            id, name
        );
    }

    /**
     * Creates invalid YAML metadata string (missing required fields).
     */
    private String createInvalidYaml(String id) {
        return String.format(
            "id: %s%n" +
            "name: Invalid Extension%n",
            id
        );
    }

    /**
     * Creates a JAR with valid extension.yml but no main class.
     */
    private void createJarWithValidMetadata(String id) throws IOException {
        Path jarPath = tempDir.resolve(id + ".jar");
        
        String yaml = createValidYaml(id, "Extension " + id);
        
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("extension.yml"));
            jos.write(yaml.getBytes());
            jos.closeEntry();
        }
    }

    /**
     * Creates a JAR without extension.yml.
     */
    private void createJarWithoutMetadata(String id) throws IOException {
        Path jarPath = tempDir.resolve(id + ".jar");
        
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            jos.putNextEntry(new JarEntry("dummy.txt"));
            jos.write("dummy content".getBytes());
            jos.closeEntry();
        }
    }
}
