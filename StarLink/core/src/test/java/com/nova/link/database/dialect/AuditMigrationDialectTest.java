package com.nova.link.database.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DDL-content tests for migration version 9 (the {@code audit_events} table
 * introduced by PANEL-006). Each dialect renders the table with its own
 * idiom — SQLite {@code INTEGER PRIMARY KEY AUTOINCREMENT}, PostgreSQL
 * {@code BIGINT GENERATED ALWAYS AS IDENTITY}, MySQL {@code BIGINT AUTO_INCREMENT}
 * with backtick-quoted reserved words — but all three must:
 *
 * <ul>
 *   <li>create the {@code audit_events} table idempotently ({@code IF NOT EXISTS})</li>
 *   <li>include every PANEL-006 column</li>
 *   <li>add indexes on {@code created_at}, {@code actor}, and {@code action}</li>
 * </ul>
 *
 * <p>These tests assert on the SQL text only; execution against a live database
 * is covered by {@code DatabaseMigrationHardeningTest}.
 *
 * <p>Requirements: PANEL-006 audit log (migration v9)
 */
@DisplayName("Migration v9 audit_events DDL (SQLite/PostgreSQL/MySQL)")
class AuditMigrationDialectTest {

    @Test
    @DisplayName("SQLite v9 creates audit_events with AUTOINCREMENT + 3 indexes")
    void sqliteV9CreatesAuditEventsTable() {
        List<String> statements = new SQLiteDialect().getMigrationStatements(9);
        String merged = String.join("\n", statements);

        // Table is created idempotently.
        assertThat(merged).contains("CREATE TABLE IF NOT EXISTS audit_events");
        // SQLite idiom: INTEGER PRIMARY KEY AUTOINCREMENT (INTEGER is 64-bit).
        assertThat(merged).contains("id INTEGER PRIMARY KEY AUTOINCREMENT");

        // Every PANEL-006 column is present.
        assertAllAuditColumns(merged);

        // Three indexes (created_at, actor, action) — each idempotent.
        assertThat(merged).contains("CREATE INDEX IF NOT EXISTS idx_audit_events_created_at");
        assertThat(merged).contains("CREATE INDEX IF NOT EXISTS idx_audit_events_actor");
        assertThat(merged).contains("CREATE INDEX IF NOT EXISTS idx_audit_events_action");
    }

    @Test
    @DisplayName("PostgreSQL v9 creates audit_events with IDENTITY + 3 indexes")
    void postgresV9CreatesAuditEventsTable() {
        List<String> statements = new PostgreSQLDialect().getMigrationStatements(9);
        String merged = String.join("\n", statements);

        assertThat(merged).contains("CREATE TABLE IF NOT EXISTS audit_events");
        // PostgreSQL idiom: BIGINT GENERATED ALWAYS AS IDENTITY.
        assertThat(merged).contains("id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY");

        assertAllAuditColumns(merged);

        assertThat(merged).contains("CREATE INDEX IF NOT EXISTS idx_audit_events_created_at");
        assertThat(merged).contains("CREATE INDEX IF NOT EXISTS idx_audit_events_actor");
        assertThat(merged).contains("CREATE INDEX IF NOT EXISTS idx_audit_events_action");
    }

    @Test
    @DisplayName("MySQL v9 creates audit_events with AUTO_INCREMENT + backtick-quoted reserved words")
    void mysqlV9CreatesAuditEventsTable() {
        List<String> statements = new MySQLDialect().getMigrationStatements(9);
        String merged = String.join("\n", statements);

        assertThat(merged).contains("CREATE TABLE IF NOT EXISTS audit_events");
        // MySQL idiom: BIGINT AUTO_INCREMENT.
        assertThat(merged).contains("id BIGINT AUTO_INCREMENT PRIMARY KEY");
        // `action` and `result` are MySQL reserved words and MUST be backtick-quoted.
        assertThat(merged).contains("`action` VARCHAR(64) NOT NULL");
        assertThat(merged).contains("`result` VARCHAR(16) NOT NULL");
        // MySQL inline INDEX entries (separate CREATE INDEX statements are
        // also valid, but this dialect uses inline for the ENGINE clause).
        assertThat(merged).contains("INDEX idx_audit_events_created_at (created_at)");
        assertThat(merged).contains("INDEX idx_audit_events_actor (actor)");
        assertThat(merged).contains("INDEX idx_audit_events_action (`action`)");
        // ENGINE + charset.
        assertThat(merged).contains("ENGINE=InnoDB");
        assertThat(merged).contains("DEFAULT CHARSET=utf8mb4");

        assertAllAuditColumns(merged);
    }

    @Test
    @DisplayName("all three dialects agree on the column set")
    void allDialectsAgreeOnColumnSet() {
        for (MigrationDialect dialect : List.of(
                new SQLiteDialect(),
                new PostgreSQLDialect(),
                new MySQLDialect())) {
            String merged = String.join("\n", dialect.getMigrationStatements(9));
            assertAllAuditColumns(merged);
        }
    }

    /**
     * Asserts every PANEL-006 column appears in the DDL. Shared across dialects
     * because the column contract is database-neutral.
     */
    private static void assertAllAuditColumns(String ddl) {
        assertThat(ddl).contains("event_id VARCHAR(64) NOT NULL");
        assertThat(ddl).contains("request_id VARCHAR(64)");
        assertThat(ddl).contains("actor VARCHAR(128)");
        assertThat(ddl).contains("role VARCHAR(32)");
        assertThat(ddl).contains("origin VARCHAR(128)");
        assertThat(ddl).contains("resource VARCHAR(255)");
        assertThat(ddl).contains("before_hash VARCHAR(64)");
        assertThat(ddl).contains("after_hash VARCHAR(64)");
        assertThat(ddl).contains("reason TEXT");
        assertThat(ddl).contains("created_at BIGINT NOT NULL");
    }
}
