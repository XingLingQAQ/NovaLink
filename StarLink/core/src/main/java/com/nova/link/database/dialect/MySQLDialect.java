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
    private static final int CURRENT_VERSION = 5;

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
                statements.add("""
                    ALTER TABLE players ADD COLUMN IF NOT EXISTS platform VARCHAR(32) NULL AFTER active_channel
                    """);
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
