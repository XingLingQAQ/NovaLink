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
    @DisplayName("current version is 7 and every version yields statements")
    void allVersionsYieldStatements() {
        assertThat(dialect.getCurrentVersion()).isEqualTo(7);
        for (int version = 1; version <= dialect.getCurrentVersion(); version++) {
            assertThat(dialect.getMigrationStatements(version)).isNotEmpty();
            assertThat(dialect.getMigrationDescription(version)).doesNotContain("Unknown");
        }
        assertThatThrownBy(() -> dialect.getMigrationStatements(99))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
