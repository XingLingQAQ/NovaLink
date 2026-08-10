package com.nova.link.database;

import com.nova.link.database.dialect.MigrationDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;

/**
 * Runs database schema migrations for NovaLink.
 *
 * <p>The runner is dialect-agnostic: all SQL (migration table DDL, per-version
 * statements, and the migration-record INSERT) is supplied by a
 * {@link MigrationDialect}. This lets a single runner drive MySQL, PostgreSQL,
 * SQLite, and any future backend without per-backend migration code.
 *
 * <p>Requirements: 22.1 - Auto-migration on startup
 */
public class DatabaseMigration {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMigration.class);

    private final DataSource dataSource;
    private final MigrationDialect dialect;

    /**
     * Creates a migration runner for the given dialect.
     *
     * @param dataSource the JDBC data source to migrate
     * @param dialect    the SQL dialect that provides migration statements
     */
    public DatabaseMigration(DataSource dataSource, MigrationDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    /**
     * Runs all pending migrations up to {@link MigrationDialect#getCurrentVersion()}.
     *
     * @throws DatabaseException if migration fails
     */
    public void migrate() throws DatabaseException {
        try (Connection conn = dataSource.getConnection()) {
            // Create migration tracking table if not exists
            createMigrationTable(conn);

            // Get current version
            int currentVersion = getCurrentVersion(conn);
            int targetVersion = dialect.getCurrentVersion();
            logger.info("Current database version: {}", currentVersion);

            // Run pending migrations
            if (currentVersion < targetVersion) {
                for (int version = currentVersion + 1; version <= targetVersion; version++) {
                    runMigration(conn, version);
                }
                logger.info("Database migrated to version {}", targetVersion);
            } else {
                logger.info("Database is up to date");
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to run database migrations", e);
        }
    }

    private void createMigrationTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(dialect.getMigrationTableDdl());
        }
    }

    private int getCurrentVersion(Connection conn) throws SQLException {
        String sql = "SELECT MAX(version) FROM " + dialect.getMigrationVersionTableName();
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

        List<String> statements = dialect.getMigrationStatements(version);
        String description = dialect.getMigrationDescription(version);

        conn.setAutoCommit(false);
        try {
            for (String sql : statements) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(sql);
                }
            }

            // Record migration
            try (PreparedStatement stmt = conn.prepareStatement(dialect.getRecordMigrationSql())) {
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
        return getVersion() < dialect.getCurrentVersion();
    }
}
