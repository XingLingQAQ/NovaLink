package com.nova.link.database;

import com.nova.link.database.dialect.MigrationDialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Shared base class for JDBC-backed {@link DatabaseProvider} implementations
 * (MySQL, PostgreSQL, SQLite, ...).
 *
 * <p>Owns the HikariCP connection pool lifecycle, runs schema migrations via a
 * {@link MigrationDialect}, and provides the common reflection-based id stamp
 * for {@link Notification}. Subclasses supply the JDBC URL, pool tuning, the
 * dialect, and the SQL for each operation (notably the upsert form, which is
 * the main thing that differs between dialects: {@code ON DUPLICATE KEY UPDATE}
 * vs {@code ON CONFLICT ... DO UPDATE}).
 *
 * <p>Requirements: 22.1, 22.5
 */
public abstract class AbstractJdbcProvider implements DatabaseProvider {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    protected HikariDataSource dataSource;

    /**
     * Builds the HikariCP configuration for this backend.
     *
     * <p>Subclasses must set at minimum the JDBC URL, username, password,
     * pool name, and maximum pool size. They may also add backend-specific
     * data-source properties (e.g. MySQL prepared-statement cache).
     *
     * @return a populated HikariConfig
     */
    protected abstract HikariConfig buildHikariConfig();

    /**
     * The migration dialect used to create and evolve the schema.
     *
     * @return the dialect
     */
    protected abstract MigrationDialect dialect();

    @Override
    public void initialize() throws DatabaseException {
        try {
            HikariConfig config = buildHikariConfig();
            dataSource = new HikariDataSource(config);

            // Run migrations
            DatabaseMigration migration = new DatabaseMigration(dataSource, dialect());
            migration.migrate();

            logger.info("{} initialized", getProviderType());
        } catch (Exception e) {
            throw new DatabaseException("Failed to initialize " + getProviderType() + " connection", e);
        }
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("{} shutdown", getProviderType());
        }
    }

    @Override
    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    /**
     * The underlying data source. Available after {@link #initialize()}.
     *
     * @return the HikariCP data source
     */
    protected DataSource getDataSource() {
        return dataSource;
    }

    // ==================== Shared helpers ====================

    /**
     * Stamps the generated auto-increment id back onto a {@link Notification}
     * after an INSERT. Works across all JDBC backends that expose generated
     * keys via {@link Statement#RETURN_GENERATED_KEYS}.
     *
     * @param stmt          the statement that was just executed (created with
     *                      {@code RETURN_GENERATED_KEYS})
     * @param notification  the notification to stamp
     */
    protected void stampGeneratedId(PreparedStatement stmt, Notification notification) {
        try (ResultSet rs = stmt.getGeneratedKeys()) {
            if (rs.next()) {
                long generatedId = rs.getLong(1);
                try {
                    java.lang.reflect.Field f = Notification.class.getDeclaredField("id");
                    f.setAccessible(true);
                    f.setLong(notification, generatedId);
                } catch (ReflectiveOperationException e) {
                    logger.debug("Could not stamp generated notification id: {}", e.getMessage());
                }
            }
        } catch (SQLException e) {
            logger.debug("Could not read generated notification id: {}", e.getMessage());
        }
    }

    /**
     * Parses a stored UUID string, tolerating null.
     *
     * @param value the raw string, or null
     * @return the parsed UUID, or null
     */
    protected static UUID parseUuid(String value) {
        return value != null ? UUID.fromString(value) : null;
    }
}
