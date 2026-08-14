package com.nova.link.database.dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * PostgreSQL dialect for schema migrations.
 *
 * <p>Renders the NovaLink schema in PostgreSQL syntax:
 * <ul>
 *   <li>{@code AUTO_INCREMENT} → {@code GENERATED ALWAYS AS IDENTITY} (PG 10+)</li>
 *   <li>{@code ON UPDATE CURRENT_TIMESTAMP} dropped — {@code updated_at} is
 *       application-managed (no trigger)</li>
 *   <li>inline {@code INDEX} → separate {@code CREATE INDEX IF NOT EXISTS}</li>
 *   <li>{@code UNIQUE KEY uk_.. (cols)} → {@code CONSTRAINT uk_.. UNIQUE (cols)}</li>
 *   <li>{@code ENGINE/CHARSET} clauses dropped</li>
 *   <li>{@code ALTER ... AFTER col} → {@code ALTER ... ADD COLUMN} without AFTER</li>
 * </ul>
 *
 * <p>Requirements: 22.1 - Auto-migration on startup (multi-database support)
 */
public class PostgreSQLDialect implements MigrationDialect {

    private static final String MIGRATION_TABLE = "novalink_migrations";
    private static final long MIGRATION_LOCK_KEY = 0x4E4F56414C494E4BL;
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
                        max_capacity INT DEFAULT 100,
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
                        id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
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
                // Add platform column to players table.
                // PostgreSQL has no AFTER clause; column is simply appended.
                statements.add("""
                    ALTER TABLE players ADD COLUMN IF NOT EXISTS platform VARCHAR(32) NULL
                    """);
            }
            case 3 -> {
                // Bans table — mirrors mutes schema.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS bans (
                        id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
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
                // Notifications table.
                statements.add("""
                    CREATE TABLE IF NOT EXISTS notifications (
                        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
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
                        id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
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
        return "SELECT pg_try_advisory_lock(" + MIGRATION_LOCK_KEY + ")";
    }

    @Override
    public String getMigrationLockReleaseSql() {
        return "SELECT pg_advisory_unlock(" + MIGRATION_LOCK_KEY + ")";
    }

    @Override
    public MigrationLock acquireMigrationLock(Connection connection, long timeoutMillis) throws SQLException {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        long deadline = System.nanoTime() + timeoutNanos;
        try (PreparedStatement statement = connection.prepareStatement(getMigrationLockAcquireSql())) {
            while (true) {
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) {
                        break;
                    }
                }

                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    throw new SQLTimeoutException(
                            "Timed out after " + timeoutMillis + " ms acquiring PostgreSQL advisory migration lock");
                }
                long sleepMillis = Math.max(1L,
                        Math.min(100L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new SQLTransientException(
                            "Interrupted while acquiring PostgreSQL advisory migration lock", exception);
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
                    if (!result.next() || !result.getBoolean(1)) {
                        throw new SQLException("PostgreSQL advisory migration lock was not owned by this connection");
                    }
                } finally {
                    held = false;
                }
            }
        };
    }
}
