package com.nova.link.database.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite dialect for schema migrations.
 *
 * <p>Renders the NovaLink schema in SQLite syntax:
 * <ul>
 *   <li>{@code INT/BIGINT AUTO_INCREMENT} → {@code INTEGER PRIMARY KEY AUTOINCREMENT}
 *       (SQLite only allows AUTOINCREMENT on INTEGER PRIMARY KEY; its INTEGER is
 *       64-bit so this covers BIGINT id columns too)</li>
 *   <li>{@code ON UPDATE CURRENT_TIMESTAMP} dropped — {@code updated_at} is
 *       application-managed</li>
 *   <li>inline {@code INDEX} → separate {@code CREATE INDEX IF NOT EXISTS}</li>
 *   <li>{@code UNIQUE KEY uk_.. (cols)} → {@code CONSTRAINT uk_.. UNIQUE (cols)}</li>
 *   <li>{@code ENGINE/CHARSET} clauses dropped</li>
 *   <li>{@code BOOLEAN} kept as a type-affinity name — the xerial JDBC driver
 *       converts between boolean and INTEGER 0/1 transparently</li>
 * </ul>
 *
 * <p>Foreign-key enforcement and WAL journal mode are enabled at runtime by
 * {@link com.nova.link.database.SQLiteProvider#initialize()}, not in the DDL.
 *
 * <p>Requirements: 22.1 - Auto-migration on startup (multi-database support)
 */
public class SQLiteDialect implements MigrationDialect {

    private static final String MIGRATION_TABLE = "novalink_migrations";
    private static final int CURRENT_VERSION = 15;

    @Override
    public int getCurrentVersion() {
        return CURRENT_VERSION;
    }

    @Override
    public String getMigrationVersionTableName() {
        return MIGRATION_TABLE;
    }

    @Override
    public String getMigrationTableDdl() {
        return """
            CREATE TABLE IF NOT EXISTS %s (
                version INTEGER PRIMARY KEY,
                checksum VARCHAR(64),
                started_at TIMESTAMP,
                completed_at TIMESTAMP,
                status VARCHAR(16),
                applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                description VARCHAR(255)
            )
            """.formatted(MIGRATION_TABLE);
    }

    @Override
    public List<String> getMigrationStatements(int version) {
        List<String> statements = new ArrayList<>();

        switch (version) {
            case 1 -> {
                // Players table
                statements.add("""
                    CREATE TABLE IF NOT EXISTS players (
                        player_id VARCHAR(36) PRIMARY KEY,
                        player_name VARCHAR(64),
                        client_id VARCHAR(64),
                        current_world VARCHAR(64),
                        joined_channels TEXT,
                        active_channel VARCHAR(64),
                        last_seen BIGINT,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_players_client_id ON players (client_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_players_last_seen ON players (last_seen)");

                // Channels table
                statements.add("""
                    CREATE TABLE IF NOT EXISTS channels (
                        channel_id VARCHAR(64) PRIMARY KEY,
                        display_name VARCHAR(128),
                        scope VARCHAR(16) NOT NULL,
                        client_id VARCHAR(64),
                        permission VARCHAR(128),
                        max_capacity INTEGER DEFAULT 100,
                        allowed_worlds TEXT,
                        password VARCHAR(128),
                        owner_id VARCHAR(36),
                        created_at BIGINT,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_channels_scope ON channels (scope)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_channels_client_id ON channels (client_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_channels_owner_id ON channels (owner_id)");

                // Mutes table
                statements.add("""
                    CREATE TABLE IF NOT EXISTS mutes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_id VARCHAR(36) NOT NULL,
                        channel_id VARCHAR(64),
                        expire_time BIGINT,
                        reason TEXT,
                        operator_id VARCHAR(36),
                        created_at BIGINT,
                        CONSTRAINT uk_mutes_player_channel UNIQUE (player_id, channel_id)
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_mutes_player_id ON mutes (player_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_mutes_channel_id ON mutes (channel_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_mutes_expire_time ON mutes (expire_time)");

                // Invitations table
                statements.add("""
                    CREATE TABLE IF NOT EXISTS invitations (
                        code VARCHAR(16) PRIMARY KEY,
                        channel_id VARCHAR(64) NOT NULL,
                        inviter_id VARCHAR(36) NOT NULL,
                        expire_time BIGINT NOT NULL,
                        created_at BIGINT,
                        used BOOLEAN DEFAULT FALSE,
                        used_by VARCHAR(36),
                        used_at BIGINT
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_invitations_channel_id ON invitations (channel_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_invitations_inviter_id ON invitations (inviter_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_invitations_expire_time ON invitations (expire_time)");

                // Channel members table
                statements.add("""
                    CREATE TABLE IF NOT EXISTS channel_members (
                        channel_id VARCHAR(64) NOT NULL,
                        player_id VARCHAR(36) NOT NULL,
                        joined_at BIGINT,
                        PRIMARY KEY (channel_id, player_id),
                        FOREIGN KEY (channel_id) REFERENCES channels(channel_id) ON DELETE CASCADE
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_channel_members_player_id ON channel_members (player_id)");
            }

            case 2 -> {
                // Add platform column to players table. SQLite has no AFTER or
                // ADD COLUMN IF NOT EXISTS clause. The migration runner holds a
                // BEGIN IMMEDIATE transaction and rolls this ALTER back on
                // failure, so retrying an interrupted v2 is safe.
                statements.add("""
                    ALTER TABLE players ADD COLUMN platform VARCHAR(32)
                    """);
            }
            case 3 -> {
                // Bans table — mirrors mutes schema.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS bans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        player_id VARCHAR(36) NOT NULL,
                        channel_id VARCHAR(64),
                        expire_time BIGINT,
                        reason TEXT,
                        operator_id VARCHAR(36),
                        created_at BIGINT,
                        CONSTRAINT uk_bans_player_channel UNIQUE (player_id, channel_id)
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_bans_player_id ON bans (player_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_bans_channel_id ON bans (channel_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_bans_expire_time ON bans (expire_time)");
            }
            case 4 -> {
                // Notifications table. SQLite INTEGER is 64-bit, so INTEGER
                // PRIMARY KEY AUTOINCREMENT covers the BIGINT id requirement.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS notifications (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        title VARCHAR(255) NOT NULL,
                        message TEXT NOT NULL,
                        level VARCHAR(16) NOT NULL DEFAULT 'info',
                        created_at BIGINT NOT NULL,
                        read BOOLEAN NOT NULL DEFAULT FALSE
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_notifications_created_at ON notifications (created_at)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_notifications_read ON notifications (read)");
            }

            case 5 -> {
                // Messages table — persisted chat history. Epoch-millis column
                // named created_at to avoid the TIMESTAMP type keyword; webhook
                // event column named event_type for cross-dialect consistency.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        channel_id VARCHAR(64),
                        sender_id VARCHAR(36),
                        sender_name VARCHAR(64),
                        client_id VARCHAR(64),
                        content TEXT,
                        created_at BIGINT NOT NULL
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_messages_created_at ON messages (created_at)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_messages_channel_id ON messages (channel_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_messages_sender_name ON messages (sender_name)");

                // Announcements table — persisted JOIN/CRON announcements.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS announcements (
                        id VARCHAR(64) PRIMARY KEY,
                        announcement_type VARCHAR(16) NOT NULL,
                        channel_id VARCHAR(64),
                        content TEXT NOT NULL,
                        cron VARCHAR(64),
                        enabled BOOLEAN NOT NULL DEFAULT TRUE,
                        created_at BIGINT NOT NULL
                    )
                    """);

                // Webhooks table — persisted webhook registrations.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS webhooks (
                        id VARCHAR(64) PRIMARY KEY,
                        url TEXT NOT NULL,
                        event_type VARCHAR(64),
                        secret VARCHAR(255),
                        active BOOLEAN NOT NULL DEFAULT TRUE,
                        created_at BIGINT NOT NULL,
                        last_triggered BIGINT NOT NULL DEFAULT 0
                    )
                    """);
            }

            case 6 -> {
                // Persist PlayerState.dmEnabled so a player's DM opt-out
                // survives restarts instead of silently reverting to the
                // field default (true). DEFAULT TRUE matches the field default.
                // SQLite has no ADD COLUMN IF NOT EXISTS clause; the migration
                // runner holds a BEGIN IMMEDIATE transaction and rolls this ALTER
                // back on failure, so retrying an interrupted v6 is safe.
                statements.add("""
                    ALTER TABLE players ADD COLUMN dm_enabled BOOLEAN NOT NULL DEFAULT TRUE
                    """);
            }

            case 7 -> {
                // Add slow_mode_seconds column to channels table so channel
                // slow-mode configuration persists. SQLite has no ADD COLUMN IF
                // NOT EXISTS clause; the migration runner holds a BEGIN IMMEDIATE
                // transaction and rolls this ALTER back on failure, so retrying
                // an interrupted v7 is safe.
                statements.add("""
                    ALTER TABLE channels ADD COLUMN slow_mode_seconds INTEGER NOT NULL DEFAULT 0
                    """);
            }

            case 8 -> {
                // Add invitation multi-use / revocation fields:
                //   max_uses   INT NOT NULL DEFAULT 1  (default 1 preserves the
                //                                       historical single-use behaviour)
                //   used_count INT NOT NULL DEFAULT 0  (0 uses so far)
                //   revoked_at INTEGER (nullable; NULL = not revoked)
                // SQLite has no ADD COLUMN IF NOT EXISTS clause; the migration
                // runner holds a BEGIN IMMEDIATE transaction and rolls this ALTER
                // back on failure, so retrying an interrupted v8 is safe. ADD
                // COLUMN with DEFAULT fills existing rows with the default.
                statements.add("""
                    ALTER TABLE invitations ADD COLUMN max_uses INTEGER NOT NULL DEFAULT 1
                    """);
                statements.add("""
                    ALTER TABLE invitations ADD COLUMN used_count INTEGER NOT NULL DEFAULT 0
                    """);
                statements.add("""
                    ALTER TABLE invitations ADD COLUMN revoked_at INTEGER
                    """);
            }

            case 9 -> {
                // Audit events table — append-only audit log (PANEL-006).
                // SQLite INTEGER is 64-bit, so INTEGER PRIMARY KEY AUTOINCREMENT
                // covers the BIGINT id requirement. before_hash/after_hash hold
                // SHA-256 hex of the resource JSON after secrets are stripped;
                // they may be NULL for create (no before) / delete (no after).
                statements.add("""
                    CREATE TABLE IF NOT EXISTS audit_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_id VARCHAR(64) NOT NULL,
                        request_id VARCHAR(64),
                        actor VARCHAR(128),
                        role VARCHAR(32),
                        origin VARCHAR(128),
                        action VARCHAR(64) NOT NULL,
                        resource VARCHAR(255),
                        before_hash VARCHAR(64),
                        after_hash VARCHAR(64),
                        reason TEXT,
                        result VARCHAR(16) NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_audit_events_created_at ON audit_events (created_at)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_audit_events_actor ON audit_events (actor)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_audit_events_action ON audit_events (action)");
            }

            case 10 -> {
                // Per-user notification state (PANEL-014). The notifications
                // table becomes an immutable event stream: recipient=NULL means
                // broadcast (visible to every admin); a non-NULL recipient marks
                // a directed notification visible only to that user. The new
                // notification_read table tracks per-user read state so one
                // admin marking read/clear never affects another admin's inbox.
                // SQLite has no ADD COLUMN IF NOT EXISTS; the migration runner
                // holds a BEGIN IMMEDIATE transaction and rolls this ALTER back
                // on failure, so retrying an interrupted v10 is safe. Existing
                // notifications default recipient=NULL (broadcast).
                statements.add("""
                    ALTER TABLE notifications ADD COLUMN recipient VARCHAR(64)
                    """);
                statements.add("""
                    CREATE TABLE IF NOT EXISTS notification_read (
                        notification_id INTEGER NOT NULL,
                        user_id VARCHAR(64) NOT NULL,
                        read BOOLEAN NOT NULL DEFAULT TRUE,
                        read_at BIGINT,
                        PRIMARY KEY (notification_id, user_id)
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_notification_read_user_read ON notification_read (user_id, read)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON notifications (recipient)");
            }

            case 11 -> {
                // Moderation case/appeal workflow (PANEL-007). Three tables:
                //   moderation_cases — the case record (filed report + lifecycle)
                //   case_evidence    — evidence attached to a case (narrowest-
                //                      scope read surface; only via case-scoped GET)
                //   appeals          — appeals against resolved cases
                // Cases/appeals are keyed by a caller-assigned UUID string
                // (VARCHAR 64 PK); evidence is append-only with an auto-incremented
                // INTEGER id (SQLite INTEGER is 64-bit). content_hash holds the
                // SHA-256 hex of the report/evidence/appeal payload — secrets are
                // never persisted. CREATE TABLE IF NOT EXISTS keeps the migration
                // idempotent on retry (the migration runner holds a BEGIN
                // IMMEDIATE transaction and rolls back on failure).
                statements.add("""
                    CREATE TABLE IF NOT EXISTS moderation_cases (
                        id VARCHAR(64) PRIMARY KEY,
                        subject_player_id VARCHAR(64) NOT NULL,
                        subject_display_name VARCHAR(128),
                        reporter_name VARCHAR(128),
                        reporter_source VARCHAR(16) NOT NULL,
                        source VARCHAR(16) NOT NULL,
                        channel_id VARCHAR(64),
                        reason VARCHAR(1024),
                        snapshot VARCHAR(1024),
                        status VARCHAR(24) NOT NULL,
                        assigned_moderator VARCHAR(128),
                        resolution_action VARCHAR(24),
                        resolution_note VARCHAR(1024),
                        content_hash VARCHAR(64),
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        closed_at BIGINT
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_moderation_cases_status ON moderation_cases (status)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_moderation_cases_subject ON moderation_cases (subject_player_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_moderation_cases_created_at ON moderation_cases (created_at)");
                statements.add("""
                    CREATE TABLE IF NOT EXISTS case_evidence (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        case_id VARCHAR(64) NOT NULL,
                        evidence_type VARCHAR(32) NOT NULL,
                        content_hash VARCHAR(64),
                        description VARCHAR(512),
                        submitted_by VARCHAR(128),
                        created_at BIGINT NOT NULL
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_case_evidence_case_id ON case_evidence (case_id)");
                statements.add("""
                    CREATE TABLE IF NOT EXISTS appeals (
                        id VARCHAR(64) PRIMARY KEY,
                        case_id VARCHAR(64) NOT NULL,
                        appellant VARCHAR(128),
                        appeal_reason VARCHAR(1024),
                        status VARCHAR(16) NOT NULL,
                        reviewed_by VARCHAR(128),
                        review_note VARCHAR(1024),
                        reviewed_at BIGINT,
                        content_hash VARCHAR(64),
                        created_at BIGINT NOT NULL
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_appeals_case_id ON appeals (case_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_appeals_status ON appeals (status)");
            }

            case 12 -> {
                // Config history / rollback (§11.6 Project 20 / PANEL proposal 10).
                //   config_history — append-only masked snapshots of the full NovaLink
                //                   config keyed by the monotonic settings revision
                //                   (PANEL-010). Only one row is active at a time
                //                   (active=TRUE); rollback appends a new row and
                //                   flips the active flag rather than mutating or
                //                   deleting prior history. snapshot_json holds the
                //                   MASKED config (secrets replaced with "***") so the
                //                   table never stores plaintext secrets — same
                //                   posture as audit_events.content_hash. SQLite
                //   allows BOOLEAN as a type-affinity name (xerial driver converts
                //   to INTEGER 0/1). CREATE TABLE IF NOT EXISTS keeps the migration
                //   idempotent on retry (the runner holds a BEGIN IMMEDIATE
                //   transaction and rolls back on failure).
                statements.add("""
                    CREATE TABLE IF NOT EXISTS config_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        revision BIGINT NOT NULL,
                        snapshot_json TEXT NOT NULL,
                        created_at BIGINT NOT NULL,
                        created_by VARCHAR(64),
                        active BOOLEAN NOT NULL DEFAULT FALSE
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_config_history_created_at ON config_history (created_at)");
            }

            case 13 -> {
                // Social relations & ignore (§11.6 item-18 / PANEL proposal 08).
                //   social_relations — directional per-player relations keyed by
                //                     the composite (source_id, target_id, type).
                //                     Initial scope: IGNORE + FAVORITE. One row of
                //                     each type may exist per ordered pair; the
                //                     provider upserts by deleting the prior
                //                     matching row before inserting. Relations gate
                //                     notifications + default sorting only — they
                //                     never bypass channel permission, ban, or
                //                     audit; ignore is NOT a server-side ban.
                //   notification_preferences — per-player notification knobs
                //                     (mentions opt-in). Deliberately does NOT
                //                     carry dm_enabled (that lives on players via
                //                     schema v6). UUIDs are VARCHAR(36) strings;
                //   timestamps are BIGINT epoch millis. SQLite accepts BOOLEAN as
                //   a type-affinity name (xerial driver converts to INTEGER 0/1)
                //   and a composite PRIMARY KEY directly. CREATE TABLE IF NOT
                //   EXISTS keeps the migration idempotent on retry.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS social_relations (
                        source_id VARCHAR(36) NOT NULL,
                        target_id VARCHAR(36) NOT NULL,
                        type VARCHAR(16) NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (source_id, target_id, type)
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_social_relations_target_id ON social_relations (target_id)");
                statements.add("""
                    CREATE TABLE IF NOT EXISTS notification_preferences (
                        player_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        mentions_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                        updated_at BIGINT NOT NULL
                    )
                    """);
            }

            case 14 -> {
                // Campaigns table (§11.6 item-19 slice B / PANEL proposal 06).
                //   campaigns — persisted campaign orchestration records keyed by
                //               the campaign id (VARCHAR(64)). The authoritative
                //               copy of campaign state lives in the in-memory
                //               CampaignManager map; this table mirrors non-terminal
                //               and recently-revoked campaigns so that restarts can
                //               rehydrate scheduled/active campaigns and keep an
                //               audit trail of revoked ones. Platforms Set<String>
                //               is serialised as a comma-joined TEXT column;
                //               delivery_policy is the enum dbValue() (INSTANT,
                //               TITLE_FALLBACK, ACTIONBAR_FALLBACK). Status is the
                //               CampaignStatus enum name (PREVIEW/SCHEDULED/ACTIVE/
                //               EXPIRED/REVOKED). creator_id/creator_client_id are
                //               nullable for system-created campaigns. revoked_at
                //               defaults to 0 and revoked_by is NULL until a revoke
                //               stamps them. CREATE TABLE IF NOT EXISTS keeps the
                //               migration idempotent on retry.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS campaigns (
                        id VARCHAR(64) NOT NULL PRIMARY KEY,
                        channel_id VARCHAR(64) NOT NULL,
                        platforms TEXT NOT NULL,
                        content TEXT NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        schedule_revision BIGINT NOT NULL,
                        delivery_policy VARCHAR(32) NOT NULL,
                        start_at BIGINT NOT NULL,
                        end_at BIGINT NOT NULL,
                        rate_limit_per_channel_per_hour INT NOT NULL,
                        creator_id VARCHAR(36),
                        creator_client_id VARCHAR(64),
                        created_at BIGINT NOT NULL,
                        revoked_at BIGINT NOT NULL DEFAULT 0,
                        revoked_by VARCHAR(36)
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_campaigns_status ON campaigns (status)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_campaigns_channel_id ON campaigns (channel_id)");
                statements.add("CREATE INDEX IF NOT EXISTS idx_campaigns_created_at ON campaigns (created_at)");
            }

            case 15 -> {
                // Config drafts + explicit backups (§11.6 item-20 / PANEL proposal 10
                //   doc-deferred sub-items). config_drafts persists staged draft YAML
                //   with a DRAFT -> APPROVED -> PUBLISHED state machine; approver must
                //   differ from createdBy (permission separation enforced in
                //   ConfigPublishService). draft_json stores the candidate YAML
                //   (masked via ConfigHistoryService.maskSecrets). config_backups
                //   captures explicit pre-publish / pre-restore snapshots of the live
                //   config (masked) with a label and the originating settingsRevision
                //   for correlation. CREATE TABLE IF NOT EXISTS keeps retry idempotent.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS config_drafts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        draft_json TEXT NOT NULL,
                        created_by VARCHAR(36) NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        approved_by VARCHAR(36),
                        created_at BIGINT NOT NULL,
                        approved_at BIGINT,
                        published_at BIGINT
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_config_drafts_status ON config_drafts (status)");
                statements.add("""
                    CREATE TABLE IF NOT EXISTS config_backups (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        label VARCHAR(255) NOT NULL,
                        backup_json TEXT NOT NULL,
                        settings_revision BIGINT NOT NULL,
                        created_by VARCHAR(36) NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_config_backups_created_at ON config_backups (created_at)");
            }

            default -> throw new IllegalArgumentException("Unknown migration version: " + version);
        }

        return statements;
    }

    @Override
    public String getMigrationDescription(int version) {
        return switch (version) {
            case 1 -> "Initial schema - players, channels, mutes, invitations tables";
            case 2 -> "Add platform column to players table";
            case 3 -> "Add bans table for player ban management";
            case 4 -> "Add notifications table for persisted panel notifications";
            case 5 -> "Add messages, announcements and webhooks tables for persistence";
            case 6 -> "Add dm_enabled column to players table to persist DM opt-out";
            case 7 -> "Add slow_mode_seconds column to channels table to persist slow-mode config";
            case 8 -> "Add max_uses, used_count and revoked_at columns to invitations for multi-use and revocation";
            case 9 -> "Add audit_events table for append-only admin audit log (PANEL-006)";
            case 10 -> "Add per-user notification state (recipient column + notification_read table) (PANEL-014)";
            case 11 -> "Add moderation_cases, case_evidence and appeals tables (PANEL-007)";
            case 12 -> "Add config_history table for masked config snapshots and rollback (PANEL proposal 10)";
            case 13 -> "Add social_relations and notification_preferences tables for ignore/favorite/mentions (PANEL proposal 08)";
            case 14 -> "Add campaigns table for persisted campaign orchestration (PANEL proposal 06)";
            case 15 -> "Add config_drafts and config_backups tables for staged draft/publish workflow and explicit backup/restore (PANEL proposal 10)";
            default -> "Unknown migration";
        };
    }

    @Override
    public String getMigrationLockAcquireSql() {
        return "BEGIN IMMEDIATE";
    }

    @Override
    public String getMigrationLockReleaseSql() {
        return "COMMIT";
    }

    @Override
    public MigrationLock acquireMigrationLock(Connection connection, long timeoutMillis) throws SQLException {
        int previousBusyTimeout;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA busy_timeout")) {
            previousBusyTimeout = result.next() ? result.getInt(1) : 0;
        }

        int busyTimeout = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, timeoutMillis));
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = " + busyTimeout);
            statement.execute(getMigrationLockAcquireSql());
        } catch (SQLException exception) {
            if (isLockTimeout(exception)) {
                SQLTimeoutException timeout = new SQLTimeoutException(
                        "Timed out after " + timeoutMillis
                                + " ms acquiring SQLite BEGIN IMMEDIATE migration lock",
                        exception.getSQLState(), exception.getErrorCode());
                timeout.initCause(exception);
                throw timeout;
            }
            throw exception;
        }

        int restoreBusyTimeout = previousBusyTimeout;
        return new MigrationLock() {
            private boolean held = true;

            @Override
            public boolean ownsTransaction() {
                return true;
            }

            @Override
            public void release(boolean commitTransaction) throws SQLException {
                if (!held) {
                    return;
                }

                SQLException failure = null;
                try (Statement statement = connection.createStatement()) {
                    statement.execute(commitTransaction ? getMigrationLockReleaseSql() : "ROLLBACK");
                } catch (SQLException exception) {
                    failure = exception;
                    if (commitTransaction) {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("ROLLBACK");
                        } catch (SQLException rollbackFailure) {
                            exception.addSuppressed(rollbackFailure);
                        }
                    }
                } finally {
                    held = false;
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("PRAGMA busy_timeout = " + restoreBusyTimeout);
                    } catch (SQLException restoreFailure) {
                        if (failure == null) {
                            failure = restoreFailure;
                        } else {
                            failure.addSuppressed(restoreFailure);
                        }
                    }
                }

                if (failure != null) {
                    throw failure;
                }
            }
        };
    }

    private static boolean isLockTimeout(SQLException exception) {
        String message = exception.getMessage();
        return exception.getErrorCode() == 5
                || exception.getErrorCode() == 6
                || (message != null && (message.toLowerCase().contains("locked")
                || message.toLowerCase().contains("busy")));
    }
}
