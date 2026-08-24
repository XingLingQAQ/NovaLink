package com.nova.link.moderation;

import java.util.Objects;

/**
 * Immutable record of a single moderation case (a filed report plus its
 * lifecycle state).
 *
 * <p>PANEL-007: a case is created either through the panel reporting endpoint
 * ({@link CaseSource#PANEL}) or auto-created when a mute/ban/kick action
 * carries a linked {@code caseId} ({@link CaseSource#SYSTEM}). The case then
 * transitions through {@link CaseStatus} values as it is assigned, resolved,
 * and (optionally) appealed.
 *
 * <p>The {@code contentHash} is the SHA-256 hex digest of the report payload
 * (subject + reason + snapshot + reporter) computed at creation time; the raw
 * payload itself is never persisted beyond the bounded {@code reason} and
 * {@code snapshot} text fields. This mirrors the {@link com.nova.link.audit.AuditEvent}
 * convention of hashing rather than storing sensitive material.
 *
 * <p>Like {@link com.nova.link.audit.AuditEvent}, instances are immutable: a
 * transition (assign/resolve/close) produces a new {@code ModerationCase} that
 * the store persists, so the REST audit trail stays append-only at the value
 * layer.
 *
 * <p>Requirements: PANEL-007 moderation case/appeal workflow
 */
public final class ModerationCase {

    private final String id;
    private final String subjectPlayerId;
    private final String subjectDisplayName;
    private final String reporterName;
    private final ReporterSource reporterSource;
    private final CaseSource source;
    private final String channelId;
    private final String reason;
    private final String snapshot;
    private final CaseStatus status;
    private final String assignedModerator;
    private final ResolutionAction resolutionAction;
    private final String resolutionNote;
    private final String contentHash;
    private final long createdAt;
    private final long updatedAt;
    private final Long closedAt;

    /**
     * Full-field constructor used by the store layer when hydrating persisted
     * rows and by {@link ModerationManager} when creating a new case.
     *
     * @param id                the UUID identifying this case
     * @param subjectPlayerId   the UUID of the reported player
     * @param subjectDisplayName a human-readable name for the subject (may be null)
     * @param reporterName      the reporter identity (operator username or player UUID)
     * @param reporterSource    the kind of reporter
     * @param source            where the case originated
     * @param channelId         the channel the incident occurred in (may be null)
     * @param reason            a free-form reason (may be null, bounded to 1024 chars)
     * @param snapshot          a JSON context snapshot (may be null, bounded to 1024 chars)
     * @param status            the lifecycle status
     * @param assignedModerator the panel operator assigned to investigate (may be null)
     * @param resolutionAction  the action taken on resolution (may be null until resolved)
     * @param resolutionNote    a note recorded on resolution (may be null)
     * @param contentHash       SHA-256 hex of the report payload
     * @param createdAt         epoch millis at which the case was filed
     * @param updatedAt         epoch millis at which the case was last updated
     * @param closedAt          epoch millis at which the case was closed (may be null)
     */
    public ModerationCase(String id, String subjectPlayerId, String subjectDisplayName,
                          String reporterName, ReporterSource reporterSource, CaseSource source,
                          String channelId, String reason, String snapshot, CaseStatus status,
                          String assignedModerator, ResolutionAction resolutionAction,
                          String resolutionNote, String contentHash, long createdAt,
                          long updatedAt, Long closedAt) {
        this.id = id;
        this.subjectPlayerId = subjectPlayerId;
        this.subjectDisplayName = subjectDisplayName;
        this.reporterName = reporterName;
        this.reporterSource = reporterSource;
        this.source = source;
        this.channelId = channelId;
        this.reason = reason;
        this.snapshot = snapshot;
        this.status = status;
        this.assignedModerator = assignedModerator;
        this.resolutionAction = resolutionAction;
        this.resolutionNote = resolutionNote;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.closedAt = closedAt;
    }

    public String getId() {
        return id;
    }

    public String getSubjectPlayerId() {
        return subjectPlayerId;
    }

    public String getSubjectDisplayName() {
        return subjectDisplayName;
    }

    public String getReporterName() {
        return reporterName;
    }

    public ReporterSource getReporterSource() {
        return reporterSource;
    }

    public CaseSource getSource() {
        return source;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getReason() {
        return reason;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public CaseStatus getStatus() {
        return status;
    }

    public String getAssignedModerator() {
        return assignedModerator;
    }

    public ResolutionAction getResolutionAction() {
        return resolutionAction;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public Long getClosedAt() {
        return closedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ModerationCase)) {
            return false;
        }
        ModerationCase that = (ModerationCase) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "ModerationCase{id=" + id
                + ", subjectPlayerId=" + subjectPlayerId
                + ", status=" + status
                + ", source=" + source
                + ", assignedModerator=" + assignedModerator
                + ", createdAt=" + createdAt + '}';
    }
}
