package com.nova.link.database;

import com.nova.link.channel.Channel;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface for database operations in the NovaLink system.
 * Provides abstraction for different storage backends (MySQL, Redis, Memory).
 * 
 * Requirements: 22.1, 22.2, 22.3, 22.5
 */
public interface DatabaseProvider {

    /**
     * Initializes the database connection and creates necessary tables/structures.
     *
     * @throws DatabaseException if initialization fails
     */
    void initialize() throws DatabaseException;

    /**
     * Closes the database connection and releases resources.
     */
    void shutdown();

    /**
     * Checks if the database is connected and operational.
     *
     * @return true if the database is available
     */
    boolean isConnected();

    // ==================== Player State Operations ====================

    /**
     * Saves a player's state to the database.
     *
     * @param state the player state to save
     * @throws DatabaseException if the save operation fails
     */
    void savePlayerState(PlayerState state) throws DatabaseException;

    /**
     * Loads a player's state from the database.
     *
     * @param playerId the player UUID
     * @return the player state, or empty if not found
     * @throws DatabaseException if the load operation fails
     */
    Optional<PlayerState> loadPlayerState(UUID playerId) throws DatabaseException;

    /**
     * Deletes a player's state from the database.
     *
     * @param playerId the player UUID
     * @throws DatabaseException if the delete operation fails
     */
    void deletePlayerState(UUID playerId) throws DatabaseException;

    /**
     * Gets all player states (for migration or backup purposes).
     *
     * @return list of all player states
     * @throws DatabaseException if the operation fails
     */
    List<PlayerState> getAllPlayerStates() throws DatabaseException;

    // ==================== Channel Operations ====================

    /**
     * Saves a channel configuration to the database.
     *
     * @param channel the channel to save
     * @throws DatabaseException if the save operation fails
     */
    void saveChannel(Channel channel) throws DatabaseException;

    /**
     * Loads a channel from the database.
     *
     * @param channelId the channel ID
     * @return the channel, or empty if not found
     * @throws DatabaseException if the load operation fails
     */
    Optional<Channel> loadChannel(String channelId) throws DatabaseException;

    /**
     * Deletes a channel from the database.
     *
     * @param channelId the channel ID
     * @throws DatabaseException if the delete operation fails
     */
    void deleteChannel(String channelId) throws DatabaseException;

    /**
     * Gets all channels from the database.
     *
     * @return list of all channels
     * @throws DatabaseException if the operation fails
     */
    List<Channel> getAllChannels() throws DatabaseException;

    // ==================== Mute Operations ====================

    /**
     * Saves a mute record.
     *
     * @param playerId the player UUID
     * @param muteInfo the mute information
     * @throws DatabaseException if the save operation fails
     */
    void saveMute(UUID playerId, MuteInfo muteInfo) throws DatabaseException;

    /**
     * Loads all mutes for a player.
     *
     * @param playerId the player UUID
     * @return list of mute records
     * @throws DatabaseException if the load operation fails
     */
    List<MuteInfo> loadMutes(UUID playerId) throws DatabaseException;

    /**
     * Deletes a mute record.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID (null for global mute)
     * @throws DatabaseException if the delete operation fails
     */
    void deleteMute(UUID playerId, String channelId) throws DatabaseException;

    /**
     * Deletes all expired mutes.
     *
     * @return number of mutes deleted
     * @throws DatabaseException if the operation fails
     */
    int cleanupExpiredMutes() throws DatabaseException;

    /**
     * Loads all active mutes (permanent, or expiring in the future) grouped by
     * player. Used at startup to warm the mute cache so persisted mutes
     * survive a backend restart.
     *
     * @return map of player UUID to that player's active mute records
     * @throws DatabaseException if the load operation fails
     */
    Map<UUID, List<MuteInfo>> getAllActiveMutes() throws DatabaseException;

    // ==================== Ban Operations ====================

    /**
     * Saves a ban record.
     *
     * @param playerId the player UUID
     * @param banInfo the ban information
     * @throws DatabaseException if the save operation fails
     */
    void saveBan(UUID playerId, BanInfo banInfo) throws DatabaseException;

    /**
     * Loads all bans for a player.
     *
     * @param playerId the player UUID
     * @return list of ban records
     * @throws DatabaseException if the load operation fails
     */
    List<BanInfo> loadBans(UUID playerId) throws DatabaseException;

    /**
     * Deletes a ban record.
     *
     * @param playerId the player UUID
     * @param channelId the channel ID (null for global ban)
     * @throws DatabaseException if the delete operation fails
     */
    void deleteBan(UUID playerId, String channelId) throws DatabaseException;

    /**
     * Deletes all expired bans.
     *
     * @return number of bans deleted
     * @throws DatabaseException if the operation fails
     */
    int cleanupExpiredBans() throws DatabaseException;

    /**
     * Loads all active bans (permanent, or expiring in the future) grouped by
     * player. Used at startup to warm the ban cache so persisted bans survive
     * a backend restart.
     *
     * @return map of player UUID to that player's active ban records
     * @throws DatabaseException if the load operation fails
     */
    Map<UUID, List<BanInfo>> getAllActiveBans() throws DatabaseException;

    // ==================== Notification Operations ====================

    /**
     * Saves a notification record. The provider assigns the id and returns the
     * persisted notification (with the generated id) to the caller via the
     * store layer.
     *
     * @param notification the notification to save
     * @throws DatabaseException if the save operation fails
     */
    void saveNotification(Notification notification) throws DatabaseException;

    /**
     * Loads notifications with pagination.
     *
     * @param offset the offset (0-based)
     * @param limit the maximum number of notifications to return
     * @param unreadOnly when true, only unread notifications are returned
     * @return list of notifications ordered by created_at descending
     * @throws DatabaseException if the load operation fails
     */
    List<Notification> getNotifications(int offset, int limit, boolean unreadOnly) throws DatabaseException;

    /**
     * Marks a notification as read.
     *
     * @param id the notification id
     * @throws DatabaseException if the operation fails
     */
    void markNotificationRead(long id) throws DatabaseException;

    /**
     * Marks all unread notifications as read.
     *
     * @throws DatabaseException if the operation fails
     */
    void markAllNotificationsRead() throws DatabaseException;

    /**
     * Deletes all notifications.
     *
     * @return number of notifications deleted
     * @throws DatabaseException if the operation fails
     */
    int clearNotifications() throws DatabaseException;

    /**
     * Gets the count of unread notifications.
     *
     * @return the unread count
     * @throws DatabaseException if the operation fails
     */
    int getUnreadCount() throws DatabaseException;

    /**
     * Counts notifications matching the pagination filter. Used to report the
     * real total for paginated notification listings.
     *
     * @param unreadOnly when true, only unread notifications are counted
     * @return the total number of matching notifications
     * @throws DatabaseException if the operation fails
     */
    int countNotifications(boolean unreadOnly) throws DatabaseException;

    // --- Per-user notification state (PANEL-014, schema v10) ---
    // These methods operate on per-user read state in the notification_read
    // table. They are interface default methods that throw
    // UnsupportedOperationException so providers that have not been upgraded
    // (e.g. RedisProvider) inherit a safe stub and continue to compile. The
    // JDBC and memory providers override them with real implementations.

    /**
     * Lists notifications visible to a specific user with pagination and
     * per-user read state. Visible notifications are those where recipient is
     * null (broadcast) or recipient equals userId. When {@code unreadOnly} is
     * true, only notifications the user has not yet marked read are returned.
     *
     * <p>Migration-period double-read: if the {@code notification_read} table
     * is absent, providers fall back to the global {@code notifications.read}
     * column so a partially-migrated deployment keeps working.
     *
     * @param offset the offset (0-based)
     * @param limit the maximum number of notifications to return
     * @param unreadOnly when true, only unread notifications are returned
     * @param userId the per-user identity (panel username), never null
     * @return list of notifications ordered by created_at descending
     * @throws DatabaseException if the load operation fails
     */
    default List<Notification> getNotifications(int offset, int limit, boolean unreadOnly, String userId)
            throws DatabaseException {
        throw new UnsupportedOperationException("Per-user notifications not supported by this provider");
    }

    /**
     * Marks a single notification as read for a specific user. Records a row
     * in {@code notification_read} (upsert) so the per-user unread count drops
     * by one for this user only.
     *
     * @param id the notification id
     * @param userId the per-user identity (panel username), never null
     * @throws DatabaseException if the operation fails
     */
    default void markNotificationRead(long id, String userId) throws DatabaseException {
        throw new UnsupportedOperationException("Per-user notifications not supported by this provider");
    }

    /**
     * Marks all notifications visible to a specific user as read. Only
     * notifications the user can see (broadcast + their directed ones) are
     * marked read; other admins' inboxes are unaffected.
     *
     * @param userId the per-user identity (panel username), never null
     * @throws DatabaseException if the operation fails
     */
    default void markAllNotificationsRead(String userId) throws DatabaseException {
        throw new UnsupportedOperationException("Per-user notifications not supported by this provider");
    }

    /**
     * Gets the count of unread notifications for a specific user.
     *
     * @param userId the per-user identity (panel username), never null
     * @return the unread count for this user
     * @throws DatabaseException if the operation fails
     */
    default int getUnreadCount(String userId) throws DatabaseException {
        throw new UnsupportedOperationException("Per-user notifications not supported by this provider");
    }

    /**
     * Counts notifications visible to a specific user matching the pagination
     * filter. Used to report the real total for paginated notification
     * listings.
     *
     * @param unreadOnly when true, only unread notifications are counted
     * @param userId the per-user identity (panel username), never null
     * @return the total number of matching notifications visible to this user
     * @throws DatabaseException if the count fails
     */
    default int countNotifications(boolean unreadOnly, String userId) throws DatabaseException {
        throw new UnsupportedOperationException("Per-user notifications not supported by this provider");
    }

    /**
     * Clears notifications for a specific user. Only directed notifications
     * where recipient equals userId are deleted; broadcast events are never
     * removed by this call (they require the global SUPER_ADMIN clear path).
     *
     * @param userId the per-user identity (panel username), never null
     * @return number of directed notifications deleted
     * @throws DatabaseException if the operation fails
     */
    default int clearNotifications(String userId) throws DatabaseException {
        throw new UnsupportedOperationException("Per-user notifications not supported by this provider");
    }

    // ==================== Audit Operations (schema v9) ====================

    /**
     * Saves an audit event (append-only). The provider assigns the id and
     * stamps it back onto the record via {@link com.nova.link.audit.AuditEvent#getId()}
     * if the underlying store supports generated ids (JDBC backends do;
     * MemoryProvider stamps the id via reflection).
     *
     * <p>Audit events are immutable: there is no update or delete path. A
     * persistence failure is surfaced to the {@link com.nova.link.audit.AuditStore}
     * layer, which swallows it so the audited mutation is not blocked.
     *
     * @param event the audit event to persist (not null)
     * @throws DatabaseException if the save operation fails
     */
    void saveAuditEvent(com.nova.link.audit.AuditEvent event) throws DatabaseException;

    /**
     * Lists audit events with pagination and optional actor/action filters,
     * ordered by created_at descending (newest first). A null or empty
     * actor/action argument means "no filter on that column".
     *
     * @param offset the 0-based row offset
     * @param limit  the maximum number of events to return
     * @param actor  optional actor filter (null/empty = no filter)
     * @param action optional action filter (null/empty = no filter)
     * @return the matching audit events, newest first
     * @throws DatabaseException if the load operation fails
     */
    List<com.nova.link.audit.AuditEvent> getAuditEvents(int offset, int limit, String actor, String action) throws DatabaseException;

    /**
     * Counts audit events matching the optional actor/action filters. Used to
     * report the real total for paginated audit listings.
     *
     * @param actor  optional actor filter (null/empty = no filter)
     * @param action optional action filter (null/empty = no filter)
     * @return the total number of matching events
     * @throws DatabaseException if the count fails
     */
    int countAuditEvents(String actor, String action) throws DatabaseException;

    // ==================== Moderation Operations (schema v11) ====================
    // PANEL-007: moderation case/appeal workflow. These methods operate on the
    // moderation_cases / case_evidence / appeals tables introduced by schema
    // migration v11. They are interface default methods that throw
    // UnsupportedOperationException so providers that have not been upgraded
    // (e.g. RedisProvider) inherit a safe stub and continue to compile. The JDBC
    // and memory providers override them with real implementations.
    //
    // NOTE: the appeal-reviewer-must-differ-from-case-moderator rule is NOT
    // enforced here; it is enforced by ModerationManager as a hard 403 (not a
    // silent fallback like the per-user notification null-userId path).

    /**
     * Saves (inserts) a moderation case. The case {@code id} is assigned by the
     * caller (a UUID); persistence is upsert-style on id so a re-save of the
     * same case (e.g. after a status transition) updates the row.
     *
     * @param moderationCase the case to persist (not null)
     * @throws DatabaseException if the save operation fails
     */
    default void saveModerationCase(com.nova.link.moderation.ModerationCase moderationCase)
            throws DatabaseException {
        throw new UnsupportedOperationException("Moderation cases not supported by this provider");
    }

    /**
     * Loads a moderation case by id.
     *
     * @param caseId the case UUID
     * @return the case, or empty if not found
     * @throws DatabaseException if the load operation fails
     */
    default java.util.Optional<com.nova.link.moderation.ModerationCase> getModerationCase(String caseId)
            throws DatabaseException {
        throw new UnsupportedOperationException("Moderation cases not supported by this provider");
    }

    /**
     * Lists moderation cases with pagination and an optional status filter,
     * ordered by created_at descending (newest first).
     *
     * @param offset the 0-based row offset
     * @param limit  the maximum number of cases to return
     * @param status optional status filter (null/empty = no filter)
     * @return the matching cases, newest first
     * @throws DatabaseException if the load operation fails
     */
    default List<com.nova.link.moderation.ModerationCase> listModerationCases(int offset, int limit,
                                                                              String status)
            throws DatabaseException {
        throw new UnsupportedOperationException("Moderation cases not supported by this provider");
    }

    /**
     * Counts moderation cases matching the optional status filter.
     *
     * @param status optional status filter (null/empty = no filter)
     * @return the total number of matching cases
     * @throws DatabaseException if the count fails
     */
    default int countModerationCases(String status) throws DatabaseException {
        throw new UnsupportedOperationException("Moderation cases not supported by this provider");
    }

    /**
     * Saves (inserts) a piece of case evidence. The provider assigns the id and
     * stamps it back onto the record via reflection (matching the audit-event
     * and notification id-stamping pattern).
     *
     * @param evidence the evidence to persist (not null)
     * @throws DatabaseException if the save operation fails
     */
    default void saveCaseEvidence(com.nova.link.moderation.CaseEvidence evidence)
            throws DatabaseException {
        throw new UnsupportedOperationException("Moderation cases not supported by this provider");
    }

    /**
     * Lists all evidence attached to a case, ordered by created_at ascending
     * (oldest first, so the evidence trail reads chronologically).
     *
     * @param caseId the case UUID
     * @return the evidence for the case, oldest first
     * @throws DatabaseException if the load operation fails
     */
    default List<com.nova.link.moderation.CaseEvidence> listCaseEvidence(String caseId)
            throws DatabaseException {
        throw new UnsupportedOperationException("Moderation cases not supported by this provider");
    }

    /**
     * Saves (inserts) an appeal. The appeal {@code id} is assigned by the caller
     * (a UUID).
     *
     * @param appeal the appeal to persist (not null)
     * @throws DatabaseException if the save operation fails
     */
    default void saveAppeal(com.nova.link.moderation.Appeal appeal) throws DatabaseException {
        throw new UnsupportedOperationException("Moderation cases not supported by this provider");
    }

    /**
     * Loads an appeal by id.
     *
     * @param appealId the appeal UUID
     * @return the appeal, or empty if not found
     * @throws DatabaseException if the load operation fails
     */
    default java.util.Optional<com.nova.link.moderation.Appeal> getAppeal(String appealId)
            throws DatabaseException {
        throw new UnsupportedOperationException("Moderation cases not supported by this provider");
    }

    /**
     * Lists appeals with pagination and an optional status filter, ordered by
     * created_at descending (newest first).
     *
     * @param offset the 0-based row offset
     * @param limit  the maximum number of appeals to return
     * @param status optional status filter (null/empty = no filter)
     * @return the matching appeals, newest first
     * @throws DatabaseException if the load operation fails
     */
    default List<com.nova.link.moderation.Appeal> listAppeals(int offset, int limit, String status)
            throws DatabaseException {
        throw new UnsupportedOperationException("Moderation cases not supported by this provider");
    }

    /**
     * Counts appeals matching the optional status filter.
     *
     * @param status optional status filter (null/empty = no filter)
     * @return the total number of matching appeals
     * @throws DatabaseException if the count fails
     */
    default int countAppeals(String status) throws DatabaseException {
        throw new UnsupportedOperationException("Moderation cases not supported by this provider");
    }

    /**
     * Updates an appeal with the reviewer decision. Records the reviewer, review
     * note, reviewed_at, and new status. The reviewer-must-differ-from-case-
     * moderator rule is enforced by {@link com.nova.link.moderation.ModerationManager}
     * before this method is reached; this method only persists the decision.
     *
     * @param appealId   the appeal UUID
     * @param status     the new appeal status
     * @param reviewedBy the reviewing operator
     * @param reviewNote a free-form note (may be null)
     * @param reviewedAt epoch millis at which the review occurred
     * @throws DatabaseException if the update fails
     */
    default void updateAppealReview(String appealId, com.nova.link.moderation.AppealStatus status,
                                    String reviewedBy, String reviewNote, long reviewedAt)
            throws DatabaseException {
        throw new UnsupportedOperationException("Moderation cases not supported by this provider");
    }

    // ==================== Config History Operations ====================
    // §11.6 Project 20 / PANEL proposal 10 — masked config snapshots + atomic
    // rollback. All five methods share the DatabaseProvider optional-method
    // convention: JDBC + Memory providers override them; RedisProvider inherits
    // the UnsupportedOperationException default (safe stub).

    /**
     * Persists a masked config snapshot keyed by the monotonic settings
     * revision (PANEL-010). The caller is responsible for masking secrets
     * before this method is reached — {@code snapshotJson} MUST already have
     * every secret field replaced with {@code "***"}. The provider stamps the
     * generated row id back onto the snapshot via reflection, matching the
     * audit-event / notification id-stamping pattern. The newly inserted row
     * is marked active and every prior row is deactivated atomically.
     *
     * @param snapshot the snapshot to persist (not null; revision + masked json
     *                 already populated)
     * @throws DatabaseException if the save operation fails
     */
    default void saveConfigSnapshot(com.nova.link.config.ConfigSnapshot snapshot)
            throws DatabaseException {
        throw new UnsupportedOperationException("Config history not supported by this provider");
    }

    /**
     * Lists config snapshots newest-first, WITHOUT the full snapshot_json blob
     * (callers that need the payload must call {@link #getConfigSnapshot(long)}).
     * Returns only the metadata columns needed to render a history list.
     *
     * @param limit the maximum number of snapshots to return
     * @return the matching snapshots (no payload), newest first
     * @throws DatabaseException if the load operation fails
     */
    default List<com.nova.link.config.ConfigSnapshot> getConfigHistory(int limit)
            throws DatabaseException {
        throw new UnsupportedOperationException("Config history not supported by this provider");
    }

    /**
     * Loads a single masked config snapshot by revision, including its
     * snapshot_json payload.
     *
     * @param revision the settings revision to look up
     * @return the snapshot, or empty if not found
     * @throws DatabaseException if the load operation fails
     */
    default java.util.Optional<com.nova.link.config.ConfigSnapshot> getConfigSnapshot(long revision)
            throws DatabaseException {
        throw new UnsupportedOperationException("Config history not supported by this provider");
    }

    /**
     * Counts the total number of persisted config snapshots.
     *
     * @return the snapshot count
     * @throws DatabaseException if the count fails
     */
    default int countConfigSnapshots() throws DatabaseException {
        throw new UnsupportedOperationException("Config history not supported by this provider");
    }

    /**
     * Deactivates every config snapshot except the one identified by
     * {@code activeRevision} (or every snapshot when {@code activeRevision}
     * is negative). Used by the atomic rollback path to flip the active flag
     * without deleting prior history (append-only contract).
     *
     * @param activeRevision the revision to keep active, or a negative value to
     *                       deactivate all rows
     * @return the number of rows deactivated
     * @throws DatabaseException if the update fails
     */
    default int deactivateOtherSnapshots(long activeRevision) throws DatabaseException {
        throw new UnsupportedOperationException("Config history not supported by this provider");
    }

    // ==================== Config Drafts (schema v15 / proposal 10) ====================
    // §11.6 item-20 / PANEL proposal 10 — staged configuration draft / approve /
    // publish workflow. The config_drafts table stores MASKED drafts keyed by
    // database id; the state machine (DRAFT → APPROVED → PUBLISHED) is driven
    // by ConfigPublishService. All four methods share the DatabaseProvider
    // optional-method convention: JDBC + Memory providers override them;
    // RedisProvider inherits the UnsupportedOperationException default (safe
    // stub). Drafts are masked at the service layer before they reach the
    // provider, so the table never stores plaintext secrets.

    /**
     * Inserts a new config draft row. The provider stamps the row id back via
     * reflection. The {@code draftJson} field MUST already be masked by the
     * caller ({@link com.nova.link.api.ConfigHistoryService#maskSecrets}).
     *
     * @param draft the draft to persist (not null)
     * @throws DatabaseException if the save fails
     */
    default void saveConfigDraft(com.nova.link.api.ConfigDraft draft) throws DatabaseException {
        throw new UnsupportedOperationException("Config drafts not supported by this provider");
    }

    /**
     * Loads a single config draft by id, including its masked draft_json payload.
     *
     * @param id the draft id
     * @return the draft, or empty if not found
     * @throws DatabaseException if the load fails
     */
    default java.util.Optional<com.nova.link.api.ConfigDraft> getConfigDraft(long id)
            throws DatabaseException {
        throw new UnsupportedOperationException("Config drafts not supported by this provider");
    }

    /**
     * Lists config drafts newest-first, WITHOUT the full draft_json blob.
     * Returns only the metadata columns needed to render a draft list.
     *
     * @param limit the maximum number of drafts to return
     * @return the matching drafts (no payload), newest first
     * @throws DatabaseException if the load fails
     */
    default java.util.List<com.nova.link.api.ConfigDraft> listConfigDrafts(int limit)
            throws DatabaseException {
        throw new UnsupportedOperationException("Config drafts not supported by this provider");
    }

    /**
     * Updates the state of a config draft row (status, approved_by, approved_at,
     * published_at). A no-op when no such draft exists.
     *
     * @param id          the draft id
     * @param status      the new status (not null)
     * @param approvedBy  the approver's username, or null
     * @param approvedAt  the approval timestamp, or 0
     * @param publishedAt the publish timestamp, or 0
     * @throws DatabaseException if the update fails
     */
    default void updateConfigDraftStatus(long id, com.nova.link.api.ConfigDraft.Status status,
                                         String approvedBy, long approvedAt, long publishedAt)
            throws DatabaseException {
        throw new UnsupportedOperationException("Config drafts not supported by this provider");
    }

    /**
     * Deletes a config draft row by id. A no-op when no such draft exists.
     *
     * @param id the draft id
     * @throws DatabaseException if the delete fails
     */
    default void deleteConfigDraft(long id) throws DatabaseException {
        throw new UnsupportedOperationException("Config drafts not supported by this provider");
    }

    // ==================== Config Backups (schema v15 / proposal 10) ====================
    // §11.6 item-20 / PANEL proposal 10 — explicit backup / restore mechanism.
    // The config_backups table stores MASKED named backups keyed by database
    // id; backups are created by ConfigPublishService.createBackup and
    // restored by ConfigPublishService.restoreFromBackup. All three methods
    // share the DatabaseProvider optional-method convention: JDBC + Memory
    // providers override them; RedisProvider inherits the
    // UnsupportedOperationException default (safe stub). Backups are masked at
    // the service layer before they reach the provider, so the table never
    // stores plaintext secrets.

    /**
     * Inserts a new config backup row. The provider stamps the row id back via
     * reflection. The {@code backupJson} field MUST already be masked by the
     * caller ({@link com.nova.link.api.ConfigHistoryService#maskSecrets}).
     *
     * @param backup the backup to persist (not null)
     * @throws DatabaseException if the save fails
     */
    default void saveConfigBackup(com.nova.link.api.ConfigBackup backup) throws DatabaseException {
        throw new UnsupportedOperationException("Config backups not supported by this provider");
    }

    /**
     * Loads a single config backup by id, including its masked backup_json payload.
     *
     * @param id the backup id
     * @return the backup, or empty if not found
     * @throws DatabaseException if the load fails
     */
    default java.util.Optional<com.nova.link.api.ConfigBackup> getConfigBackup(long id)
            throws DatabaseException {
        throw new UnsupportedOperationException("Config backups not supported by this provider");
    }

    /**
     * Lists config backups newest-first, WITHOUT the full backup_json blob.
     * Returns only the metadata columns needed to render a backup list.
     *
     * @param limit the maximum number of backups to return
     * @return the matching backups (no payload), newest first
     * @throws DatabaseException if the load fails
     */
    default java.util.List<com.nova.link.api.ConfigBackup> listConfigBackups(int limit)
            throws DatabaseException {
        throw new UnsupportedOperationException("Config backups not supported by this provider");
    }

    // ==================== Social Relation Operations (提案 08) ====================
    // §11.6 item-18 / PANEL proposal 08 — per-player social relations (initial
    // scope: IGNORE + FAVORITE) and notification preferences. All six methods
    // share the DatabaseProvider optional-method convention: JDBC + Memory
    // providers override them; RedisProvider inherits the
    // UnsupportedOperationException default (safe stub). Relations affect
    // NOTIFICATIONS and default sorting only — they MUST NOT bypass channel
    // permission, ban, or audit; ignore is NOT a server-side ban. Platforms
    // without persistence use session memory (MemoryProvider) and warn of
    // restart loss.

    /**
     * Returns whether {@code sourceId} holds an IGNORE relation toward
     * {@code targetId}. Directional: a one-directional ignore does NOT imply
     * the reverse. Null arguments return {@code false} rather than throwing, so
     * callers can short-circuit on unresolved player ids without a try/catch.
     *
     * @param sourceId the player who may be ignoring (null → false)
     * @param targetId the player who may be ignored (null → false)
     * @return true iff sourceId holds an IGNORE relation toward targetId
     * @throws DatabaseException if the lookup fails
     */
    default boolean isIgnored(java.util.UUID sourceId, java.util.UUID targetId) throws DatabaseException {
        throw new UnsupportedOperationException("Social relations not supported by this provider");
    }

    /**
     * Lists the relations of a given type held by {@code sourceId}, newest-first
     * by {@code createdAt}. Returns a defensive copy; never null.
     *
     * @param sourceId the player whose relations to list (not null)
     * @param type     the relation kind to filter by (not null)
     * @return the matching relations, newest first; empty if none
     * @throws DatabaseException if the lookup fails
     */
    default java.util.List<com.nova.link.social.SocialRelation> getSocialRelations(
            java.util.UUID sourceId, com.nova.link.social.SocialRelation.RelationType type)
            throws DatabaseException {
        throw new UnsupportedOperationException("Social relations not supported by this provider");
    }

    /**
     * Upserts a social relation on its composite natural key
     * {@code (sourceId, targetId, type)}. Any prior matching row is replaced.
     * The caller is responsible for stamping {@code createdAt}/{@code updatedAt}
     * on a freshly-recorded relation; the provider does not re-stamp.
     *
     * @param relation the relation to persist (not null)
     * @throws DatabaseException if the save fails
     */
    default void saveSocialRelation(com.nova.link.social.SocialRelation relation) throws DatabaseException {
        throw new UnsupportedOperationException("Social relations not supported by this provider");
    }

    /**
     * Removes the relation of the given type held by {@code sourceId} toward
     * {@code targetId}, if any. A no-op when no such relation exists.
     *
     * @param sourceId the player who holds the relation (not null)
     * @param targetId the player the relation is held toward (not null)
     * @param type     the relation kind to remove (not null)
     * @throws DatabaseException if the delete fails
     */
    default void removeSocialRelation(java.util.UUID sourceId, java.util.UUID targetId,
                                      com.nova.link.social.SocialRelation.RelationType type)
            throws DatabaseException {
        throw new UnsupportedOperationException("Social relations not supported by this provider");
    }

    /**
     * Loads the notification preferences for {@code playerId}. Implementations
     * return {@link com.nova.link.social.NotificationPreference#defaults(UUID)}
     * when nothing is persisted, so callers can read the fields unconditionally
     * without a null check.
     *
     * @param playerId the player id (not null)
     * @return the persisted preferences, or the defaults; never null
     * @throws DatabaseException if the lookup fails
     */
    default com.nova.link.social.NotificationPreference getNotificationPreference(java.util.UUID playerId)
            throws DatabaseException {
        throw new UnsupportedOperationException("Social relations not supported by this provider");
    }

    /**
     * Upserts the notification preferences for a player. The whole row is
     * replaced on the natural key {@code playerId}.
     *
     * @param preference the preferences to persist (not null)
     * @throws DatabaseException if the save fails
     */
    default void saveNotificationPreference(com.nova.link.social.NotificationPreference preference)
            throws DatabaseException {
        throw new UnsupportedOperationException("Social relations not supported by this provider");
    }

    // ==================== Campaign Operations (提案 06) ====================
    // §11.6 item-19 slice B / PANEL proposal 06 — persisted campaign
    // orchestration. All five methods share the DatabaseProvider optional-method
    // convention: JDBC + Memory providers override them; RedisProvider inherits
    // the UnsupportedOperationException default (safe stub). The authoritative
    // copy of campaign state lives in the in-memory CampaignManager map; these
    // methods mirror non-terminal and recently-revoked campaigns so that
    // restarts can rehydrate scheduled/active campaigns and keep an audit trail
    // of revoked ones. Fail-open: a persistence failure MUST be caught by the
    // caller (CampaignManager) and logged, never propagated — the in-memory
    // state is authoritative and must not be blocked by a DB write error.

    /**
     * Upserts a campaign row. The whole row is replaced on the primary key
     * {@code campaign.id()} (DELETE+INSERT in JDBC providers). Callers should
     * invoke this after every mutating operation (create/schedule/activate/
     * revoke) so the persisted copy tracks the latest in-memory snapshot.
     *
     * @param campaign the campaign to persist (not null)
     * @throws DatabaseException if the save fails
     */
    default void saveCampaign(com.nova.link.announcement.Campaign campaign) throws DatabaseException {
        throw new UnsupportedOperationException("Campaigns not supported by this provider");
    }

    /**
     * Loads a single campaign by id.
     *
     * @param id the campaign id (not null)
     * @return the campaign, or empty if not found
     * @throws DatabaseException if the load fails
     */
    default java.util.Optional<com.nova.link.announcement.Campaign> getCampaign(String id)
            throws DatabaseException {
        throw new UnsupportedOperationException("Campaigns not supported by this provider");
    }

    /**
     * Loads all persisted campaigns. Used at startup to rehydrate the
     * CampaignManager in-memory map with non-terminal (PREVIEW/SCHEDULED/
     * ACTIVE) and recently-revoked campaigns.
     *
     * @return a list of all persisted campaigns; empty list if none (never null)
     * @throws DatabaseException if the load fails
     */
    default java.util.List<com.nova.link.announcement.Campaign> getAllPersistedCampaigns()
            throws DatabaseException {
        throw new UnsupportedOperationException("Campaigns not supported by this provider");
    }

    /**
     * Deletes a campaign row by id. A no-op when no such campaign exists.
     *
     * @param id the campaign id (not null)
     * @throws DatabaseException if the delete fails
     */
    default void deleteCampaign(String id) throws DatabaseException {
        throw new UnsupportedOperationException("Campaigns not supported by this provider");
    }

    /**
     * Updates the status, revokedAt and revokedBy columns of a campaign row.
     * Used to persist terminal transitions (ACTIVE → REVOKED) without rewriting
     * the whole row. A no-op when no such campaign exists.
     *
     * @param id        the campaign id (not null)
     * @param status    the new status (not null)
     * @param revokedAt the revoke timestamp (epoch millis); 0 if not revoked
     * @param revokedBy the revoker's UUID; null if not revoked
     * @throws DatabaseException if the update fails
     */
    default void updateCampaignStatus(String id, com.nova.link.announcement.CampaignStatus status,
                                      long revokedAt, java.util.UUID revokedBy) throws DatabaseException {
        throw new UnsupportedOperationException("Campaigns not supported by this provider");
    }

    // ==================== Invitation Operations ====================

    /**
     * Saves an invitation code.
     *
     * @param invitation the invitation to save
     * @throws DatabaseException if the save operation fails
     */
    void saveInvitation(Invitation invitation) throws DatabaseException;

    /**
     * Loads an invitation by code.
     *
     * @param code the invitation code
     * @return the invitation, or empty if not found
     * @throws DatabaseException if the load operation fails
     */
    Optional<Invitation> loadInvitation(String code) throws DatabaseException;

    /**
     * Marks an invitation as used.
     *
     * <p>Implementations must make the used=false → used=true flip atomic and
     * return {@code true} only when this caller actually performed the flip.
     * A {@code false} return means another caller already consumed the
     * invitation, so the caller must NOT proceed with side effects (e.g.
     * adding the player to the channel).
     *
     * @param code the invitation code
     * @param usedBy the UUID of the player who used it
     * @return true if this call marked the invitation as used; false if it was
     *         already used (or missing) when this call ran
     * @throws DatabaseException if the operation fails
     */
    boolean markInvitationUsed(String code, UUID usedBy) throws DatabaseException;

    /**
     * Atomically claims one use of an invitation. This is the race-safe path
     * for both single-use ({@code maxUses == 1}) and multi-use ({@code maxUses > 1})
     * invitations: a single atomic statement increments {@code usedCount},
     * stamps the accepter/timestamp, and flips {@code used = true} once the
     * quota is exhausted. The claim succeeds (returns {@code 1}) only when
     * this caller actually advanced {@code usedCount}; otherwise it returns
     * {@code 0} (invitation already exhausted, revoked, or missing).
     *
     * <p>The atomic guard must reject the claim when any of these hold:
     * the invitation does not exist, {@code used = true} (quota reached),
     * {@code revoked_at} is set, or {@code used_count >= max_uses}. When the
     * claim succeeds, the caller may proceed with side effects (adding the
     * player to the channel). When it fails, the caller must NOT proceed and
     * should {@link #loadInvitation(String) re-load} the invitation only to
     * distinguish the rejection reason (used vs. revoked vs. not found) — a
     * best-effort diagnostic that never accepts, so it cannot reintroduce the
     * race even if it reads stale state.
     *
     * <p>The {@code now} timestamp is supplied by the caller (rather than read
     * from {@code System.currentTimeMillis()} inside the provider) so tests can
     * assert deterministic {@code usedAt} values; production passes
     * {@code System.currentTimeMillis()}.
     *
     * @param code     the invitation code
     * @param playerId the UUID of the player claiming a use
     * @param now      the timestamp to record as {@code used_at}
     * @return 1 if this caller claimed a use; 0 if the invitation was already
     *         exhausted, revoked, or missing
     * @throws DatabaseException if the operation fails
     */
    int claimInvitationUse(String code, UUID playerId, long now) throws DatabaseException;

    /**
     * Deletes an invitation.
     *
     * @param code the invitation code
     * @throws DatabaseException if the delete operation fails
     */
    void deleteInvitation(String code) throws DatabaseException;

    /**
     * Deletes all expired invitations.
     *
     * @return number of invitations deleted
     * @throws DatabaseException if the operation fails
     */
    int cleanupExpiredInvitations() throws DatabaseException;

    // ==================== Message History Operations (schema v5) ====================

    /**
     * Persists a chat message. The provider assigns the id and stamps it back
     * onto the record via {@link ChatMessageRecord#setId(long)}.
     *
     * @param message the message record to save
     * @throws DatabaseException if the save operation fails
     */
    void saveMessage(ChatMessageRecord message) throws DatabaseException;

    /**
     * Searches persisted messages matching the filter, ordered by timestamp
     * descending (newest first), with pagination.
     *
     * @param filter the filter criteria (never null; use {@link MessageFilter#any()})
     * @param offset the 0-based row offset
     * @param limit the maximum number of rows to return
     * @return the matching messages, newest first
     * @throws DatabaseException if the search fails
     */
    List<ChatMessageRecord> searchMessages(MessageFilter filter, int offset, int limit) throws DatabaseException;

    /**
     * Counts persisted messages matching the filter (same criteria as
     * {@link #searchMessages}) so paginated listings can report a real total.
     *
     * @param filter the filter criteria
     * @return the total number of matching messages
     * @throws DatabaseException if the count fails
     */
    int countMessages(MessageFilter filter) throws DatabaseException;

    /**
     * Deletes messages older than the cutoff (retention policy).
     *
     * @param cutoffTimestamp epoch milliseconds; rows with timestamp strictly
     *                        below this value are removed
     * @return number of messages deleted
     * @throws DatabaseException if the cleanup fails
     */
    int cleanupMessagesBefore(long cutoffTimestamp) throws DatabaseException;

    // ==================== Announcement Operations (schema v5) ====================

    /**
     * Saves (inserts or updates) a persisted announcement (JOIN/CRON types).
     *
     * @param announcement the announcement to save
     * @throws DatabaseException if the save operation fails
     */
    void saveAnnouncement(com.nova.link.announcement.Announcement announcement) throws DatabaseException;

    /**
     * Deletes a persisted announcement.
     *
     * @param announcementId the announcement id
     * @throws DatabaseException if the delete operation fails
     */
    void deleteAnnouncement(String announcementId) throws DatabaseException;

    /**
     * Loads all persisted announcements (used at startup to restore JOIN
     * triggers and CRON schedules).
     *
     * @return list of all persisted announcements
     * @throws DatabaseException if the load operation fails
     */
    List<com.nova.link.announcement.Announcement> getAllPersistedAnnouncements() throws DatabaseException;

    // ==================== Webhook Operations (schema v5) ====================

    /**
     * Saves (inserts or updates) a webhook, including its {@code active} flag
     * and {@code lastTriggered} timestamp.
     *
     * @param webhook the webhook to save
     * @throws DatabaseException if the save operation fails
     */
    void saveWebhook(com.nova.link.api.Webhook webhook) throws DatabaseException;

    /**
     * Deletes a persisted webhook.
     *
     * @param webhookId the webhook id
     * @throws DatabaseException if the delete operation fails
     */
    void deleteWebhook(String webhookId) throws DatabaseException;

    /**
     * Loads all persisted webhooks (used at startup so webhooks survive a
     * backend restart).
     *
     * @return list of all persisted webhooks
     * @throws DatabaseException if the load operation fails
     */
    List<com.nova.link.api.Webhook> getAllPersistedWebhooks() throws DatabaseException;

    /**
     * Gets the database provider type name.
     *
     * @return the provider type (e.g., "MySQL", "Redis", "Memory")
     */
    String getProviderType();
}
