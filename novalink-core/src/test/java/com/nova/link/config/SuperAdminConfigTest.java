package com.nova.link.config;

import com.nova.link.auth.AuthManager;
import com.nova.link.auth.SuperAdminCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for super-admin config parsing/saving with the optional
 * human-readable {@code username} and plain-text {@code password} fields.
 *
 * Covers:
 *  (a) username present  -> web login uses username
 *  (b) username absent   -> web login uses UUID (backward compat)
 *  (c) plain password     -> SHA-256 hashed at load time
 *  (d) password-hash      -> used as-is
 *
 * Requirements: 20.1-20.6, 2.2
 */
@DisplayName("SuperAdmin Config: username + plain-text password")
class SuperAdminConfigTest {

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path tempDir;

    private NovaLinkConfig loadFromYaml(String yaml) throws Exception {
        Path file = tempDir.resolve("novalink-test.yml");
        Files.writeString(file, yaml);
        ConfigLoader loader = new ConfigLoader(file);
        return loader.load();
    }

    @Test
    @DisplayName("(a) username present -> web-login username resolves to 'admin'")
    void usernamePresent_webLoginUsesUsername() throws Exception {
        String yaml = """
                server:
                  bind-address: 0.0.0.0
                  port: 8888
                  websocket-port: 8889
                  secret-key: change-me-in-production
                  worker-threads: 4
                  locale: zh_CN
                super-admins:
                  - uuid: "00000000-0000-0000-0000-000000000001"
                    username: admin
                    password: admin123
                """;
        NovaLinkConfig config = loadFromYaml(yaml);

        assertThat(config.getSuperAdmins()).hasSize(1);
        SuperAdminCredentials admin = config.getSuperAdmins().get(0);
        assertThat(admin.getUuid()).isEqualTo(ADMIN_UUID);
        assertThat(admin.getUsername()).isEqualTo("admin");

        // Web-login username resolution mirrors NovaLinkMain: username wins over UUID.
        String webLoginUsername = (admin.getUsername() != null && !admin.getUsername().isBlank())
                ? admin.getUsername() : admin.getUuid().toString();
        assertThat(webLoginUsername).isEqualTo("admin");
    }

    @Test
    @DisplayName("(b) username absent -> web-login username resolves to UUID string (backward compat)")
    void usernameAbsent_webLoginUsesUuid() throws Exception {
        String yaml = """
                server:
                  bind-address: 0.0.0.0
                  port: 8888
                  websocket-port: 8889
                  secret-key: change-me-in-production
                  worker-threads: 4
                  locale: zh_CN
                super-admins:
                  - uuid: "00000000-0000-0000-0000-000000000001"
                    password-hash: "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9"
                """;
        NovaLinkConfig config = loadFromYaml(yaml);

        SuperAdminCredentials admin = config.getSuperAdmins().get(0);
        assertThat(admin.getUsername()).isNull();

        String webLoginUsername = (admin.getUsername() != null && !admin.getUsername().isBlank())
                ? admin.getUsername() : admin.getUuid().toString();
        assertThat(webLoginUsername).isEqualTo(ADMIN_UUID.toString());
    }

    @Test
    @DisplayName("(c) plain password -> SHA-256 hashed at load time, matches admin123")
    void plainPassword_hashedAtLoad() throws Exception {
        String yaml = """
                server:
                  bind-address: 0.0.0.0
                  port: 8888
                  websocket-port: 8889
                  secret-key: change-me-in-production
                  worker-threads: 4
                  locale: zh_CN
                super-admins:
                  - uuid: "00000000-0000-0000-0000-000000000001"
                    username: admin
                    password: admin123
                """;
        NovaLinkConfig config = loadFromYaml(yaml);

        SuperAdminCredentials admin = config.getSuperAdmins().get(0);
        String expectedHash = AuthManager.hashPassword("admin123");
        assertThat(admin.getPasswordHash()).isEqualTo(expectedHash);

        // And the resolved hash authenticates admin123 via the AuthManager path.
        AuthManager authManager = new AuthManager();
        authManager.registerSuperAdmin("admin", admin.getPasswordHash());
        assertThat(authManager.authenticate("admin", "admin123").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("(d) password-hash present -> used as-is (not re-hashed)")
    void passwordHashPresent_usedAsIs() throws Exception {
        String precomputed = "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9";
        String yaml = """
                server:
                  bind-address: 0.0.0.0
                  port: 8888
                  websocket-port: 8889
                  secret-key: change-me-in-production
                  worker-threads: 4
                  locale: zh_CN
                super-admins:
                  - uuid: "00000000-0000-0000-0000-000000000001"
                    username: admin
                    password-hash: "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9"
                """;
        NovaLinkConfig config = loadFromYaml(yaml);

        SuperAdminCredentials admin = config.getSuperAdmins().get(0);
        assertThat(admin.getPasswordHash()).isEqualTo(precomputed);
        // If it had been re-hashed, it would no longer match the precomputed value.
        assertThat(admin.getPasswordHash()).isNotEqualTo(AuthManager.hashPassword(precomputed));
    }

    @Test
    @DisplayName("both password and password-hash present -> password-hash wins")
    void bothPresent_passwordHashWins() throws Exception {
        String precomputed = "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9";
        String yaml = """
                server:
                  bind-address: 0.0.0.0
                  port: 8888
                  websocket-port: 8889
                  secret-key: change-me-in-production
                  worker-threads: 4
                  locale: zh_CN
                super-admins:
                  - uuid: "00000000-0000-0000-0000-000000000001"
                    username: admin
                    password: shouldNotBeUsed
                    password-hash: "240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9"
                """;
        NovaLinkConfig config = loadFromYaml(yaml);

        SuperAdminCredentials admin = config.getSuperAdmins().get(0);
        assertThat(admin.getPasswordHash()).isEqualTo(precomputed);
    }

    @Test
    @DisplayName("neither password nor password-hash -> entry skipped")
    void neitherPresent_entrySkipped() throws Exception {
        String yaml = """
                server:
                  bind-address: 0.0.0.0
                  port: 8888
                  websocket-port: 8889
                  secret-key: change-me-in-production
                  worker-threads: 4
                  locale: zh_CN
                super-admins:
                  - uuid: "00000000-0000-0000-0000-000000000001"
                    username: admin
                """;
        NovaLinkConfig config = loadFromYaml(yaml);

        assertThat(config.getSuperAdmins()).isEmpty();
    }

    @Test
    @DisplayName("round-trip: plain password saved as hash, username preserved")
    void roundTrip_plainPasswordSavedAsHash_usernamePreserved() throws Exception {
        NovaLinkConfig original = NovaLinkConfig.createDefault();
        original.getSuperAdmins().clear();
        original.getSuperAdmins().add(
                new SuperAdminCredentials(ADMIN_UUID, AuthManager.hashPassword("admin123"), "admin"));

        Path file = tempDir.resolve("novalink-roundtrip.yml");
        ConfigLoader loader = new ConfigLoader(file);
        java.lang.reflect.Field field = ConfigLoader.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(loader, original);
        loader.save();

        // Reload and verify username + resolved hash survive (no plain password on disk).
        ConfigLoader loader2 = new ConfigLoader(file);
        NovaLinkConfig reloaded = loader2.load();

        assertThat(reloaded.getSuperAdmins()).hasSize(1);
        SuperAdminCredentials admin = reloaded.getSuperAdmins().get(0);
        assertThat(admin.getUsername()).isEqualTo("admin");
        assertThat(admin.getPasswordHash()).isEqualTo(AuthManager.hashPassword("admin123"));

        // The written file must not contain the plain password.
        String onDisk = Files.readString(file);
        assertThat(onDisk).doesNotContain("admin123");
        assertThat(onDisk).contains("username: admin");
    }
}
