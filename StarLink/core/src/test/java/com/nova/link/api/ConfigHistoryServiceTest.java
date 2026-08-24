package com.nova.link.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.audit.AuditEvent;
import com.nova.link.audit.AuditStore;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.config.ClientConfig;
import com.nova.link.config.ConfigManager;
import com.nova.link.config.ConfigSnapshot;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §11.6 Project 20 / PANEL proposal 10 — pure service-layer coverage for
 * {@link ConfigHistoryService}, independent of the HTTP layer.
 *
 * <p>The HTTP contract (RBAC, status codes, route dispatch) is covered by
 * {@code RestApiHandlerTest}; this class exercises the service directly against
 * a {@link MemoryProvider} so the masking, diff, atomic-rollback, and
 * append-only / audit semantics can be asserted at the unit level without
 * routing through Netty.
 *
 * <p>Acceptance hook: §11.6 @804 — "validation/diff/approval/atomic
 * publish/rollback recovery". The rollback-recovery path is the one the HTTP
 * tests cannot reach: it asserts that a rollback onto a live config whose
 * secrets differ from the snapshot leaves the live secrets in place (fail-safe
 * against restoring a stale plaintext secret that has since been rotated).
 */
@DisplayName("ConfigHistoryService masking + diff + atomic rollback")
class ConfigHistoryServiceTest {

    @TempDir
    Path tempDir;

    private MemoryProvider db;
    private ConfigManager configManager;
    private ConfigHistoryService service;
    private AuditStore auditStore;

    /**
     * Monotonic counter feeding {@link #record(String)} revisions that stay
     * clear of {@code configManager.getSettingsRevision()} (which the rollback
     * tests bump via {@code save()}). Keeps diff-test snapshots from colliding
     * on the same revision.
     */
    private long nextTestRevision = 1000L;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();
        configManager = new ConfigManager(tempDir.resolve("config-history-service.yml"));
        configManager.load();
        auditStore = new AuditStore(db);
        service = new ConfigHistoryService(db, configManager, auditStore);
        // Wire the service so save() auto-records snapshots (same wiring the
        // REST layer's lazy factory performs).
        configManager.setConfigHistoryService(service);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    // ====================== masking ======================

    @Test
    @DisplayName("maskSecrets replaces every secret field spelling with the sentinel")
    void maskSecretsReplacesEverySecretField() {
        // A config JSON carrying every secret-bearing field, in BOTH the
        // camelCase (live Gson) and kebab-case (reloaded YAML alias) shapes
        // for the server secret so both spellings are exercised.
        String json = "{"
                + "\"server\":{"
                + "\"secretKey\":\"live-server-secret-camel\","
                + "\"secret-key\":\"live-server-secret-kebab\","
                + "\"port\":8888"
                + "},"
                + "\"database\":{"
                + "\"mysql\":{\"password\":\"mysql-pw\",\"host\":\"127.0.0.1\"},"
                + "\"postgresql\":{\"password\":\"pg-pw\"},"
                + "\"redis\":{\"password\":\"redis-pw\",\"enabled\":true}"
                + "},"
                + "\"clients\":[{\"username\":\"Survival\",\"password\":\"client-pw-1\"},"
                + "{\"username\":\"Creative\",\"password\":\"client-pw-2\"}],"
                + "\"super-admins\":[{\"uuid\":\"00000000-0000-0000-0000-000000000001\","
                + "\"password-hash\":\"admin-hash-1\"}],"
                + "\"panel-users\":[{\"username\":\"alice\",\"password-hash\":\"panel-hash-1\","
                + "\"role\":\"ADMIN\"}]"
                + "}";

        String masked = service.maskSecrets(json);

        // No plaintext secret survives.
        assertThat(masked)
                .doesNotContain("live-server-secret-camel")
                .doesNotContain("live-server-secret-kebab")
                .doesNotContain("mysql-pw")
                .doesNotContain("pg-pw")
                .doesNotContain("redis-pw")
                .doesNotContain("client-pw-1")
                .doesNotContain("client-pw-2")
                .doesNotContain("admin-hash-1")
                .doesNotContain("panel-hash-1");
        // The mask sentinel is present.
        assertThat(masked).contains("\"***\"");

        JsonObject root = JsonParser.parseString(masked).getAsJsonObject();
        // server: both spellings masked.
        JsonObject server = root.getAsJsonObject("server");
        assertThat(server.get("secretKey").getAsString()).isEqualTo("***");
        assertThat(server.get("secret-key").getAsString()).isEqualTo("***");
        // The non-secret sibling survives.
        assertThat(server.get("port").getAsInt()).isEqualTo(8888);
        // database passwords masked; hosts survive.
        JsonObject database = root.getAsJsonObject("database");
        assertThat(database.getAsJsonObject("mysql").get("password").getAsString()).isEqualTo("***");
        assertThat(database.getAsJsonObject("mysql").get("host").getAsString()).isEqualTo("127.0.0.1");
        assertThat(database.getAsJsonObject("postgresql").get("password").getAsString()).isEqualTo("***");
        assertThat(database.getAsJsonObject("redis").get("password").getAsString()).isEqualTo("***");
        assertThat(database.getAsJsonObject("redis").get("enabled").getAsBoolean()).isTrue();
        // Array leaves: every element masked; non-secret siblings survive.
        JsonArray clients = root.getAsJsonArray("clients");
        assertThat(clients.size()).isEqualTo(2);
        for (JsonElement el : clients) {
            assertThat(el.getAsJsonObject().get("password").getAsString()).isEqualTo("***");
            assertThat(el.getAsJsonObject().has("username")).isTrue();
        }
        assertThat(root.getAsJsonArray("super-admins").get(0).getAsJsonObject()
                .get("password-hash").getAsString()).isEqualTo("***");
        assertThat(root.getAsJsonArray("panel-users").get(0).getAsJsonObject()
                .get("password-hash").getAsString()).isEqualTo("***");
        // Non-secret sibling on the panel-user survives.
        assertThat(root.getAsJsonArray("panel-users").get(0).getAsJsonObject()
                .get("role").getAsString()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("maskSecrets leaves non-secret config (features, filter, templates) intact")
    void maskSecretsPreservesNonSecretConfig() {
        String json = "{"
                + "\"features\":{\"filterEnabled\":true,\"messageLogRetentionDays\":14},"
                + "\"filter\":{\"words\":[\"badword\"],\"patterns\":[]},"
                + "\"templates\":{\"standard_local\":{\"scope\":\"SERVER\"}}"
                + "}";
        String masked = service.maskSecrets(json);
        // Round-trip identity for a config with no secret fields: the masked
        // tree equals the input tree (modulo formatting).
        JsonObject original = JsonParser.parseString(json).getAsJsonObject();
        JsonObject maskedTree = JsonParser.parseString(masked).getAsJsonObject();
        assertThat(maskedTree).isEqualTo(original);
    }

    @Test
    @DisplayName("maskSecrets tolerates null/blank/unparseable input without throwing")
    void maskSecretsToleratesBadInput() {
        assertThat(service.maskSecrets(null)).isNull();
        assertThat(service.maskSecrets("")).isEmpty();
        assertThat(service.maskSecrets("   ")).isEqualTo("   ");
        // Unparseable JSON is returned as-is (the caller still persists the
        // original — a malformed snapshot beats dropping the history row).
        String malformed = "{not valid json";
        assertThat(service.maskSecrets(malformed)).isEqualTo(malformed);
        // A non-object root (array) is returned unchanged.
        String array = "[1,2,3]";
        assertThat(service.maskSecrets(array)).isEqualTo(array);
    }

    @Test
    @DisplayName("maskSecrets masks a missing-but-expected secret field to keep the structural shape")
    void maskSecretsMasksAbsentSecretField() {
        // server with no secret-key at all: masking still injects the sentinel
        // so a downstream structural diff reports the path consistently.
        String json = "{\"server\":{\"port\":8888},"
                + "\"database\":{\"mysql\":{\"host\":\"h\"}}}";
        String masked = service.maskSecrets(json);
        JsonObject server = JsonParser.parseString(masked).getAsJsonObject().getAsJsonObject("server");
        assertThat(server.has("secretKey")).isTrue();
        assertThat(server.has("secret-key")).isTrue();
        assertThat(server.get("secretKey").getAsString()).isEqualTo("***");
        assertThat(server.get("secret-key").getAsString()).isEqualTo("***");
    }

    // ====================== diff ======================

    @Test
    @DisplayName("diffSettings reports added/removed/changed leaves between two revisions")
    void diffSettingsReportsAddedRemovedChanged() throws Exception {
        long from = record("{\"features\":{\"filterEnabled\":false,\"messageLogRetentionDays\":30},"
                + "\"server\":{\"port\":8888}}");
        long to = record("{\"features\":{\"filterEnabled\":true,\"messageLogRetentionDays\":30,"
                + "\"crossServerChatEnabled\":true},"
                + "\"server\":{\"port\":9999}}");

        Map<String, Object> diff = service.diffSettings(from, to);
        assertThat(diff).isNotEmpty();
        assertThat(((Number) diff.get("fromRevision")).longValue()).isEqualTo(from);
        assertThat(((Number) diff.get("toRevision")).longValue()).isEqualTo(to);

        JsonArray changed = (JsonArray) diff.get("changed");
        JsonArray added = (JsonArray) diff.get("added");
        JsonArray removed = (JsonArray) diff.get("removed");

        // filterEnabled false->true and server.port 8888->9999 are both changed.
        boolean filterChanged = false;
        boolean portChanged = false;
        for (JsonElement el : changed) {
            JsonObject pair = el.getAsJsonObject();
            String path = pair.get("path").getAsString();
            if (path.equals("features.filterEnabled")) {
                assertThat(pair.get("from").getAsBoolean()).isFalse();
                assertThat(pair.get("to").getAsBoolean()).isTrue();
                filterChanged = true;
            }
            if (path.equals("server.port")) {
                assertThat(pair.get("from").getAsInt()).isEqualTo(8888);
                assertThat(pair.get("to").getAsInt()).isEqualTo(9999);
                portChanged = true;
            }
        }
        assertThat(filterChanged).isTrue();
        assertThat(portChanged).isTrue();
        // crossServerChatEnabled is present in `to` but absent in `from` -> added.
        assertThat(added.toString()).contains("true");
        // messageLogRetentionDays is unchanged -> not in any bucket.
        assertThat(removed.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("diffSettings output never leaks plaintext secrets carried in snapshots")
    void diffSettingsOutputIsMasked() throws Exception {
        // Snapshots that (hypothetically, due to a regression) carried plaintext
        // redis password + server secret-key. maskSecrets runs at recordSnapshot
        // time, so both fields become the MASK sentinel in storage and the diff
        // sees only masked values — equal on both sides, so they do NOT surface
        // as changed entries. A regression that skipped masking would make them
        // plaintext AND differing, surfacing them in the diff.
        long from = record("{\"database\":{\"redis\":{\"password\":\"leaked-from-pw\"}},"
                + "\"server\":{\"secret-key\":\"leaked-from-key\"}}");
        long to = record("{\"database\":{\"redis\":{\"password\":\"leaked-to-pw\"}},"
                + "\"server\":{\"secret-key\":\"leaked-to-key\"}}");

        Map<String, Object> diff = service.diffSettings(from, to);
        assertThat(diff).isNotEmpty();
        // No plaintext secret leaks anywhere in the serialized diff payload.
        String serialized = new com.google.gson.Gson().toJson(diff);
        assertThat(serialized)
                .doesNotContain("leaked-from-pw")
                .doesNotContain("leaked-to-pw")
                .doesNotContain("leaked-from-key")
                .doesNotContain("leaked-to-key");
        // Because both snapshots masked the same secret fields to the same
        // sentinel, the diff reports no changed leaves for those paths. The
        // proof of masking is the absence of plaintext above; the diff buckets
        // themselves are empty (no added/removed/changed).
        assertThat(((JsonArray) diff.get("added")).isEmpty()).isTrue();
        assertThat(((JsonArray) diff.get("removed")).isEmpty()).isTrue();
        assertThat(((JsonArray) diff.get("changed")).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("diffSettings returns empty when either revision is missing")
    void diffSettingsReturnsEmptyWhenMissing() throws Exception {
        long from = record("{\"features\":{\"filterEnabled\":false}}");
        // to revision 999999 does not exist.
        Map<String, Object> diff = service.diffSettings(from, 999999L);
        assertThat(diff).isEmpty();
        // Both missing.
        assertThat(service.diffSettings(888888L, 999999L)).isEmpty();
    }

    @Test
    @DisplayName("diffSettings between identical revisions has empty added/removed/changed")
    void diffSettingsIdenticalIsEmpty() throws Exception {
        long rev = record("{\"features\":{\"filterEnabled\":true},\"server\":{\"port\":8888}}");
        Map<String, Object> diff = service.diffSettings(rev, rev);
        assertThat(((JsonArray) diff.get("added")).isEmpty()).isTrue();
        assertThat(((JsonArray) diff.get("removed")).isEmpty()).isTrue();
        assertThat(((JsonArray) diff.get("changed")).isEmpty()).isTrue();
    }

    // ====================== rollback ======================

    @Test
    @DisplayName("rollback preserves live secrets for masked fields (client/db/server/admin/panel)")
    void rollbackPreservesLiveSecrets() throws Exception {
        // Seed the live config with real-looking secrets and a client roster.
        NovaLinkConfig live = configManager.getConfig();
        live.getServer().setSecretKey("live-server-secret-xyz");
        live.getDatabase().getMysql().setPassword("live-mysql-pw");
        live.getDatabase().getPostgresql().setPassword("live-pg-pw");
        live.getDatabase().getRedis().setPassword("live-redis-pw");
        live.getClients().clear();
        live.getClients().add(client("Survival", "live-client-pw-survival"));
        live.getClients().add(client("Creative", "live-client-pw-creative"));
        UUID adminUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        live.getSuperAdmins().clear();
        live.getSuperAdmins().add(new SuperAdminCredentials(adminUuid, "live-admin-hash", "root"));
        live.getPanelUsers().clear();
        live.getPanelUsers().add(new PanelUserConfig("alice", "live-panel-hash", "ADMIN"));

        // r1: snapshot the live config (secrets are masked in storage).
        configManager.save();
        long r1 = configManager.getSettingsRevision();

        // Mutate a NON-secret field and save again -> r2 becomes active.
        boolean r1FilterEnabled = live.getFeatures().isFilterEnabled();
        live.getFeatures().setFilterEnabled(!r1FilterEnabled);
        // Rotate every live secret so a naive "restore from snapshot" would
        // overwrite them with the masked sentinel or stale value.
        live.getServer().setSecretKey("rotated-server-secret");
        live.getDatabase().getMysql().setPassword("rotated-mysql-pw");
        live.getDatabase().getPostgresql().setPassword("rotated-pg-pw");
        live.getDatabase().getRedis().setPassword("rotated-redis-pw");
        live.getClients().get(0).setPassword("rotated-client-pw-survival");
        live.getSuperAdmins().set(0, new SuperAdminCredentials(adminUuid, "rotated-admin-hash", "root"));
        live.getPanelUsers().set(0, new PanelUserConfig("alice", "rotated-panel-hash", "ADMIN"));
        configManager.save();
        long r2 = configManager.getSettingsRevision();
        assertThat(r2).isGreaterThan(r1);

        // Roll back to r1. The non-secret filterEnabled flip reverts, but the
        // rotated live secrets must survive (they are newer than the snapshot).
        long newRev = service.rollback(r1, "operator");
        assertThat(newRev).isGreaterThan(r2);

        NovaLinkConfig restored = configManager.getConfig();
        // Non-secret field reverted to r1's value (filterEnabled flips back).
        assertThat(restored.getFeatures().isFilterEnabled()).isEqualTo(r1FilterEnabled);
        // Every live secret is preserved — NOT the masked sentinel, NOT the
        // r1 value (which was rotated past).
        assertThat(restored.getServer().getSecretKey()).isEqualTo("rotated-server-secret");
        assertThat(restored.getDatabase().getMysql().getPassword()).isEqualTo("rotated-mysql-pw");
        assertThat(restored.getDatabase().getPostgresql().getPassword()).isEqualTo("rotated-pg-pw");
        assertThat(restored.getDatabase().getRedis().getPassword()).isEqualTo("rotated-redis-pw");
        assertThat(restored.getClients()).hasSize(2);
        assertThat(restored.getClients().get(0).getUsername()).isEqualTo("Survival");
        assertThat(restored.getClients().get(0).getPassword()).isEqualTo("rotated-client-pw-survival");
        assertThat(restored.getClients().get(1).getUsername()).isEqualTo("Creative");
        assertThat(restored.getClients().get(1).getPassword()).isEqualTo("live-client-pw-creative");
        assertThat(restored.getSuperAdmins().get(0).getUuid()).isEqualTo(adminUuid);
        assertThat(restored.getSuperAdmins().get(0).getPasswordHash()).isEqualTo("rotated-admin-hash");
        assertThat(restored.getPanelUsers().get(0).getUsername()).isEqualTo("alice");
        assertThat(restored.getPanelUsers().get(0).getPasswordHash()).isEqualTo("rotated-panel-hash");
    }

    @Test
    @DisplayName("rollback appends a new active row and deactivates prior rows (append-only)")
    void rollbackIsAppendOnlyWithSingleActiveRow() throws Exception {
        configManager.save();
        long r1 = configManager.getSettingsRevision();
        configManager.getConfig().getFeatures().setFilterEnabled(
                !configManager.getConfig().getFeatures().isFilterEnabled());
        configManager.save();
        long r2 = configManager.getSettingsRevision();

        int beforeCount = db.countConfigSnapshots();
        long newRev = service.rollback(r1, "operator");
        assertThat(newRev).isGreaterThan(r2);

        // Append-only: no row deleted, plus the rollback row added (the save()
        // inside rollback bumps the revision and records a snapshot, then the
        // rollback path appends a second row tagged with newRev).
        int afterCount = db.countConfigSnapshots();
        assertThat(afterCount).isEqualTo(beforeCount + 2);
        // Exactly one row is active, and it is the rollback row.
        List<ConfigSnapshot> history = service.getHistory(100);
        long activeCount = history.stream().filter(ConfigSnapshot::isActive).count();
        assertThat(activeCount).isEqualTo(1);
        ConfigSnapshot activeRow = history.stream()
                .filter(ConfigSnapshot::isActive)
                .findFirst()
                .orElseThrow();
        assertThat(activeRow.getRevision()).isEqualTo(newRev);
        // The rolled-back-to target is now inactive.
        Optional<ConfigSnapshot> r1Snap = service.getSnapshot(r1);
        assertThat(r1Snap).isPresent();
        assertThat(r1Snap.get().isActive()).isFalse();
    }

    @Test
    @DisplayName("rollback writes an audit event with action=settings.rollback")
    void rollbackWritesAuditEvent() throws Exception {
        configManager.save();
        long r1 = configManager.getSettingsRevision();
        configManager.getConfig().getFeatures().setFilterEnabled(
                !configManager.getConfig().getFeatures().isFilterEnabled());
        configManager.save();

        int auditBefore = auditStore.count(null, "settings.rollback");
        service.rollback(r1, "operator");
        int auditAfter = auditStore.count(null, "settings.rollback");
        assertThat(auditAfter).isEqualTo(auditBefore + 1);

        // The audit row carries the expected shape.
        List<AuditEvent> events = auditStore.list(0, 100, "operator", "settings.rollback");
        assertThat(events).isNotEmpty();
        AuditEvent event = events.get(0);
        assertThat(event.getAction()).isEqualTo("settings.rollback");
        assertThat(event.getActor()).isEqualTo("operator");
        assertThat(event.getResult()).isEqualTo("success");
        assertThat(event.getResource()).contains("rollback").contains(Long.toString(r1));
        // beforeHash/afterHash are SHA-256 hexes of the masked configs.
        assertThat(event.getBeforeHash()).isNotNull();
        assertThat(event.getAfterHash()).isNotNull();
        assertThat(event.getBeforeHash()).hasSize(64);
        assertThat(event.getAfterHash()).hasSize(64);
    }

    @Test
    @DisplayName("rollback returns -1 when the target is already the active row")
    void rollbackReturnsAlreadyActive() throws Exception {
        configManager.save();
        long active = configManager.getSettingsRevision();
        // Rolling back to the currently-active revision is a no-op.
        long result = service.rollback(active, "operator");
        assertThat(result).isEqualTo(-1L);
    }

    @Test
    @DisplayName("rollback returns -2 when the target revision does not exist")
    void rollbackReturnsNotFound() {
        long result = service.rollback(999999L, "operator");
        assertThat(result).isEqualTo(-2L);
    }

    @Test
    @DisplayName("rollback throws IllegalStateException when a dependency is missing (fail-closed)")
    void rollbackFailsClosedWithoutDependencies() {
        ConfigHistoryService noDb = new ConfigHistoryService(null, configManager, auditStore);
        assertThatThrownBy(() -> noDb.rollback(1L, "operator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Database provider");
    }

    // ====================== history read path ======================

    @Test
    @DisplayName("getHistory returns metadata-only snapshots newest first")
    void getHistoryIsMetadataOnlyAndNewestFirst() throws Exception {
        configManager.save();
        configManager.getConfig().getFeatures().setFilterEnabled(
                !configManager.getConfig().getFeatures().isFilterEnabled());
        configManager.save();
        configManager.getConfig().getFeatures().setFilterEnabled(
                !configManager.getConfig().getFeatures().isFilterEnabled());
        configManager.save();

        List<ConfigSnapshot> history = service.getHistory(100);
        assertThat(history.size()).isGreaterThanOrEqualTo(3);
        // Newest first: revisions strictly non-increasing.
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i - 1).getCreatedAt())
                    .isGreaterThanOrEqualTo(history.get(i).getCreatedAt());
        }
        // Metadata-only: no snapshot_json payload on any row.
        for (ConfigSnapshot s : history) {
            assertThat(s.getSnapshotJson()).isNull();
        }
        // Exactly one active row across the full history.
        long activeCount = history.stream().filter(ConfigSnapshot::isActive).count();
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    @DisplayName("getSnapshot returns the masked payload, empty when absent")
    void getSnapshotReturnsMaskedPayload() throws Exception {
        NovaLinkConfig live = configManager.getConfig();
        live.getDatabase().getRedis().setPassword("pl-redis-pw");
        live.getServer().setSecretKey("pl-server-key");
        configManager.save();
        long rev = configManager.getSettingsRevision();

        Optional<ConfigSnapshot> snap = service.getSnapshot(rev);
        assertThat(snap).isPresent();
        assertThat(snap.get().getSnapshotJson()).contains("\"***\"");
        assertThat(snap.get().getSnapshotJson())
                .doesNotContain("pl-redis-pw")
                .doesNotContain("pl-server-key");
        // A missing revision returns empty, not null.
        assertThat(service.getSnapshot(rev + 1_000_000)).isEmpty();
    }

    @Test
    @DisplayName("recordSnapshot is best-effort and never throws on a provider failure")
    void recordSnapshotIsBestEffort() {
        // A service whose provider has been shut down: saveConfigSnapshot
        // throws DatabaseException, which recordSnapshot must swallow so the
        // calling save() still completes.
        db.shutdown();
        ConfigHistoryService deadProvider = new ConfigHistoryService(db, configManager, auditStore);
        // Must not throw — the live config save must not be blocked.
        deadProvider.recordSnapshot(42L, "{}", "operator");
    }

    // ====================== helpers ======================

    /**
     * Persists a raw (pre-mask) config JSON as a snapshot at the next monotonic
     * revision, returning the revision. Bypasses ConfigManager.save() so diff
     * tests can feed exactly the JSON they want without the full config
     * round-trip.
     */
    private long record(String json) {
        long revision = nextTestRevision++;
        service.recordSnapshot(revision, json, "test");
        return revision;
    }

    private static ClientConfig client(String username, String password) {
        ClientConfig c = new ClientConfig();
        c.setUsername(username);
        c.setPassword(password);
        return c;
    }
}
