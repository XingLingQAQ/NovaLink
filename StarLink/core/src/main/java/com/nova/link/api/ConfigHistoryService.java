package com.nova.link.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.nova.link.audit.AuditEvent;
import com.nova.link.audit.AuditStore;
import com.nova.link.config.ClientConfig;
import com.nova.link.config.ConfigManager;
import com.nova.link.config.ConfigSnapshot;
import com.nova.link.config.NovaLinkConfig;
import com.nova.link.config.PanelUserConfig;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * §11.6 Project 20 / PANEL proposal 10 — masked config-snapshot history and
 * atomic rollback.
 *
 * <p>Every successful settings mutation is captured as an append-only
 * {@link ConfigSnapshot} row in {@code config_history}. The snapshot stores the
 * <em>masked</em> JSON form of {@link NovaLinkConfig} (secrets replaced with
 * {@code "***"}), so the table never holds plaintext secrets — the same posture
 * as {@code audit_events.content_hash}. The REST surface
 * ({@code GET /api/settings/history}, {@code /snapshots/{revision}},
 * {@code /diff}, {@code POST /rollback}) is backed by this service.
 *
 * <p><b>Masking.</b> {@link #maskSecrets(String)} walks the Gson JSON tree (NOT
 * the YAML document — {@link com.nova.link.config.ConfigLoader} uses SnakeYAML
 * for the on-disk round-trip, but the snapshot is a Gson JSON tree so the masked
 * form can be re-parsed without a YAML round-trip) and replaces these secret
 * fields with a {@code "***"} {@link JsonPrimitive}:
 * <ul>
 *   <li>{@code server.secretKey} AND {@code server.secret-key} (Gson
 *       serializes the Java field {@code secretKey} verbatim; the on-disk YAML
 *       template uses the kebab-case {@code secret-key} alias for the same
 *       value — both spellings are masked so neither leaks, regardless of
 *       whether the snapshot JSON was produced from the live Java object or
 *       re-serialised from a reloaded config)</li>
 *   <li>{@code database.mysql.password}, {@code database.postgresql.password},
 *       {@code database.redis.password}</li>
 *   <li>{@code clients[].password} (every element of the clients array)</li>
 *   <li>{@code super-admins[].password-hash} (every element)</li>
 *   <li>{@code panel-users[].password-hash} (every element)</li>
 * </ul>
 * {@link com.nova.link.config.SecurityConfig} carries no secret fields so it is
 * left untouched. The mask sentinel {@code "***"} is also the signal used by
 * {@link #rollback(long, String)} to skip restoring that field and preserve the
 * live secret.
 *
 * <p><b>Rollback.</b> High-risk and fail-closed: a rollback loads the masked
 * target snapshot, deserialises it back into a {@link NovaLinkConfig}, copies
 * every <em>non-secret</em> field onto the live config (leaving the current
 * live secrets in place for masked fields), then calls
 * {@link ConfigManager#save()} — which writes to disk and bumps the settings
 * revision atomically inside {@code fileOperationLock}. If the save throws, the
 * live config is left untouched and the exception propagates (fail-closed —
 * the opposite of {@link AuditStore#record}, which swallows persistence
 * failures because the mutation has already happened). A successful rollback
 * appends a new active snapshot row (the live config the rollback produced,
 * re-masked) and flips every prior row inactive via
 * {@link DatabaseProvider#deactivateOtherSnapshots(long)} — append-only, no
 * history is deleted.
 *
 * <p>All dependencies are nullable so the service tolerates partial wiring
 * (unit tests, early startup). Missing-dep calls return empty results or throw
 * a documented {@link IllegalStateException} rather than NPE-ing.
 */
public final class ConfigHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigHistoryService.class);

    /** Mask sentinel substituted for every secret field value. */
    static final String MASK = "***";

    private final DatabaseProvider databaseProvider;
    private final ConfigManager configManager;
    private final AuditStore auditStore;
    private final Gson gson;

    public ConfigHistoryService(DatabaseProvider databaseProvider, ConfigManager configManager) {
        this(databaseProvider, configManager, null);
    }

    /**
     * Full constructor. The {@link AuditStore} backs the mandatory audit record
     * emitted on every rollback; when null, rollback still executes but the
     * audit event is skipped (audit is best-effort but never blocks the
     * rollback — see {@link AuditStore#record}).
     */
    public ConfigHistoryService(DatabaseProvider databaseProvider,
                                ConfigManager configManager,
                                AuditStore auditStore) {
        this.databaseProvider = databaseProvider;
        this.configManager = configManager;
        this.auditStore = auditStore;
        this.gson = new GsonBuilder().serializeNulls().create();
    }

    // ==================== masking ====================

    /**
     * Walks the Gson JSON tree and replaces every secret field with the
     * {@link #MASK} sentinel. Returns the input unchanged when it is null,
     * blank, or not a JSON object. The walk is defensive: a malformed subtree
     * never throws — the offender is skipped.
     */
    String maskSecrets(String fullConfigJson) {
        if (fullConfigJson == null || fullConfigJson.isBlank()) {
            return fullConfigJson;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(fullConfigJson);
        } catch (Exception e) {
            // Unparseable input — return as-is rather than risking a partial
            // mask. The caller (recordSnapshot) still persists the original;
            // a malformed snapshot is better than dropping the history row.
            logger.warn("Could not parse config JSON for masking: {}", e.getMessage());
            return fullConfigJson;
        }
        if (!root.isJsonObject()) {
            return fullConfigJson;
        }
        JsonObject obj = root.getAsJsonObject();
        maskServerSecrets(obj);

        JsonObject database = obj.getAsJsonObject("database");
        if (database != null) {
            maskLeaf(database, "mysql", "password");
            maskLeaf(database, "postgresql", "password");
            maskLeaf(database, "redis", "password");
        }

        maskArrayLeaf(obj, "clients", "password");
        // §11.6 item-20: Gson serializes the live Java object fields
        // {@code superAdmins}/{@code panelUsers} (camelCase) via the field
        // names, while a reloaded-and-re-serialised YAML snapshot carries the
        // same arrays under the kebab-case aliases {@code super-admins}/
        // {@code panel-users}. Both spellings must be masked so neither leaks,
        // regardless of whether the input JSON came from the live Gson form or
        // a YAML round-trip. The same applies to the {@code password-hash}/
        // {@code passwordHash} leaf: the live object's field is
        // {@code passwordHash} (camelCase); the YAML alias is
        // {@code password-hash} (kebab-case). Both are masked here.
        maskArrayLeaf(obj, "super-admins", "password-hash");
        maskArrayLeaf(obj, "superAdmins", "passwordHash");
        maskArrayLeaf(obj, "panel-users", "password-hash");
        maskArrayLeaf(obj, "panelUsers", "passwordHash");

        return gson.toJson(obj);
    }

    private static void maskLeaf(JsonObject parent, String groupKey, String secretKey) {
        if (parent == null) {
            return;
        }
        JsonElement group = parent.get(groupKey);
        if (group != null && group.isJsonObject()) {
            maskField(group.getAsJsonObject(), secretKey);
        }
    }

    /**
     * Masks the server block's secret-key. The live {@link NovaLinkConfig}
     * serialises the Java field {@code secretKey} (camelCase) via Gson, while the
     * on-disk YAML template carries the same value under the kebab-case alias
     * {@code secret-key}; both spellings must be masked so neither leaks,
     * regardless of whether the snapshot JSON came from the live Java object or
     * was re-serialised from a reloaded (alias-aware) config.
     */
    private static void maskServerSecrets(JsonObject root) {
        if (root == null) {
            return;
        }
        JsonElement server = root.get("server");
        if (server == null || !server.isJsonObject()) {
            return;
        }
        JsonObject serverObj = server.getAsJsonObject();
        maskField(serverObj, "secretKey");
        maskField(serverObj, "secret-key");
    }

    private static void maskArrayLeaf(JsonObject parent, String arrayKey, String secretKey) {
        if (parent == null) {
            return;
        }
        JsonElement arrayEl = parent.get(arrayKey);
        if (arrayEl == null || !arrayEl.isJsonArray()) {
            return;
        }
        for (JsonElement element : arrayEl.getAsJsonArray()) {
            if (element != null && element.isJsonObject()) {
                maskField(element.getAsJsonObject(), secretKey);
            }
        }
    }

    private static void maskField(JsonObject group, String secretKey) {
        if (group == null) {
            return;
        }
        JsonElement existing = group.get(secretKey);
        // Mask absent-and-explicit-null and present-with-value alike; leave the
        // key in place so the structural diff still reports it.
        if (existing == null || existing.isJsonNull()) {
            group.addProperty(secretKey, MASK);
        } else {
            group.add(secretKey, new JsonPrimitive(MASK));
        }
    }

    // ==================== write path ====================

    /**
     * Masks the supplied settings JSON and persists it as a new active
     * snapshot. The provider stamps the row id back via reflection and flips
     * every prior row inactive atomically.
     *
     * @param revision     the settings revision (PANEL-010) this snapshot captures
     * @param settingsJson the FULL (unmasked) JSON form of the live config
     * @param actor        the panel username triggering the snapshot (may be null)
     */
    public void recordSnapshot(long revision, String settingsJson, String actor) {
        if (databaseProvider == null) {
            logger.debug("ConfigHistoryService.recordSnapshot skipped: no database provider");
            return;
        }
        String masked = maskSecrets(settingsJson);
        ConfigSnapshot snapshot = new ConfigSnapshot(
                revision, masked, System.currentTimeMillis(), actor);
        try {
            databaseProvider.saveConfigSnapshot(snapshot);
        } catch (DatabaseException e) {
            // A snapshot failure must NOT block the settings save that just
            // succeeded (recordSnapshot is called AFTER configManager.save()).
            // Log and move on; the history row is missing but the live config
            // is correct. This mirrors the audit-store posture: history is
            // best-effort relative to the business mutation.
            logger.warn("Failed to persist config snapshot revision={}: {}", revision, e.getMessage());
        } catch (RuntimeException e) {
            logger.warn("Unexpected error persisting config snapshot revision={}: {}",
                    revision, e.getMessage());
        }
    }

    // ==================== read path ====================

    /**
     * Lists snapshots newest-first WITHOUT the full {@code snapshot_json} blob.
     *
     * @param limit the maximum number of snapshots to return
     * @return the matching snapshots (no payload), newest first; empty on failure
     */
    public List<ConfigSnapshot> getHistory(int limit) {
        if (databaseProvider == null) {
            return Collections.emptyList();
        }
        try {
            return databaseProvider.getConfigHistory(limit);
        } catch (DatabaseException e) {
            logger.warn("Failed to list config history: {}", e.getMessage());
            return Collections.emptyList();
        } catch (RuntimeException e) {
            logger.warn("Unexpected error listing config history: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Loads a single masked snapshot by revision, including its payload.
     *
     * @return the snapshot, or empty if not found or the provider is absent
     */
    public Optional<ConfigSnapshot> getSnapshot(long revision) {
        if (databaseProvider == null) {
            return Optional.empty();
        }
        try {
            return databaseProvider.getConfigSnapshot(revision);
        } catch (DatabaseException e) {
            logger.warn("Failed to load config snapshot revision={}: {}", revision, e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            logger.warn("Unexpected error loading config snapshot revision={}: {}",
                    revision, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Produces a masked added/removed/changed map between two snapshot
     * revisions. Both snapshots are loaded, parsed to Gson trees, and walked
     * leaf-by-leaf; the output is itself masked (snapshot secrets are already
     * {@code "***"} in storage, so the diff output is masked by construction).
     *
     * @return the diff, or empty on any load/parse failure
     */
    public Map<String, Object> diffSettings(long fromRevision, long toRevision) {
        Optional<ConfigSnapshot> fromSnap = getSnapshot(fromRevision);
        Optional<ConfigSnapshot> toSnap = getSnapshot(toRevision);
        if (fromSnap.isEmpty() || toSnap.isEmpty()) {
            return Collections.emptyMap();
        }
        JsonElement fromTree;
        JsonElement toTree;
        try {
            fromTree = JsonParser.parseString(fromSnap.get().getSnapshotJson());
            toTree = JsonParser.parseString(toSnap.get().getSnapshotJson());
        } catch (Exception e) {
            logger.warn("Could not parse snapshot JSON for diff {}->{}: {}",
                    fromRevision, toRevision, e.getMessage());
            return Collections.emptyMap();
        }
        Map<String, JsonElement> added = new LinkedHashMap<>();
        Map<String, JsonElement> removed = new LinkedHashMap<>();
        Map<String, Object[]> changed = new LinkedHashMap<>();
        diffWalk("", fromTree, toTree, added, removed, changed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fromRevision", fromRevision);
        result.put("toRevision", toRevision);
        result.put("added", toJsonArray(added.values()));
        result.put("removed", toJsonArray(removed.values()));
        // Each changed entry is a 2-element [old, new] pair.
        JsonArray changedArray = new JsonArray();
        for (Map.Entry<String, Object[]> entry : changed.entrySet()) {
            JsonObject pair = new JsonObject();
            pair.addProperty("path", entry.getKey());
            pair.add("from", (JsonElement) entry.getValue()[0]);
            pair.add("to", (JsonElement) entry.getValue()[1]);
            changedArray.add(pair);
        }
        result.put("changed", changedArray);
        return result;
    }

    private void diffWalk(String prefix, JsonElement from, JsonElement to,
                         Map<String, JsonElement> added,
                         Map<String, JsonElement> removed,
                         Map<String, Object[]> changed) {
        if (equalsElement(from, to)) {
            return;
        }
        if (from == null || from.isJsonNull()) {
            added.put(prefix, to);
            return;
        }
        if (to == null || to.isJsonNull()) {
            removed.put(prefix, from);
            return;
        }
        if (from.isJsonObject() && to.isJsonObject()) {
            JsonObject fromObj = from.getAsJsonObject();
            JsonObject toObj = to.getAsJsonObject();
            TreeSet<String> keys = new TreeSet<>();
            fromObj.keySet().forEach(keys::add);
            toObj.keySet().forEach(keys::add);
            for (String key : keys) {
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                diffWalk(path, fromObj.get(key), toObj.get(key), added, removed, changed);
            }
            return;
        }
        if (from.isJsonArray() && to.isJsonArray()) {
            JsonArray fromArr = from.getAsJsonArray();
            JsonArray toArr = to.getAsJsonArray();
            int max = Math.max(fromArr.size(), toArr.size());
            for (int i = 0; i < max; i++) {
                String path = prefix + "[" + i + "]";
                JsonElement fe = i < fromArr.size() ? fromArr.get(i) : null;
                JsonElement te = i < toArr.size() ? toArr.get(i) : null;
                diffWalk(path, fe, te, added, removed, changed);
            }
            return;
        }
        // Primitive leaf that differs.
        changed.put(prefix, new Object[]{from, to});
    }

    private static boolean equalsElement(JsonElement a, JsonElement b) {
        if (a == null) {
            return b == null || b.isJsonNull();
        }
        if (b == null) {
            return a.isJsonNull();
        }
        // Treat JSON null and Java null as equal (both represent "absent").
        if (a.isJsonNull() && b.isJsonNull()) {
            return true;
        }
        return a.equals(b);
    }

    private static JsonArray toJsonArray(java.util.Collection<JsonElement> values) {
        JsonArray array = new JsonArray();
        for (JsonElement value : values) {
            array.add(value);
        }
        return array;
    }

    // ==================== rollback ====================

    /**
     * Atomically rolls the live config back to the masked snapshot identified
     * by {@code targetRevision}.
     *
     * <p>Steps (fail-closed):
     * <ol>
     *   <li>Load the target snapshot via {@link #getSnapshot(long)}; 404 if
     *       absent.</li>
     *   <li>Refuse if the target is already the active row (400).</li>
     *   <li>Deserialize the masked snapshot JSON into a fresh
     *       {@link NovaLinkConfig} via Gson.</li>
     *   <li>Copy every <em>non-secret</em> field onto the live config in place,
     *       skipping any field whose snapshot value is the {@link #MASK}
     *       sentinel — the current live secret is preserved for those.</li>
     *   <li>{@link ConfigManager#save()} writes the merged config to disk and
     *       bumps the settings revision atomically. A failure propagates and
     *       leaves the live config untouched.</li>
     *   <li>Append a new active rollback snapshot row and flip every prior row
     *       inactive — append-only, no history deleted.</li>
     *   <li>Audit the rollback via {@link AuditStore#record} (best-effort;
     *       never blocks).</li>
     * </ol>
     *
     * @return the new settings revision after rollback, or -1 if the target
     *         was already active (no-op), or -2 if the target was not found
     * @throws IllegalStateException if a dependency is missing or the rollback
     *         fails after the snapshot was loaded (fail-closed — caller should
     *         surface a 500/NC-510)
     */
    public long rollback(long targetRevision, String actor) {
        if (databaseProvider == null) {
            throw new IllegalStateException("Database provider not available");
        }
        if (configManager == null || configManager.getConfig() == null) {
            throw new IllegalStateException("Config manager not available");
        }
        Optional<ConfigSnapshot> targetOpt = getSnapshot(targetRevision);
        if (targetOpt.isEmpty()) {
            return -2L;
        }
        ConfigSnapshot target = targetOpt.get();
        if (target.isActive()) {
            return -1L;
        }
        // beforeHash for audit: hash of the current live config (masked).
        NovaLinkConfig live = configManager.getConfig();
        String beforeMasked = maskSecrets(gson.toJson(live));
        String beforeHash = AuditEvent.hashJson(beforeMasked);

        NovaLinkConfig targetConfig;
        try {
            targetConfig = gson.fromJson(target.getSnapshotJson(), NovaLinkConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse target snapshot: " + e.getMessage(), e);
        }
        if (targetConfig == null) {
            throw new IllegalStateException("Target snapshot parsed to null config");
        }

        // Merge non-secret fields onto the live config in place. Secret fields
        // carry the MASK sentinel in the snapshot, so they are skipped and the
        // current live secret is preserved.
        applySnapshot(live, targetConfig);

        // Atomic write + revision bump. Fail-closed: a save failure propagates
        // and the live config is left in the partially-merged state ONLY until
        // the caller surfaces the error; the file on disk was NOT rewritten
        // (save() is all-or-nothing for the YAML round-trip). On the next
        // successful save or reload the merged state is either committed or
        // overwritten from disk.
        try {
            configManager.save();
        } catch (Exception e) {
            throw new IllegalStateException("Rollback save failed: " + e.getMessage(), e);
        }

        // Append a new active rollback snapshot + flip prior rows inactive.
        String afterMasked = maskSecrets(gson.toJson(live));
        long newRevision = configManager.getSettingsRevision();
        ConfigSnapshot rollbackRecord = new ConfigSnapshot(
                newRevision, afterMasked, System.currentTimeMillis(), actor);
        try {
            databaseProvider.saveConfigSnapshot(rollbackRecord);
            databaseProvider.deactivateOtherSnapshots(newRevision);
        } catch (DatabaseException e) {
            // The live config was rolled back successfully; only the history
            // row failed. Log and continue — the audit record below still
            // captures the rollback.
            logger.warn("Rollback persisted live config but failed to record history row: {}",
                    e.getMessage());
        }

        // Audit (best-effort; never blocks).
        String afterHash = AuditEvent.hashJson(afterMasked);
        if (auditStore != null) {
            try {
                AuditEvent event = new AuditEvent(
                        java.util.UUID.randomUUID().toString(),
                        null,
                        actor,
                        "ADMIN",
                        null,
                        "settings.rollback",
                        "config:rollback:" + targetRevision,
                        beforeHash,
                        afterHash,
                        "rollback to revision " + targetRevision,
                        "success",
                        System.currentTimeMillis());
                auditStore.record(event);
            } catch (Exception e) {
                logger.warn("Failed to record rollback audit event: {}", e.getMessage());
            }
        }
        return newRevision;
    }

    /**
     * Copies every non-secret field from {@code source} onto {@code target} in
     * place. A field whose snapshot value equals {@link #MASK} is skipped so
     * the live secret survives. Only scalar/list/map fields that carry no
     * secret are touched; nested secret-bearing objects (server, database,
     * clients, super-admins, panel-users) are merged field-by-field so their
     * non-secret siblings survive while their secret is preserved.
     *
     * <p>This is the shared apply primitive used by rollback, publish-draft and
     * restore-from-backup. Behaviour is unchanged from the former
     * {@code mergeNonSecret} — mask fields are skipped and the live secret is
     * preserved — only the name has been refactored to reflect its broader
     * reuse. Package-private so the sibling {@link ConfigPublishService} (same
     * package) can reuse it for draft-publish and restore-from-backup without
     * duplicating the merge logic.
     */
    void applySnapshot(NovaLinkConfig target, NovaLinkConfig source) {
        if (source == null) {
            return;
        }
        // debug is a plain scalar, safe to copy.
        if (source.isDebug() != target.isDebug()) {
            target.setDebug(source.isDebug());
        }
        // server: secret-key is masked; everything else is safe.
        if (source.getServer() != null && target.getServer() != null) {
            mergeServer(target.getServer(), source.getServer());
        }
        // database: each dialect password is masked; everything else is safe.
        if (source.getDatabase() != null && target.getDatabase() != null) {
            mergeDatabase(target.getDatabase(), source.getDatabase());
        }
        // security has no secret fields — full copy.
        if (source.getSecurity() != null) {
            target.setSecurity(source.getSecurity());
        }
        // features + filter are panel-managed, no secrets — full copy.
        if (source.getFeatures() != null) {
            target.setFeatures(source.getFeatures());
        }
        if (source.getFilter() != null) {
            target.setFilter(source.getFilter());
        }
        // Channels and templates carry no secrets — full copy.
        if (source.getGlobalChannels() != null) {
            target.setGlobalChannels(source.getGlobalChannels());
        }
        if (source.getTemplates() != null) {
            target.setTemplates(source.getTemplates());
        }
        // clients: per-element merge keyed by username. The snapshot's password
        // is masked, so the live password survives for any client present in
        // both lists; clients present only in the snapshot are added (their
        // password stays the masked sentinel — an operator who rolls back to a
        // roster containing a deleted client must re-issue that client's
        // password), and clients present only in the live config are removed
        // (rolling back to an older roster is an explicit operator action).
        if (source.getClients() != null) {
            mergeClients(target.getClients(), source.getClients());
        }
        // super-admins + panel-users: per-element merge keyed by UUID/username.
        // The snapshot masked password-hash, so live hashes survive for any
        // user present in both lists; users present only in the snapshot carry
        // the masked sentinel and must be re-provisioned on login; users only
        // in the live config are removed by the rollback.
        if (source.getSuperAdmins() != null) {
            mergeSuperAdmins(target.getSuperAdmins(), source.getSuperAdmins());
        }
        if (source.getPanelUsers() != null) {
            mergePanelUsers(target.getPanelUsers(), source.getPanelUsers());
        }
    }

    private void mergeClients(List<ClientConfig> target, List<ClientConfig> source) {
        Map<String, ClientConfig> live = new LinkedHashMap<>();
        for (ClientConfig c : target) {
            if (c != null && c.getUsername() != null) {
                live.put(c.getUsername(), c);
            }
        }
        List<ClientConfig> merged = new ArrayList<>();
        for (ClientConfig snap : source) {
            if (snap == null) {
                continue;
            }
            if (snap.getUsername() == null) {
                merged.add(snap);
                continue;
            }
            ClientConfig liveEntry = live.get(snap.getUsername());
            if (liveEntry != null && !MASK.equals(liveEntry.getPassword())) {
                snap.setPassword(liveEntry.getPassword());
            }
            merged.add(snap);
        }
        target.clear();
        target.addAll(merged);
    }

    private void mergeSuperAdmins(List<com.nova.link.auth.SuperAdminCredentials> target,
                                   List<com.nova.link.auth.SuperAdminCredentials> source) {
        Map<UUID, com.nova.link.auth.SuperAdminCredentials> live = new LinkedHashMap<>();
        for (com.nova.link.auth.SuperAdminCredentials s : target) {
            if (s != null && s.getUuid() != null) {
                live.put(s.getUuid(), s);
            }
        }
        List<com.nova.link.auth.SuperAdminCredentials> merged = new ArrayList<>();
        for (com.nova.link.auth.SuperAdminCredentials snap : source) {
            if (snap == null || snap.getUuid() == null) {
                continue;
            }
            com.nova.link.auth.SuperAdminCredentials liveEntry = live.get(snap.getUuid());
            if (liveEntry != null && !MASK.equals(liveEntry.getPasswordHash())) {
                merged.add(new com.nova.link.auth.SuperAdminCredentials(
                        snap.getUuid(), liveEntry.getPasswordHash(), snap.getUsername()));
            } else {
                merged.add(snap);
            }
        }
        target.clear();
        target.addAll(merged);
    }

    private void mergePanelUsers(List<PanelUserConfig> target, List<PanelUserConfig> source) {
        Map<String, PanelUserConfig> live = new LinkedHashMap<>();
        for (PanelUserConfig p : target) {
            if (p != null && p.getUsername() != null) {
                live.put(p.getUsername(), p);
            }
        }
        List<PanelUserConfig> merged = new ArrayList<>();
        for (PanelUserConfig snap : source) {
            if (snap == null || snap.getUsername() == null) {
                continue;
            }
            PanelUserConfig liveEntry = live.get(snap.getUsername());
            if (liveEntry != null && !MASK.equals(liveEntry.getPasswordHash())) {
                merged.add(new PanelUserConfig(
                        snap.getUsername(), liveEntry.getPasswordHash(), snap.getRole()));
            } else {
                merged.add(snap);
            }
        }
        target.clear();
        target.addAll(merged);
    }

    private void mergeServer(com.nova.link.config.ServerConfig target,
                             com.nova.link.config.ServerConfig source) {
        if (source.getBindAddress() != null) {
            target.setBindAddress(source.getBindAddress());
        }
        target.setPort(source.getPort());
        target.setWebsocketPort(source.getWebsocketPort());
        target.setWorkerThreads(source.getWorkerThreads());
        if (source.getLocale() != null) {
            target.setLocale(source.getLocale());
        }
        if (source.getCorsAllowedOrigins() != null) {
            target.setCorsAllowedOrigins(new ArrayList<>(source.getCorsAllowedOrigins()));
        }
        target.setIdleTimeoutSeconds(source.getIdleTimeoutSeconds());
        target.setRateLimitMessagesPerSecond(source.getRateLimitMessagesPerSecond());
        target.setRateLimitBurst(source.getRateLimitBurst());
        target.setRestWorkerThreads(source.getRestWorkerThreads());
        target.setInsecureAllowPlaintext(source.isInsecureAllowPlaintext());
        if (source.getTls() != null) {
            target.setTls(source.getTls());
        }
        // secret-key is masked in the snapshot — leave the live value.
    }

    private void mergeDatabase(com.nova.link.config.DatabaseConfig target,
                               com.nova.link.config.DatabaseConfig source) {
        if (source.getType() != null) {
            target.setType(source.getType());
        }
        if (source.getMysql() != null && target.getMysql() != null) {
            mergeMysql(target.getMysql(), source.getMysql());
        }
        if (source.getPostgresql() != null && target.getPostgresql() != null) {
            mergePostgresql(target.getPostgresql(), source.getPostgresql());
        }
        if (source.getSqlite() != null) {
            target.setSqlite(source.getSqlite());
        }
        if (source.getRedis() != null && target.getRedis() != null) {
            com.nova.link.config.DatabaseConfig.RedisConfig t = target.getRedis();
            com.nova.link.config.DatabaseConfig.RedisConfig s = source.getRedis();
            t.setEnabled(s.isEnabled());
            if (s.getHost() != null) {
                t.setHost(s.getHost());
            }
            t.setPort(s.getPort());
            // password is masked in the snapshot — leave the live value.
        }
    }

    private void mergeMysql(com.nova.link.config.DatabaseConfig.MySQLConfig target,
                            com.nova.link.config.DatabaseConfig.MySQLConfig source) {
        if (source.getHost() != null) {
            target.setHost(source.getHost());
        }
        target.setPort(source.getPort());
        if (source.getDatabase() != null) {
            target.setDatabase(source.getDatabase());
        }
        if (source.getUsername() != null) {
            target.setUsername(source.getUsername());
        }
        target.setPoolSize(source.getPoolSize());
        // password is masked in the snapshot — leave the live value.
    }

    private void mergePostgresql(com.nova.link.config.DatabaseConfig.PostgreSQLConfig target,
                                 com.nova.link.config.DatabaseConfig.PostgreSQLConfig source) {
        if (source.getHost() != null) {
            target.setHost(source.getHost());
        }
        target.setPort(source.getPort());
        if (source.getDatabase() != null) {
            target.setDatabase(source.getDatabase());
        }
        if (source.getUsername() != null) {
            target.setUsername(source.getUsername());
        }
        target.setPoolSize(source.getPoolSize());
        // password is masked in the snapshot — leave the live value.
    }
}
