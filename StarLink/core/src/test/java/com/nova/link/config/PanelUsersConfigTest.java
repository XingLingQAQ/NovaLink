package com.nova.link.config;

import com.nova.link.auth.AuthManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the new {@code panel-users} config section (web-panel ADMIN/VIEWER
 * accounts) and {@code server.cors-allowed-origins}.
 */
@DisplayName("panel-users + cors-allowed-origins config parsing")
class PanelUsersConfigTest {

    @TempDir
    Path tempDir;

    private NovaLinkConfig loadFromYaml(String yaml) throws Exception {
        Path file = tempDir.resolve("novalink-test.yml");
        Files.writeString(file, yaml);
        return new ConfigLoader(file).load();
    }

    /** ConfigLoader.save() persists its internal config; inject it via reflection. */
    private void saveConfig(Path file, NovaLinkConfig config) throws Exception {
        ConfigLoader loader = new ConfigLoader(file);
        java.lang.reflect.Field field = ConfigLoader.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(loader, config);
        loader.save();
    }

    private static final String BASE_SERVER = """
            server:
              bind-address: 0.0.0.0
              port: 8888
              websocket-port: 8889
              secret-key: change-me-in-production
              worker-threads: 4
              locale: zh_CN
            """;

    @Test
    @DisplayName("plain password entries are hashed at load time")
    void plainPasswordHashed() throws Exception {
        NovaLinkConfig config = loadFromYaml(BASE_SERVER + """
                panel-users:
                  - username: mod
                    password: modpass
                    role: ADMIN
                """);

        assertThat(config.getPanelUsers()).hasSize(1);
        PanelUserConfig user = config.getPanelUsers().get(0);
        assertThat(user.getUsername()).isEqualTo("mod");
        assertThat(user.getRole()).isEqualTo("ADMIN");
        assertThat(user.getPasswordHash()).isEqualTo(AuthManager.hashPassword("modpass"));
    }

    @Test
    @DisplayName("password-hash entries are used as-is and win over password")
    void passwordHashUsedAsIs() throws Exception {
        String hash = AuthManager.hashPassword("realpass");
        NovaLinkConfig config = loadFromYaml(BASE_SERVER + """
                panel-users:
                  - username: watcher
                    password-hash: "%s"
                    password: ignored
                    role: viewer
                """.formatted(hash));

        assertThat(config.getPanelUsers()).hasSize(1);
        PanelUserConfig user = config.getPanelUsers().get(0);
        assertThat(user.getPasswordHash()).isEqualTo(hash);
        // Role is normalized to upper case.
        assertThat(user.getRole()).isEqualTo("VIEWER");
    }

    @Test
    @DisplayName("SUPER_ADMIN role is rejected in panel-users (reserved for super-admins)")
    void superAdminRoleRejected() throws Exception {
        NovaLinkConfig config = loadFromYaml(BASE_SERVER + """
                panel-users:
                  - username: sneaky
                    password: pass
                    role: SUPER_ADMIN
                  - username: ok
                    password: pass
                    role: ADMIN
                """);

        assertThat(config.getPanelUsers()).hasSize(1);
        assertThat(config.getPanelUsers().get(0).getUsername()).isEqualTo("ok");
    }

    @Test
    @DisplayName("invalid/missing role or missing credentials are skipped")
    void invalidEntriesSkipped() throws Exception {
        NovaLinkConfig config = loadFromYaml(BASE_SERVER + """
                panel-users:
                  - username: norole
                    password: pass
                  - username: badrole
                    password: pass
                    role: WIZARD
                  - username: nopass
                    role: ADMIN
                  - password: orphanpass
                    role: ADMIN
                """);

        assertThat(config.getPanelUsers()).isEmpty();
    }

    @Test
    @DisplayName("absent panel-users section defaults to an empty list (backward compat)")
    void absentSectionDefaultsEmpty() throws Exception {
        NovaLinkConfig config = loadFromYaml(BASE_SERVER);
        assertThat(config.getPanelUsers()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("panel-users survive a save/load round-trip with hash persisted")
    void roundTripPersistsHash() throws Exception {
        NovaLinkConfig config = loadFromYaml(BASE_SERVER + """
                panel-users:
                  - username: mod
                    password: modpass
                    role: ADMIN
                """);

        Path out = tempDir.resolve("roundtrip.yml");
        saveConfig(out, config);

        String saved = Files.readString(out);
        // Never persist the plain password; persist the resolved hash + role.
        assertThat(saved).doesNotContain("modpass");
        assertThat(saved).contains(AuthManager.hashPassword("modpass"));

        NovaLinkConfig reloaded = new ConfigLoader(out).load();
        assertThat(reloaded.getPanelUsers()).hasSize(1);
        assertThat(reloaded.getPanelUsers().get(0))
                .isEqualTo(new PanelUserConfig("mod", AuthManager.hashPassword("modpass"), "ADMIN"));
    }

    // ====================== cors-allowed-origins ======================

    @Test
    @DisplayName("cors-allowed-origins is parsed as a string list")
    void corsOriginsParsed() throws Exception {
        NovaLinkConfig config = loadFromYaml("""
                server:
                  bind-address: 0.0.0.0
                  port: 8888
                  websocket-port: 8889
                  secret-key: change-me-in-production
                  worker-threads: 4
                  locale: zh_CN
                  cors-allowed-origins:
                    - "https://panel.example.com"
                    - "http://localhost:5173"
                """);

        assertThat(config.getServer().getCorsAllowedOrigins())
                .containsExactly("https://panel.example.com", "http://localhost:5173");
    }

    @Test
    @DisplayName("absent cors-allowed-origins defaults to an explicit example origin (PANEL-011)")
    void corsOriginsDefault() throws Exception {
        // PANEL-011: the bundled template no longer ships ["*"] (wildcard).
        // An absent section inherits the template default, which is an explicit
        // example origin rather than a wildcard. The runtime still accepts "*"
        // if an operator sets it explicitly, but it is no longer the default.
        NovaLinkConfig config = loadFromYaml(BASE_SERVER);
        assertThat(config.getServer().getCorsAllowedOrigins())
                .containsExactly("https://panel.example.com");
    }

    @Test
    @DisplayName("cors-allowed-origins survives a save/load round-trip")
    void corsOriginsRoundTrip() throws Exception {
        NovaLinkConfig config = NovaLinkConfig.createDefault();
        config.getServer().setCorsAllowedOrigins(List.of("https://panel.example.com"));

        Path out = tempDir.resolve("cors-roundtrip.yml");
        saveConfig(out, config);
        NovaLinkConfig reloaded = new ConfigLoader(out).load();

        assertThat(reloaded.getServer().getCorsAllowedOrigins())
                .containsExactly("https://panel.example.com");
    }
}
