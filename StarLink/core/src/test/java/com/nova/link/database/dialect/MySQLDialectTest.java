package com.nova.link.database.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MySQLDialect} migration DDL.
 *
 * <p>Primarily a regression guard for the v4 notifications table: {@code READ}
 * is a reserved word in MySQL 8 / MariaDB, so the column must always be
 * backtick-quoted or the migration fails with a syntax error at startup.
 */
@DisplayName("MySQLDialect DDL tests")
class MySQLDialectTest {

    private final MySQLDialect dialect = new MySQLDialect();

    @Test
    @DisplayName("v4 migration quotes the reserved word `read` in column and index")
    void v4NotificationsTableQuotesReadColumn() {
        List<String> statements = dialect.getMigrationStatements(4);

        assertThat(statements).hasSize(1);
        String ddl = statements.get(0);
        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS notifications");
        // Column definition must be quoted: `read` BOOLEAN ...
        assertThat(ddl).contains("`read` BOOLEAN NOT NULL DEFAULT FALSE");
        // Index over the column must be quoted too.
        assertThat(ddl).contains("INDEX idx_read (`read`)");
        // No unquoted occurrence of the reserved word as a column definition.
        assertThat(ddl).doesNotContainPattern("(?m)^\\s*read\\s+BOOLEAN");
        assertThat(ddl).doesNotContain("(read)");
    }

    @Test
    @DisplayName("v13 migration creates social_relations and notification_preferences")
    void v13CreatesSocialRelationsTables() {
        List<String> statements = dialect.getMigrationStatements(13);
        String ddl = String.join("\n", statements);

        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS social_relations");
        assertThat(ddl).contains("CREATE TABLE IF NOT EXISTS notification_preferences");
        // Composite natural key.
        assertThat(ddl).contains("PRIMARY KEY (source_id, target_id, type)");
        // Reverse-lookup index for "who is ignoring me".
        assertThat(ddl).contains("idx_social_relations_target_id");
        // Preferences default mentions to enabled.
        assertThat(ddl).contains("player_id VARCHAR(36) NOT NULL PRIMARY KEY");
        assertThat(ddl).contains("mentions_enabled BOOLEAN NOT NULL DEFAULT TRUE");
    }

    @Test
    @DisplayName("current version is 13 and every version yields statements")
    void allVersionsYieldStatements() {
        assertThat(dialect.getCurrentVersion()).isEqualTo(13);
        for (int version = 1; version <= dialect.getCurrentVersion(); version++) {
            assertThat(dialect.getMigrationStatements(version)).isNotEmpty();
            assertThat(dialect.getMigrationDescription(version)).doesNotContain("Unknown");
        }
        assertThatThrownBy(() -> dialect.getMigrationStatements(99))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
