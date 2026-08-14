package com.nova.link.database;

import com.nova.link.announcement.Announcement;
import com.nova.link.announcement.AnnouncementType;
import com.nova.link.api.Webhook;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    // ==================== Message History (schema v5) ====================
    //
    // The SQL below is deliberately dialect-neutral (LOWER()+LIKE with an
    // explicit ESCAPE char, LIMIT/OFFSET, DELETE+INSERT instead of upsert) so
    // one implementation serves MySQL, PostgreSQL and SQLite alike.

    @Override
    public void saveMessage(ChatMessageRecord message) throws DatabaseException {
        String sql = """
            INSERT INTO messages (channel_id, sender_id, sender_name, client_id, content, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, message.getChannelId());
            stmt.setString(2, message.getSenderId());
            stmt.setString(3, message.getSenderName());
            stmt.setString(4, message.getClientId());
            stmt.setString(5, message.getContent());
            stmt.setLong(6, message.getTimestamp());
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    message.setId(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save message", e);
        }
    }

    @Override
    public List<ChatMessageRecord> searchMessages(MessageFilter filter, int offset, int limit) throws DatabaseException {
        StringBuilder sql = new StringBuilder(
                "SELECT id, channel_id, sender_id, sender_name, client_id, content, created_at FROM messages");
        List<Object> params = new ArrayList<>();
        appendMessageFilter(sql, params, filter);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<ChatMessageRecord> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new ChatMessageRecord(
                            rs.getLong("id"),
                            rs.getString("channel_id"),
                            rs.getString("sender_id"),
                            rs.getString("sender_name"),
                            rs.getString("client_id"),
                            rs.getString("content"),
                            rs.getLong("created_at")
                    ));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to search messages", e);
        }
    }

    @Override
    public int countMessages(MessageFilter filter) throws DatabaseException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM messages");
        List<Object> params = new ArrayList<>();
        appendMessageFilter(sql, params, filter);

        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to count messages", e);
        }
    }

    @Override
    public int cleanupMessagesBefore(long cutoffTimestamp) throws DatabaseException {
        String sql = "DELETE FROM messages WHERE created_at < ?";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, cutoffTimestamp);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to cleanup expired messages", e);
        }
    }

    /**
     * Appends the WHERE clause for a {@link MessageFilter} and collects the
     * bind parameters. Substring matches are case-insensitive via LOWER() and
     * use {@code !} as the LIKE escape character (portable across dialects,
     * unlike backslash which MySQL treats specially in string literals).
     */
    private static void appendMessageFilter(StringBuilder sql, List<Object> params, MessageFilter filter) {
        List<String> conditions = new ArrayList<>();
        if (filter.getChannelId() != null) {
            conditions.add("channel_id = ?");
            params.add(filter.getChannelId());
        }
        if (filter.getClientId() != null) {
            conditions.add("client_id = ?");
            params.add(filter.getClientId());
        }
        if (filter.getSenderName() != null) {
            conditions.add("LOWER(sender_name) LIKE ? ESCAPE '!'");
            params.add(likePattern(filter.getSenderName()));
        }
        if (filter.getContentQuery() != null) {
            conditions.add("LOWER(content) LIKE ? ESCAPE '!'");
            params.add(likePattern(filter.getContentQuery()));
        }
        if (filter.getFrom() != null) {
            conditions.add("created_at >= ?");
            params.add(filter.getFrom());
        }
        if (filter.getTo() != null) {
            conditions.add("created_at <= ?");
            params.add(filter.getTo());
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }

    private static String likePattern(String query) {
        String escaped = query.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static void bindParams(PreparedStatement stmt, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Long l) {
                stmt.setLong(i + 1, l);
            } else if (p instanceof Integer n) {
                stmt.setInt(i + 1, n);
            } else {
                stmt.setString(i + 1, (String) p);
            }
        }
    }

    // ==================== Announcements (schema v5) ====================

    @Override
    public void saveAnnouncement(Announcement announcement) throws DatabaseException {
        // DELETE + INSERT in one transaction instead of a dialect-specific
        // upsert — announcement writes are rare, portability wins here.
        try (Connection conn = getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement("DELETE FROM announcements WHERE id = ?")) {
                    delete.setString(1, announcement.getId());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement("""
                        INSERT INTO announcements (id, announcement_type, channel_id, content, cron, enabled, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, announcement.getId());
                    insert.setString(2, announcement.getType().dbValue());
                    insert.setString(3, announcement.getChannelId());
                    insert.setString(4, announcement.getContent());
                    insert.setString(5, announcement.getCronExpression());
                    insert.setBoolean(6, announcement.isEnabled());
                    insert.setLong(7, announcement.getCreatedAt());
                    insert.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save announcement: " + announcement.getId(), e);
        }
    }

    @Override
    public void deleteAnnouncement(String announcementId) throws DatabaseException {
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM announcements WHERE id = ?")) {
            stmt.setString(1, announcementId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete announcement: " + announcementId, e);
        }
    }

    @Override
    public List<Announcement> getAllPersistedAnnouncements() throws DatabaseException {
        String sql = "SELECT id, announcement_type, channel_id, content, cron, enabled, created_at "
                + "FROM announcements ORDER BY created_at";
        List<Announcement> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                AnnouncementType type = AnnouncementType.fromDbValue(rs.getString("announcement_type"));
                if (type == null) {
                    logger.warn("Skipping announcement {} with unknown type {}",
                            rs.getString("id"), rs.getString("announcement_type"));
                    continue;
                }
                Announcement announcement = new Announcement(
                        rs.getString("id"),
                        rs.getString("channel_id"),
                        rs.getString("content"),
                        type,
                        null,
                        null,
                        rs.getLong("created_at"),
                        rs.getBoolean("enabled")
                );
                announcement.setCronExpression(rs.getString("cron"));
                results.add(announcement);
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load announcements", e);
        }
    }

    // ==================== Webhooks (schema v5) ====================

    @Override
    public void saveWebhook(Webhook webhook) throws DatabaseException {
        // Same portable DELETE + INSERT pattern as saveAnnouncement.
        try (Connection conn = getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement("DELETE FROM webhooks WHERE id = ?")) {
                    delete.setString(1, webhook.getId());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement("""
                        INSERT INTO webhooks (id, url, event_type, secret, active, created_at, last_triggered)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, webhook.getId());
                    insert.setString(2, webhook.getUrl());
                    insert.setString(3, webhook.getEvent());
                    insert.setString(4, webhook.getSecret());
                    insert.setBoolean(5, webhook.isActive());
                    insert.setLong(6, webhook.getCreatedAt());
                    insert.setLong(7, webhook.getLastTriggered());
                    insert.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save webhook: " + webhook.getId(), e);
        }
    }

    @Override
    public void deleteWebhook(String webhookId) throws DatabaseException {
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM webhooks WHERE id = ?")) {
            stmt.setString(1, webhookId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete webhook: " + webhookId, e);
        }
    }

    @Override
    public List<Webhook> getAllPersistedWebhooks() throws DatabaseException {
        String sql = "SELECT id, url, event_type, secret, active, created_at, last_triggered "
                + "FROM webhooks ORDER BY created_at";
        List<Webhook> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(new Webhook(
                        rs.getString("id"),
                        rs.getString("url"),
                        rs.getString("event_type"),
                        rs.getString("secret"),
                        rs.getBoolean("active"),
                        rs.getLong("created_at"),
                        rs.getLong("last_triggered")
                ));
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load webhooks", e);
        }
    }
}
