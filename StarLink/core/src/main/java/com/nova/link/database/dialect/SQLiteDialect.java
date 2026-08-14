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
