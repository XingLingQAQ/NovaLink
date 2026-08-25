package com.nova.link.api;

import java.util.Objects;

/**
 * A staged configuration draft (§11.6 item-20 / proposal 10, doc-deferred
 * sub-item 1: draft → approve → publish workflow).
 *
 * <p>A draft carries the full YAML document the operator proposed, the
 * operator's panel username ({@code createdBy}), an immutable state machine
 * ({@link Status#DRAFT} → {@link Status#APPROVED} → {@link Status#PUBLISHED}),
 * and the audit actor that approved it. The draft is stored <em>masked</em>
 * the same way {@code config_history} snapshots are: every secret field is
 * replaced with {@code "***"} before persistence, so the {@code config_drafts}
 * table never holds plaintext secrets (same posture as
 * {@code audit_events.content_hash} and {@link com.nova.link.config.ConfigSnapshot}).
 *
 * <p>State machine:
 * <ul>
 *   <li>{@link Status#DRAFT} — initial; only state from which
 *       {@link ConfigPublishService#discardDraft} may remove the row.</li>
 *   <li>{@link Status#APPROVED} — a SUPER_ADMIN approver distinct from
 *       {@code createdBy} has signed off; the draft is now publishable.</li>
 *   <li>{@link Status#PUBLISHED} — the draft has been applied to the live
 *       config via {@link ConfigPublishService#publishDraft}; the row stays
 *       for audit and is no longer mutable.</li>
 * </ul>
 *
 * <p>The {@code id} field is the database-assigned row id (0 when not yet
 * persisted); it is stamped back by the provider via reflection, mirroring
 * the {@link com.nova.link.config.ConfigSnapshot} id-stamping pattern. The
 * {@code approvedBy} field is null until a successful
 * {@link ConfigPublishService#approveDraft} call stamps it. The
 * {@code approvedAt}/{@code publishedAt} fields are 0 until the matching
 * transition stamps them.
 *
 * <p>Requirements: §11.6 Project 20 (draft / approve / publish workflow).
 */
public final class ConfigDraft {

    /** Draft lifecycle states. */
    public enum Status {
        DRAFT,
        APPROVED,
        PUBLISHED
    }

    private long id;
    private final String draftJson;
    private final String createdBy;
    private Status status;
    private String approvedBy;
    private final long createdAt;
    private long approvedAt;
    private long publishedAt;

    /**
     * Full-field constructor used by the store layer when hydrating persisted
     * rows (id is the assigned database id).
     *
     * @param id          the database-assigned id (0 when not yet persisted)
     * @param draftJson   the MASKED JSON form of the proposed config
     * @param createdBy   the panel username that created the draft (not null)
     * @param status      the lifecycle state (not null)
     * @param approvedBy  the approver's panel username, or null if not yet approved
     * @param createdAt   epoch millis at which the draft was created
     * @param approvedAt  epoch millis at which the draft was approved, or 0
     * @param publishedAt epoch millis at which the draft was published, or 0
     */
    public ConfigDraft(long id, String draftJson, String createdBy, Status status,
                       String approvedBy, long createdAt, long approvedAt, long publishedAt) {
        this.id = id;
        this.draftJson = draftJson;
        this.createdBy = createdBy;
        this.status = status;
        this.approvedBy = approvedBy;
        this.createdAt = createdAt;
        this.approvedAt = approvedAt;
        this.publishedAt = publishedAt;
    }

    /**
     * Convenience constructor for a new (not-yet-persisted) DRAFT. The id is
     * set to 0, status defaults to {@link Status#DRAFT}, and the approvedBy/
     * approvedAt/publishedAt fields default to null/0/0. The provider stamps
     * the row id on insert.
     *
     * @param draftJson the MASKED JSON form of the proposed config
     * @param createdBy the panel username that created the draft (not null)
     * @param createdAt epoch millis at which the draft was created
     */
    public ConfigDraft(String draftJson, String createdBy, long createdAt) {
        this(0L, draftJson, createdBy, Status.DRAFT, null, createdAt, 0L, 0L);
    }

    public long getId() {
        return id;
    }

    public String getDraftJson() {
        return draftJson;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Status getStatus() {
        return status;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getApprovedAt() {
        return approvedAt;
    }

    public long getPublishedAt() {
        return publishedAt;
    }

    /**
     * Sets the database-assigned id. Public to match the reflection stamping
     * convention used for {@link com.nova.link.config.ConfigSnapshot}.
     */
    public void setId(long id) {
        this.id = id;
    }

    /**
     * Transitions the draft to {@link Status#APPROVED} and stamps the
     * approver. Package-private: only {@link ConfigPublishService} drives
     * the state machine.
     */
    void markApproved(String approver, long approvedAt) {
        this.status = Status.APPROVED;
        this.approvedBy = approver;
        this.approvedAt = approvedAt;
    }

    /**
     * Transitions the draft to {@link Status#PUBLISHED} and stamps the
     * publish time. Package-private: only {@link ConfigPublishService} drives
     * the state machine.
     */
    void markPublished(long publishedAt) {
        this.status = Status.PUBLISHED;
        this.publishedAt = publishedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConfigDraft that = (ConfigDraft) o;
        return id == that.id
                && createdAt == that.createdAt
                && approvedAt == that.approvedAt
                && publishedAt == that.publishedAt
                && status == that.status
                && Objects.equals(draftJson, that.draftJson)
                && Objects.equals(createdBy, that.createdBy)
                && Objects.equals(approvedBy, that.approvedBy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, draftJson, createdBy, status, approvedBy,
                createdAt, approvedAt, publishedAt);
    }

    @Override
    public String toString() {
        return "ConfigDraft{id=" + id
                + ", status=" + status
                + ", createdBy=" + createdBy
                + ", approvedBy=" + approvedBy
                + ", createdAt=" + createdAt
                + ", approvedAt=" + approvedAt
                + ", publishedAt=" + publishedAt + '}';
    }
}
