package com.nova.link.database.dialect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationDialectHardeningTest {

    @Test
    void mysqlUsesNamedLockAndStoresChecksumLifecycleMetadata() {
        MySQLDialect dialect = new MySQLDialect();

        assertThat(dialect.getMigrationLockAcquireSql())
                .containsIgnoringCase("GET_LOCK")
                .contains("?");
        assertThat(dialect.getMigrationLockReleaseSql())
                .containsIgnoringCase("RELEASE_LOCK");
        assertLifecycleMetadata(dialect.getMigrationTableDdl());
    }

    @Test
    void postgresUsesAdvisoryLockAndStoresChecksumLifecycleMetadata() {
        PostgreSQLDialect dialect = new PostgreSQLDialect();

        assertThat(dialect.getMigrationLockAcquireSql())
                .containsIgnoringCase("pg_try_advisory_lock");
        assertThat(dialect.getMigrationLockReleaseSql())
                .containsIgnoringCase("pg_advisory_unlock");
        assertLifecycleMetadata(dialect.getMigrationTableDdl());
    }

    @Test
    void sqliteUsesImmediateTransactionLockAndStoresChecksumLifecycleMetadata() {
        SQLiteDialect dialect = new SQLiteDialect();

        assertThat(dialect.getMigrationLockAcquireSql()).isEqualToIgnoringCase("BEGIN IMMEDIATE");
        assertThat(dialect.getMigrationLockReleaseSql()).isEqualToIgnoringCase("COMMIT");
        assertLifecycleMetadata(dialect.getMigrationTableDdl());
    }

    @Test
    void nonTransactionalMysqlAlterIsIdempotentForSafeRetry() {
        String migration = String.join("\n", new MySQLDialect().getMigrationStatements(2));

        assertThat(migration).containsIgnoringCase("ADD COLUMN IF NOT EXISTS");
    }

    @Test
    void postgresAlterIsAlsoIdempotentForInterruptedRetry() {
        String migration = String.join("\n", new PostgreSQLDialect().getMigrationStatements(2));

        assertThat(migration).containsIgnoringCase("ADD COLUMN IF NOT EXISTS");
    }

    private static void assertLifecycleMetadata(String ddl) {
        assertThat(ddl)
                .containsIgnoringCase("checksum")
                .containsIgnoringCase("started_at")
                .containsIgnoringCase("completed_at")
                .containsIgnoringCase("status");
    }
}
