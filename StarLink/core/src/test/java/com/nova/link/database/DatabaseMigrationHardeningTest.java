package com.nova.link.database;

import com.nova.link.database.dialect.SQLiteDialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMigrationHardeningTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentMigratorsExecuteEachVersionOnlyOnce() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(1);
        CountDownLatch secondReachedMigration = new CountDownLatch(1);
        AtomicInteger statementRequests = new AtomicInteger();
        SQLiteDialect dialect = new SingleVersionDialect(List.of(
                "CREATE TABLE IF NOT EXISTS migration_probe (value INTEGER NOT NULL)",
                "INSERT INTO migration_probe (value) VALUES (1)"
        )) {
            @Override
            public List<String> getMigrationStatements(int version) {
                int request = statementRequests.incrementAndGet();
                if (request == 1) {
                    bothStarted.countDown();
                    await(secondReachedMigration, 500);
                } else {
                    secondReachedMigration.countDown();
                }
                return super.getMigrationStatements(version);
            }
        };

        try (HikariDataSource dataSource = sqliteDataSource(tempDir.resolve("concurrent.db"), 4)) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Future<?> first = executor.submit(() -> migrateAfter(start, dataSource, dialect));
                Future<?> second = executor.submit(() -> migrateAfter(start, dataSource, dialect));
                start.countDown();

                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }

            assertThat(queryInt(dataSource, "SELECT COUNT(*) FROM migration_probe")).isEqualTo(1);
            assertThat(queryInt(dataSource,
                    "SELECT COUNT(*) FROM novalink_migrations WHERE version = 1 AND status = 'COMPLETED'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void rejectsChangedSqlForAnAlreadyRecordedVersion() throws Exception {
        try (HikariDataSource dataSource = sqliteDataSource(tempDir.resolve("checksum.db"), 2)) {
            new DatabaseMigration(dataSource, new SingleVersionDialect(
                    List.of("CREATE TABLE checksum_probe (value TEXT)"))).migrate();

            DatabaseMigration changed = new DatabaseMigration(dataSource, new SingleVersionDialect(
                    List.of("CREATE TABLE checksum_probe (value INTEGER)")));

            assertThatThrownBy(changed::migrate)
                    .isInstanceOf(DatabaseException.class)
                    .hasMessageContaining("checksum")
                    .hasMessageContaining("version 1")
                    .hasMessageContaining("refusing");
        }
    }

    @Test
    void checksumIsStableAcrossFormattingOnlyWhitespaceChanges() throws Exception {
        try (HikariDataSource dataSource = sqliteDataSource(tempDir.resolve("checksum-format.db"), 2)) {
            new DatabaseMigration(dataSource, new SingleVersionDialect(List.of("""
                    CREATE   TABLE checksum_format_probe (
                        value TEXT
                    )
                    """))).migrate();

            new DatabaseMigration(dataSource, new SingleVersionDialect(List.of(
                    " \r\n CREATE TABLE checksum_format_probe ( value TEXT ) \r\n "))).migrate();

            assertThat(queryInt(dataSource,
                    "SELECT COUNT(*) FROM novalink_migrations WHERE status = 'COMPLETED'")).isEqualTo(1);
        }
    }

    @Test
    void failedMigrationIsRolledBackAndRecordedAsFailed() throws Exception {
        SQLiteDialect dialect = new SingleVersionDialect(List.of(
                "CREATE TABLE failure_probe (value INTEGER)",
                "INSERT INTO failure_probe (value) VALUES (1)",
                "THIS IS NOT VALID SQL"
        ));

        try (HikariDataSource dataSource = sqliteDataSource(tempDir.resolve("failed.db"), 2)) {
            assertThatThrownBy(() -> new DatabaseMigration(dataSource, dialect).migrate())
                    .isInstanceOf(DatabaseException.class)
                    .hasMessageContaining("migration version 1");

            assertThat(queryInt(dataSource,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'failure_probe'"))
                    .isZero();

            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT status, checksum, started_at, completed_at
                         FROM novalink_migrations
                         WHERE version = 1
                         """);
                 ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("status")).isEqualTo("FAILED");
                assertThat(result.getString("checksum")).hasSize(64);
                assertThat(result.getTimestamp("started_at")).isNotNull();
                assertThat(result.getTimestamp("completed_at")).isNull();
            }
        }
    }

    @Test
    void upgradesLegacyV1MetadataThroughV7AndRerunDoesNothing() throws Exception {
        SQLiteDialect baseDialect = new SQLiteDialect();
        try (HikariDataSource dataSource = sqliteDataSource(tempDir.resolve("legacy-v1.db"), 2)) {
            try (Connection connection = dataSource.getConnection()) {
                execute(connection, baseDialect.getMigrationStatements(1));
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            CREATE TABLE novalink_migrations (
                                version INTEGER PRIMARY KEY,
                                applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                            )
                            """);
                    statement.execute("INSERT INTO novalink_migrations (version) VALUES (1)");
                    statement.execute("""
                            CREATE TABLE migration_execution_probe (
                                version INTEGER PRIMARY KEY
                            )
                            """);
                }
            }

            SQLiteDialect observableDialect = new SQLiteDialect() {
                @Override
                public List<String> getMigrationStatements(int version) {
                    List<String> statements = new ArrayList<>(super.getMigrationStatements(version));
                    statements.add("INSERT INTO migration_execution_probe (version) VALUES (" + version + ")");
                    return statements;
                }
            };
            DatabaseMigration migration = new DatabaseMigration(dataSource, observableDialect);

            migration.migrate();

            assertThat(migration.getVersion()).isEqualTo(7);
            assertThat(queryInts(dataSource,
                    "SELECT version FROM migration_execution_probe ORDER BY version"))
                    .containsExactly(2, 3, 4, 5, 6, 7);
            assertThat(queryInt(dataSource,
                    "SELECT COUNT(*) FROM novalink_migrations WHERE status = 'COMPLETED'")).isEqualTo(7);
            assertThat(queryInt(dataSource,
                    "SELECT COUNT(*) FROM novalink_migrations WHERE checksum IS NULL")).isZero();
            assertThat(queryInt(dataSource,
                    "SELECT COUNT(*) FROM novalink_migrations WHERE started_at IS NULL OR completed_at IS NULL"))
                    .isZero();
            assertThat(queryInt(dataSource,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN " +
                            "('messages', 'announcements', 'webhooks')")).isEqualTo(3);

            migration.migrate();

            assertThat(queryInts(dataSource,
                    "SELECT version FROM migration_execution_probe ORDER BY version"))
                    .containsExactly(2, 3, 4, 5, 6, 7);
            assertThat(queryInt(dataSource, "SELECT COUNT(*) FROM novalink_migrations")).isEqualTo(7);
        }
    }

    private static void migrateAfter(CountDownLatch start, HikariDataSource dataSource,
                                     SQLiteDialect dialect) {
        await(start, 5_000);
        try {
            new DatabaseMigration(dataSource, dialect).migrate();
        } catch (DatabaseException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch, long timeoutMillis) {
        try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating migration test", exception);
        }
    }

    private static HikariDataSource sqliteDataSource(Path file, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setConnectionInitSql("PRAGMA busy_timeout = 5000");
        return new HikariDataSource(config);
    }

    private static void execute(Connection connection, List<String> statements) throws SQLException {
        for (String sql : statements) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    private static int queryInt(HikariDataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static List<Integer> queryInts(HikariDataSource dataSource, String sql) throws SQLException {
        List<Integer> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getInt(1));
            }
        }
        return values;
    }

    private static class SingleVersionDialect extends SQLiteDialect {
        private final List<String> statements;

        private SingleVersionDialect(List<String> statements) {
            this.statements = List.copyOf(statements);
        }

        @Override
        public int getCurrentVersion() {
            return 1;
        }

        @Override
        public List<String> getMigrationStatements(int version) {
            if (version != 1) {
                throw new IllegalArgumentException("Unknown migration version: " + version);
            }
            return statements;
        }

        @Override
        public String getMigrationDescription(int version) {
            return "Test migration";
        }
    }
}
