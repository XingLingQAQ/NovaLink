package com.nova.link.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.audit.AuditEvent;
import com.nova.link.audit.AuditStore;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.config.ClientConfig;
import com.nova.link.config.ConfigManager;
import com.nova.link.config.NovaLinkConfig;
import com.nova.link.config.PanelUserConfig;
import com.nova.link.database.MemoryProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §11.6 item-20 / PANEL proposal 10 (doc-deferred sub-items 1+2+3) — pure
 * service-layer coverage for {@link ConfigPublishService}, independent of the
 * HTTP layer.
 *
 * <p>The HTTP contract (RBAC, status codes, route dispatch) is covered by
 * {@code RestApiHandlerConfigPublishTest}; this class exercises the service
 * directly against a {@link MemoryProvider} so the draft lifecycle, permission
 * separation, fail-closed save, secret preservation, and mask-at-create-time
 * semantics can be asserted at the unit level without routing through Netty.
 *
 * <p>Mirrors the scaffold of {@code ConfigHistoryServiceTest}: same @TempDir +
 * MemoryProvider + real ConfigManager + AuditStore wiring, same helper
 * conventions. Every secret-bearing assertion checks both that the plaintext
 * is absent and that the {@code "***"} sentinel is present, matching the
 * history-snapshot masking tests.
 */
@DisplayName("ConfigPublishService draft/approve/publish + backup/restore")
class ConfigPublishServiceTest {

    private static final String SECRET_KEY = "test-secret-key-at-least-32-chars-long";

    @TempDir
    Path tempDir;

    private MemoryProvider db;
    private ConfigManager configManager;
    private ConfigHistoryService historyService;
    private ConfigPublishService service;
    private AuditStore auditStore;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();
        configManager = new ConfigManager(tempDir.resolve("config-publish-service.yml"));
        configManager.load();
        // Seed a live server secret so publish/restore secret-preservation
        // tests have a real plaintext to protect.
        configManager.getConfig().getServer().setSecretKey(SECRET_KEY);
        auditStore = new AuditStore(db);
        historyService = new ConfigHistoryService(db, configManager, auditStore);
        configManager.setConfigHistoryService(historyService);
        service = new ConfigPublishService(db, configManager, historyService, auditStore);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    // ====================== draft lifecycle ======================

    @Test
    @DisplayName("createDraft persists a DRAFT with masked payload and stamps an id")
    void createDraftPersistsMaskedDraft() {
        ConfigDraft draft = service.createDraft(validYaml(), "alice");
        assertThat(draft.getId()).isPositive();
        assertThat(draft.getStatus()).isEqualTo(ConfigDraft.Status.DRAFT);
        assertThat(draft.getCreatedBy()).isEqualTo("alice");
        assertThat(draft.getApprovedBy()).isNull();
        // draftJson is masked: no plaintext secret survives.
        assertThat(draft.getDraftJson()).contains("\"***\"");
        assertThat(draft.getDraftJson()).doesNotContain(SECRET_KEY);
    }

    @Test
    @DisplayName("createDraft rejects blank YAML / blank createdBy with IllegalArgumentException")
    void createDraftValidatesInputs() {
        assertThatThrownBy(() -> service.createDraft(null, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> service.createDraft("   ", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> service.createDraft(validYaml(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("createdBy");
        assertThatThrownBy(() -> service.createDraft(validYaml(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("createdBy");
    }

    @Test
    @DisplayName("createDraft rejects structurally invalid YAML with IllegalArgumentException")
    void createDraftRejectsInvalidYaml() {
        // max_capacity = 0 fails requiredPositiveInt.
        String broken = validYaml().replace("    max_capacity: 1000\n", "    max_capacity: 0\n");
        assertThatThrownBy(() -> service.createDraft(broken, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validation failed");
    }

    @Test
    @DisplayName("approveDraft transitions DRAFT -> APPROVED and records audit")
    void approveDraftTransitionsToApproved() {
        ConfigDraft draft = service.createDraft(validYaml(), "alice");
        Optional<ConfigDraft> approved = service.approveDraft(draft.getId(), "bob");
        assertThat(approved).isPresent();
        assertThat(approved.get().getStatus()).isEqualTo(ConfigDraft.Status.APPROVED);
        assertThat(approved.get().getApprovedBy()).isEqualTo("bob");
        assertThat(approved.get().getApprovedAt()).isPositive();

        // Audit row attributed to the approver (not "system").
        List<AuditEvent> events = auditStore.list(0, 100, "bob", "settings.draft.approve");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getActor()).isEqualTo("bob");
        assertThat(events.get(0).getAction()).isEqualTo("settings.draft.approve");
        assertThat(events.get(0).getResource()).isEqualTo("draft:" + draft.getId());
    }

    @Test
    @DisplayName("approveDraft returns empty when the draft is absent")
    void approveDraftAbsentReturnsEmpty() {
        assertThat(service.approveDraft(999999L, "bob")).isEmpty();
    }

    @Test
    @DisplayName("approveDraft 403s (IllegalStateException) when approver == createdBy")
    void approveDraftRejectsSelfApproval() {
        ConfigDraft draft = service.createDraft(validYaml(), "alice");
        assertThatThrownBy(() -> service.approveDraft(draft.getId(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Approver must differ from createdBy");
    }

    @Test
    @DisplayName("approveDraft rejects a non-DRAFT draft (IllegalStateException)")
    void approveDraftRejectsNonDraft() {
        ConfigDraft draft = service.createDraft(validYaml(), "alice");
        service.approveDraft(draft.getId(), "bob");
        assertThatThrownBy(() -> service.approveDraft(draft.getId(), "carol"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in DRAFT state");
    }

    @Test
    @DisplayName("publishDraft returns -1 when the draft is absent")
    void publishDraftAbsentReturnsMinusOne() {
        assertThat(service.publishDraft(999999L, "carol")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("publishDraft returns -2 when the draft is not APPROVED")
    void publishDraftNotApprovedReturnsMinusTwo() {
        ConfigDraft draft = service.createDraft(validYaml(), "alice");
        assertThat(service.publishDraft(draft.getId(), "carol")).isEqualTo(-2L);
    }

    @Test
    @DisplayName("publishDraft applies an APPROVED draft to live, flips to PUBLISHED, and bumps the revision")
    void publishDraftAppliesApprovedDraft() {
        // Live starts with filterEnabled=true (default); the draft flips it false.
        boolean liveBefore = configManager.getConfig().getFeatures().isFilterEnabled();
        ConfigDraft draft = service.createDraft(validYamlFilterEnabled(false), "alice");
        service.approveDraft(draft.getId(), "bob");

        long revBefore = configManager.getSettingsRevision();
        long newRev = service.publishDraft(draft.getId(), "carol");
        assertThat(newRev).isGreaterThan(revBefore);
        assertThat(configManager.getConfig().getFeatures().isFilterEnabled())
                .isNotEqualTo(liveBefore)
                .isFalse();

        // The draft is now PUBLISHED.
        Optional<ConfigDraft> reloaded = service.getDraft(draft.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo(ConfigDraft.Status.PUBLISHED);
        assertThat(reloaded.get().getPublishedAt()).isPositive();

        // Audit row attributed to the publisher.
        List<AuditEvent> events = auditStore.list(0, 100, "carol", "settings.draft.publish");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getActor()).isEqualTo("carol");
        assertThat(events.get(0).getBeforeHash()).isNotNull().hasSize(64);
        assertThat(events.get(0).getAfterHash()).isNotNull().hasSize(64);
    }

    @Test
    @DisplayName("publishDraft preserves live secrets (masked draft does not overwrite live secret with ***)")
    void publishDraftPreservesLiveSecrets() {
        // Seed live secrets with known plaintext.
        NovaLinkConfig live = configManager.getConfig();
        live.getServer().setSecretKey("live-server-secret-xyz");
        live.getDatabase().getMysql().setPassword("live-mysql-pw");
        live.getDatabase().getRedis().setPassword("live-redis-pw");
        live.getClients().clear();
        live.getClients().add(client("Survival", "live-client-pw-survival"));
        UUID adminUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        live.getSuperAdmins().clear();
        live.getSuperAdmins().add(new SuperAdminCredentials(adminUuid, "live-admin-hash", "root"));
        live.getPanelUsers().clear();
        live.getPanelUsers().add(new PanelUserConfig("alice", "live-panel-hash", "ADMIN"));

        // The draft carries the MASK sentinel for every secret (masking ran
        // at create time). Publish must skip masked fields and keep the live
        // plaintext secrets. The draft YAML carries the SAME admin UUID and
        // SAME panel-user username as the live config so applySnapshot's
        // mergeSuperAdmins/mergePanelUsers can key them and preserve the live
        // passwordHash.
        ConfigDraft draft = service.createDraft(validYamlFilterEnabled(false), "alice");
        service.approveDraft(draft.getId(), "bob");
        service.publishDraft(draft.getId(), "carol");

        NovaLinkConfig restored = configManager.getConfig();
        assertThat(restored.getServer().getSecretKey()).isEqualTo("live-server-secret-xyz");
        assertThat(restored.getDatabase().getMysql().getPassword()).isEqualTo("live-mysql-pw");
        assertThat(restored.getDatabase().getRedis().getPassword()).isEqualTo("live-redis-pw");
        assertThat(restored.getClients()).hasSize(1);
        assertThat(restored.getClients().get(0).getPassword()).isEqualTo("live-client-pw-survival");
        assertThat(restored.getSuperAdmins().get(0).getPasswordHash()).isEqualTo("live-admin-hash");
        assertThat(restored.getPanelUsers().get(0).getPasswordHash()).isEqualTo("live-panel-hash");
    }

    @Test
    @DisplayName("discardDraft removes a DRAFT and records audit")
    void discardDraftRemovesDraft() {
        ConfigDraft draft = service.createDraft(validYaml(), "alice");
        boolean discarded = service.discardDraft(draft.getId(), "alice");
        assertThat(discarded).isTrue();
        assertThat(service.getDraft(draft.getId())).isEmpty();
        List<AuditEvent> events = auditStore.list(0, 100, "alice", "settings.draft.discard");
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("discardDraft returns false when the draft is absent")
    void discardDraftAbsentReturnsFalse() {
        assertThat(service.discardDraft(999999L, "alice")).isFalse();
    }

    @Test
    @DisplayName("discardDraft rejects an APPROVED draft (IllegalStateException)")
    void discardDraftRejectsApproved() {
        ConfigDraft draft = service.createDraft(validYaml(), "alice");
        service.approveDraft(draft.getId(), "bob");
        assertThatThrownBy(() -> service.discardDraft(draft.getId(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only a DRAFT can be discarded");
    }

    @Test
    @DisplayName("discardDraft rejects a PUBLISHED draft (IllegalStateException)")
    void discardDraftRejectsPublished() {
        ConfigDraft draft = service.createDraft(validYamlFilterEnabled(false), "alice");
        service.approveDraft(draft.getId(), "bob");
        service.publishDraft(draft.getId(), "carol");
        assertThatThrownBy(() -> service.discardDraft(draft.getId(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only a DRAFT can be discarded");
    }

    // ====================== backup / restore ======================

    @Test
    @DisplayName("createBackup persists a masked backup stamped with the live settingsRevision")
    void createBackupPersistsMasked() throws com.nova.link.config.ConfigException {
        configManager.save(); // bump revision so the backup captures a non-zero rev
        long liveRev = configManager.getSettingsRevision();
        ConfigBackup backup = service.createBackup("pre-deploy", "alice");
        assertThat(backup.getId()).isPositive();
        assertThat(backup.getLabel()).isEqualTo("pre-deploy");
        assertThat(backup.getSettingsRevision()).isEqualTo(liveRev);
        assertThat(backup.getCreatedBy()).isEqualTo("alice");
        // backupJson masked: no plaintext secret.
        assertThat(backup.getBackupJson()).contains("\"***\"");
        assertThat(backup.getBackupJson()).doesNotContain(SECRET_KEY);

        List<AuditEvent> events = auditStore.list(0, 100, "alice", "settings.backup.create");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getResource()).isEqualTo("backup:" + backup.getId());
    }

    @Test
    @DisplayName("createBackup rejects blank label / blank createdBy")
    void createBackupValidatesInputs() {
        assertThatThrownBy(() -> service.createBackup(null, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createBackup("  ", "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createBackup("label", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("restoreFromBackup reuses applySnapshot: live secrets survive a restore")
    void restoreFromBackupPreservesLiveSecrets() throws com.nova.link.config.ConfigException {
        NovaLinkConfig live = configManager.getConfig();
        live.getServer().setSecretKey("rotated-server-secret");
        live.getDatabase().getRedis().setPassword("rotated-redis-pw");
        live.getClients().clear();
        live.getClients().add(client("Survival", "rotated-client-pw"));
        configManager.save();
        long revAfterRotation = configManager.getSettingsRevision();

        // Take a backup NOW (masked; secrets are *** in the backup).
        ConfigBackup backup = service.createBackup("before-restore", "alice");

        // Mutate non-secret live state (flip filterEnabled) so the restore
        // has something to revert. Keep the rotated secrets in place.
        live.getFeatures().setFilterEnabled(!live.getFeatures().isFilterEnabled());
        configManager.save();
        long revAfterFlip = configManager.getSettingsRevision();
        assertThat(revAfterFlip).isGreaterThan(revAfterRotation);

        // Restore from the backup: filterEnabled reverts, secrets stay rotated.
        long newRev = service.restoreFromBackup(backup.getId(), "carol");
        assertThat(newRev).isGreaterThan(revAfterFlip);
        NovaLinkConfig restored = configManager.getConfig();
        // The backup's filterEnabled matches the value at backup time, which
        // was the default (true) — and the flip above toggled it to false,
        // so the restore reverts it back to true.
        assertThat(restored.getFeatures().isFilterEnabled()).isTrue();
        // Secrets survived (NOT overwritten with the *** sentinel).
        assertThat(restored.getServer().getSecretKey()).isEqualTo("rotated-server-secret");
        assertThat(restored.getDatabase().getRedis().getPassword()).isEqualTo("rotated-redis-pw");
        assertThat(restored.getClients()).hasSize(1);
        assertThat(restored.getClients().get(0).getPassword()).isEqualTo("rotated-client-pw");

        // Audit row attributed to the restorer.
        List<AuditEvent> events = auditStore.list(0, 100, "carol", "settings.backup.restore");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getActor()).isEqualTo("carol");
        assertThat(events.get(0).getBeforeHash()).isNotNull().hasSize(64);
        assertThat(events.get(0).getAfterHash()).isNotNull().hasSize(64);
    }

    @Test
    @DisplayName("restoreFromBackup returns -1 when the backup is absent")
    void restoreFromBackupAbsentReturnsMinusOne() {
        assertThat(service.restoreFromBackup(999999L, "carol")).isEqualTo(-1L);
    }

    @Test
    @DisplayName("listDrafts / listBackups are metadata-only (no payload) and newest-first")
    void listOperationsAreMetadataOnlyNewestFirst() {
        ConfigDraft d1 = service.createDraft(validYaml(), "alice");
        ConfigDraft d2 = service.createDraft(validYamlFilterEnabled(false), "alice");
        List<ConfigDraft> drafts = service.listDrafts(10);
        assertThat(drafts).hasSize(2);
        // Newest first: d2 has a greater createdAt (or equal, in which case the
        // tie-breaker is id descending).
        assertThat(drafts.get(0).getId()).isEqualTo(d2.getId());
        assertThat(drafts.get(1).getId()).isEqualTo(d1.getId());
        // Metadata-only: no draft_json.
        for (ConfigDraft d : drafts) {
            assertThat(d.getDraftJson()).isNull();
        }

        ConfigBackup b1 = service.createBackup("first", "alice");
        ConfigBackup b2 = service.createBackup("second", "alice");
        List<ConfigBackup> backups = service.listBackups(10);
        assertThat(backups).hasSize(2);
        assertThat(backups.get(0).getId()).isEqualTo(b2.getId());
        assertThat(backups.get(1).getId()).isEqualTo(b1.getId());
        for (ConfigBackup b : backups) {
            assertThat(b.getBackupJson()).isNull();
        }
    }

    @Test
    @DisplayName("getDraft returns the masked payload; getSnapshot-style masking coverage")
    void getDraftReturnsMaskedPayload() {
        ConfigDraft draft = service.createDraft(validYaml(), "alice");
        Optional<ConfigDraft> reloaded = service.getDraft(draft.getId());
        assertThat(reloaded).isPresent();
        // The payload is masked at create time and stays masked on read.
        assertThat(reloaded.get().getDraftJson()).contains("\"***\"");
        assertThat(reloaded.get().getDraftJson()).doesNotContain(SECRET_KEY);
        // Structural shape preserved: the masked tree still carries the
        // features block.
        JsonObject tree = JsonParser.parseString(reloaded.get().getDraftJson()).getAsJsonObject();
        assertThat(tree.has("features")).isTrue();
    }

    @Test
    @DisplayName("maskSecrets coverage: draft and backup storage never leaks any secret spelling")
    void maskSecretsCoversEverySecretField() {
        // Seed every secret-bearing field with a distinctive plaintext.
        NovaLinkConfig live = configManager.getConfig();
        live.getServer().setSecretKey("server-secret-camel");
        live.getDatabase().getMysql().setPassword("mysql-pw");
        live.getDatabase().getPostgresql().setPassword("pg-pw");
        live.getDatabase().getRedis().setPassword("redis-pw");
        live.getClients().clear();
        live.getClients().add(client("Survival", "client-pw-1"));
        live.getClients().add(client("Creative", "client-pw-2"));
        live.getSuperAdmins().clear();
        live.getSuperAdmins().add(new SuperAdminCredentials(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "admin-hash-1", "root"));
        live.getPanelUsers().clear();
        live.getPanelUsers().add(new PanelUserConfig("alice", "panel-hash-1", "ADMIN"));

        // A draft masks the supplied YAML (which itself carries the live
        // secrets via the YAML's secret-key/client entries). A backup masks
        // the live JSON. Both must redact every plaintext.
        ConfigDraft draft = service.createDraft(validYamlWithSecrets(), "alice");
        ConfigBackup backup = service.createBackup("full-coverage", "alice");

        String draftJson = draft.getDraftJson();
        String backupJson = backup.getBackupJson();
        for (String secret : new String[]{
                "server-secret-camel", "mysql-pw", "pg-pw", "redis-pw",
                "client-pw-1", "client-pw-2", "admin-hash-1", "panel-hash-1"
        }) {
            assertThat(draftJson).doesNotContain(secret);
            assertThat(backupJson).doesNotContain(secret);
        }
        assertThat(draftJson).contains("\"***\"");
        assertThat(backupJson).contains("\"***\"");
    }

    // ====================== fail-closed ======================

    @Test
    @DisplayName("save failure during publishDraft throws, leaves live config untouched, draft stays APPROVED")
    void publishDraftFailClosedOnSaveError() throws Exception {
        // Make configManager.save() throw by pointing it at a read-only path.
        // The easiest portable way is to substitute a ConfigManager whose
        // save() throws: we close the underlying file channel by replacing
        // the config path with a directory (write fails on Windows + Linux).
        ConfigManager throwingManager = new ConfigManager(
                tempDir.resolve("failing-config.yml")) {
            @Override
            public void save() throws com.nova.link.config.ConfigException {
                throw new com.nova.link.config.ConfigException("simulated save failure");
            }
        };
        throwingManager.load();
        ConfigHistoryService throwingHistory = new ConfigHistoryService(db, throwingManager, auditStore);
        throwingManager.setConfigHistoryService(throwingHistory);
        ConfigPublishService failing = new ConfigPublishService(
                db, throwingManager, throwingHistory, auditStore);

        ConfigDraft draft = failing.createDraft(validYamlFilterEnabled(false), "alice");
        failing.approveDraft(draft.getId(), "bob");
        long revBefore = throwingManager.getSettingsRevision();
        assertThatThrownBy(() -> failing.publishDraft(draft.getId(), "carol"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Publish save failed");
        // Live config revision unchanged (save threw before the bump).
        assertThat(throwingManager.getSettingsRevision()).isEqualTo(revBefore);
        // Draft stays APPROVED (publish failed before the state flip).
        Optional<ConfigDraft> reloaded = failing.getDraft(draft.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo(ConfigDraft.Status.APPROVED);
    }

    @Test
    @DisplayName("save failure during restoreFromBackup throws, live config untouched, backup retained")
    void restoreFromBackupFailClosedOnSaveError() throws com.nova.link.config.ConfigException {
        ConfigManager throwingManager = new ConfigManager(
                tempDir.resolve("failing-restore.yml")) {
            @Override
            public void save() throws com.nova.link.config.ConfigException {
                throw new com.nova.link.config.ConfigException("simulated restore save failure");
            }
        };
        throwingManager.load();
        ConfigHistoryService throwingHistory = new ConfigHistoryService(db, throwingManager, auditStore);
        throwingManager.setConfigHistoryService(throwingHistory);
        ConfigPublishService failing = new ConfigPublishService(
                db, throwingManager, throwingHistory, auditStore);

        ConfigBackup backup = failing.createBackup("pre-restore", "alice");
        long revBefore = throwingManager.getSettingsRevision();
        assertThatThrownBy(() -> failing.restoreFromBackup(backup.getId(), "carol"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Restore save failed");
        assertThat(throwingManager.getSettingsRevision()).isEqualTo(revBefore);
        // Backup still present.
        assertThat(failing.listBackups(10)).hasSize(1);
    }

    @Test
    @DisplayName("service methods throw IllegalStateException when a dependency is missing (fail-closed)")
    void missingDependenciesFailClosed() {
        ConfigPublishService noDb = new ConfigPublishService(null, configManager, historyService, auditStore);
        assertThatThrownBy(() -> noDb.createDraft(validYaml(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database provider");
        ConfigPublishService noManager = new ConfigPublishService(db, null, historyService, auditStore);
        assertThatThrownBy(() -> noManager.createDraft(validYaml(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Config manager");
        ConfigPublishService noHistory = new ConfigPublishService(db, configManager, null, auditStore);
        assertThatThrownBy(() -> noHistory.createDraft(validYaml(), "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Config history service");
    }

    // ====================== helpers ======================

    private static ClientConfig client(String username, String password) {
        ClientConfig c = new ClientConfig();
        c.setUsername(username);
        c.setPassword(password);
        return c;
    }

    /**
     * A complete, structurally-valid YAML document used as the baseline for
     * the create-draft cases. Mirrors the bundled novalink.yml template minus
     * comments so parseYaml accepts it. {@code filterEnabled} defaults to
     * {@code true} (the template default).
     */
    private static String validYaml() {
        return validYamlFilterEnabled(true);
    }

    /**
     * Variant of {@link #validYaml()} with {@code filter-enabled} set to the
     * supplied value. Used by the publish/restore tests to guarantee a
     * non-secret field flip between the live config and the draft/backup.
     *
     * <p>Carries the SAME super-admin UUID ({@code 00000000-...-000000000001})
     * and SAME panel-user username ({@code alice}) as the live seeds in the
     * secret-preservation tests so {@code applySnapshot}'s keyed merge can
     * pair the draft's masked entry with the live entry and preserve the live
     * passwordHash. The draft's {@code password-hash} value is irrelevant —
     * masking will replace it with {@code "***"} at create time, and the merge
     * skips masked fields.
     */
    private static String validYamlFilterEnabled(boolean filterEnabled) {
        return "server:\n"
                + "  bind-address: 0.0.0.0\n"
                + "  port: 8888\n"
                + "  websocket-port: 8889\n"
                + "  secret-key: change-me-in-production\n"
                + "  worker-threads: 4\n"
                + "  locale: zh_CN\n"
                + "  cors-allowed-origins:\n"
                + "    - \"https://panel.example.com\"\n"
                + "  idle-timeout-seconds: 90\n"
                + "  rest-worker-threads: 4\n"
                + "  rate-limit:\n"
                + "    messages-per-second: 10\n"
                + "    burst: 20\n"
                + "  insecure-allow-plaintext: false\n"
                + "database:\n"
                + "  type: memory\n"
                + "  mysql:\n"
                + "    host: 127.0.0.1\n"
                + "    port: 3306\n"
                + "    database: novalink\n"
                + "    username: root\n"
                + "    password: \"\"\n"
                + "    pool-size: 10\n"
                + "  postgresql:\n"
                + "    host: 127.0.0.1\n"
                + "    port: 5432\n"
                + "    database: novalink\n"
                + "    username: postgres\n"
                + "    password: \"\"\n"
                + "    pool-size: 10\n"
                + "  sqlite:\n"
                + "    file-path: data/novalink.db\n"
                + "    pool-size: 5\n"
                + "  redis:\n"
                + "    enabled: false\n"
                + "    host: 127.0.0.1\n"
                + "    port: 6379\n"
                + "    password: \"\"\n"
                + "security:\n"
                + "  allowed-ips:\n"
                + "    - 127.0.0.1\n"
                + "  ip-ban-duration: 300\n"
                + "super-admins:\n"
                + "  - uuid: 00000000-0000-0000-0000-000000000001\n"
                + "    password-hash: draft-admin-hash\n"
                + "    name: root\n"
                + "panel-users:\n"
                + "  - username: alice\n"
                + "    password-hash: draft-panel-hash\n"
                + "    role: ADMIN\n"
                + "debug: false\n"
                + "global_channels:\n"
                + "  global:\n"
                + "    display_name: 全服\n"
                + "    permission: novachat.channel.global\n"
                + "    max_capacity: 1000\n"
                + "    slow_mode: 0\n"
                + "templates:\n"
                + "  standard_local:\n"
                + "    display_name: 本地\n"
                + "    scope: SERVER\n"
                + "    max_capacity: 100\n"
                + "clients:\n"
                + "  - username: Survival\n"
                + "    password: draft-client-pw\n"
                + "features:\n"
                + "  filter-enabled: " + filterEnabled + "\n"
                + "  message-log-enabled: false\n"
                + "  cross-server-chat-enabled: true\n"
                + "  private-messages-enabled: true\n"
                + "  message-log-retention-days: 30\n"
                + "filter:\n"
                + "  words: []\n"
                + "  patterns: []\n";
    }

    /**
     * A variant of the baseline YAML that populates every secret-bearing field
     * with a distinctive plaintext, used by the maskSecrets coverage test.
     */
    private static String validYamlWithSecrets() {
        return "server:\n"
                + "  bind-address: 0.0.0.0\n"
                + "  port: 8888\n"
                + "  websocket-port: 8889\n"
                + "  secret-key: server-secret-camel\n"
                + "  worker-threads: 4\n"
                + "  locale: zh_CN\n"
                + "  cors-allowed-origins:\n"
                + "    - \"https://panel.example.com\"\n"
                + "  idle-timeout-seconds: 90\n"
                + "  rest-worker-threads: 4\n"
                + "  rate-limit:\n"
                + "    messages-per-second: 10\n"
                + "    burst: 20\n"
                + "  insecure-allow-plaintext: false\n"
                + "database:\n"
                + "  type: memory\n"
                + "  mysql:\n"
                + "    host: 127.0.0.1\n"
                + "    port: 3306\n"
                + "    database: novalink\n"
                + "    username: root\n"
                + "    password: mysql-pw\n"
                + "    pool-size: 10\n"
                + "  postgresql:\n"
                + "    host: 127.0.0.1\n"
                + "    port: 5432\n"
                + "    database: novalink\n"
                + "    username: postgres\n"
                + "    password: pg-pw\n"
                + "    pool-size: 10\n"
                + "  sqlite:\n"
                + "    file-path: data/novalink.db\n"
                + "    pool-size: 5\n"
                + "  redis:\n"
                + "    enabled: false\n"
                + "    host: 127.0.0.1\n"
                + "    port: 6379\n"
                + "    password: redis-pw\n"
                + "security:\n"
                + "  allowed-ips:\n"
                + "    - 127.0.0.1\n"
                + "  ip-ban-duration: 300\n"
                + "super-admins:\n"
                + "  - uuid: 00000000-0000-0000-0000-000000000001\n"
                + "    password-hash: admin-hash-1\n"
                + "    name: root\n"
                + "panel-users:\n"
                + "  - username: alice\n"
                + "    password-hash: panel-hash-1\n"
                + "    role: ADMIN\n"
                + "debug: false\n"
                + "global_channels:\n"
                + "  global:\n"
                + "    display_name: 全服\n"
                + "    permission: novachat.channel.global\n"
                + "    max_capacity: 1000\n"
                + "    slow_mode: 0\n"
                + "templates:\n"
                + "  standard_local:\n"
                + "    display_name: 本地\n"
                + "    scope: SERVER\n"
                + "    max_capacity: 100\n"
                + "clients:\n"
                + "  - username: Survival\n"
                + "    password: client-pw-1\n"
                + "  - username: Creative\n"
                + "    password: client-pw-2\n"
                + "features:\n"
                + "  filter-enabled: true\n"
                + "  message-log-enabled: false\n"
                + "  cross-server-chat-enabled: true\n"
                + "  private-messages-enabled: true\n"
                + "  message-log-retention-days: 30\n"
                + "filter:\n"
                + "  words: []\n"
                + "  patterns: []\n";
    }
}
