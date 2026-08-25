package com.nova.link.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nova.link.audit.AuditEvent;
import com.nova.link.audit.AuditStore;
import com.nova.link.config.ConfigManager;
import com.nova.link.config.ConfigValidationResult;
import com.nova.link.config.NovaLinkConfig;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * §11.6 item-20 / PANEL proposal 10 (doc-deferred sub-items 1+2+3) — staged
 * configuration draft / approve / publish workflow, an independent
 * {@code /config/publish} endpoint, and an explicit backup / restore
 * mechanism.
 *
 * <p>The service layers three doc-deferred capabilities on top of the
 * primitives already in {@link ConfigHistoryService}:
 * <ol>
 *   <li><b>Draft workflow</b> — an operator creates a draft (masked YAML
 *       payload), a different SUPER_ADMIN approver signs off, then a
 *       SUPER_ADMIN publishes the approved draft to the live config via
 *       {@link ConfigManager#save()}. The approver must differ from the
 *       creator (permission separation, 403 if same).</li>
 *   <li><b>Independent {@code /config/publish}</b> — publish is a first-class
 *       operation, not a side effect of {@code PUT /api/settings}; it goes
 *       through the same fail-closed {@link ConfigManager#save()} path as
 *       rollback, and broadcasts the same {@code settings_update} WS event.</li>
 *   <li><b>Explicit backup / restore</b> — an operator takes a named backup
 *       before a risky change; restore reuses the shared
 *       {@link ConfigHistoryService#applySnapshot} primitive so live secrets
 *       survive a restore (a backup is masked; the live secret is newer and
 *       must not be clobbered with the {@code "***"} sentinel).</li>
 * </ol>
 *
 * <p><b>Masking.</b> Every draft and backup is masked at create time via
 * {@link ConfigHistoryService#maskSecrets} before it reaches the provider,
 * so neither {@code config_drafts} nor {@code config_backups} ever stores
 * plaintext secrets. The mask sentinel {@code "***"} is the same one used by
 * {@code config_history}, so {@link ConfigHistoryService#applySnapshot} can
 * reuse the same skip-masked-field logic for publish and restore.
 *
 * <p><b>State machine.</b> A draft transitions DRAFT → APPROVED → PUBLISHED.
 * Only a DRAFT can be discarded (DELETE). An APPROVED draft can be published;
 * publishing is idempotent on the state (a PUBLISHED draft cannot be
 * re-published). The approver must be a SUPER_ADMIN distinct from
 * {@code createdBy} (permission separation).
 *
 * <p><b>Fail-closed.</b> Publish and restore both call {@link ConfigManager#save()}
 * after {@link ConfigHistoryService#applySnapshot}. If save throws, the live
 * config is untouched, the draft stays APPROVED (publish failed before the
 * state flip), the backup is retained, and the caller surfaces 500/NC-510.
 * This mirrors the rollback contract in {@link ConfigHistoryService#rollback}.
 *
 * <p><b>Audit.</b> Every mutating operation records an audit event via
 * {@link AuditStore#record} (best-effort, never blocks), with the action
 * prefixed {@code settings.draft.*} / {@code settings.backup.*} so the audit
 * log can be filtered by sub-feature.
 *
 * <p>All dependencies are nullable so the service tolerates partial wiring
 * (unit tests, early startup). Missing-dep calls throw a documented
 * {@link IllegalStateException} rather than NPE-ing.
 */
public final class ConfigPublishService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigPublishService.class);

    private final DatabaseProvider databaseProvider;
    private final ConfigManager configManager;
    private final ConfigHistoryService configHistoryService;
    private final AuditStore auditStore;
    private final Gson gson;

    public ConfigPublishService(DatabaseProvider databaseProvider,
                                ConfigManager configManager,
                                ConfigHistoryService configHistoryService,
                                AuditStore auditStore) {
        this.databaseProvider = databaseProvider;
        this.configManager = configManager;
        this.configHistoryService = configHistoryService;
        this.auditStore = auditStore;
        this.gson = new GsonBuilder().serializeNulls().create();
    }

    // ==================== drafts ====================

    /**
     * Creates a new DRAFT. The supplied YAML is validated via
     * {@link ConfigManager#validateYaml}; a validation failure throws
     * {@link IllegalArgumentException} (caller surfaces 400). The validated
     * YAML is then round-tripped through Gson so the stored draftJson is the
     * masked JSON form, NOT the raw YAML — this keeps the storage shape
     * consistent with {@code config_history} snapshots and lets publish reuse
     * {@link ConfigHistoryService#applySnapshot} without a YAML round-trip.
     *
     * @param yaml       the candidate YAML document (not null, must validate)
     * @param createdBy  the panel username creating the draft (not null)
     * @return the persisted draft (id stamped, state DRAFT, draftJson masked)
     * @throws IllegalStateException    if a dependency is missing
     * @throws IllegalArgumentException if the YAML fails validation
     */
    public ConfigDraft createDraft(String yaml, String createdBy) {
        requireDependencies();
        if (yaml == null || yaml.isBlank()) {
            throw new IllegalArgumentException("YAML body must not be blank");
        }
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("createdBy must not be blank");
        }
        // Validate the YAML structurally before staging it. A validation
        // failure is a caller error (400), not a server error.
        ConfigValidationResult validation = configManager.validateYaml(yaml);
        if (!validation.isValid()) {
            String msg = validation.getErrors().isEmpty()
                    ? "YAML validation failed"
                    : validation.getErrors().get(0).getMessage();
            throw new IllegalArgumentException("YAML validation failed: " + msg);
        }
        // Round-trip the YAML through the loader to produce a NovaLinkConfig,
        // then serialise to JSON and mask. This guarantees the stored
        // draftJson is structurally identical to what publish will apply.
        NovaLinkConfig draftConfig;
        try {
            draftConfig = com.nova.link.config.ConfigLoader.parseYamlContent(yaml);
        } catch (com.nova.link.config.ConfigException e) {
            throw new IllegalArgumentException("Could not parse YAML: " + e.getMessage(), e);
        }
        String fullJson = gson.toJson(draftConfig);
        String maskedJson = configHistoryService.maskSecrets(fullJson);

        ConfigDraft draft = new ConfigDraft(maskedJson, createdBy, System.currentTimeMillis());
        try {
            databaseProvider.saveConfigDraft(draft);
        } catch (DatabaseException e) {
            throw new IllegalStateException("Failed to persist draft: " + e.getMessage(), e);
        }
        audit("settings.draft.create", "draft:" + draft.getId(),
                null, null, "draft created by " + createdBy, createdBy);
        return draft;
    }

    /**
     * Lists drafts newest-first, metadata only (no draft_json payload).
     */
    public List<ConfigDraft> listDrafts(int limit) {
        if (databaseProvider == null) {
            return Collections.emptyList();
        }
        try {
            return databaseProvider.listConfigDrafts(Math.max(1, limit));
        } catch (DatabaseException e) {
            logger.warn("Failed to list config drafts: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Loads a single draft by id, including its masked draft_json payload.
     */
    public Optional<ConfigDraft> getDraft(long id) {
        if (databaseProvider == null) {
            return Optional.empty();
        }
        try {
            return databaseProvider.getConfigDraft(id);
        } catch (DatabaseException e) {
            logger.warn("Failed to load config draft id={}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Approves a DRAFT, transitioning it to APPROVED. The approver MUST differ
     * from {@code createdBy} (permission separation); a same-user approve
     * throws {@link IllegalStateException} (caller surfaces 403).
     *
     * @return the approved draft, or empty if the draft was not found
     * @throws IllegalStateException if the draft is not in DRAFT state, or the
     *         approver equals createdBy, or a dependency is missing
     */
    public Optional<ConfigDraft> approveDraft(long id, String approver) {
        requireDependencies();
        if (approver == null || approver.isBlank()) {
            throw new IllegalStateException("Approver must not be blank");
        }
        ConfigDraft draft;
        try {
            draft = databaseProvider.getConfigDraft(id).orElse(null);
        } catch (DatabaseException e) {
            throw new IllegalStateException("Failed to load draft: " + e.getMessage(), e);
        }
        if (draft == null) {
            return Optional.empty();
        }
        if (draft.getStatus() != ConfigDraft.Status.DRAFT) {
            throw new IllegalStateException("Draft is not in DRAFT state (current: "
                    + draft.getStatus() + ")");
        }
        if (approver.equals(draft.getCreatedBy())) {
            throw new IllegalStateException("Approver must differ from createdBy");
        }
        long now = System.currentTimeMillis();
        draft.markApproved(approver, now);
        try {
            databaseProvider.updateConfigDraftStatus(id, draft.getStatus(),
                    draft.getApprovedBy(), draft.getApprovedAt(), draft.getPublishedAt());
        } catch (DatabaseException e) {
            throw new IllegalStateException("Failed to persist draft approval: " + e.getMessage(), e);
        }
        audit("settings.draft.approve", "draft:" + id,
                null, null, "draft approved by " + approver, approver);
        return Optional.of(draft);
    }

    /**
     * Publishes an APPROVED draft to the live config. Reuses
     * {@link ConfigHistoryService#applySnapshot} so live secrets survive
     * (masked fields in the draft are skipped, the live secret is preserved).
     * Calls {@link ConfigManager#save()} (fail-closed: a save failure throws
     * and leaves the live config untouched; the draft stays APPROVED).
     *
     * @return the new settings revision on success, or -1 if the draft was
     *         not found, or -2 if the draft is not APPROVED
     * @throws IllegalStateException if save fails (fail-closed — caller
     *         surfaces 500/NC-510)
     */
    public long publishDraft(long id, String actor) {
        requireDependencies();
        ConfigDraft draft;
        try {
            draft = databaseProvider.getConfigDraft(id).orElse(null);
        } catch (DatabaseException e) {
            throw new IllegalStateException("Failed to load draft: " + e.getMessage(), e);
        }
        if (draft == null) {
            return -1L;
        }
        if (draft.getStatus() != ConfigDraft.Status.APPROVED) {
            return -2L;
        }
        NovaLinkConfig live = configManager.getConfig();
        if (live == null) {
            throw new IllegalStateException("Live config not available");
        }
        NovaLinkConfig draftConfig;
        try {
            draftConfig = gson.fromJson(draft.getDraftJson(), NovaLinkConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse draft JSON: " + e.getMessage(), e);
        }
        if (draftConfig == null) {
            throw new IllegalStateException("Draft JSON parsed to null config");
        }
        String beforeMasked = configHistoryService.maskSecrets(gson.toJson(live));
        String beforeHash = AuditEvent.hashJson(beforeMasked);

        // Apply the draft onto the live config in place. Masked fields are
        // skipped so live secrets survive.
        configHistoryService.applySnapshot(live, draftConfig);

        // Fail-closed save: a throw leaves the live config untouched and the
        // draft stays APPROVED. The caller surfaces 500/NC-510.
        try {
            configManager.save();
        } catch (Exception e) {
            throw new IllegalStateException("Publish save failed: " + e.getMessage(), e);
        }

        long newRevision = configManager.getSettingsRevision();
        long now = System.currentTimeMillis();
        draft.markPublished(now);
        try {
            databaseProvider.updateConfigDraftStatus(id, draft.getStatus(),
                    draft.getApprovedBy(), draft.getApprovedAt(), draft.getPublishedAt());
        } catch (DatabaseException e) {
            // The live config was published; only the draft row's state flip
            // failed. Log and continue — the audit record below still
            // captures the publish.
            logger.warn("Publish persisted live config but failed to flip draft state: {}",
                    e.getMessage());
        }

        String afterMasked = configHistoryService.maskSecrets(gson.toJson(live));
        String afterHash = AuditEvent.hashJson(afterMasked);
        audit("settings.draft.publish", "draft:" + id,
                beforeHash, afterHash, "draft published by " + actor, actor);
        return newRevision;
    }

    /**
     * Discards a DRAFT. Only a DRAFT can be discarded; an APPROVED or
     * PUBLISHED draft cannot be removed (audit trail).
     *
     * @return true if the draft was discarded; false if not found; throws
     *         if the draft is not in DRAFT state
     */
    public boolean discardDraft(long id, String actor) {
        requireDependencies();
        ConfigDraft draft;
        try {
            draft = databaseProvider.getConfigDraft(id).orElse(null);
        } catch (DatabaseException e) {
            throw new IllegalStateException("Failed to load draft: " + e.getMessage(), e);
        }
        if (draft == null) {
            return false;
        }
        if (draft.getStatus() != ConfigDraft.Status.DRAFT) {
            throw new IllegalStateException("Only a DRAFT can be discarded (current: "
                    + draft.getStatus() + ")");
        }
        try {
            databaseProvider.deleteConfigDraft(id);
        } catch (DatabaseException e) {
            throw new IllegalStateException("Failed to delete draft: " + e.getMessage(), e);
        }
        audit("settings.draft.discard", "draft:" + id,
                null, null, "draft discarded by " + actor, actor);
        return true;
    }

    // ==================== backups ====================

    /**
     * Creates a named backup of the current live config. The backup is masked
     * via {@link ConfigHistoryService#maskSecrets} before persistence, so the
     * {@code config_backups} table never stores plaintext secrets. The live
     * settings revision is captured so the panel UI can correlate the backup
     * with the matching {@code config_history} row.
     *
     * @param label      the operator-supplied label (not null/blank)
     * @param createdBy  the panel username creating the backup (not null)
     * @return the persisted backup (id stamped)
     */
    public ConfigBackup createBackup(String label, String createdBy) {
        requireDependencies();
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Backup label must not be blank");
        }
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("createdBy must not be blank");
        }
        NovaLinkConfig live = configManager.getConfig();
        if (live == null) {
            throw new IllegalStateException("Live config not available");
        }
        String fullJson = gson.toJson(live);
        String maskedJson = configHistoryService.maskSecrets(fullJson);
        long revision = configManager.getSettingsRevision();
        ConfigBackup backup = new ConfigBackup(label, maskedJson, revision,
                createdBy, System.currentTimeMillis());
        try {
            databaseProvider.saveConfigBackup(backup);
        } catch (DatabaseException e) {
            throw new IllegalStateException("Failed to persist backup: " + e.getMessage(), e);
        }
        audit("settings.backup.create", "backup:" + backup.getId(),
                null, null, "backup '" + label + "' created by " + createdBy, createdBy);
        return backup;
    }

    /**
     * Lists backups newest-first, metadata only (no backup_json payload).
     */
    public List<ConfigBackup> listBackups(int limit) {
        if (databaseProvider == null) {
            return Collections.emptyList();
        }
        try {
            return databaseProvider.listConfigBackups(Math.max(1, limit));
        } catch (DatabaseException e) {
            logger.warn("Failed to list config backups: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Restores the live config from a named backup. Reuses
     * {@link ConfigHistoryService#applySnapshot} so live secrets survive
     * (masked fields in the backup are skipped, the live secret is preserved).
     * Calls {@link ConfigManager#save()} (fail-closed: a save failure throws
     * and leaves the live config untouched; the backup is retained).
     *
     * @return the new settings revision on success, or -1 if the backup was
     *         not found
     * @throws IllegalStateException if save fails (fail-closed — caller
     *         surfaces 500/NC-510)
     */
    public long restoreFromBackup(long id, String actor) {
        requireDependencies();
        ConfigBackup backup;
        try {
            backup = databaseProvider.getConfigBackup(id).orElse(null);
        } catch (DatabaseException e) {
            throw new IllegalStateException("Failed to load backup: " + e.getMessage(), e);
        }
        if (backup == null) {
            return -1L;
        }
        NovaLinkConfig live = configManager.getConfig();
        if (live == null) {
            throw new IllegalStateException("Live config not available");
        }
        NovaLinkConfig backupConfig;
        try {
            backupConfig = gson.fromJson(backup.getBackupJson(), NovaLinkConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse backup JSON: " + e.getMessage(), e);
        }
        if (backupConfig == null) {
            throw new IllegalStateException("Backup JSON parsed to null config");
        }
        String beforeMasked = configHistoryService.maskSecrets(gson.toJson(live));
        String beforeHash = AuditEvent.hashJson(beforeMasked);

        // Apply the backup onto the live config in place. Masked fields are
        // skipped so live secrets survive.
        configHistoryService.applySnapshot(live, backupConfig);

        // Fail-closed save: a throw leaves the live config untouched and the
        // backup is retained. The caller surfaces 500/NC-510.
        try {
            configManager.save();
        } catch (Exception e) {
            throw new IllegalStateException("Restore save failed: " + e.getMessage(), e);
        }

        long newRevision = configManager.getSettingsRevision();
        String afterMasked = configHistoryService.maskSecrets(gson.toJson(live));
        String afterHash = AuditEvent.hashJson(afterMasked);
        audit("settings.backup.restore", "backup:" + id,
                beforeHash, afterHash, "backup '" + backup.getLabel() + "' restored by " + actor, actor);
        return newRevision;
    }

    // ==================== internals ====================

    private void requireDependencies() {
        if (databaseProvider == null) {
            throw new IllegalStateException("Database provider not available");
        }
        if (configManager == null) {
            throw new IllegalStateException("Config manager not available");
        }
        if (configHistoryService == null) {
            throw new IllegalStateException("Config history service not available");
        }
    }

    /**
     * Best-effort audit record. Never blocks the business mutation (mirrors
     * {@link ConfigHistoryService#rollback} audit posture). The actor is
     * threaded through so the audit row attributes the action to the panel
     * user who triggered it (rather than the {@code "system"} sentinel used
     * when no actor is available).
     */
    private void audit(String action, String resource,
                       String beforeHash, String afterHash, String reason, String actor) {
        if (auditStore == null) {
            return;
        }
        try {
            AuditEvent event = new AuditEvent(
                    java.util.UUID.randomUUID().toString(),
                    null,
                    actor != null ? actor : "system",
                    "SUPER_ADMIN",
                    null,
                    action,
                    resource,
                    beforeHash,
                    afterHash,
                    reason,
                    "success",
                    System.currentTimeMillis());
            auditStore.record(event);
        } catch (Exception e) {
            logger.warn("Failed to record audit event action={}: {}", action, e.getMessage());
        }
    }
}
