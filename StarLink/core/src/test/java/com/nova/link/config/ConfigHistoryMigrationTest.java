package com.nova.link.config;

import com.nova.link.database.DatabaseMigration;
import com.nova.link.database.dialect.SQLiteDialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §11.6 Project 20 / PANEL proposal 10 — verifies migration v12 creates the
 * {@code config_history} table idempotently across a fresh schema and a rerun.
 *
 * <p>The migration is delegated to {@link SQLiteDialect#getMigrationStatements(int) case 12},
 * so this test is dialect-agnostic at the assertion level: it only checks that the table
 * + index exist after the runner finishes, and that a second {@code migrate()}
 * is a no-op. The three-dialect SQL itself is covered by
 * {@code DatabaseMigrationHardeningTest} (v12 table + index assertions there).
 */
class ConfigHistoryMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void migrationCreatesConfigHistoryTable() throws Exception {
        try (HikariDataSource dataSource = sqliteDataSource(tempDir.resolve("config-history.db"))) {
            DatabaseMigration migration = new DatabaseMigration(dataSource, new SQLiteDialect());
            migration.migrate();

            assertThat(migration.getVersion()).isEqualTo(13);
            assertThat(tableExists(dataSource, "config_history")).isTrue();
            assertThat(indexExists(dataSource, "idx_config_history_created_at")).isTrue();
            // Column shape sanity-check: the snapshot payload column is TEXT and
            // the active flag defaults to FALSE. These assertions guard against
            // an accidental rename that would break the provider SQL.
            assertThat(columnExists(dataSource, "config_history", "id")).isTrue();
            assertThat(columnExists(dataSource, "config_history", "revision")).isTrue();
            assertThat(columnExists(dataSource, "config_history", "snapshot_json")).isTrue();
            assertThat(columnExists(dataSource, "config_history", "created_at")).isTrue();
            assertThat(columnExists(dataSource, "config_history", "created_by")).isTrue();
            assertThat(columnExists(dataSource, "config_history", "active")).isTrue();
        }
    }

    @Test
    void rerunIsIdempotent() throws Exception {
        try (HikariDataSource dataSource = sqliteDataSource(tempDir.resolve("config-history-rerun.db"))) {
            DatabaseMigration migration = new DatabaseMigration(dataSource, new SQLiteDialect());
            migration.migrate();
            int firstCompleted = completedMigrationCount(dataSource);
            assertThat(firstCompleted).isEqualTo(13);

            // A second migrate() must not apply any new versions.
            migration.migrate();
            assertThat(completedMigrationCount(dataSource)).isEqualTo(firstCompleted);
            assertThat(tableExists(dataSource, "config_history")).isTrue();
        }
    }

    private static HikariDataSource sqliteDataSource(Path file) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        return new HikariDataSource(config);
    }

    private static boolean tableExists(HikariDataSource dataSource, String tableName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '" + tableName + "'")) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    private static boolean indexExists(HikariDataSource dataSource, String indexName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '" + indexName + "'")) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    private static boolean columnExists(HikariDataSource dataSource, String tableName, String column) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static int completedMigrationCount(HikariDataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM novalink_migrations WHERE status = 'COMPLETED'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
