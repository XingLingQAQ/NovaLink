package com.nova.link.database;

import com.nova.link.database.dialect.MigrationDialect;
import com.nova.link.database.dialect.MySQLDialect;
import com.nova.link.database.dialect.PostgreSQLDialect;
import com.nova.link.database.dialect.SQLiteDialect;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMigrationMySQLIndexTest {
    private static final String INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON notifications (recipient)";
    private static final DataSource UNUSED_DATA_SOURCE = jdbc(DataSource.class, (proxy, method, args) -> {
        throw new AssertionError("Statement execution must reuse the locked migration connection");
    });

    @Test
    void allMysqlVersionsExecuteWithoutUnsupportedConditionalIndexSyntax() throws Exception {
        MySQLDialect dialect = new MySQLDialect();
        JdbcStatements jdbc = new JdbcStatements();
        DatabaseMigration migration = new DatabaseMigration(UNUSED_DATA_SOURCE, dialect);

        for (int version = 1; version <= dialect.getCurrentVersion(); version++) {
            migration.executeStatements(jdbc.connection(), dialect.getMigrationStatements(version));
        }

        assertThat(jdbc.executed).noneMatch(sql -> sql.matches(
                "(?is)\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+IF\\s+NOT\\s+EXISTS\\b.*"));
        assertThat(jdbc.executed).contains("CREATE INDEX idx_notifications_recipient ON notifications (recipient)");
        assertThat(jdbc.lookupSql).contains("table_schema = DATABASE()", "table_name = ?", "index_name = ?");
        assertThat(jdbc.parameters).containsEntry(1, "notifications").containsEntry(2, "idx_notifications_recipient");
        assertThat(dialect.getMigrationStatements(10)).contains(INDEX_SQL);
    }

    @Test
    void versionTenReplaysAfterImplicitCommitWithoutCreatingTheIndexTwice() throws Exception {
        MySQLDialect dialect = new MySQLDialect();
        JdbcStatements jdbc = new JdbcStatements();
        jdbc.failAfterIndexCommit = true;
        DatabaseMigration migration = new DatabaseMigration(UNUSED_DATA_SOURCE, dialect);
        List<String> versionTen = dialect.getMigrationStatements(10);

        assertThat(String.join("\n", versionTen)).contains("information_schema.columns", "column_name = 'recipient'");
        assertThatThrownBy(() -> migration.executeStatements(jdbc.connection(), versionTen))
                .isInstanceOf(SQLException.class).hasMessageContaining("after implicit commit");
        assertThat(jdbc.indexExists).isTrue();

        migration.executeStatements(jdbc.connection(), versionTen);

        assertThat(jdbc.indexCreates).isEqualTo(1);
        assertThat(jdbc.lookups).isEqualTo(2);
        assertThat(jdbc.executed).filteredOn(sql -> sql.startsWith("CALL novalink_add_notification_recipient_column"))
                .hasSize(2);
    }

    @Test
    void existingIndexIsSkippedOnRetry() throws Exception {
        JdbcStatements jdbc = new JdbcStatements();
        jdbc.indexExists = true;

        new DatabaseMigration(UNUSED_DATA_SOURCE, new MySQLDialect())
                .executeStatements(jdbc.connection(), List.of(INDEX_SQL));

        assertThat(jdbc.lookups).isEqualTo(1);
        assertThat(jdbc.executed).isEmpty();
    }

    @Test
    void metadataFailureStillStopsMigration() {
        JdbcStatements jdbc = new JdbcStatements();
        jdbc.lookupFailure = new SQLException("statistics access denied");

        assertThatThrownBy(() -> new DatabaseMigration(UNUSED_DATA_SOURCE, new MySQLDialect())
                .executeStatements(jdbc.connection(), List.of(INDEX_SQL)))
                .isSameAs(jdbc.lookupFailure);
        assertThat(jdbc.executed).isEmpty();
    }

    @Test
    void createIndexFailureStillStopsMigration() {
        JdbcStatements jdbc = new JdbcStatements();
        jdbc.createFailure = new SQLException("index creation denied");

        assertThatThrownBy(() -> new DatabaseMigration(UNUSED_DATA_SOURCE, new MySQLDialect())
                .executeStatements(jdbc.connection(), List.of(INDEX_SQL)))
                .isSameAs(jdbc.createFailure);
        assertThat(jdbc.indexExists).isFalse();
    }

    @Test
    void quotedUniqueIndexKeepsItsDefinitionAndBindsUnquotedNames() throws Exception {
        JdbcStatements jdbc = new JdbcStatements();
        String sql = "CREATE UNIQUE INDEX IF NOT EXISTS `idx_recipient` ON `notifications` (`recipient`)";

        new DatabaseMigration(UNUSED_DATA_SOURCE, new MySQLDialect())
                .executeStatements(jdbc.connection(), List.of(sql));

        assertThat(jdbc.executed).containsExactly("CREATE UNIQUE INDEX `idx_recipient` ON `notifications` (`recipient`)");
        assertThat(jdbc.parameters).containsEntry(1, "notifications").containsEntry(2, "idx_recipient");
    }

    @Test
    void otherDialectsKeepTheirNativeIndexStatements() throws Exception {
        for (MigrationDialect dialect : List.of(new PostgreSQLDialect(), new SQLiteDialect())) {
            JdbcStatements jdbc = new JdbcStatements();
            new DatabaseMigration(UNUSED_DATA_SOURCE, dialect)
                    .executeStatements(jdbc.connection(), List.of(INDEX_SQL));

            assertThat(jdbc.executed).containsExactly(INDEX_SQL);
            assertThat(jdbc.lookups).isZero();
        }
    }

    private static class JdbcStatements {
        private final List<String> executed = new ArrayList<>();
        private final Map<Integer, String> parameters = new HashMap<>();
        private String lookupSql;
        private boolean indexExists;
        private boolean failAfterIndexCommit;
        private int indexCreates;
        private int lookups;
        private SQLException lookupFailure;
        private SQLException createFailure;

        private Connection connection() {
            return jdbc(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "createStatement" -> jdbc(Statement.class, (statement, operation, values) -> {
                    if (operation.getName().equals("close")) return null;
                    if (!operation.getName().equals("execute")) throw new AssertionError(operation);
                    String sql = (String) values[0];
                    executed.add(sql);
                    if (sql.matches("(?is)\\s*CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?!IF\\b).*")) {
                        if (createFailure != null) throw createFailure;
                        if (indexExists) throw new SQLException("duplicate index");
                        indexExists = true;
                        indexCreates++;
                        if (failAfterIndexCommit) {
                            failAfterIndexCommit = false;
                            throw new SQLException("connection failed after implicit commit");
                        }
                    }
                    return false;
                });
                case "prepareStatement" -> {
                    lookupSql = (String) args[0];
                    yield jdbc(PreparedStatement.class, (statement, operation, values) -> {
                        switch (operation.getName()) {
                            case "close": return null;
                            case "setString": parameters.put((Integer) values[0], (String) values[1]); return null;
                            case "executeQuery":
                                if (lookupFailure != null) throw lookupFailure;
                                lookups++;
                                boolean found = indexExists;
                                return jdbc(ResultSet.class, (result, action, arguments) -> {
                                    if (action.getName().equals("close")) return null;
                                    if (action.getName().equals("next")) return found;
                                    throw new AssertionError(action);
                                });
                            default: throw new AssertionError(operation);
                        }
                    });
                }
                default -> throw new AssertionError(method);
            });
        }
    }

    private static <T> T jdbc(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }
}
