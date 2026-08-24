package com.nova.link.moderation;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.audit.AuditEvent;
import com.nova.link.audit.AuditStore;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Business-logic service for the PANEL-007 moderation case/appeal workflow.
 *
 * <p>Holds the create/assign/resolve/add-evidence/create-appeal/review-appeal
 * lifecycle on top of {@link DatabaseProvider} (schema v11) and records every
 * state transition to the append-only {@link AuditStore}. Like the audit
 * layer, this service never persists raw secrets: the {@code contentHash} is
 * the SHA-256 hex of the report/evidence/appeal payload, and the bounded
 * {@code reason}/{@code snapshot} text is the only payload kept verbatim.
 *
 * <p><b>Permission isolation</b> — the appeal-reviewer rule is the central
 * security invariant of this workflow: the operator who reviews an appeal must
 * NOT be the same operator who was assigned as the case's moderator. This is
 * enforced server-side in {@link #reviewAppeal} as a hard {@code NC-403} via
 * {@link ModerationException} — it is <em>not</em> a silent fallback to a
 * default reviewer. This deliberately diverges from the per-user notification
 * pattern (where a missing {@code userId} falls back to the global stream);
 * the reviewer isolation rule must fail loudly so the attempted self-review is
 * visible in the audit trail.
 *
 * <p>The validation bounds (PANEL-007 §6):
 * <ul>
 *   <li>{@code item_json} schema: {@code itemId} ≤ 64 chars,
 *       {@code displayName} ≤ 128 chars, {@code count} 0–64,
 *       up to 16 enchantments each with id ≤ 64, total serialized ≤ 2KB.</li>
 *   <li>{@code mention} (player-identified report context) ≤ 512 chars.</li>
 *   <li>{@code reason} and {@code snapshot} each ≤ 1024 chars.</li>
 *   <li>{@code contentHash} is always a SHA-256 hex (64 lowercase chars).</li>
 * </ul>
 *
 * <p>Requirements: PANEL-007 moderation case/appeal workflow
 */
public class ModerationManager {

    private static final Logger logger = LoggerFactory.getLogger(ModerationManager.class);

    private static final Gson GSON = new Gson();

    private static final int REASON_MAX = 1024;
    private static final int SNAPSHOT_MAX = 1024;
    private static final int MENTION_MAX = 512;
    private static final int ITEM_JSON_MAX_BYTES = 2 * 1024; // 2KB
    private static final int ITEM_ID_MAX = 64;
    private static final int DISPLAY_NAME_MAX = 128;
    private static final int COUNT_MAX = 64;
    private static final int ENCHANTMENTS_MAX = 16;
    private static final int E2_MAX_PER_ID = 64;
    private static final int DESCRIPTION_MAX = 512;

    private final DatabaseProvider databaseProvider;
    private final AuditStore auditStore;

    /**
     * @param databaseProvider the persistence layer (must support moderation ops;
     *                          MemoryProvider and the JDBC providers do;
     *                          RedisProvider inherits the safe stub)
     * @param auditStore       the append-only audit store (may be null to disable
     *                          audit recording — same null tolerance as the
     *                          REST handler's audit hook)
     */
    public ModerationManager(DatabaseProvider databaseProvider, AuditStore auditStore) {
        this.databaseProvider = databaseProvider;
        this.auditStore = auditStore;
    }

    /**
     * Creates a new moderation case from a panel report.
     *
     * <p>PANEL-007 §6 validation is applied before persistence; a violation
     * raises {@link ModerationException} with code {@code NC-400}. The
     * {@code contentHash} is the SHA-256 hex of the subject + reason + snapshot
     * + reporterName payload (the raw payload is never persisted beyond the
     * bounded text fields). A {@code moderation.case.create} audit event is
     * recorded on success and {@code moderation.case.create.failure} on a
     * validation failure.
     *
     * @param subjectPlayerId    the reported player's UUID (required)
     * @param subjectDisplayName a human-readable subject name (may be null)
     * @param reporterName       the reporter identity (operator username or player UUID)
     * @param reporterSource     the kind of reporter
     * @param source             where the case originated
     * @param channelId          the channel context (may be null)
     * @param reason             a free-form reason (may be null, ≤1024 chars)
     * @param snapshot           a JSON context snapshot (may be null, ≤1024 chars)
     * @param itemJson           the validated {@code item_json} payload (may be null)
     * @param mention            the player-identified report context (may be null, ≤512 chars)
     * @param actor              the panel username for audit attribution (may be null)
     * @return the persisted {@link ModerationCase} (status {@link CaseStatus#OPEN})
     */
    public ModerationCase createReport(String subjectPlayerId, String subjectDisplayName,
                                       String reporterName, ReporterSource reporterSource,
                                       CaseSource source, String channelId, String reason,
                                       String snapshot, String itemJson, String mention,
                                       String actor) {
        validateSubject(subjectPlayerId);
        validateBounded("reason", reason, REASON_MAX);
        validateBounded("snapshot", snapshot, SNAPSHOT_MAX);
        validateBounded("mention", mention, MENTION_MAX);
        if (reporterSource == null) {
            throw new ModerationException("NC-400", "reporterSource is required");
        }
        if (source == null) {
            throw new ModerationException("NC-400", "source is required");
        }
        validateItemJson(itemJson);

        String hash = hashPayload(subjectPlayerId, reason, snapshot, reporterName);
        long now = System.currentTimeMillis();
        String caseId = UUID.randomUUID().toString();
        ModerationCase moderationCase = new ModerationCase(
                caseId, subjectPlayerId, subjectDisplayName, reporterName, reporterSource,
                source, channelId, reason, snapshot, CaseStatus.OPEN,
                null, null, null, hash, now, now, null);

        try {
            databaseProvider.saveModerationCase(moderationCase);
        } catch (DatabaseException e) {
            recordAudit(actor, "moderation.case.create", "moderation_case:" + caseId,
                    hash, null, reason, "failure");
            throw new ModerationException("NC-500", "Failed to persist moderation case: " + e.getMessage());
        }
        recordAudit(actor, "moderation.case.create", "moderation_case:" + caseId,
                null, hash, reason, "success");
        return moderationCase;
    }

    /**
     * Assigns a moderator to a case and flips its status to
     * {@link CaseStatus#UNDER_REVIEW}. A case that has already been closed or
     * resolved cannot be re-assigned ({@code NC-403}).
     *
     * @return the updated case
     */
    public ModerationCase assignCase(String caseId, String assignedModerator, String actor) {
        ModerationCase existing = requireCase(caseId);
        if (existing.getStatus() == CaseStatus.CLOSED) {
            throw new ModerationException("NC-403", "Cannot assign a closed case");
        }
        long now = System.currentTimeMillis();
        ModerationCase updated = new ModerationCase(
                existing.getId(), existing.getSubjectPlayerId(), existing.getSubjectDisplayName(),
                existing.getReporterName(), existing.getReporterSource(), existing.getSource(),
                existing.getChannelId(), existing.getReason(), existing.getSnapshot(),
                CaseStatus.UNDER_REVIEW, assignedModerator, existing.getResolutionAction(),
                existing.getResolutionNote(), existing.getContentHash(),
                existing.getCreatedAt(), now, existing.getClosedAt());
        persistCase(updated, "moderation.case.assign", actor, existing.getContentHash());
        return updated;
    }

    /**
     * Resolves a case with a {@link ResolutionAction}. The case moves to
     * {@link CaseStatus#RESOLVED} (or {@link CaseStatus#CLOSED} when the
     * resolution is {@link ResolutionAction#DISMISSED}). Only an OPEN or
     * UNDER_REVIEW case may be resolved ({@code NC-403} otherwise).
     *
     * @return the updated case
     */
    public ModerationCase resolveCase(String caseId, ResolutionAction action,
                                     String resolutionNote, String actor) {
        ModerationCase existing = requireCase(caseId);
        if (existing.getStatus() != CaseStatus.OPEN
                && existing.getStatus() != CaseStatus.UNDER_REVIEW) {
            throw new ModerationException("NC-403",
                    "Cannot resolve a case that is not OPEN or UNDER_REVIEW");
        }
        if (action == null) {
            throw new ModerationException("NC-400", "resolutionAction is required");
        }
        validateBounded("resolutionNote", resolutionNote, REASON_MAX);
        long now = System.currentTimeMillis();
        CaseStatus nextStatus = action == ResolutionAction.DISMISSED
                ? CaseStatus.CLOSED : CaseStatus.RESOLVED;
        // closedAt is only stamped when a case is actually closed (DISMISSED);
        // a RESOLVED case preserves the prior closedAt (which may be null). Use
        // an if-statement instead of a ternary so the boxed Long is never
        // unboxed to a primitive long (which would NPE on a null prior value).
        Long closedAt;
        if (nextStatus == CaseStatus.CLOSED) {
            closedAt = now;
        } else {
            closedAt = existing.getClosedAt();
        }
        ModerationCase updated = new ModerationCase(
                existing.getId(), existing.getSubjectPlayerId(), existing.getSubjectDisplayName(),
                existing.getReporterName(), existing.getReporterSource(), existing.getSource(),
                existing.getChannelId(), existing.getReason(), existing.getSnapshot(),
                nextStatus, existing.getAssignedModerator(), action,
                resolutionNote, existing.getContentHash(),
                existing.getCreatedAt(), now, closedAt);
        persistCase(updated, "moderation.case.resolve", actor, existing.getContentHash());
        return updated;
    }

    /**
     * Attaches a piece of evidence to a case. The raw payload is never
     * persisted — only its SHA-256 hex {@code contentHash} and a bounded
     * {@code description}. Evidence is append-only (no update/delete path),
     * matching the audit-event convention.
     *
     * @return the persisted evidence (with the database-assigned id stamped in)
     */
    public CaseEvidence addEvidence(String caseId, CaseEvidenceType evidenceType,
                                    String contentPayload, String description,
                                    String submittedBy, String actor) {
        ModerationCase existing = requireCase(caseId);
        if (evidenceType == null) {
            throw new ModerationException("NC-400", "evidenceType is required");
        }
        validateBounded("description", description, DESCRIPTION_MAX);
        String hash = hashPayload(contentPayload);
        long now = System.currentTimeMillis();
        CaseEvidence evidence = new CaseEvidence(
                existing.getId(), evidenceType, hash, description, submittedBy, now);
        try {
            databaseProvider.saveCaseEvidence(evidence);
        } catch (DatabaseException e) {
            recordAudit(actor, "moderation.evidence.add", "moderation_case:" + caseId,
                    hash, null, description, "failure");
            throw new ModerationException("NC-500", "Failed to persist case evidence: " + e.getMessage());
        }
        recordAudit(actor, "moderation.evidence.add", "moderation_case:" + caseId,
                null, hash, description, "success");
        return evidence;
    }

    /**
     * Lists all evidence attached to a case (oldest first). Evidence is the
     * narrowest-scope read surface in the workflow — it is only retrievable
     * via this case-scoped call, never via a global list endpoint.
     */
    public List<CaseEvidence> listEvidence(String caseId) {
        requireCase(caseId);
        try {
            return databaseProvider.listCaseEvidence(caseId);
        } catch (DatabaseException e) {
            throw new ModerationException("NC-500", "Failed to list case evidence: " + e.getMessage());
        }
    }

    /**
     * Creates an appeal against a resolved case. The appeal is created
     * {@link AppealStatus#PENDING}; the case status moves to
     * {@link CaseStatus#APPEALED}. Only a RESOLVED case may be appealed
     * ({@code NC-403} otherwise). The {@code contentHash} is the SHA-256 hex
     * of caseId + appellant + appealReason.
     *
     * @return the persisted appeal
     */
    public Appeal createAppeal(String caseId, String appellant, String appealReason, String actor) {
        ModerationCase existing = requireCase(caseId);
        if (existing.getStatus() != CaseStatus.RESOLVED) {
            throw new ModerationException("NC-403",
                    "Can only appeal a RESOLVED case");
        }
        validateBounded("appealReason", appealReason, REASON_MAX);
        String hash = hashPayload(caseId, appellant, appealReason);
        long now = System.currentTimeMillis();
        String appealId = UUID.randomUUID().toString();
        Appeal appeal = new Appeal(appealId, caseId, appellant, appealReason,
                AppealStatus.PENDING, null, null, null, hash, now);
        try {
            databaseProvider.saveAppeal(appeal);
        } catch (DatabaseException e) {
            recordAudit(actor, "moderation.appeal.create", "appeal:" + appealId,
                    hash, null, appealReason, "failure");
            throw new ModerationException("NC-500", "Failed to persist appeal: " + e.getMessage());
        }
        // Flip the case to APPEALED so listings reflect the open appeal.
        long nowCase = System.currentTimeMillis();
        ModerationCase updated = new ModerationCase(
                existing.getId(), existing.getSubjectPlayerId(), existing.getSubjectDisplayName(),
                existing.getReporterName(), existing.getReporterSource(), existing.getSource(),
                existing.getChannelId(), existing.getReason(), existing.getSnapshot(),
                CaseStatus.APPEALED, existing.getAssignedModerator(), existing.getResolutionAction(),
                existing.getResolutionNote(), existing.getContentHash(),
                existing.getCreatedAt(), nowCase, existing.getClosedAt());
        try {
            databaseProvider.saveModerationCase(updated);
        } catch (DatabaseException e) {
            // Non-fatal: the appeal itself was saved. Log and continue.
            logger.warn("Failed to flip case {} to APPEALED: {}", caseId, e.getMessage());
        }
        recordAudit(actor, "moderation.appeal.create", "appeal:" + appealId,
                null, hash, appealReason, "success");
        return appeal;
    }

    /**
     * Reviews an appeal, setting its status to a reviewed value.
     *
     * <p><b>Hard 403 — reviewer isolation:</b> {@code reviewedBy} must differ
     * from the case's {@code assignedModerator}. If they are the same (and the
     * case had an assigned moderator), this throws {@link ModerationException}
     * with code {@code NC-403} <em>before</em> persisting anything, and records
     * a {@code moderation.appeal.review.failure} audit event so the attempted
     * self-review is visible. This is deliberately NOT a silent fallback to a
     * default reviewer — the per-user notification pattern uses a fallback for
     * a missing userId, but the appeal-reviewer rule must fail loudly.
     *
     * <p>PANEL-007 contract drift: the locked frontend sends
     * {@code APPROVED/DENIED/ESCALATED}; the original backend javadoc named
     * {@code REJECTED}. All non-PENDING values are accepted here so the panel
     * UI and legacy callers agree on the same {@link AppealStatus} name space
     * (only {@link AppealStatus#PENDING} is rejected as a review outcome).
     *
     * @param appealId    the appeal UUID
     * @param status      APPROVED, REJECTED, DENIED or ESCALATED (PENDING is invalid)
     * @param reviewedBy  the reviewing operator's username
     * @param reviewNote  a free-form note (may be null)
     * @param actor       the panel username for audit attribution
     * @return the updated appeal
     */
    public Appeal reviewAppeal(String appealId, AppealStatus status,
                               String reviewedBy, String reviewNote, String actor) {
        Appeal appeal = requireAppeal(appealId);
        if (status == null || status == AppealStatus.PENDING) {
            throw new ModerationException("NC-400",
                    "Appeal review status must be APPROVED, REJECTED, DENIED or ESCALATED");
        }
        ModerationCase caseRecord = requireCase(appeal.getCaseId());
        String caseModerator = caseRecord.getAssignedModerator();
        if (caseModerator != null && !caseModerator.isBlank()
                && caseModerator.equals(reviewedBy)) {
            // Hard 403: the reviewer must differ from the assigned moderator.
            recordAudit(actor, "moderation.appeal.review", "appeal:" + appealId,
                    appeal.getContentHash(), null, reviewNote, "failure");
            throw new ModerationException("NC-403",
                    "Appeal reviewer must differ from the case moderator");
        }
        validateBounded("reviewNote", reviewNote, REASON_MAX);
        long now = System.currentTimeMillis();
        try {
            databaseProvider.updateAppealReview(appealId, status, reviewedBy, reviewNote, now);
        } catch (DatabaseException e) {
            recordAudit(actor, "moderation.appeal.review", "appeal:" + appealId,
                    appeal.getContentHash(), null, reviewNote, "failure");
            throw new ModerationException("NC-500", "Failed to update appeal review: " + e.getMessage());
        }
        recordAudit(actor, "moderation.appeal.review", "appeal:" + appealId,
                null, appeal.getContentHash(), reviewNote, "success");
        // Re-hydrate so the caller sees the reviewedBy/reviewedAt/status.
        return requireAppeal(appealId);
    }

    /**
     * Loads a single case by id.
     *
     * @return the case, or empty if not found
     */
    public Optional<ModerationCase> getCase(String caseId) {
        try {
            return databaseProvider.getModerationCase(caseId);
        } catch (DatabaseException e) {
            throw new ModerationException("NC-500", "Failed to load moderation case: " + e.getMessage());
        }
    }

    /**
     * Lists cases with pagination and an optional status filter (newest first).
     */
    public List<ModerationCase> listCases(int offset, int limit, String status) {
        try {
            return databaseProvider.listModerationCases(offset, limit, status);
        } catch (DatabaseException e) {
            throw new ModerationException("NC-500", "Failed to list moderation cases: " + e.getMessage());
        }
    }

    /**
     * Counts cases matching the optional status filter.
     */
    public int countCases(String status) {
        try {
            return databaseProvider.countModerationCases(status);
        } catch (DatabaseException e) {
            throw new ModerationException("NC-500", "Failed to count moderation cases: " + e.getMessage());
        }
    }

    /**
     * Loads a single appeal by id.
     */
    public Optional<Appeal> getAppeal(String appealId) {
        try {
            return databaseProvider.getAppeal(appealId);
        } catch (DatabaseException e) {
            throw new ModerationException("NC-500", "Failed to load appeal: " + e.getMessage());
        }
    }

    /**
     * Lists appeals with pagination and an optional status filter (newest first).
     */
    public List<Appeal> listAppeals(int offset, int limit, String status) {
        try {
            return databaseProvider.listAppeals(offset, limit, status);
        } catch (DatabaseException e) {
            throw new ModerationException("NC-500", "Failed to list appeals: " + e.getMessage());
        }
    }

    /**
     * Counts appeals matching the optional status filter.
     */
    public int countAppeals(String status) {
        try {
            return databaseProvider.countAppeals(status);
        } catch (DatabaseException e) {
            throw new ModerationException("NC-500", "Failed to count appeals: " + e.getMessage());
        }
    }

    // ==================== internals ====================

    private ModerationCase requireCase(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            throw new ModerationException("NC-400", "caseId is required");
        }
        try {
            Optional<ModerationCase> opt = databaseProvider.getModerationCase(caseId);
            if (opt.isEmpty()) {
                throw new ModerationException("NC-404", "Moderation case not found: " + caseId);
            }
            return opt.get();
        } catch (DatabaseException e) {
            throw new ModerationException("NC-500", "Failed to load moderation case: " + e.getMessage());
        }
    }

    private Appeal requireAppeal(String appealId) {
        if (appealId == null || appealId.isBlank()) {
            throw new ModerationException("NC-400", "appealId is required");
        }
        try {
            Optional<Appeal> opt = databaseProvider.getAppeal(appealId);
            if (opt.isEmpty()) {
                throw new ModerationException("NC-404", "Appeal not found: " + appealId);
            }
            return opt.get();
        } catch (DatabaseException e) {
            throw new ModerationException("NC-500", "Failed to load appeal: " + e.getMessage());
        }
    }

    private void persistCase(ModerationCase updated, String action, String actor, String contentHash) {
        try {
            databaseProvider.saveModerationCase(updated);
        } catch (DatabaseException e) {
            recordAudit(actor, action, "moderation_case:" + updated.getId(),
                    contentHash, null, updated.getReason(), "failure");
            throw new ModerationException("NC-500", "Failed to persist moderation case: " + e.getMessage());
        }
        recordAudit(actor, action, "moderation_case:" + updated.getId(),
                null, contentHash, updated.getReason(), "success");
    }

    private void recordAudit(String actor, String action, String resource,
                             String beforeHash, String afterHash,
                             String reason, String result) {
        if (auditStore == null) {
            return;
        }
        try {
            String eventId = UUID.randomUUID().toString();
            AuditEvent event = new AuditEvent(
                    eventId, null, actor, null, null, action, resource,
                    beforeHash, afterHash, reason, result, System.currentTimeMillis());
            auditStore.record(event);
        } catch (Exception e) {
            // Audit must never block the mutation.
            logger.warn("Failed to record moderation audit event action={}: {}", action, e.getMessage());
        }
    }

    private static void validateSubject(String subjectPlayerId) {
        if (subjectPlayerId == null || subjectPlayerId.isBlank()) {
            throw new ModerationException("NC-400", "subjectPlayerId is required");
        }
        if (subjectPlayerId.length() > ITEM_ID_MAX) {
            throw new ModerationException("NC-400", "subjectPlayerId exceeds " + ITEM_ID_MAX + " chars");
        }
    }

    private static void validateBounded(String fieldName, String value, int max) {
        if (value != null && value.length() > max) {
            throw new ModerationException("NC-400",
                    fieldName + " exceeds " + max + " chars");
        }
    }

    /**
     * Validates the {@code item_json} payload schema (PANEL-007 §6):
     * itemId ≤ 64, displayName ≤ 128, count 0–64, up to 16 enchantments each
     * with id ≤ 64, total serialized ≤ 2KB. A null {@code itemJson} is allowed
     * (the field is optional). Malformed JSON is rejected with {@code NC-400}.
     */
    static void validateItemJson(String itemJson) {
        if (itemJson == null || itemJson.isBlank()) {
            return;
        }
        if (itemJson.getBytes(StandardCharsets.UTF_8).length > ITEM_JSON_MAX_BYTES) {
            throw new ModerationException("NC-400", "item_json exceeds 2KB");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(itemJson);
        } catch (Exception e) {
            throw new ModerationException("NC-400", "item_json is not valid JSON");
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new ModerationException("NC-400", "item_json must be a JSON object");
        }
        JsonObject obj = parsed.getAsJsonObject();
        if (obj.has("itemId")) {
            validateStringField(obj, "itemId", ITEM_ID_MAX);
        }
        if (obj.has("displayName")) {
            validateStringField(obj, "displayName", DISPLAY_NAME_MAX);
        }
        if (obj.has("count")) {
            try {
                int count = obj.get("count").getAsInt();
                if (count < 0 || count > COUNT_MAX) {
                    throw new ModerationException("NC-400",
                            "item_json count out of range [0," + COUNT_MAX + "]");
                }
            } catch (ModerationException e) {
                throw e;
            } catch (Exception e) {
                throw new ModerationException("NC-400", "item_json count must be an integer");
            }
        }
        if (obj.has("enchantments")) {
            JsonElement ench = obj.get("enchantments");
            if (!ench.isJsonArray()) {
                throw new ModerationException("NC-400", "item_json enchantments must be an array");
            }
            if (ench.getAsJsonArray().size() > ENCHANTMENTS_MAX) {
                throw new ModerationException("NC-400",
                        "item_json enchantments exceed " + ENCHANTMENTS_MAX + " entries");
            }
            for (JsonElement e : ench.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    throw new ModerationException("NC-400",
                            "item_json enchantment entries must be objects");
                }
                JsonObject entry = e.getAsJsonObject();
                if (entry.has("id")) {
                    validateStringField(entry, "id", E2_MAX_PER_ID);
                }
            }
        }
    }

    private static void validateStringField(JsonObject obj, String fieldName, int max) {
        JsonElement el = obj.get(fieldName);
        if (el == null || el.isJsonNull()) {
            return;
        }
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            throw new ModerationException("NC-400", "item_json " + fieldName + " must be a string");
        }
        String s = el.getAsString();
        if (s.length() > max) {
            throw new ModerationException("NC-400",
                    "item_json " + fieldName + " exceeds " + max + " chars");
        }
    }

    /**
     * SHA-256 hex digest of the concatenated payload parts (UTF-8). Mirrors
     * {@link AuditEvent#hashJson} semantics — null parts are skipped, and the
     * raw payload is never persisted beyond the bounded text fields.
     */
    static String hashPayload(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            boolean any = false;
            for (String p : parts) {
                if (p == null) {
                    continue;
                }
                any = true;
                md.update(p.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0x1f); // unit separator so a|bc != ab|c
            }
            if (!any) {
                return null;
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA; effectively unreachable.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * @return whether {@code reviewer} equals the case's assigned moderator.
     * Package-private so tests can assert the isolation check directly.
     */
    static boolean isSameReviewer(ModerationCase caseRecord, String reviewer) {
        Objects.requireNonNull(caseRecord, "caseRecord");
        String moderator = caseRecord.getAssignedModerator();
        return moderator != null && !moderator.isBlank() && moderator.equals(reviewer);
    }
}
