package com.nova.link.database.dialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Dialect abstraction for database schema migrations.
 *
 * <p>Each concrete dialect knows how to render the NovaLink schema (versions
 * 1..{@link #getCurrentVersion()}) in its own SQL flavour. The migration
 * runner ({@link com.nova.link.database.DatabaseMigration}) is dialect-agnostic
 * and pulls all SQL from the dialect, so new backends can be added without
 * touching the runner.
 *
 * <p>Requirements: 22.1 - Auto-migration on startup (multi-database support)
 */
public interface MigrationDialect {

    /**
     * A column required by the hardened migration metadata schema.
     *
     * @param name       unquoted column name used when probing result metadata
     * @param definition complete column definition used after {@code ADD COLUMN}
     */
    record MigrationMetadataColumn(String name, String definition) {
    }

    /**
     * A database-level migration lock held for the whole migration run.
     *
     * <p>SQLite's lock owns the surrounding transaction, while MySQL and
     * PostgreSQL use session locks and therefore leave per-version transaction
     * handling to the runner.
     */
    interface MigrationLock {
        boolean ownsTransaction();

        /**
         * Releases the lock. Transaction-owning locks commit only when
         * {@code commitTransaction} is true; otherwise they roll back.
         */
        void release(boolean commitTransaction) throws SQLException;
    }

    /**
     * The latest schema version this dialect knows how to produce.
     *
     * @return the current schema version
     */
    int getCurrentVersion();

    /**
     * The name of the table that tracks which migrations have been applied.
     *
     * @return the migration tracking table name
     */
    String getMigrationVersionTableName();

    /**
     * DDL for the migration tracking table. Executed once on startup with
     * CREATE TABLE IF NOT EXISTS semantics (the dialect is responsible for
     * emitting the IF NOT EXISTS guard in its own syntax).
     *
     * @return the CREATE TABLE statement for the migration tracking table
     */
    String getMigrationTableDdl();

    /**
     * Columns added when bootstrapping a legacy migration table. Definitions
     * deliberately omit non-constant defaults so SQLite can add them in place.
     */
    default List<MigrationMetadataColumn> getMigrationMetadataColumns() {
        return List.of(
                new MigrationMetadataColumn("applied_at", "applied_at TIMESTAMP"),
                new MigrationMetadataColumn("description", "description VARCHAR(255)"),
                new MigrationMetadataColumn("checksum", "checksum VARCHAR(64)"),
                new MigrationMetadataColumn("started_at", "started_at TIMESTAMP"),
                new MigrationMetadataColumn("completed_at", "completed_at TIMESTAMP"),
                new MigrationMetadataColumn("status", "status VARCHAR(16)")
        );
    }

    /**
     * The ordered list of DDL/DML statements to run when migrating to the
     * given {@code version}. Each statement is executed individually inside
     * a single transaction (where the backend supports DDL transactions).
     *
     * @param version the target migration version (1..{@link #getCurrentVersion()})
     * @return the ordered statements for that version
     */
    List<String> getMigrationStatements(int version);

    /**
     * A short human-readable description of the migration at the given
     * version, recorded in the migration tracking table.
     *
     * @param version the migration version
     * @return the description
     */
    String getMigrationDescription(int version);

    /**
     * SQL used to acquire the backend's database-level migration lock.
     *
     * @return lock acquisition SQL, exposed for diagnostics and dialect tests
     */
    String getMigrationLockAcquireSql();

    /**
     * SQL used to release the backend's database-level migration lock.
     *
     * @return lock release SQL, exposed for diagnostics and dialect tests
     */
    String getMigrationLockReleaseSql();

    /**
     * Acquires the backend's database-level migration lock.
     *
     * @param connection    dedicated connection held for the whole migration
     * @param timeoutMillis maximum time to wait for another migrator
     * @return the acquired lock
     * @throws SQLException when acquisition fails or times out
     */
    MigrationLock acquireMigrationLock(Connection connection, long timeoutMillis) throws SQLException;
}
