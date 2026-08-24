package com.nova.link.moderation;

import java.util.Objects;

/**
 * Immutable record of an appeal against a resolved {@link ModerationCase}.
 *
 * <p>PANEL-007: an appeal is filed by the subject (or their representative)
 * after a case reaches {@link CaseStatus#RESOLVED}. The appeal is created
 * {@link AppealStatus#PENDING} and moves to {@link AppealStatus#APPROVED} or
 * {@link AppealStatus#REJECTED} once reviewed.
 *
 * <p>The reviewer must differ from the case's assigned moderator. This rule is
 * enforced server-side in {@link ModerationManager#reviewAppeal} as a hard 403 —
 * it is <em>not</em> a silent fallback to a default reviewer (the per-user
 * notification pattern uses a fallback; the appeal-reviewer rule must not).
 *
 * <p>The {@code contentHash} is the SHA-256 hex digest of the appeal payload
 * (caseId + appellant + appealReason), computed at creation time.
 *
 * <p>Requirements: PANEL-007 moderation case/appeal workflow
 */
public final class Appeal {

    private final String id;
    private final String caseId;
    private final String appellant;
    private final String appealReason;
    private final AppealStatus status;
    private final String reviewedBy;
    private final String reviewNote;
    private final Long reviewedAt;
    private final String contentHash;
    private final long createdAt;

    /**
     * Full-field constructor used by the store layer when hydrating persisted
     * rows and by {@link ModerationManager} when creating a new appeal.
     *
     * @param id           the UUID identifying this appeal
     * @param caseId       the UUID of the appealed {@link ModerationCase}
     * @param appellant    who filed the appeal (operator username or player UUID)
     * @param appealReason a free-form reason (bounded to 1024 chars)
     * @param status       the review status
     * @param reviewedBy   the panel operator who reviewed the appeal (may be null)
     * @param reviewNote   a note recorded on review (may be null)
     * @param reviewedAt   epoch millis at which the appeal was reviewed (may be null)
     * @param contentHash  SHA-256 hex of the appeal payload
     * @param createdAt    epoch millis at which the appeal was filed
     */
    public Appeal(String id, String caseId, String appellant, String appealReason,
                  AppealStatus status, String reviewedBy, String reviewNote,
                  Long reviewedAt, String contentHash, long createdAt) {
        this.id = id;
        this.caseId = caseId;
        this.appellant = appellant;
        this.appealReason = appealReason;
        this.status = status;
        this.reviewedBy = reviewedBy;
        this.reviewNote = reviewNote;
        this.reviewedAt = reviewedAt;
        this.contentHash = contentHash;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getCaseId() {
        return caseId;
    }

    public String getAppellant() {
        return appellant;
    }

    public String getAppealReason() {
        return appealReason;
    }

    public AppealStatus getStatus() {
        return status;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public Long getReviewedAt() {
        return reviewedAt;
    }

    public String getContentHash() {
        return contentHash;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Appeal)) {
            return false;
        }
        Appeal that = (Appeal) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "Appeal{id=" + id
                + ", caseId=" + caseId
                + ", status=" + status
                + ", appellant=" + appellant
                + ", reviewedBy=" + reviewedBy
                + ", createdAt=" + createdAt + '}';
    }
}
