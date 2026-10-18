package com.nova.link.moderation;

/**
 * Lifecycle state of a {@link ModerationCase}.
 *
 * <p>PANEL-007: a case progresses OPEN → UNDER_REVIEW → RESOLVED, with APPEALED
 * and CLOSED as terminal-ish states. The status is the only mutable field on an
 * otherwise immutable case record (a new {@link ModerationCase} is persisted on
 * each transition so the audit trail stays append-only at the REST layer).
 *
 * <p>Requirements: PANEL-007 moderation case/appeal workflow
 */
public enum CaseStatus {
    /** Newly filed; no moderator assigned yet. */
    OPEN,
    /** A moderator has been assigned and is investigating. */
    UNDER_REVIEW,
    /** The case has been resolved with a {@link ResolutionAction}. */
    RESOLVED,
    /** The resolved case has been appealed and the appeal is pending review. */
    APPEALED,
    /** The case is closed: resolved and (if appealed) the appeal has been decided. */
    CLOSED
}
