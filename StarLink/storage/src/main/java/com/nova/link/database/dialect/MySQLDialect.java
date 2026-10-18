package com.nova.link.database.dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL/MariaDB dialect for schema migrations.
 *
 * <p>This is a straight extraction of the SQL that historically lived in
 * {@code DatabaseMigration}: InnoDB tables with utf8mb4, inline indexes,
 * AUTO_INCREMENT, ON UPDATE CURRENT_TIMESTAMP, and ALTER...AFTER. It targets
 * MySQL 8+ and is also compatible with MariaDB 10.3+.
 *
 * <p>Requirements: 22.1 - Auto-migration on startup
 */
public class MySQLDialect implements MigrationDialect {

    private static final String MIGRATION_TABLE = "novalink_migrations";
    private static final String MIGRATION_LOCK_NAME = "novalink_schema_migration";
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
                version INT PRIMARY KEY,
                checksum VARCHAR(64),
                started_at TIMESTAMP NULL,
                completed_at TIMESTAMP NULL,
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
                // Initial schema - Version 1
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
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_client_id (client_id),
                        INDEX idx_last_seen (last_seen)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

                // Channels table
                statements.add("""
                    CREATE TABLE IF NOT EXISTS channels (
                        channel_id VARCHAR(64) PRIMARY KEY,
                        display_name VARCHAR(128),
                        scope VARCHAR(16) NOT NULL,
                        client_id VARCHAR(64),
                        permission VARCHAR(128),
                        max_capacity INT DEFAULT 100,
                        allowed_worlds TEXT,
                        password VARCHAR(128),
                        owner_id VARCHAR(36),
                        created_at BIGINT,
                        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_scope (scope),
                        INDEX idx_client_id (client_id),
                        INDEX idx_owner_id (owner_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

                // Mutes table
                statements.add("""
                    CREATE TABLE IF NOT EXISTS mutes (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        player_id VARCHAR(36) NOT NULL,
                        channel_id VARCHAR(64),
                        expire_time BIGINT,
                        reason TEXT,
                        operator_id VARCHAR(36),
                        created_at BIGINT,
                        INDEX idx_player_id (player_id),
                        INDEX idx_channel_id (channel_id),
                        INDEX idx_expire_time (expire_time),
                        UNIQUE KEY uk_player_channel (player_id, channel_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

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
                        used_at BIGINT,
                        INDEX idx_channel_id (channel_id),
                        INDEX idx_inviter_id (inviter_id),
                        INDEX idx_expire_time (expire_time)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

                // Channel members table (for tracking membership separately)
                statements.add("""
                    CREATE TABLE IF NOT EXISTS channel_members (
                        channel_id VARCHAR(64) NOT NULL,
                        player_id VARCHAR(36) NOT NULL,
                        joined_at BIGINT,
                        PRIMARY KEY (channel_id, player_id),
                        INDEX idx_player_id (player_id),
                        FOREIGN KEY (channel_id) REFERENCES channels(channel_id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            }

            // Future migrations can be added here
            case 2 -> {
                // Add platform column to players table for tracking the platform
                // a player connects from (e.g. BUKKIT, VELOCITY, NUKKIT).
                // Both fresh and existing v1 databases reach this migration on
                // upgrade to v2; the v1 CREATE TABLE intentionally omits the column
                // so this ALTER is the single source of truth for it.
                //
                // Standard MySQL 8.0 does NOT support "ADD COLUMN IF NOT EXISTS"
                // (that is a MariaDB-only extension). To keep the migration
                // idempotent across retries without relying on MariaDB syntax,
                // a stored procedure checks information_schema.columns and only
                // issues the ALTER when the column is absent. The statement text
                // is deterministic so migration checksums stay stable, while the
                // runtime behaviour is conditional. The procedure is dropped
                // after use so no schema artefact lingers.
                statements.add("DROP PROCEDURE IF EXISTS novalink_add_platform_column");
                statements.add("""
                    CREATE PROCEDURE novalink_add_platform_column()
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE()
                              AND table_name = 'players'
                              AND column_name = 'platform'
                        ) THEN
                            ALTER TABLE players ADD COLUMN platform VARCHAR(32) NULL AFTER active_channel;
                        END IF;
                    END
                    """);
                statements.add("CALL novalink_add_platform_column()");
                statements.add("DROP PROCEDURE IF EXISTS novalink_add_platform_column");
            }
            case 3 -> {
                // Bans table — mirrors mutes schema. channelId NULL means a
                // global ban. UNIQUE(player_id, channel_id) keeps one active ban
                // per player/channel combination.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS bans (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        player_id VARCHAR(36) NOT NULL,
                        channel_id VARCHAR(64),
                        expire_time BIGINT,
                        reason TEXT,
                        operator_id VARCHAR(36),
                        created_at BIGINT,
                        INDEX idx_player_id (player_id),
                        INDEX idx_channel_id (channel_id),
                        INDEX idx_expire_time (expire_time),
                        UNIQUE KEY uk_player_channel (player_id, channel_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            }
            case 4 -> {
                // Notifications table — persisted panel notifications with a
                // read flag for unread tracking and an index on created_at for
                // ordered pagination.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS notifications (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        title VARCHAR(255) NOT NULL,
                        message TEXT NOT NULL,
                        level VARCHAR(16) NOT NULL DEFAULT 'info',
                        created_at BIGINT NOT NULL,
                        `read` BOOLEAN NOT NULL DEFAULT FALSE,
                        INDEX idx_created_at (created_at),
                        INDEX idx_read (`read`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            }

            case 5 -> {
                // Messages table — persisted chat history. The epoch-millis
                // column is named created_at (not "timestamp", which is a SQL
                // type keyword); the webhook event column is event_type (EVENT
                // is a MySQL keyword).
                statements.add("""
                    CREATE TABLE IF NOT EXISTS messages (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        channel_id VARCHAR(64),
                        sender_id VARCHAR(36),
                        sender_name VARCHAR(64),
                        client_id VARCHAR(64),
                        content TEXT,
                        created_at BIGINT NOT NULL,
                        INDEX idx_messages_created_at (created_at),
                        INDEX idx_messages_channel_id (channel_id),
                        INDEX idx_messages_sender_name (sender_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);

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
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
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
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            }

            case 6 -> {
                // Persist PlayerState.dmEnabled so a player's DM opt-out
                // survives restarts instead of silently reverting to the
                // field default (true). DEFAULT TRUE matches the field default.
                // Same information_schema-guarded procedure pattern as v2:
                // standard MySQL 8.0 has no "ADD COLUMN IF NOT EXISTS".
                statements.add("DROP PROCEDURE IF EXISTS novalink_add_dm_enabled_column");
                statements.add("""
                    CREATE PROCEDURE novalink_add_dm_enabled_column()
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE()
                              AND table_name = 'players'
                              AND column_name = 'dm_enabled'
                        ) THEN
                            ALTER TABLE players ADD COLUMN dm_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER platform;
                        END IF;
                    END
                    """);
                statements.add("CALL novalink_add_dm_enabled_column()");
                statements.add("DROP PROCEDURE IF EXISTS novalink_add_dm_enabled_column");
            }

            case 7 -> {
                // Add slow_mode_seconds column to channels table so channel
                // slow-mode configuration persists. Same information_schema-guarded
                // procedure pattern as v2/v6: standard MySQL 8.0 has no
                // "ADD COLUMN IF NOT EXISTS".
                statements.add("DROP PROCEDURE IF EXISTS novalink_add_slow_mode_seconds_column");
                statements.add("""
                    CREATE PROCEDURE novalink_add_slow_mode_seconds_column()
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE()
                              AND table_name = 'channels'
                              AND column_name = 'slow_mode_seconds'
                        ) THEN
                            ALTER TABLE channels ADD COLUMN slow_mode_seconds INT NOT NULL DEFAULT 0 AFTER owner_id;
                        END IF;
                    END
                    """);
                statements.add("CALL novalink_add_slow_mode_seconds_column()");
                statements.add("DROP PROCEDURE IF EXISTS novalink_add_slow_mode_seconds_column");
            }

            case 8 -> {
                // Add invitation multi-use / revocation fields:
                //   max_uses   INT NOT NULL DEFAULT 1  (default 1 preserves the
                //                                       historical single-use behaviour)
                //   used_count INT NOT NULL DEFAULT 0  (0 uses so far)
                //   revoked_at BIGINT NULL            (NULL = not revoked)
                // Same information_schema-guarded procedure pattern as v2/v6/v7:
                // standard MySQL 8.0 has no "ADD COLUMN IF NOT EXISTS". ADD COLUMN
                // with DEFAULT fills existing rows with the default.
                statements.add("DROP PROCEDURE IF EXISTS novalink_add_invitation_multi_use_columns");
                statements.add("""
                    CREATE PROCEDURE novalink_add_invitation_multi_use_columns()
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE()
                              AND table_name = 'invitations'
                              AND column_name = 'max_uses'
                        ) THEN
                            ALTER TABLE invitations ADD COLUMN max_uses INT NOT NULL DEFAULT 1 AFTER used_at;
                        END IF;
                        IF NOT EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE()
                              AND table_name = 'invitations'
                              AND column_name = 'used_count'
                        ) THEN
                            ALTER TABLE invitations ADD COLUMN used_count INT NOT NULL DEFAULT 0 AFTER max_uses;
                        END IF;
                        IF NOT EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE()
                              AND table_name = 'invitations'
                              AND column_name = 'revoked_at'
                        ) THEN
                            ALTER TABLE invitations ADD COLUMN revoked_at BIGINT NULL AFTER used_count;
                        END IF;
                    END
                    """);
                statements.add("CALL novalink_add_invitation_multi_use_columns()");
                statements.add("DROP PROCEDURE IF EXISTS novalink_add_invitation_multi_use_columns");
            }

            case 9 -> {
                // Audit events table — append-only audit log (PANEL-006).
                // before_hash/after_hash hold SHA-256 hex of the resource JSON
                // after secrets are stripped; they may be NULL for create (no
                // before) / delete (no after). `action` and `result` are
                // backtick-quoted because they are MySQL reserved words.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS audit_events (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        event_id VARCHAR(64) NOT NULL,
                        request_id VARCHAR(64),
                        actor VARCHAR(128),
                        role VARCHAR(32),
                        origin VARCHAR(128),
                        `action` VARCHAR(64) NOT NULL,
                        resource VARCHAR(255),
                        before_hash VARCHAR(64),
                        after_hash VARCHAR(64),
                        reason TEXT,
                        `result` VARCHAR(16) NOT NULL,
                        created_at BIGINT NOT NULL,
                        INDEX idx_audit_events_created_at (created_at),
                        INDEX idx_audit_events_actor (actor),
                        INDEX idx_audit_events_action (`action`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
            }

            case 10 -> {
                // Per-user notification state (PANEL-014). notifications becomes
                // an immutable event stream: recipient=NULL = broadcast; a
                // non-NULL recipient marks a directed notification. The new
                // notification_read table tracks per-user read state so one
                // admin's read/clear never affects another's inbox. Same
                // information_schema-guarded procedure pattern as v2/v6/v7/v8:
                // standard MySQL 8.0 has no "ADD COLUMN IF NOT EXISTS". `read`
                // is backtick-quoted because it is a MySQL reserved word.
                // Existing notifications default recipient=NULL (broadcast).
                statements.add("DROP PROCEDURE IF EXISTS novalink_add_notification_recipient_column");
                statements.add("""
                    CREATE PROCEDURE novalink_add_notification_recipient_column()
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_schema = DATABASE()
                              AND table_name = 'notifications'
                              AND column_name = 'recipient'
                        ) THEN
                            ALTER TABLE notifications ADD COLUMN recipient VARCHAR(64) NULL AFTER `read`;
                        END IF;
                    END
                    """);
                statements.add("CALL novalink_add_notification_recipient_column()");
                statements.add("DROP PROCEDURE IF EXISTS novalink_add_notification_recipient_column");
                statements.add("""
                    CREATE TABLE IF NOT EXISTS notification_read (
                        notification_id BIGINT NOT NULL,
                        user_id VARCHAR(64) NOT NULL,
                        `read` BOOLEAN NOT NULL DEFAULT TRUE,
                        read_at BIGINT NULL,
                        PRIMARY KEY (notification_id, user_id),
                        INDEX idx_notification_read_user_read (user_id, `read`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
                statements.add("CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON notifications (recipient)");
            }

            case 11 -> {
                // Moderation case/appeal workflow (PANEL-007). Three new tables:
                //   moderation_cases  one row per report/moderation action
                //   case_evidence     append-only evidence attached to a case
                //   appeals           appeals against resolved cases
                // content_hash holds the SHA-256 hex of the case/appeal payload
                // (secrets are never persisted — only the hash). `status` is a
                // MySQL non-reserved keyword (the novalink_migrations table uses
                // it un-quoted at v1), so it is left bare here for consistency.
                // `read`/`action`/`result` are reserved words and are not used
                // by these tables. CREATE TABLE IF NOT EXISTS is idempotent in
                // MySQL so no information_schema guard is needed.
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
                        status VARCHAR(16) NOT NULL,
                        assigned_moderator VARCHAR(128),
                        resolution_action VARCHAR(16),
                        resolution_note VARCHAR(1024),
                        content_hash VARCHAR(64),
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        closed_at BIGINT NULL,
                        INDEX idx_moderation_cases_status (status),
                        INDEX idx_moderation_cases_subject (subject_player_id),
                        INDEX idx_moderation_cases_created_at (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
                statements.add("""
                    CREATE TABLE IF NOT EXISTS case_evidence (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        case_id VARCHAR(64) NOT NULL,
                        evidence_type VARCHAR(32) NOT NULL,
                        content_hash VARCHAR(64),
                        description VARCHAR(512),
                        submitted_by VARCHAR(128),
                        created_at BIGINT NOT NULL,
                        INDEX idx_case_evidence_case_id (case_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
                statements.add("""
                    CREATE TABLE IF NOT EXISTS appeals (
                        id VARCHAR(64) PRIMARY KEY,
                        case_id VARCHAR(64) NOT NULL,
                        appellant VARCHAR(128),
                        appeal_reason VARCHAR(1024),
                        status VARCHAR(16) NOT NULL,
                        reviewed_by VARCHAR(128),
                        review_note VARCHAR(1024),
                        reviewed_at BIGINT NULL,
                        content_hash VARCHAR(64),
                        created_at BIGINT NOT NULL,
                        INDEX idx_appeals_case_id (case_id),
                        INDEX idx_appeals_status (status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
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
                //                   posture as audit_events.content_hash. `active`
                //   is a non-reserved word in MySQL (the moderation tables already
                //   use `status` bare for the same reason), so it is left un-quoted.
                //   CREATE TABLE IF NOT EXISTS is idempotent in MySQL so no
                //   information_schema guard is needed.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS config_history (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        revision BIGINT NOT NULL,
                        snapshot_json TEXT NOT NULL,
                        created_at BIGINT NOT NULL,
                        created_by VARCHAR(64),
                        active BOOLEAN NOT NULL DEFAULT FALSE,
                        INDEX idx_config_history_created_at (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
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
                //                     schema v6). `type` is a non-reserved word in
                //                     MySQL (moderation uses `status` bare for the
                //                     same reason) so it is left un-quoted. UUIDs
                //   are VARCHAR(36) strings; timestamps are BIGINT epoch millis.
                //   CREATE TABLE IF NOT EXISTS is idempotent so no information_schema
                //   guard is needed.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS social_relations (
                        source_id VARCHAR(36) NOT NULL,
                        target_id VARCHAR(36) NOT NULL,
                        type VARCHAR(16) NOT NULL,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (source_id, target_id, type),
                        INDEX idx_social_relations_target_id (target_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
                statements.add("""
                    CREATE TABLE IF NOT EXISTS notification_preferences (
                        player_id VARCHAR(36) NOT NULL PRIMARY KEY,
                        mentions_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                        updated_at BIGINT NOT NULL
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
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
                //               stamps them. CREATE TABLE IF NOT EXISTS is idempotent
                //               so no information_schema guard is needed.
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
                        revoked_by VARCHAR(36),
                        INDEX idx_campaigns_status (status),
                        INDEX idx_campaigns_channel_id (channel_id),
                        INDEX idx_campaigns_created_at (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
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
                //   for correlation. CREATE TABLE IF NOT EXISTS is idempotent so no
                //   information_schema guard is needed.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS config_drafts (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        draft_json TEXT NOT NULL,
                        created_by VARCHAR(36) NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        approved_by VARCHAR(36),
                        created_at BIGINT NOT NULL,
                        approved_at BIGINT,
                        published_at BIGINT,
                        PRIMARY KEY (id),
                        INDEX idx_config_drafts_status (status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
                statements.add("""
                    CREATE TABLE IF NOT EXISTS config_backups (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        label VARCHAR(255) NOT NULL,
                        backup_json TEXT NOT NULL,
                        settings_revision BIGINT NOT NULL,
                        created_by VARCHAR(36) NOT NULL,
                        created_at BIGINT NOT NULL,
                        PRIMARY KEY (id),
                        INDEX idx_config_backups_created_at (created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
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
        return "SELECT GET_LOCK('" + MIGRATION_LOCK_NAME + "', ?)";
    }

    @Override
    public String getMigrationLockReleaseSql() {
        return "SELECT RELEASE_LOCK('" + MIGRATION_LOCK_NAME + "')";
    }

    @Override
    public MigrationLock acquireMigrationLock(Connection connection, long timeoutMillis) throws SQLException {
        long roundedSeconds = Math.max(1L, (timeoutMillis + 999L) / 1_000L);
        int timeoutSeconds = (int) Math.min(Integer.MAX_VALUE, roundedSeconds);
        try (PreparedStatement statement = connection.prepareStatement(getMigrationLockAcquireSql())) {
            statement.setInt(1, timeoutSeconds);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1) || result.wasNull()) {
                    throw new SQLTimeoutException(
                            "Timed out after " + timeoutSeconds + " seconds acquiring MySQL migration lock '"
                                    + MIGRATION_LOCK_NAME + "'");
                }
            }
        }

        return new MigrationLock() {
            private boolean held = true;

            @Override
            public boolean ownsTransaction() {
                return false;
            }

            @Override
            public void release(boolean commitTransaction) throws SQLException {
                if (!held) {
                    return;
                }
                try (PreparedStatement statement = connection.prepareStatement(getMigrationLockReleaseSql());
                     ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !result.getBoolean(1) || result.wasNull()) {
                        throw new SQLException("MySQL migration lock was not owned by this connection");
                    }
                } finally {
                    held = false;
                }
            }
        };
    }
}
