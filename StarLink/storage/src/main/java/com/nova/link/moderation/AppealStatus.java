package com.nova.link.moderation;

/**
 * Lifecycle state of an {@link Appeal}.
 *
 * <p>PANEL-007: an appeal is created {@link #PENDING}, then moves to a reviewed
 * state after review. The reviewer must differ from the case's assigned
 * moderator (enforced server-side as a hard 403, not a silent fallback).
 *
 * <p>The reviewed states are intentionally permissive: the locked frontend
 * contract ({@code POST /api/appeals/{id}/review}) sends {@code decision}
 * values drawn from {@code {APPROVED, DENIED, ESCALATED}}, while the original
 * backend javadoc referenced {@link #REJECTED}. Both spellings are accepted by
 * {@link ModerationManager#reviewAppeal} so legacy callers and the panel UI
 * agree on the same {@code AppealStatus} name space; only {@link #PENDING} is
 * rejected as a review outcome.
 */
public enum AppealStatus {
    /** Filed, awaiting review. */
    PENDING,
    /** Reviewed and upheld (original resolution stands). */
    APPROVED,
    /** Reviewed and rejected (original resolution reversed). */
    REJECTED,
    /** Reviewed and the original resolution is denied/overturned (frontend
     *  spelling for REJECTED). */
    DENIED,
    /** Reviewed and escalated to a senior moderator for further handling. */
    ESCALATED
}
