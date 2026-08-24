package com.nova.link.social;

import java.util.Objects;
import java.util.UUID;

/**
 * A directional social relation held by one player toward another (§11.6
 * Project 18 / PANEL proposal 08 — social relations & ignore).
 *
 * <p>Relations are <em>directional</em>: a row owned by {@code sourceId} records
 * {@code sourceId}'s stance toward {@code targetId}, never the reverse. A
 * one-directional IGNORE therefore does NOT silently suppress delivery to the
 * ignored party — it only gates <em>notifications</em> and default sorting for
 * the source. Callers that need symmetric suppression must persist two rows.
 * Ignore is NOT a server-side ban: it never bypasses channel permission, ban,
 * or audit checks (§提案08 @560-565).
 *
 * <p>The natural key is the composite {@code (sourceId, targetId, type)} — at
 * most one row of each type may exist for a given ordered pair. Persistence
 * upserts on this key (the JDBC provider deletes any prior matching row before
 * inserting; the memory provider does the same inside a {@code compute}). No
 * database-assigned surrogate id is used, so the reflection id-stamping pattern
 * from {@link com.nova.link.config.ConfigSnapshot} does not apply here.
 *
 * <p>Requirements: §11.6 item-18 (social relations & ignore)
 */
public final class SocialRelation {

    /** The kinds of directional relation one player may hold toward another. */
    public enum RelationType {
        /** Source declines notifications originating from / about the target. */
        IGNORE,
        /** Source pins the target to the top of the default sort. */
        FAVORITE
    }

    private final UUID sourceId;
    private final UUID targetId;
    private final RelationType type;
    private final long createdAt;
    private final long updatedAt;

    /**
     * Full-field constructor used by the store layer when hydrating persisted
     * rows (timestamps come straight from the database).
     *
     * @param sourceId  the player who holds the relation (not null)
     * @param targetId  the player the relation is held toward (not null)
     * @param type      the relation kind (not null)
     * @param createdAt epoch millis when the relation was first recorded
     * @param updatedAt epoch millis when the relation was last touched
     */
    public SocialRelation(UUID sourceId, UUID targetId, RelationType type,
                          long createdAt, long updatedAt) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.type = type;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Convenience constructor for a freshly-recorded relation. Both timestamps
     * are stamped to {@code System.currentTimeMillis()} — the provider does not
     * re-stamp them.
     *
     * @param sourceId  the player who holds the relation (not null)
     * @param targetId  the player the relation is held toward (not null)
     * @param type      the relation kind (not null)
     */
    public SocialRelation(UUID sourceId, UUID targetId, RelationType type) {
        this(sourceId, targetId, type, System.currentTimeMillis(), System.currentTimeMillis());
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public RelationType getType() {
        return type;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SocialRelation that = (SocialRelation) o;
        return Objects.equals(sourceId, that.sourceId)
                && Objects.equals(targetId, that.targetId)
                && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, targetId, type);
    }

    @Override
    public String toString() {
        return "SocialRelation{sourceId=" + sourceId
                + ", targetId=" + targetId
                + ", type=" + type
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt + '}';
    }
}
