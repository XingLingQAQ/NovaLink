package com.nova.link.moderation;

import java.util.Objects;

/**
 * Immutable record of a single piece of evidence attached to a
 * {@link ModerationCase}.
 *
 * <p>PANEL-007: evidence is the narrowest-scope read surface in the moderation
 * workflow. It is only retrievable via the case-scoped endpoint
 * ({@code GET /api/moderation/cases/{id}/evidence}); there is no global
 * evidence-list route, and {@code GET /api/private-messages} does not exist at
 * all (a 404 in the not-found branch handles its absence).
 *
 * <p>The {@code contentHash} is the SHA-256 hex digest of the evidence payload
 * (the raw screenshot bytes, chat excerpt, or snapshot JSON). The raw payload is
 * never persisted by this layer — only the hash and a bounded {@code description}.
 * This follows the {@link com.nova.link.audit.AuditEvent} convention of hashing
 * rather than storing sensitive material.
 *
 * <p>Like {@link com.nova.link.audit.AuditEvent} and
 * {@link com.nova.link.database.Notification}, the {@code id} is a
 * database-assigned {@code long} stamped in by the store layer on save (0
 * before persistence).
 *
 * <p>Requirements: PANEL-007 moderation case/appeal workflow
 */
public final class CaseEvidence {

    private final long id;
    private final String caseId;
    private final CaseEvidenceType evidenceType;
    private final String contentHash;
    private final String description;
    private final String submittedBy;
    private final long createdAt;

    /**
     * Full-field constructor used by the store layer when hydrating persisted
     * rows (id is the assigned database id).
     *
     * @param id           the database-assigned id (0 when not yet persisted)
     * @param caseId       the UUID of the owning {@link ModerationCase}
     * @param evidenceType the kind of evidence
     * @param contentHash  SHA-256 hex of the evidence payload
     * @param description  a bounded free-form description (may be null, ≤512 chars)
     * @param submittedBy  who attached the evidence (may be null)
     * @param createdAt    epoch millis at which the evidence was recorded
     */
    public CaseEvidence(long id, String caseId, CaseEvidenceType evidenceType,
                        String contentHash, String description, String submittedBy,
                        long createdAt) {
        this.id = id;
        this.caseId = caseId;
        this.evidenceType = evidenceType;
        this.contentHash = contentHash;
        this.description = description;
        this.submittedBy = submittedBy;
        this.createdAt = createdAt;
    }

    /**
     * Convenience constructor for new (not-yet-persisted) evidence. The id is
     * set to 0 and will be assigned by the store layer on save.
     */
    public CaseEvidence(String caseId, CaseEvidenceType evidenceType, String contentHash,
                        String description, String submittedBy, long createdAt) {
        this(0L, caseId, evidenceType, contentHash, description, submittedBy, createdAt);
    }

    public long getId() {
        return id;
    }

    public String getCaseId() {
        return caseId;
    }

    public CaseEvidenceType getEvidenceType() {
        return evidenceType;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getDescription() {
        return description;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CaseEvidence)) {
            return false;
        }
        CaseEvidence that = (CaseEvidence) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "CaseEvidence{id=" + id
                + ", caseId=" + caseId
                + ", evidenceType=" + evidenceType
                + ", contentHash=" + contentHash
                + ", createdAt=" + createdAt + '}';
    }
}
