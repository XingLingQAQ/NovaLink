package com.nova.link.moderation;

/**
 * Where a {@link ModerationCase} originated.
 *
 * <p>PANEL-007: cases arrive either through the panel reporting form
 * ({@link #PANEL}) or are auto-created when a mute/ban/kick is executed with a
 * linked caseId ({@link #SYSTEM}). The source is recorded for audit but never
 * affects authorization.
 */
public enum CaseSource {
    /** Filed manually through the panel reporting endpoint. */
    PANEL,
    /** Auto-created by a mute/ban/kick action that carried a caseId. */
    SYSTEM
}
