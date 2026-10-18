package com.nova.link.api;

import java.util.Objects;

/**
 * An explicit backup of the live configuration (§11.6 item-20 / proposal 10,
 * doc-deferred sub-item 3: backup/restore mechanism).
 *
 * <p>Backups are created by {@link ConfigPublishService#createBackup} and
 * restored by {@link ConfigPublishService#restoreFromBackup}. Unlike the
 * append-only {@link com.nova.link.config.ConfigSnapshot} rows managed by
 * {@link ConfigHistoryService}, backups are <em>named</em> (operator-supplied
 * label) and <em>restorable by id</em>: they exist so an operator can take a
 * named snapshot before a risky change and revert to it by id, even if the
 * intermediate config revisions have been pruned from {@code config_history}.
 *
 * <p>The backup stores the <em>masked</em> JSON form of {@link com.nova.link.config.NovaLinkConfig}
 * — every secret field replaced with {@code "***"} before persistence — so
 * the {@code config_backups} table never holds plaintext secrets (same posture
 * as {@code config_history} and {@code audit_events.content_hash}).
 *
 * <p>The {@code id} field is the database-assigned row id (0 when not yet
 * persisted); it is stamped back by the provider via reflection, mirroring
 * the {@link com.nova.link.config.ConfigSnapshot} id-stamping pattern. The
 * {@code createdBy} field is the panel username that created the backup; the
 * {@code settingsRevision} field is the live settings revision (PANEL-010) at
 * the moment the backup was taken, so the panel UI can correlate a backup
 * with the {@code config_history} row of the same revision.
 *
 * <p>Requirements: §11.6 Project 20 (backup / restore mechanism).
 */
public final class ConfigBackup {

    private long id;
    private final String label;
    private final String backupJson;
    private final long settingsRevision;
    private final String createdBy;
    private final long createdAt;

    /**
     * Full-field constructor used by the store layer when hydrating persisted
     * rows (id is the assigned database id).
     *
     * @param id              the database-assigned id (0 when not yet persisted)
     * @param label           the operator-supplied label (not null)
     * @param backupJson      the MASKED JSON form of the live config
     * @param settingsRevision the live settings revision at backup time
     * @param createdBy       the panel username that created the backup (not null)
     * @param createdAt       epoch millis at which the backup was created
     */
    public ConfigBackup(long id, String label, String backupJson, long settingsRevision,
                        String createdBy, long createdAt) {
        this.id = id;
        this.label = label;
        this.backupJson = backupJson;
        this.settingsRevision = settingsRevision;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /**
     * Convenience constructor for a new (not-yet-persisted) backup. The id is
     * set to 0; the provider stamps the row id on insert.
     *
     * @param label           the operator-supplied label (not null)
     * @param backupJson      the MASKED JSON form of the live config
     * @param settingsRevision the live settings revision at backup time
     * @param createdBy       the panel username that created the backup (not null)
     * @param createdAt       epoch millis at which the backup was created
     */
    public ConfigBackup(String label, String backupJson, long settingsRevision,
                        String createdBy, long createdAt) {
        this(0L, label, backupJson, settingsRevision, createdBy, createdAt);
    }

    public long getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public String getBackupJson() {
        return backupJson;
    }

    public long getSettingsRevision() {
        return settingsRevision;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets the database-assigned id. Public to match the reflection stamping
     * convention used for {@link com.nova.link.config.ConfigSnapshot}.
     */
    public void setId(long id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigBackup that = (ConfigBackup) o;
        return id == that.id
                && settingsRevision == that.settingsRevision
                && createdAt == that.createdAt
                && Objects.equals(label, that.label)
                && Objects.equals(backupJson, that.backupJson)
                && Objects.equals(createdBy, that.createdBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, label, backupJson, settingsRevision, createdBy, createdAt);
    }

    @Override
    public String toString() {
        return "ConfigBackup{id=" + id
                + ", label=" + label
                + ", settingsRevision=" + settingsRevision
                + ", createdBy=" + createdBy
                + ", createdAt=" + createdAt + '}';
    }
}
