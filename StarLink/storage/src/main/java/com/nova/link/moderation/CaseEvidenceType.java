package com.nova.link.moderation;

/**
 * The kind of evidence attached to a {@link ModerationCase}.
 *
 * <p>PANEL-007: evidence is the narrowest-scope read surface in the moderation
 * workflow — it is only retrievable via the case-scoped evidence endpoint
 * ({@code GET /api/moderation/cases/{id}/evidence}), never via a global list.
 * The type is stored for filtering but the payload itself is hashed, never the
 * raw secret/asset.
 */
public enum CaseEvidenceType {
    /** A chat-log excerpt (plain text, bounded length). */
    CHAT_LOG,
    /** A screenshot reference (URL or object key, hashed). */
    SCREENSHOT,
    /** A witness statement (plain text, bounded length). */
    WITNESS_STATEMENT,
    /** A system-generated snapshot (JSON, bounded size). */
    SYSTEM_SNAPSHOT
}
