package com.nova.link.database.dialect;

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
     * The prepared INSERT used to record an applied migration in the
     * tracking table. Must have two bind parameters: version (int) and
     * description (String), in that order.
     *
     * @return the INSERT SQL for recording a migration
     */
    String getRecordMigrationSql();
}
