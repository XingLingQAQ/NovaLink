package com.nova.link.database;

import com.nova.link.database.dialect.MigrationDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

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
    private static final long DEFAULT_LOCK_TIMEOUT_MILLIS = 30_000L;
    private static final String STATUS_STARTED = "STARTED";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";

    private final DataSource dataSource;
    private final MigrationDialect dialect;
    private final long lockTimeoutMillis;

    /**
     * Creates a migration runner for the given dialect.
     *
     * @param dataSource the JDBC data source to migrate
     * @param dialect    the SQL dialect that provides migration statements
     */
    public DatabaseMigration(DataSource dataSource, MigrationDialect dialect) {
        this(dataSource, dialect, DEFAULT_LOCK_TIMEOUT_MILLIS);
    }

    DatabaseMigration(DataSource dataSource, MigrationDialect dialect, long lockTimeoutMillis) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.lockTimeoutMillis = Math.max(0L, lockTimeoutMillis);
    }

    /**
     * Runs all pending migrations up to {@link MigrationDialect#getCurrentVersion()}.
     *
     * @throws DatabaseException if migration fails
     */
    public void migrate() throws DatabaseException {
        try (Connection conn = dataSource.getConnection()) {
            MigrationDialect.MigrationLock lock;
            try {
                lock = dialect.acquireMigrationLock(conn, lockTimeoutMillis);
            } catch (SQLTimeoutException exception) {
                throw new DatabaseException(exception.getMessage(), exception);
            } catch (SQLException exception) {
                throw new DatabaseException(
                        "Failed to acquire database migration lock: " + exception.getMessage(), exception);
            }

            boolean commitLockTransaction = false;
            DatabaseException failure = null;
            try {
                migrateLocked(conn, lock.ownsTransaction());
                commitLockTransaction = true;
            } catch (MigrationExecutionException exception) {
                commitLockTransaction = exception.canCommitOuterTransaction();
                failure = new DatabaseException(
                        "Failed migration version " + exception.version() + " ("
                                + exception.description() + "): " + exception.getMessage(),
                        exception);
            } catch (DatabaseException exception) {
                failure = exception;
            } catch (SQLException exception) {
                failure = new DatabaseException(
                        "Failed to run database migrations: " + exception.getMessage(), exception);
            } catch (RuntimeException exception) {
                failure = new DatabaseException(
                        "Unexpected failure while running database migrations: " + exception.getMessage(),
                        exception);
            } finally {
                try {
                    lock.release(commitLockTransaction);
                } catch (SQLException releaseFailure) {
                    if (failure == null) {
                        failure = new DatabaseException(
                                "Failed to release database migration lock: " + releaseFailure.getMessage(),
                                releaseFailure);
                    } else {
                        failure.addSuppressed(releaseFailure);
                    }
                }
            }

            if (failure != null) {
                throw failure;
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Failed to open migration connection", exception);
        }
    }

    private void migrateLocked(Connection conn, boolean outerTransaction)
            throws SQLException, DatabaseException, MigrationExecutionException {
        bootstrapMigrationMetadata(conn);
        Map<Integer, MigrationRecord> records = readMigrationRecords(conn);
        int currentVersion = validateRecordsAndGetCurrentVersion(records);
        int targetVersion = dialect.getCurrentVersion();
        logger.info("Current database version: {}", currentVersion);

        if (currentVersion >= targetVersion) {
            logger.info("Database is up to date");
            return;
        }

        for (int version = currentVersion + 1; version <= targetVersion; version++) {
            MigrationRecord previousAttempt = records.get(version);
            if (previousAttempt != null) {
                logger.warn(
                        "Retrying migration {} previously left in {} state. "
                                + "Transactional DDL is rolled back automatically; non-transactional DDL "
                                + "must be idempotent or repaired before retry.",
                        version, previousAttempt.status());
            }
            runMigration(conn, version, outerTransaction);
        }
        logger.info("Database migrated to version {}", targetVersion);
    }

    private void bootstrapMigrationMetadata(Connection conn) throws SQLException, DatabaseException {
        try (Statement statement = conn.createStatement()) {
            statement.execute(dialect.getMigrationTableDdl());
        }

        Set<String> existingColumns = readMigrationColumns(conn);
        String table = dialect.getMigrationVersionTableName();
        for (MigrationDialect.MigrationMetadataColumn column : dialect.getMigrationMetadataColumns()) {
            if (existingColumns.add(column.name().toLowerCase(Locale.ROOT))) {
                try (Statement statement = conn.createStatement()) {
                    statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column.definition());
                }
                logger.info("Added migration metadata column {}", column.name());
            }
        }

        backfillLegacyMigrationRows(conn);
    }

    private Set<String> readMigrationColumns(Connection conn) throws SQLException {
        Set<String> columns = new HashSet<>();
        String sql = "SELECT * FROM " + dialect.getMigrationVersionTableName() + " WHERE 1 = 0";
        try (Statement statement = conn.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = result.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                columns.add(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private void backfillLegacyMigrationRows(Connection conn) throws SQLException, DatabaseException {
        List<LegacyMigrationRecord> legacyRows = new ArrayList<>();
        String table = dialect.getMigrationVersionTableName();
        try (Statement statement = conn.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT version, applied_at, description, checksum, started_at, completed_at, status "
                             + "FROM " + table + " ORDER BY version")) {
            while (result.next()) {
                legacyRows.add(new LegacyMigrationRecord(
                        result.getInt("version"),
                        result.getTimestamp("applied_at"),
                        result.getString("description"),
                        result.getString("checksum"),
                        result.getTimestamp("started_at"),
                        result.getTimestamp("completed_at"),
                        result.getString("status")
                ));
            }
        }

        String updateSql = "UPDATE " + table + " SET applied_at = ?, description = ?, checksum = ?, "
                + "started_at = ?, completed_at = ?, status = ? WHERE version = ?";
        for (LegacyMigrationRecord row : legacyRows) {
            if (row.version() < 1 || row.version() > dialect.getCurrentVersion()) {
                throw new DatabaseException(
                        "Database contains migration version " + row.version()
                                + " which is not supported by this application; refusing to start");
            }

            String normalizedStatus = row.status() == null
                    ? STATUS_COMPLETED
                    : row.status().trim().toUpperCase(Locale.ROOT);
            Timestamp now = Timestamp.from(Instant.now());
            Timestamp appliedAt = row.appliedAt() != null ? row.appliedAt() : now;
            Timestamp startedAt = row.startedAt() != null ? row.startedAt() : appliedAt;
            Timestamp completedAt = STATUS_COMPLETED.equals(normalizedStatus)
                    ? (row.completedAt() != null ? row.completedAt() : appliedAt)
                    : null;
            String description = row.description() != null
                    ? row.description()
                    : dialect.getMigrationDescription(row.version());
            String checksum = row.checksum() != null
                    ? row.checksum()
                    : checksum(dialect.getMigrationStatements(row.version()));

            boolean needsUpdate = row.appliedAt() == null
                    || row.description() == null
                    || row.checksum() == null
                    || row.startedAt() == null
                    || !Objects.equals(row.completedAt(), completedAt)
                    || !Objects.equals(row.status(), normalizedStatus);
            if (!needsUpdate) {
                continue;
            }

            try (PreparedStatement statement = conn.prepareStatement(updateSql)) {
                statement.setTimestamp(1, appliedAt);
                statement.setString(2, description);
                statement.setString(3, checksum);
                statement.setTimestamp(4, startedAt);
                statement.setTimestamp(5, completedAt);
                statement.setString(6, normalizedStatus);
                statement.setInt(7, row.version());
                statement.executeUpdate();
            }
        }
    }

    private Map<Integer, MigrationRecord> readMigrationRecords(Connection conn) throws SQLException {
        Map<Integer, MigrationRecord> records = new LinkedHashMap<>();
        String sql = "SELECT version, checksum, status FROM "
                + dialect.getMigrationVersionTableName() + " ORDER BY version";
        try (Statement statement = conn.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                int version = result.getInt("version");
                records.put(version, new MigrationRecord(
                        version,
                        result.getString("checksum"),
                        result.getString("status")
                ));
            }
        }
        return records;
    }

    private int validateRecordsAndGetCurrentVersion(Map<Integer, MigrationRecord> records)
            throws DatabaseException {
        int targetVersion = dialect.getCurrentVersion();
        for (MigrationRecord record : records.values()) {
            if (record.version() < 1 || record.version() > targetVersion) {
                throw new DatabaseException(
                        "Database contains migration version " + record.version()
                                + " newer than supported version " + targetVersion + "; refusing to start");
            }
            if (!Set.of(STATUS_STARTED, STATUS_COMPLETED, STATUS_FAILED).contains(record.status())) {
                throw new DatabaseException(
                        "Migration version " + record.version() + " has invalid status '"
                                + record.status() + "'; refusing to start");
            }

            String expectedChecksum = checksum(dialect.getMigrationStatements(record.version()));
            if (record.checksum() == null || !record.checksum().equalsIgnoreCase(expectedChecksum)) {
                throw new DatabaseException(
                        "Migration checksum mismatch for version " + record.version()
                                + ": recorded=" + record.checksum() + ", current=" + expectedChecksum
                                + "; refusing to start because recorded migration SQL was modified");
            }
        }

        int currentVersion = 0;
        int firstPendingVersion = 0;
        for (int version = 1; version <= targetVersion; version++) {
            MigrationRecord record = records.get(version);
            boolean completed = record != null && STATUS_COMPLETED.equals(record.status());
            if (completed) {
                if (firstPendingVersion != 0) {
                    throw new DatabaseException(
                            "Inconsistent migration metadata: version " + version
                                    + " is completed after pending version " + firstPendingVersion
                                    + "; refusing to start");
                }
                currentVersion = version;
            } else if (firstPendingVersion == 0) {
                firstPendingVersion = version;
            }
        }
        return currentVersion;
    }

    private void runMigration(Connection conn, int version, boolean outerTransaction)
            throws MigrationExecutionException {
        logger.info("Running migration version {}", version);
        List<String> statements = List.copyOf(dialect.getMigrationStatements(version));
        String description = dialect.getMigrationDescription(version);
        String checksum = checksum(statements);
        Timestamp startedAt = Timestamp.from(Instant.now());

        if (outerTransaction) {
            runMigrationInSavepoint(conn, version, description, checksum, startedAt, statements);
        } else {
            runMigrationInTransaction(conn, version, description, checksum, startedAt, statements);
        }
    }

    private void runMigrationInSavepoint(
            Connection conn,
            int version,
            String description,
            String checksum,
            Timestamp startedAt,
            List<String> statements
    ) throws MigrationExecutionException {
        String savepoint = "novalink_migration_" + version;
        try {
            executeControlSql(conn, "SAVEPOINT " + savepoint);
            writeMigrationState(conn, version, checksum, description, startedAt, null, STATUS_STARTED);
            executeStatements(conn, statements);
            Timestamp completedAt = Timestamp.from(Instant.now());
            writeMigrationState(
                    conn, version, checksum, description, startedAt, completedAt, STATUS_COMPLETED);
            executeControlSql(conn, "RELEASE SAVEPOINT " + savepoint);
            logger.info("Migration {} completed: {}", version, description);
        } catch (SQLException migrationFailure) {
            boolean savepointRolledBack = false;
            try {
                executeControlSql(conn, "ROLLBACK TO SAVEPOINT " + savepoint);
                executeControlSql(conn, "RELEASE SAVEPOINT " + savepoint);
                savepointRolledBack = true;
            } catch (SQLException rollbackFailure) {
                migrationFailure.addSuppressed(rollbackFailure);
            }

            boolean failedStateRecorded = false;
            if (savepointRolledBack) {
                try {
                    writeMigrationState(
                            conn, version, checksum, description, startedAt, null, STATUS_FAILED);
                    failedStateRecorded = true;
                } catch (SQLException statusFailure) {
                    migrationFailure.addSuppressed(statusFailure);
                }
            }
            throw new MigrationExecutionException(
                    version, description, migrationFailure, failedStateRecorded);
        }
    }

    private void runMigrationInTransaction(
            Connection conn,
            int version,
            String description,
            String checksum,
            Timestamp startedAt,
            List<String> statements
    ) throws MigrationExecutionException {
        boolean originalAutoCommit;
        try {
            originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
        } catch (SQLException exception) {
            throw new MigrationExecutionException(version, description, exception, false);
        }

        try {
            writeMigrationState(conn, version, checksum, description, startedAt, null, STATUS_STARTED);
            executeStatements(conn, statements);
            Timestamp completedAt = Timestamp.from(Instant.now());
            writeMigrationState(
                    conn, version, checksum, description, startedAt, completedAt, STATUS_COMPLETED);
            conn.commit();
            logger.info("Migration {} completed: {}", version, description);
        } catch (SQLException migrationFailure) {
            boolean rollbackSucceeded = false;
            try {
                conn.rollback();
                rollbackSucceeded = true;
            } catch (SQLException rollbackFailure) {
                migrationFailure.addSuppressed(rollbackFailure);
            }

            boolean failedStateRecorded = false;
            if (rollbackSucceeded) {
                try {
                    writeMigrationState(
                            conn, version, checksum, description, startedAt, null, STATUS_FAILED);
                    conn.commit();
                    failedStateRecorded = true;
                } catch (SQLException statusFailure) {
                    migrationFailure.addSuppressed(statusFailure);
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackFailure) {
                        migrationFailure.addSuppressed(rollbackFailure);
                    }
                }
            }
            throw new MigrationExecutionException(
                    version, description, migrationFailure, failedStateRecorded);
        } finally {
            try {
                conn.setAutoCommit(originalAutoCommit);
            } catch (SQLException restoreFailure) {
                logger.error("Failed to restore migration connection auto-commit", restoreFailure);
            }
        }
    }

    private void executeStatements(Connection conn, List<String> statements) throws SQLException {
        for (String sql : statements) {
            try (Statement statement = conn.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private void executeControlSql(Connection conn, String sql) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute(sql);
        }
    }

    private void writeMigrationState(
            Connection conn,
            int version,
            String checksum,
            String description,
            Timestamp startedAt,
            Timestamp completedAt,
            String status
    ) throws SQLException {
        String table = dialect.getMigrationVersionTableName();
        String updateSql = "UPDATE " + table
                + " SET checksum = ?, description = ?, started_at = ?, completed_at = ?, "
                + "status = ?, applied_at = ? WHERE version = ?";
        int updated;
        try (PreparedStatement statement = conn.prepareStatement(updateSql)) {
            statement.setString(1, checksum);
            statement.setString(2, description);
            statement.setTimestamp(3, startedAt);
            statement.setTimestamp(4, completedAt);
            statement.setString(5, status);
            statement.setTimestamp(6, STATUS_COMPLETED.equals(status) ? completedAt : null);
            statement.setInt(7, version);
            updated = statement.executeUpdate();
        }

        if (updated == 0) {
            String insertSql = "INSERT INTO " + table
                    + " (version, checksum, description, started_at, completed_at, status, applied_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = conn.prepareStatement(insertSql)) {
                statement.setInt(1, version);
                statement.setString(2, checksum);
                statement.setString(3, description);
                statement.setTimestamp(4, startedAt);
                statement.setTimestamp(5, completedAt);
                statement.setString(6, status);
                statement.setTimestamp(7, STATUS_COMPLETED.equals(status) ? completedAt : null);
                statement.executeUpdate();
            }
        }
    }

    private static String checksum(List<String> statements) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String statement : statements) {
                digest.update(normalizeSql(statement).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizeSql(String sql) {
        Objects.requireNonNull(sql, "migration SQL statement");
        StringBuilder normalized = new StringBuilder(sql.length());
        char quote = 0;
        boolean pendingWhitespace = false;
        for (int index = 0; index < sql.length(); index++) {
            char character = sql.charAt(index);
            if (quote != 0) {
                normalized.append(character);
                if (character == quote) {
                    if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                        normalized.append(sql.charAt(++index));
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }

            if (Character.isWhitespace(character)) {
                pendingWhitespace = normalized.length() > 0;
                continue;
            }
            if (pendingWhitespace) {
                normalized.append(' ');
                pendingWhitespace = false;
            }
            normalized.append(character);
            if (character == '\'' || character == '"' || character == '`') {
                quote = character;
            }
        }
        return normalized.toString();
    }

    /**
     * Gets the current database version.
     *
     * @return the current version number
     * @throws DatabaseException if query fails
     */
    public int getVersion() throws DatabaseException {
        try (Connection conn = dataSource.getConnection()) {
            Set<String> columns = readMigrationColumns(conn);
            String statusExpression = columns.contains("status")
                    ? "status"
                    : "'" + STATUS_COMPLETED + "'";
            String sql = "SELECT version, " + statusExpression + " AS migration_status FROM "
                    + dialect.getMigrationVersionTableName() + " ORDER BY version";
            int expectedVersion = 1;
            int currentVersion = 0;
            try (Statement statement = conn.createStatement();
                 ResultSet result = statement.executeQuery(sql)) {
                while (result.next()) {
                    int version = result.getInt("version");
                    String status = result.getString("migration_status");
                    if (version != expectedVersion || !STATUS_COMPLETED.equals(status)) {
                        break;
                    }
                    currentVersion = version;
                    expectedVersion++;
                }
            }
            return currentVersion;
        } catch (SQLException exception) {
            throw new DatabaseException("Failed to get database version", exception);
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

    private record MigrationRecord(int version, String checksum, String status) {
    }

    private record LegacyMigrationRecord(
            int version,
            Timestamp appliedAt,
            String description,
            String checksum,
            Timestamp startedAt,
            Timestamp completedAt,
            String status
    ) {
    }

    private static final class MigrationExecutionException extends SQLException {
        private final int version;
        private final String description;
        private final boolean canCommitOuterTransaction;

        private MigrationExecutionException(
                int version,
                String description,
                SQLException cause,
                boolean canCommitOuterTransaction
        ) {
            super(cause.getMessage(), cause.getSQLState(), cause.getErrorCode(), cause);
            this.version = version;
            this.description = description;
            this.canCommitOuterTransaction = canCommitOuterTransaction;
        }

        private int version() {
            return version;
        }

        private String description() {
            return description;
        }

        private boolean canCommitOuterTransaction() {
            return canCommitOuterTransaction;
        }
    }
}
