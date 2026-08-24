package com.nova.link.moderation;

/**
 * Who filed a {@link ModerationCase}.
 *
 * <p>PANEL-007: a report is filed either by a named panel operator
 * ({@link #OPERATOR}) or by an in-game player identified by UUID
 * ({@link #PLAYER}). The reporter identity drives the contact-back path and is
 * persisted alongside the case for the audit trail.
 */
public enum ReporterSource {
    /** A panel operator (username stored in reporterName). */
    OPERATOR,
    /** An in-game player (UUID stored in reporterName). */
    PLAYER
}
