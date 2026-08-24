package com.nova.link.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-module static consistency check for the TCP listener port.
 *
 * <p>Audit issue CONFIG-001 (line 331): the backend {@code novalink.yml}
 * {@code server.port} default must equal every Bedrock client template's
 * configured {@code backend.port}. The canonical value is {@code 8888}.
 *
 * <p>This test reaches across module boundaries (StarLink backend + three
 * Bedrock client templates living under {@code NovaChat/Bedrock/...}) and
 * asserts that all four files agree. It parses the templates directly from
 * disk rather than going through each project's own loader, so a template
 * regression is caught here regardless of whether the owning project's
 * toolchain is available on this host.
 *
 * <p>The LeviLamina case is GREEN: the ACL lock on
 * {@code default-config.json} has been lifted and the template now carries
 * {@code 8888}. All four cases below are GREEN regression gates that fail
 * if any template regresses back to the legacy {@code 18888}; they must
 * not be disabled or swallowed.
 */
@DisplayName("CONFIG-001: backend default port == client template default port")
class ConfigCrossModulePortConsistencyTest {

    /** Canonical TCP port shared by the backend listener and every client template. */
    private static final int CANONICAL_PORT = 8888;

    /** Backend source of truth. */
    private static final String BACKEND_YAML =
            "StarLink/core/src/main/resources/novalink.yml";

    /** Bedrock client templates that must agree with the backend. */
    private static final String PMMP_YAML =
            "NovaChat/Bedrock/pmmp/resources/config.yml";
    private static final String ENDSTONE_YAML =
            "NovaChat/Bedrock/endstone/novachat_endstone/config/default_config.yml";
    private static final String LEVILAMINA_JSON =
            "NovaChat/Bedrock/levilamina/resources/default-config.json";

    @Test
    @DisplayName("backend novalink.yml server.port == 8888")
    void backendPortMatchesCanonical() throws Exception {
        int port = readYamlPort(repoFile(BACKEND_YAML), "server", "port");
        assertThat(port)
                .as("backend %s server.port", BACKEND_YAML)
                .isEqualTo(CANONICAL_PORT);
    }

    @Test
    @DisplayName("pmmp config.yml backend.port == 8888 == backend port")
    void pmmpPortMatchesBackend() throws Exception {
        int backendPort = readYamlPort(repoFile(BACKEND_YAML), "server", "port");
        int pmmpPort = readYamlPort(repoFile(PMMP_YAML), "backend", "port");
        assertThat(pmmpPort)
                .as("pmmp %s backend.port", PMMP_YAML)
                .isEqualTo(CANONICAL_PORT)
                .isEqualTo(backendPort);
    }

    @Test
    @DisplayName("endstone default_config.yml backend.port == 8888 == backend port")
    void endstonePortMatchesBackend() throws Exception {
        int backendPort = readYamlPort(repoFile(BACKEND_YAML), "server", "port");
        int endstonePort = readYamlPort(repoFile(ENDSTONE_YAML), "backend", "port");
        assertThat(endstonePort)
                .as("endstone %s backend.port", ENDSTONE_YAML)
                .isEqualTo(CANONICAL_PORT)
                .isEqualTo(backendPort);
    }

    /**
     * Regression gate for the LeviLamina template.
     *
     * <p>{@code default-config.json} previously carried the legacy
     * {@code 18888} while the file was ACL-locked. The ACL lock has since
     * been lifted and the template now carries {@code 8888}. This
     * assertion is a GREEN regression gate that fails if the template
     * regresses back to {@code 18888}; do NOT disable, skip, or
     * catch-and-swallow it.
     *
     * @throws Exception if the file cannot be read
     */
    @Test
    @DisplayName("levilamina default-config.json backend.port == 8888 == backend port")
    void levilaminaPortMatchesBackend() throws Exception {
        int backendPort = readYamlPort(repoFile(BACKEND_YAML), "server", "port");
        int levilaminaPort = readJsonPort(repoFile(LEVILAMINA_JSON));
        assertThat(levilaminaPort)
                .as("levilamina %s backend.port (GREEN regression gate against legacy 18888)",
                        LEVILAMINA_JSON)
                .isEqualTo(CANONICAL_PORT)
                .isEqualTo(backendPort);
    }

    // ---------- helpers ----------

    /**
     * Walks up from {@code user.dir} until it finds a directory containing a
     * {@code .git} entry (dir or file), which it treats as the repo root. This
     * makes the test robust regardless of which Gradle module's
     * {@code test} task invokes it — the working directory may be
     * {@code StarLink/core/} or the repo root, but the repo root is always
     * reachable by walking up.
     */
    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (dir != null) {
            if (Files.exists(dir.resolve(".git"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not locate repository root (.git) by walking up from user.dir="
                        + System.getProperty("user.dir")
                        + "; the test must run inside the NovaLink repo checkout.");
    }

    /** Resolves a repo-relative path, failing the test with a clear message if missing. */
    private static Path repoFile(String relative) {
        Path resolved = findRepoRoot().resolve(relative).normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException(
                    "Expected config template not found at repo-relative path: " + relative
                            + " (resolved to " + resolved + ")");
        }
        return resolved;
    }

    /** Reads {@code <section>.<portKey>} from a YAML file as an int. */
    @SuppressWarnings("unchecked")
    private static int readYamlPort(Path file, String section, String portKey) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        Object loaded = new Yaml().load(content);
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalStateException(file + ": YAML root is not a mapping");
        }
        Object sectionValue = root.get(section);
        if (!(sectionValue instanceof Map<?, ?> sectionMap)) {
            throw new IllegalStateException(
                    file + ": '" + section + "' section is missing or not a mapping");
        }
        Object portValue = sectionMap.get(portKey);
        if (!(portValue instanceof Number portNumber)) {
            throw new IllegalStateException(
                    file + ": " + section + "." + portKey
                            + " is missing or not numeric (value=" + portValue + ")");
        }
        return portNumber.intValue();
    }

    /** Reads {@code backend.port} from a JSON file as an int. */
    private static int readJsonPort(Path file) throws Exception {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(content).getAsJsonObject();
        JsonObject backend = root.getAsJsonObject("backend");
        if (backend == null) {
            throw new IllegalStateException(file + ": 'backend' object is missing");
        }
        if (!backend.has("port")) {
            throw new IllegalStateException(file + ": backend.port is missing");
        }
        return backend.get("port").getAsInt();
    }
}
