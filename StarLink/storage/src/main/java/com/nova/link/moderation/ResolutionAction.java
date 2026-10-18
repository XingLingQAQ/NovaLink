package com.nova.link.moderation;

/**
 * The action taken when a {@link ModerationCase} is resolved.
 *
 * <p>PANEL-007: the resolution action is recorded at {@link CaseStatus#RESOLVED}
 * and is immutable once set. It mirrors the existing mute/ban/kick verbs but is
 * a case-level record, not the enforcement mechanism itself (enforcement still
 * flows through MuteManager/BanManager with the linked caseId).
 */
public enum ResolutionAction {
    /** No action warranted; the report was dismissed. */
    DISMISSED,
    /** A warning was issued. */
    WARNED,
    /** The subject was muted. */
    MUTED,
    /** The subject was kicked. */
    KICKED,
    /** The subject was banned. */
    BANNED,
    /** The case was escalated to a senior moderator. */
    ESCALATED
}
