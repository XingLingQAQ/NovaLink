package com.nova.link.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles database schema migrations for NovaLink.
 * Automatically creates and updates database tables on startup.
 * 
 * Requirements: 22.1 - Auto-migration on startup
 */
public class DatabaseMigration {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMigration.class);
    private static final String MIGRATION_TABLE = "novalink_migrations";
    private static final int CURRENT_VERSION = 1;

    private final DataSource dataSource;

    public DatabaseMigration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs all pending migrations.
     *
     * @throws DatabaseException if migration fails
     */
    public void migrate() throws DatabaseException {
        try (Connection conn = dataSource.getConnection()) {
            // Create migration tracking table if not exists
            createMigrationTable(conn);
            
            // Get current version
            int currentVersion = getCurrentVersion(conn);
            logger.info("Current database version: {}", currentVersion);
            
            // Run pending migrations
            if (currentVersion < CURRENT_VERSION) {
                for (int version = currentVersion + 1; version <= CURRENT_VERSION; version++) {
                    runMigration(conn, version);
                }
                logger.info("Database migrated to version {}", CURRENT_VERSION);
            } else {
                logger.info("Database is up to date");
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to run database migrations", e);
        }
    }

    private void createMigrationTable(Connection conn) throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS %s (
                version INT PRIMARY KEY,
                applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                description VARCHAR(255)
            )
            """.formatted(MIGRATION_TABLE);
        
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private int getCurrentVersion(Connection conn) throws SQLException {
        String sql = "SELECT MAX(version) FROM " + MIGRATION_TABLE;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    private void runMigration(Connection conn, int version) throws SQLException {
        logger.info("Running migration version {}", version);
        
        List<String> statements = getMigrationStatements(version);
        String description = getMigrationDescription(version);
        
        conn.setAutoCommit(false);
        try {
            for (String sql : statements) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                }
            }
            
            // Record migration
            String recordSql = "INSERT INTO " + MIGRATION_TABLE + " (version, description) VALUES (?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(recordSql)) {
                stmt.setInt(1, version);
                stmt.setString(2, description);
                stmt.executeUpdate();
            }
            
            conn.commit();
            logger.info("Migration {} completed: {}", version, description);
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private List<String> getMigrationStatements(int version) {
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
            // case 2 -> { ... }
            
            default -> throw new IllegalArgumentException("Unknown migration version: " + version);
        }
        
        return statements;
    }

    private String getMigrationDescription(int version) {
        return switch (version) {
            case 1 -> "Initial schema - players, channels, mutes, invitations tables";
            default -> "Unknown migration";
        };
    }

    /**
     * Gets the current database version.
     *
     * @return the current version number
     * @throws DatabaseException if query fails
     */
    public int getVersion() throws DatabaseException {
        try (Connection conn = dataSource.getConnection()) {
            return getCurrentVersion(conn);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to get database version", e);
        }
    }

    /**
     * Checks if the database needs migration.
     *
     * @return true if migration is needed
     * @throws DatabaseException if check fails
     */
    public boolean needsMigration() throws DatabaseException {
        return getVersion() < CURRENT_VERSION;
    }
}
