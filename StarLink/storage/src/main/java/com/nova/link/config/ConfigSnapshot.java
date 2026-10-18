package com.nova.link.config;

import java.util.Objects;

/**
 * A masked, append-only snapshot of the full NovaLink configuration keyed by
 * the monotonic settings revision (PANEL-010).
 *
 * <p>§11.6 Project 20 / PANEL proposal 10: config diff + atomic rollback. The
 * snapshot persists the <em>masked</em> JSON form of {@link NovaLinkConfig} —
 * every secret field (server {@code secret-key}, database passwords, client
 * passwords, super-admin / panel-user password hashes) has been replaced with
 * {@code "***"} before this object reaches the provider, so the
 * {@code config_history} table never stores plaintext secrets (same posture as
 * {@code audit_events.content_hash}).
 *
 * <p>The {@code id} field is the database-assigned row id (0 when not yet
 * persisted); it is stamped back by the provider via reflection, mirroring the
 * audit-event / notification id-stamping pattern. The {@code active} flag marks
 * the single row that represents the current live config; rollback flips it
 * atomically and appends a new active row rather than mutating or deleting
 * prior history (append-only contract).
 *
 * <p>Requirements: §11.6 Project 20 (config diff / rollback)
 */
public class ConfigSnapshot {

    private long id;
    private final long revision;
    private final String snapshotJson;
    private final long createdAt;
    private final String createdBy;
    private boolean active;

    /**
     * Full-field constructor used by the store layer when hydrating persisted
     * rows (id is the assigned database id).
     *
     * @param id           the database-assigned id (0 when not yet persisted)
     * @param revision     the settings revision (PANEL-010) this snapshot captures
     * @param snapshotJson the MASKED JSON form of the full NovaLinkConfig
     * @param createdAt    epoch millis at which the snapshot was recorded
     * @param createdBy    the panel username that triggered the snapshot (may be null)
     * @param active       whether this row is the currently-active snapshot
     */
    public ConfigSnapshot(long id, long revision, String snapshotJson,
                          long createdAt, String createdBy, boolean active) {
        this.id = id;
        this.revision = revision;
        this.snapshotJson = snapshotJson;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.active = active;
    }

    /**
     * Convenience constructor for a new (not-yet-persisted) snapshot. The id is
     * set to 0 and {@code active} defaults to {@code false}; the provider marks
     * the row active on insert. Used by {@link ConfigHistoryService#recordSnapshot}.
     */
    public ConfigSnapshot(long revision, String snapshotJson,
                          long createdAt, String createdBy) {
        this(0L, revision, snapshotJson, createdAt, createdBy, false);
    }

    public long getId() {
        return id;
    }

    public long getRevision() {
        return revision;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Marks this snapshot as the active row. Called by the provider after
     * deactivateOtherSnapshots flips every prior row to inactive.
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Sets the database-assigned id. Public to match the reflection stamping
     * convention used for audit events / evidence, which live in a different
     * package than the providers that stamp them.
     */
    public void setId(long id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigSnapshot that = (ConfigSnapshot) o;
        return id == that.id
                && revision == that.revision
                && createdAt == that.createdAt
                && active == that.active
                && Objects.equals(snapshotJson, that.snapshotJson)
                && Objects.equals(createdBy, that.createdBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, revision, snapshotJson, createdAt, createdBy, active);
    }

    @Override
    public String toString() {
        return "ConfigSnapshot{revision=" + revision
                + ", id=" + id
                + ", active=" + active
                + ", createdBy=" + createdBy
                + ", createdAt=" + createdAt + '}';
    }
}
