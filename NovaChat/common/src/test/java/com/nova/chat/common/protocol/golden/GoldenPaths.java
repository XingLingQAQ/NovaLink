package com.nova.chat.common.protocol.golden;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the repository-level golden byte directory {@code test/protocol-golden}.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>System property {@code novalink.repo.root} (absolute path to the repo root)</li>
 *   <li>Walk upwards from the current working directory (the Gradle test working
 *       directory is the module dir {@code NovaChat/common}) until a directory
 *       containing {@code settings.gradle} or {@code .git} is found.</li>
 * </ol>
 */
public final class GoldenPaths {

    public static final String GOLDEN_DIR_RELATIVE = "test/protocol-golden";

    private GoldenPaths() {
    }

    /**
     * @return the repository root directory
     * @throws IllegalStateException if the root cannot be located
     */
    public static Path repoRoot() {
        String override = System.getProperty("novalink.repo.root");
        if (override != null && !override.isBlank()) {
            Path p = Paths.get(override).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
            throw new IllegalStateException("novalink.repo.root does not exist: " + p);
        }

        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("settings.gradle"))
                    || Files.exists(current.resolve("settings.gradle.kts"))
                    || Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Could not locate repository root (no settings.gradle/.git above "
                        + Paths.get("").toAbsolutePath() + "). Set -Dnovalink.repo.root=<path>.");
    }

    /**
     * @return the golden byte directory {@code <repo>/test/protocol-golden}
     */
    public static Path goldenDir() {
        return repoRoot().resolve(GOLDEN_DIR_RELATIVE);
    }
}
