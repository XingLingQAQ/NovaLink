package com.nova.link.database.dialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity checks for the schema v5 migration DDL across all three dialects:
 * the {@code messages}, {@code announcements} and {@code webhooks} tables.
 *
 * <p>Guards the reserved-word policy (no {@code timestamp}/{@code event}
 * column names — {@code created_at}/{@code event_type} are used instead) and
 * the required indexes on the messages table.
 */
@DisplayName("Schema v5 migration DDL (three dialects)")
class SchemaV5DialectTest {

    private final MySQLDialect mysql = new MySQLDialect();
    private final PostgreSQLDialect postgres = new PostgreSQLDialect();
    private final SQLiteDialect sqlite = new SQLiteDialect();

    @Test
    @DisplayName("all three dialects report current version 5 with a description")
    void currentVersionIsFive() {
        for (MigrationDialect dialect : List.of(mysql, postgres, sqlite)) {
            assertThat(dialect.getCurrentVersion())
                    .as("%s current version", dialect.getClass().getSimpleName())
                    .isEqualTo(5);
            assertThat(dialect.getMigrationDescription(5)).doesNotContain("Unknown");
        }
    }

    @Test
    @DisplayName("v5 creates messages, announcements and webhooks tables in every dialect")
    void v5CreatesAllThreeTables() {
        for (MigrationDialect dialect : List.of(mysql, postgres, sqlite)) {
            String all = String.join("\n", dialect.getMigrationStatements(5));
            assertThat(all)
                    .as("%s v5 DDL", dialect.getClass().getSimpleName())
                    .contains("CREATE TABLE IF NOT EXISTS messages")
                    .contains("CREATE TABLE IF NOT EXISTS announcements")
                    .contains("CREATE TABLE IF NOT EXISTS webhooks");
        }
    }

    @Test
    @DisplayName("v5 avoids reserved column names (timestamp/event) and indexes messages")
    void v5AvoidsReservedWordsAndCreatesIndexes() {
        for (MigrationDialect dialect : List.of(mysql, postgres, sqlite)) {
            String all = String.join("\n", dialect.getMigrationStatements(5));
            String name = dialect.getClass().getSimpleName();
            // Millisecond timestamp column is created_at, not the SQL keyword.
            assertThat(all).as("%s created_at", name).contains("created_at");
            assertThat(all).as("%s no bare timestamp column", name)
                    .doesNotContainPattern("(?mi)^\\s*timestamp\\s");
            // Webhook event column avoids the EVENT keyword.
            assertThat(all).as("%s event_type", name).contains("event_type");
            assertThat(all).as("%s no bare event column", name)
                    .doesNotContainPattern("(?mi)^\\s*event\\s");
            // Required message indexes: created_at, channel_id, sender_name.
            assertThat(all).as("%s message indexes", name)
                    .contains("idx_messages_created_at")
                    .contains("idx_messages_channel_id")
                    .contains("idx_messages_sender_name");
        }
    }
}
