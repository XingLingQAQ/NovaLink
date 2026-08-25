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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
        Set<String> allowedChannelIds = filter.getAllowedChannelIds();
        if (allowedChannelIds != null) {
            if (allowedChannelIds.isEmpty()) {
                conditions.add("1 = 0");
            } else {
                conditions.add("channel_id IN ("
                        + String.join(", ", Collections.nCopies(allowedChannelIds.size(), "?")) + ")");
                params.addAll(allowedChannelIds);
            }
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

    // ==================== Audit Events (schema v9) ====================
    //
    // Like the message-history SQL above, the audit CRUD is fully
    // dialect-neutral: LOWER()+LIKE with an explicit ESCAPE char, LIMIT/OFFSET,
    // and straight INSERT (audit events are append-only — no upsert). One
    // implementation serves MySQL, PostgreSQL and SQLite alike.

    @Override
    public void saveAuditEvent(com.nova.link.audit.AuditEvent event) throws DatabaseException {
        String sql = """
                INSERT INTO audit_events (event_id, request_id, actor, role, origin,
                                         action, resource, before_hash, after_hash,
                                         reason, result, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, event.getEventId());
            stmt.setString(2, event.getRequestId());
            stmt.setString(3, event.getActor());
            stmt.setString(4, event.getRole());
            stmt.setString(5, event.getOrigin());
            stmt.setString(6, event.getAction());
            stmt.setString(7, event.getResource());
            stmt.setString(8, event.getBeforeHash());
            stmt.setString(9, event.getAfterHash());
            stmt.setString(10, event.getReason());
            stmt.setString(11, event.getResult());
            stmt.setLong(12, event.getCreatedAt());
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    long generatedId = rs.getLong(1);
                    try {
                        java.lang.reflect.Field f = com.nova.link.audit.AuditEvent.class.getDeclaredField("id");
                        f.setAccessible(true);
                        f.setLong(event, generatedId);
                    } catch (ReflectiveOperationException e) {
                        logger.debug("Could not stamp generated audit event id: {}", e.getMessage());
                    }
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save audit event", e);
        }
    }

    @Override
    public List<com.nova.link.audit.AuditEvent> getAuditEvents(int offset, int limit, String actor, String action) throws DatabaseException {
        StringBuilder sql = new StringBuilder(
                "SELECT id, event_id, request_id, actor, role, origin, action, resource, "
                        + "before_hash, after_hash, reason, result, created_at FROM audit_events");
        List<Object> params = new ArrayList<>();
        appendAuditFilter(sql, params, actor, action);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<com.nova.link.audit.AuditEvent> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new com.nova.link.audit.AuditEvent(
                            rs.getLong("id"),
                            rs.getString("event_id"),
                            rs.getString("request_id"),
                            rs.getString("actor"),
                            rs.getString("role"),
                            rs.getString("origin"),
                            rs.getString("action"),
                            rs.getString("resource"),
                            rs.getString("before_hash"),
                            rs.getString("after_hash"),
                            rs.getString("reason"),
                            rs.getString("result"),
                            rs.getLong("created_at")
                    ));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to list audit events", e);
        }
    }

    @Override
    public int countAuditEvents(String actor, String action) throws DatabaseException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM audit_events");
        List<Object> params = new ArrayList<>();
        appendAuditFilter(sql, params, actor, action);

        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to count audit events", e);
        }
    }

    /**
     * Appends the WHERE clause for optional actor/action filters and collects
     * the bind parameters. A null or empty argument means "no filter on that
     * column". Substring matches are case-insensitive via LOWER() and use
     * {@code !} as the LIKE escape character, mirroring
     * {@link #appendMessageFilter}.
     */
    private static void appendAuditFilter(StringBuilder sql, List<Object> params, String actor, String action) {
        List<String> conditions = new ArrayList<>();
        if (actor != null && !actor.isEmpty()) {
            conditions.add("LOWER(actor) LIKE ? ESCAPE '!'");
            params.add(likePattern(actor));
        }
        if (action != null && !action.isEmpty()) {
            conditions.add("LOWER(action) LIKE ? ESCAPE '!'");
            params.add(likePattern(action));
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }

    // ==================== Moderation (schema v11) ====================
    //
    // PANEL-007: moderation case/appeal workflow. The SQL here is dialect-
    // neutral in the same spirit as the audit CRUD above — straight INSERT/
    // upsert-by-DELETE+INSERT, LOWER()+LIKE ESCAPE '!', LIMIT/OFFSET — so one
    // implementation serves MySQL, PostgreSQL and SQLite. Cases and appeals are
    // keyed by a caller-assigned UUID string (stored in a VARCHAR column), so
    // persistence is upsert-style on id. Evidence is append-only with an
    // auto-incremented bigint id stamped by reflection, mirroring the audit-
    // event id-stamping pattern.

    @Override
    public void saveModerationCase(com.nova.link.moderation.ModerationCase moderationCase) throws DatabaseException {
        // DELETE + INSERT upsert (portable across dialects; case writes are rare).
        try (Connection conn = getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM moderation_cases WHERE id = ?")) {
                    delete.setString(1, moderationCase.getId());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement("""
                        INSERT INTO moderation_cases (id, subject_player_id, subject_display_name,
                            reporter_name, reporter_source, source, channel_id, reason, snapshot,
                            status, assigned_moderator, resolution_action, resolution_note,
                            content_hash, created_at, updated_at, closed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, moderationCase.getId());
                    insert.setString(2, moderationCase.getSubjectPlayerId());
                    insert.setString(3, moderationCase.getSubjectDisplayName());
                    insert.setString(4, moderationCase.getReporterName());
                    insert.setString(5, moderationCase.getReporterSource().name());
                    insert.setString(6, moderationCase.getSource().name());
                    insert.setString(7, moderationCase.getChannelId());
                    insert.setString(8, moderationCase.getReason());
                    insert.setString(9, moderationCase.getSnapshot());
                    insert.setString(10, moderationCase.getStatus().name());
                    insert.setString(11, moderationCase.getAssignedModerator());
                    insert.setString(12, moderationCase.getResolutionAction() != null
                            ? moderationCase.getResolutionAction().name() : null);
                    insert.setString(13, moderationCase.getResolutionNote());
                    insert.setString(14, moderationCase.getContentHash());
                    insert.setLong(15, moderationCase.getCreatedAt());
                    insert.setLong(16, moderationCase.getUpdatedAt());
                    if (moderationCase.getClosedAt() != null) {
                        insert.setLong(17, moderationCase.getClosedAt());
                    } else {
                        insert.setNull(17, java.sql.Types.BIGINT);
                    }
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
            throw new DatabaseException("Failed to save moderation case: " + moderationCase.getId(), e);
        }
    }

    @Override
    public java.util.Optional<com.nova.link.moderation.ModerationCase> getModerationCase(String caseId) throws DatabaseException {
        String sql = """
                SELECT id, subject_player_id, subject_display_name, reporter_name, reporter_source,
                    source, channel_id, reason, snapshot, status, assigned_moderator,
                    resolution_action, resolution_note, content_hash, created_at, updated_at, closed_at
                FROM moderation_cases WHERE id = ?
                """;
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, caseId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return java.util.Optional.of(hydrateCase(rs));
                }
                return java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load moderation case: " + caseId, e);
        }
    }

    @Override
    public List<com.nova.link.moderation.ModerationCase> listModerationCases(int offset, int limit, String status) throws DatabaseException {
        StringBuilder sql = new StringBuilder(
                "SELECT id, subject_player_id, subject_display_name, reporter_name, reporter_source, "
                        + "source, channel_id, reason, snapshot, status, assigned_moderator, "
                        + "resolution_action, resolution_note, content_hash, created_at, updated_at, closed_at "
                        + "FROM moderation_cases");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            sql.append(" WHERE status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<com.nova.link.moderation.ModerationCase> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(hydrateCase(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to list moderation cases", e);
        }
    }

    @Override
    public int countModerationCases(String status) throws DatabaseException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM moderation_cases");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            sql.append(" WHERE status = ?");
            params.add(status);
        }
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to count moderation cases", e);
        }
    }

    @Override
    public void saveCaseEvidence(com.nova.link.moderation.CaseEvidence evidence) throws DatabaseException {
        String sql = """
                INSERT INTO case_evidence (case_id, evidence_type, content_hash, description,
                                           submitted_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, evidence.getCaseId());
            stmt.setString(2, evidence.getEvidenceType().name());
            stmt.setString(3, evidence.getContentHash());
            stmt.setString(4, evidence.getDescription());
            stmt.setString(5, evidence.getSubmittedBy());
            stmt.setLong(6, evidence.getCreatedAt());
            stmt.executeUpdate();
            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    long generatedId = rs.getLong(1);
                    try {
                        java.lang.reflect.Field f = com.nova.link.moderation.CaseEvidence.class.getDeclaredField("id");
                        f.setAccessible(true);
                        f.setLong(evidence, generatedId);
                    } catch (ReflectiveOperationException e) {
                        logger.debug("Could not stamp generated case evidence id: {}", e.getMessage());
                    }
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save case evidence", e);
        }
    }

    @Override
    public List<com.nova.link.moderation.CaseEvidence> listCaseEvidence(String caseId) throws DatabaseException {
        String sql = """
                SELECT id, case_id, evidence_type, content_hash, description, submitted_by, created_at
                FROM case_evidence WHERE case_id = ? ORDER BY created_at ASC, id ASC
                """;
        List<com.nova.link.moderation.CaseEvidence> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, caseId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new com.nova.link.moderation.CaseEvidence(
                            rs.getLong("id"),
                            rs.getString("case_id"),
                            com.nova.link.moderation.CaseEvidenceType.valueOf(rs.getString("evidence_type")),
                            rs.getString("content_hash"),
                            rs.getString("description"),
                            rs.getString("submitted_by"),
                            rs.getLong("created_at")
                    ));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to list case evidence", e);
        }
    }

    @Override
    public void saveAppeal(com.nova.link.moderation.Appeal appeal) throws DatabaseException {
        // DELETE + INSERT upsert, mirroring saveModerationCase.
        try (Connection conn = getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM appeals WHERE id = ?")) {
                    delete.setString(1, appeal.getId());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement("""
                        INSERT INTO appeals (id, case_id, appellant, appeal_reason, status,
                            reviewed_by, review_note, reviewed_at, content_hash, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, appeal.getId());
                    insert.setString(2, appeal.getCaseId());
                    insert.setString(3, appeal.getAppellant());
                    insert.setString(4, appeal.getAppealReason());
                    insert.setString(5, appeal.getStatus().name());
                    insert.setString(6, appeal.getReviewedBy());
                    insert.setString(7, appeal.getReviewNote());
                    if (appeal.getReviewedAt() != null) {
                        insert.setLong(8, appeal.getReviewedAt());
                    } else {
                        insert.setNull(8, java.sql.Types.BIGINT);
                    }
                    insert.setString(9, appeal.getContentHash());
                    insert.setLong(10, appeal.getCreatedAt());
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
            throw new DatabaseException("Failed to save appeal: " + appeal.getId(), e);
        }
    }

    @Override
    public java.util.Optional<com.nova.link.moderation.Appeal> getAppeal(String appealId) throws DatabaseException {
        String sql = """
                SELECT id, case_id, appellant, appeal_reason, status, reviewed_by,
                    review_note, reviewed_at, content_hash, created_at
                FROM appeals WHERE id = ?
                """;
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, appealId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return java.util.Optional.of(hydrateAppeal(rs));
                }
                return java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load appeal: " + appealId, e);
        }
    }

    @Override
    public List<com.nova.link.moderation.Appeal> listAppeals(int offset, int limit, String status) throws DatabaseException {
        StringBuilder sql = new StringBuilder(
                "SELECT id, case_id, appellant, appeal_reason, status, reviewed_by, "
                        + "review_note, reviewed_at, content_hash, created_at FROM appeals");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            sql.append(" WHERE status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        List<com.nova.link.moderation.Appeal> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(hydrateAppeal(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to list appeals", e);
        }
    }

    @Override
    public int countAppeals(String status) throws DatabaseException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM appeals");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            sql.append(" WHERE status = ?");
            params.add(status);
        }
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            bindParams(stmt, params);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to count appeals", e);
        }
    }

    @Override
    public void updateAppealReview(String appealId, com.nova.link.moderation.AppealStatus status,
                                   String reviewedBy, String reviewNote, long reviewedAt)
            throws DatabaseException {
        String sql = """
                UPDATE appeals SET status = ?, reviewed_by = ?, review_note = ?, reviewed_at = ?
                WHERE id = ?
                """;
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            stmt.setString(2, reviewedBy);
            stmt.setString(3, reviewNote);
            stmt.setLong(4, reviewedAt);
            stmt.setString(5, appealId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to update appeal review: " + appealId, e);
        }
    }

    private static com.nova.link.moderation.ModerationCase hydrateCase(ResultSet rs) throws SQLException {
        String resolutionActionName = rs.getString("resolution_action");
        long closedAtLong = rs.getLong("closed_at");
        Long closedAt = rs.wasNull() ? null : closedAtLong;
        return new com.nova.link.moderation.ModerationCase(
                rs.getString("id"),
                rs.getString("subject_player_id"),
                rs.getString("subject_display_name"),
                rs.getString("reporter_name"),
                com.nova.link.moderation.ReporterSource.valueOf(rs.getString("reporter_source")),
                com.nova.link.moderation.CaseSource.valueOf(rs.getString("source")),
                rs.getString("channel_id"),
                rs.getString("reason"),
                rs.getString("snapshot"),
                com.nova.link.moderation.CaseStatus.valueOf(rs.getString("status")),
                rs.getString("assigned_moderator"),
                resolutionActionName != null
                        ? com.nova.link.moderation.ResolutionAction.valueOf(resolutionActionName) : null,
                rs.getString("resolution_note"),
                rs.getString("content_hash"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                closedAt
        );
    }

    private static com.nova.link.moderation.Appeal hydrateAppeal(ResultSet rs) throws SQLException {
        long reviewedAtLong = rs.getLong("reviewed_at");
        Long reviewedAt = rs.wasNull() ? null : reviewedAtLong;
        return new com.nova.link.moderation.Appeal(
                rs.getString("id"),
                rs.getString("case_id"),
                rs.getString("appellant"),
                rs.getString("appeal_reason"),
                com.nova.link.moderation.AppealStatus.valueOf(rs.getString("status")),
                rs.getString("reviewed_by"),
                rs.getString("review_note"),
                reviewedAt,
                rs.getString("content_hash"),
                rs.getLong("created_at")
        );
    }

    // ==================== Config History (schema v12) ====================
    //
    // §11.6 Project 20 / PANEL proposal 10. The config_history CRUD is fully
    // dialect-neutral: straight INSERT with RETURN_GENERATED_KEYS, LIMIT, and a
    // single-row UPDATE for the active-flag flip. One implementation serves
    // MySQL, PostgreSQL and SQLite alike — the only dialect-specific bit
    // (AUTO_INCREMENT vs GENERATED ALWAYS AS IDENTITY) is handled by the
    // schema migration, not by this code. Active-flag management: the new row
    // is inserted with active=TRUE and every prior row is flipped to FALSE
    // inside the same transaction so history stays consistent even if the
    // connection drops mid-write.

    @Override
    public void saveConfigSnapshot(com.nova.link.config.ConfigSnapshot snapshot) throws DatabaseException {
        if (snapshot == null) {
            throw new DatabaseException("Cannot save a null config snapshot", null);
        }
        String insertSql = """
                INSERT INTO config_history (revision, snapshot_json, created_at, created_by, active)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection conn = getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // Flip every prior row inactive inside the same transaction so
                // the table never has two active rows even on a crash. The new
                // row is inserted with active=TRUE below.
                try (PreparedStatement deactivate = conn.prepareStatement(
                        "UPDATE config_history SET active = FALSE")) {
                    deactivate.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setLong(1, snapshot.getRevision());
                    stmt.setString(2, snapshot.getSnapshotJson());
                    stmt.setLong(3, snapshot.getCreatedAt());
                    stmt.setString(4, snapshot.getCreatedBy());
                    stmt.setBoolean(5, true);
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            long generatedId = rs.getLong(1);
                            snapshot.setId(generatedId);
                            snapshot.setActive(true);
                        }
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save config snapshot revision=" + snapshot.getRevision(), e);
        }
    }

    @Override
    public List<com.nova.link.config.ConfigSnapshot> getConfigHistory(int limit) throws DatabaseException {
        String sql = "SELECT id, revision, created_at, created_by, active FROM config_history "
                + "ORDER BY created_at DESC, id DESC LIMIT ?";
        List<com.nova.link.config.ConfigSnapshot> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // snapshot_json deliberately NOT selected — the history
                    // list is metadata-only; callers fetch payloads lazily.
                    results.add(new com.nova.link.config.ConfigSnapshot(
                            rs.getLong("id"),
                            rs.getLong("revision"),
                            null,
                            rs.getLong("created_at"),
                            rs.getString("created_by"),
                            rs.getBoolean("active")
                    ));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to list config history", e);
        }
    }

    @Override
    public java.util.Optional<com.nova.link.config.ConfigSnapshot> getConfigSnapshot(long revision) throws DatabaseException {
        String sql = "SELECT id, revision, snapshot_json, created_at, created_by, active FROM config_history "
                + "WHERE revision = ? ORDER BY id DESC LIMIT 1";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, revision);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(new com.nova.link.config.ConfigSnapshot(
                        rs.getLong("id"),
                        rs.getLong("revision"),
                        rs.getString("snapshot_json"),
                        rs.getLong("created_at"),
                        rs.getString("created_by"),
                        rs.getBoolean("active")
                ));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load config snapshot revision=" + revision, e);
        }
    }

    @Override
    public int countConfigSnapshots() throws DatabaseException {
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM config_history");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to count config snapshots", e);
        }
    }

    @Override
    public int deactivateOtherSnapshots(long activeRevision) throws DatabaseException {
        // Negative sentinel means "deactivate all rows"; otherwise keep the
        // row matching activeRevision active and flip every other row off. The
        // rollback path calls this with the freshly-written rollback revision.
        String sql = activeRevision < 0
                ? "UPDATE config_history SET active = FALSE"
                : "UPDATE config_history SET active = FALSE WHERE revision <> ?";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (activeRevision >= 0) {
                stmt.setLong(1, activeRevision);
            }
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to deactivate config snapshots", e);
        }
    }

    // ==================== Social Relations (schema v13 / 提案 08) ====================
    //
    // §11.6 item-18 / PANEL proposal 08. The social_relations + notification_preferences
    // CRUD is dialect-neutral: straight DELETE+INSERT upsert on the composite key,
    // a SELECT for isIgnored / list, and single-row upsert for preferences. One
    // implementation serves MySQL, PostgreSQL and SQLite alike. The upsert uses
    // DELETE-then-INSERT inside a transaction so a crash never leaves two rows
    // for the same composite key. Relation type is stored as the enum name
    // (VARCHAR(16)); UUIDs are VARCHAR(36) strings via setString/parseUuid.

    @Override
    public boolean isIgnored(UUID sourceId, UUID targetId) throws DatabaseException {
        if (sourceId == null || targetId == null) {
            return false;
        }
        String sql = "SELECT COUNT(*) FROM social_relations "
                + "WHERE source_id = ? AND target_id = ? AND type = ?";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sourceId.toString());
            stmt.setString(2, targetId.toString());
            stmt.setString(3, com.nova.link.social.SocialRelation.RelationType.IGNORE.name());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to check ignore relation", e);
        }
    }

    @Override
    public List<com.nova.link.social.SocialRelation> getSocialRelations(
            UUID sourceId, com.nova.link.social.SocialRelation.RelationType type) throws DatabaseException {
        if (sourceId == null || type == null) {
            return new ArrayList<>();
        }
        String sql = "SELECT source_id, target_id, type, created_at, updated_at FROM social_relations "
                + "WHERE source_id = ? AND type = ? ORDER BY created_at DESC";
        List<com.nova.link.social.SocialRelation> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sourceId.toString());
            stmt.setString(2, type.name());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new com.nova.link.social.SocialRelation(
                            parseUuid(rs.getString("source_id")),
                            parseUuid(rs.getString("target_id")),
                            com.nova.link.social.SocialRelation.RelationType.valueOf(rs.getString("type")),
                            rs.getLong("created_at"),
                            rs.getLong("updated_at")
                    ));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to list social relations", e);
        }
    }

    @Override
    public void saveSocialRelation(com.nova.link.social.SocialRelation relation) throws DatabaseException {
        if (relation == null) {
            throw new DatabaseException("Cannot save a null social relation", null);
        }
        if (relation.getSourceId() == null || relation.getTargetId() == null || relation.getType() == null) {
            throw new DatabaseException("Social relation sourceId/targetId/type must not be null", null);
        }
        String deleteSql = "DELETE FROM social_relations "
                + "WHERE source_id = ? AND target_id = ? AND type = ?";
        String insertSql = """
                INSERT INTO social_relations (source_id, target_id, type, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection conn = getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                    stmt.setString(1, relation.getSourceId().toString());
                    stmt.setString(2, relation.getTargetId().toString());
                    stmt.setString(3, relation.getType().name());
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, relation.getSourceId().toString());
                    stmt.setString(2, relation.getTargetId().toString());
                    stmt.setString(3, relation.getType().name());
                    stmt.setLong(4, relation.getCreatedAt());
                    stmt.setLong(5, relation.getUpdatedAt());
                    stmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save social relation", e);
        }
    }

    @Override
    public void removeSocialRelation(UUID sourceId, UUID targetId,
                                      com.nova.link.social.SocialRelation.RelationType type) throws DatabaseException {
        if (sourceId == null || targetId == null || type == null) {
            return;
        }
        String sql = "DELETE FROM social_relations WHERE source_id = ? AND target_id = ? AND type = ?";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sourceId.toString());
            stmt.setString(2, targetId.toString());
            stmt.setString(3, type.name());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to remove social relation", e);
        }
    }

    @Override
    public com.nova.link.social.NotificationPreference getNotificationPreference(UUID playerId) throws DatabaseException {
        if (playerId == null) {
            return com.nova.link.social.NotificationPreference.defaults(null);
        }
        String sql = "SELECT player_id, mentions_enabled, updated_at FROM notification_preferences "
                + "WHERE player_id = ?";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return com.nova.link.social.NotificationPreference.defaults(playerId);
                }
                return new com.nova.link.social.NotificationPreference(
                        parseUuid(rs.getString("player_id")),
                        rs.getBoolean("mentions_enabled"),
                        rs.getLong("updated_at")
                );
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load notification preference", e);
        }
    }

    @Override
    public void saveNotificationPreference(com.nova.link.social.NotificationPreference preference) throws DatabaseException {
        if (preference == null) {
            throw new DatabaseException("Cannot save a null notification preference", null);
        }
        if (preference.getPlayerId() == null) {
            throw new DatabaseException("Notification preference playerId must not be null", null);
        }
        // Upsert on player_id. The three dialects differ on upsert syntax, so
        // mirror the config-history pattern: DELETE then INSERT inside a single
        // transaction. This is safe because player_id is the primary key.
        String deleteSql = "DELETE FROM notification_preferences WHERE player_id = ?";
        String insertSql = """
                INSERT INTO notification_preferences (player_id, mentions_enabled, updated_at)
                VALUES (?, ?, ?)
                """;
        try (Connection conn = getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                    stmt.setString(1, preference.getPlayerId().toString());
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, preference.getPlayerId().toString());
                    stmt.setBoolean(2, preference.isMentionsEnabled());
                    stmt.setLong(3, preference.getUpdatedAt());
                    stmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save notification preference", e);
        }
    }

    // ==================== Campaigns (schema v14 / 提案 06) ====================
    //
    // §11.6 item-19 slice B / PANEL proposal 06. The campaigns CRUD is
    // dialect-neutral: straight DELETE+INSERT upsert on the primary key (id),
    // a SELECT for single/list, and an UPDATE for status transitions. One
    // implementation serves MySQL, PostgreSQL and SQLite alike. The upsert
    // uses DELETE-then-INSERT inside a transaction so a crash never leaves
    // two rows for the same id. Platforms Set<String> is serialised as a
    // comma-joined TEXT column (sorted for determinism); delivery_policy is
    // the enum dbValue(); status is the CampaignStatus enum name; UUIDs are
    // VARCHAR(36) strings via setString/parseUuid; nullable creator_id and
    // revoked_by use setString(idx, null) when absent.

    @Override
    public void saveCampaign(com.nova.link.announcement.Campaign campaign) throws DatabaseException {
        if (campaign == null) {
            throw new DatabaseException("Cannot save a null campaign", null);
        }
        // Serialise the platforms set as a deterministic comma-joined string so
        // that round-trip reads reconstruct the same set contents. A LinkedHashSet
        // preserves insertion order on read-back via Arrays.asList(split).
        String platformsJoined = String.join(",", campaign.getPlatforms());
        String deleteSql = "DELETE FROM campaigns WHERE id = ?";
        String insertSql = """
                INSERT INTO campaigns (id, channel_id, platforms, content, status,
                                       schedule_revision, delivery_policy, start_at, end_at,
                                       rate_limit_per_channel_per_hour, creator_id, creator_client_id,
                                       created_at, revoked_at, revoked_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
                    stmt.setString(1, campaign.getId());
                    stmt.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, campaign.getId());
                    stmt.setString(2, campaign.getChannelId());
                    stmt.setString(3, platformsJoined);
                    stmt.setString(4, campaign.getContent());
                    stmt.setString(5, campaign.getStatus().name());
                    stmt.setLong(6, campaign.getScheduleRevision());
                    stmt.setString(7, campaign.getDeliveryPolicy().dbValue());
                    stmt.setLong(8, campaign.getStartAt());
                    stmt.setLong(9, campaign.getEndAt());
                    stmt.setInt(10, campaign.getRateLimitPerChannelPerHour());
                    stmt.setString(11, campaign.getCreatorId() != null ? campaign.getCreatorId().toString() : null);
                    stmt.setString(12, campaign.getCreatorClientId());
                    stmt.setLong(13, campaign.getCreatedAt());
                    stmt.setLong(14, campaign.getRevokedAt());
                    stmt.setString(15, campaign.getRevokedBy() != null ? campaign.getRevokedBy().toString() : null);
                    stmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save campaign: " + campaign.getId(), e);
        }
    }

    @Override
    public java.util.Optional<com.nova.link.announcement.Campaign> getCampaign(String id) throws DatabaseException {
        if (id == null) {
            return java.util.Optional.empty();
        }
        String sql = "SELECT id, channel_id, platforms, content, status, schedule_revision, "
                + "delivery_policy, start_at, end_at, rate_limit_per_channel_per_hour, "
                + "creator_id, creator_client_id, created_at, revoked_at, revoked_by "
                + "FROM campaigns WHERE id = ?";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load campaign: " + id, e);
        }
    }

    @Override
    public List<com.nova.link.announcement.Campaign> getAllPersistedCampaigns() throws DatabaseException {
        String sql = "SELECT id, channel_id, platforms, content, status, schedule_revision, "
                + "delivery_policy, start_at, end_at, rate_limit_per_channel_per_hour, "
                + "creator_id, creator_client_id, created_at, revoked_at, revoked_by "
                + "FROM campaigns ORDER BY created_at ASC";
        List<com.nova.link.announcement.Campaign> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to list campaigns", e);
        }
    }

    @Override
    public void deleteCampaign(String id) throws DatabaseException {
        if (id == null) {
            return;
        }
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM campaigns WHERE id = ?")) {
            stmt.setString(1, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete campaign: " + id, e);
        }
    }

    @Override
    public void updateCampaignStatus(String id, com.nova.link.announcement.CampaignStatus status,
                                     long revokedAt, java.util.UUID revokedBy) throws DatabaseException {
        if (id == null || status == null) {
            return;
        }
        String sql = "UPDATE campaigns SET status = ?, revoked_at = ?, revoked_by = ? WHERE id = ?";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            stmt.setLong(2, revokedAt);
            stmt.setString(3, revokedBy != null ? revokedBy.toString() : null);
            stmt.setString(4, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to update campaign status: " + id, e);
        }
    }

    /**
     * Maps a campaigns ResultSet row to a Campaign object. Reconstructs the
     * platforms Set from the comma-joined TEXT column, the delivery policy
     * via {@link com.nova.link.announcement.DeliveryPolicy#fromDbValue}, and
     * the status via {@link com.nova.link.announcement.CampaignStatus#valueOf}.
     */
    private com.nova.link.announcement.Campaign mapRow(ResultSet rs) throws SQLException {
        String platformsJoined = rs.getString("platforms");
        Set<String> platforms = new java.util.LinkedHashSet<>();
        if (platformsJoined != null && !platformsJoined.isBlank()) {
            for (String p : platformsJoined.split(",")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    platforms.add(trimmed);
                }
            }
        }
        return new com.nova.link.announcement.Campaign(
                rs.getString("id"),
                rs.getString("channel_id"),
                platforms,
                rs.getString("content"),
                com.nova.link.announcement.CampaignStatus.valueOf(rs.getString("status")),
                rs.getLong("schedule_revision"),
                com.nova.link.announcement.DeliveryPolicy.fromDbValue(rs.getString("delivery_policy")),
                rs.getLong("start_at"),
                rs.getLong("end_at"),
                rs.getInt("rate_limit_per_channel_per_hour"),
                parseUuid(rs.getString("creator_id")),
                rs.getString("creator_client_id"),
                rs.getLong("created_at"),
                rs.getLong("revoked_at"),
                parseUuid(rs.getString("revoked_by"))
        );
    }

    // ==================== Config Drafts (schema v15 / proposal 10) ====================
    //
    // §11.6 item-20 / PANEL proposal 10 — staged configuration draft / approve /
    // publish workflow. The config_drafts CRUD is dialect-neutral: straight
    // INSERT with RETURN_GENERATED_KEYS, a SELECT for single/list, an UPDATE
    // for the state-machine transition, and a DELETE for discard. One
    // implementation serves MySQL, PostgreSQL and SQLite alike. The
    // draft_json payload is stored masked (the service masks before calling
    // saveConfigDraft); status is the ConfigDraft.Status enum name.

    @Override
    public void saveConfigDraft(com.nova.link.api.ConfigDraft draft) throws DatabaseException {
        if (draft == null) {
            throw new DatabaseException("Cannot save a null config draft", null);
        }
        String insertSql = """
                INSERT INTO config_drafts (draft_json, created_by, status, approved_by,
                                            created_at, approved_at, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setString(1, draft.getDraftJson());
                    stmt.setString(2, draft.getCreatedBy());
                    stmt.setString(3, draft.getStatus().name());
                    stmt.setString(4, draft.getApprovedBy());
                    stmt.setLong(5, draft.getCreatedAt());
                    stmt.setLong(6, draft.getApprovedAt());
                    stmt.setLong(7, draft.getPublishedAt());
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            draft.setId(rs.getLong(1));
                        }
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save config draft", e);
        }
    }

    @Override
    public java.util.Optional<com.nova.link.api.ConfigDraft> getConfigDraft(long id) throws DatabaseException {
        String sql = "SELECT id, draft_json, created_by, status, approved_by, "
                + "created_at, approved_at, published_at FROM config_drafts WHERE id = ?";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(mapDraftRow(rs));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load config draft id=" + id, e);
        }
    }

    @Override
    public List<com.nova.link.api.ConfigDraft> listConfigDrafts(int limit) throws DatabaseException {
        // Metadata-only: draft_json deliberately NOT selected so the list
        // path never leaks the (masked) payload — same posture as
        // getConfigHistory. Callers fetch the payload via getConfigDraft(id).
        String sql = "SELECT id, NULL AS draft_json, created_by, status, approved_by, "
                + "created_at, approved_at, published_at FROM config_drafts "
                + "ORDER BY created_at DESC, id DESC LIMIT ?";
        List<com.nova.link.api.ConfigDraft> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapDraftRow(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to list config drafts", e);
        }
    }

    @Override
    public void updateConfigDraftStatus(long id, com.nova.link.api.ConfigDraft.Status status,
                                         String approvedBy, long approvedAt, long publishedAt)
            throws DatabaseException {
        if (status == null) {
            return;
        }
        String sql = "UPDATE config_drafts SET status = ?, approved_by = ?, "
                + "approved_at = ?, published_at = ? WHERE id = ?";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.name());
            stmt.setString(2, approvedBy);
            stmt.setLong(3, approvedAt);
            stmt.setLong(4, publishedAt);
            stmt.setLong(5, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to update config draft status id=" + id, e);
        }
    }

    @Override
    public void deleteConfigDraft(long id) throws DatabaseException {
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM config_drafts WHERE id = ?")) {
            stmt.setLong(1, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete config draft id=" + id, e);
        }
    }

    private com.nova.link.api.ConfigDraft mapDraftRow(ResultSet rs) throws SQLException {
        String statusName = rs.getString("status");
        return new com.nova.link.api.ConfigDraft(
                rs.getLong("id"),
                rs.getString("draft_json"),
                rs.getString("created_by"),
                statusName == null ? null : com.nova.link.api.ConfigDraft.Status.valueOf(statusName),
                rs.getString("approved_by"),
                rs.getLong("created_at"),
                rs.getLong("approved_at"),
                rs.getLong("published_at")
        );
    }

    // ==================== Config Backups (schema v15 / proposal 10) ====================
    //
    // §11.6 item-20 / PANEL proposal 10 — explicit backup / restore mechanism.
    // The config_backups CRUD is dialect-neutral: straight INSERT with
    // RETURN_GENERATED_KEYS and a SELECT for single/list. One implementation
    // serves MySQL, PostgreSQL and SQLite alike. The backup_json payload is
    // stored masked (the service masks before calling saveConfigBackup).

    @Override
    public void saveConfigBackup(com.nova.link.api.ConfigBackup backup) throws DatabaseException {
        if (backup == null) {
            throw new DatabaseException("Cannot save a null config backup", null);
        }
        String insertSql = """
                INSERT INTO config_backups (label, backup_json, settings_revision, created_by, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (Connection conn = getDataSource().getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                    stmt.setString(1, backup.getLabel());
                    stmt.setString(2, backup.getBackupJson());
                    stmt.setLong(3, backup.getSettingsRevision());
                    stmt.setString(4, backup.getCreatedBy());
                    stmt.setLong(5, backup.getCreatedAt());
                    stmt.executeUpdate();
                    try (ResultSet rs = stmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            backup.setId(rs.getLong(1));
                        }
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save config backup", e);
        }
    }

    @Override
    public java.util.Optional<com.nova.link.api.ConfigBackup> getConfigBackup(long id) throws DatabaseException {
        String sql = "SELECT id, label, backup_json, settings_revision, created_by, created_at "
                + "FROM config_backups WHERE id = ?";
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(mapBackupRow(rs));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load config backup id=" + id, e);
        }
    }

    @Override
    public List<com.nova.link.api.ConfigBackup> listConfigBackups(int limit) throws DatabaseException {
        // Metadata-only: backup_json deliberately NOT selected so the list
        // path never leaks the (masked) payload — same posture as
        // getConfigHistory / listConfigDrafts.
        String sql = "SELECT id, label, NULL AS backup_json, settings_revision, created_by, created_at "
                + "FROM config_backups ORDER BY created_at DESC, id DESC LIMIT ?";
        List<com.nova.link.api.ConfigBackup> results = new ArrayList<>();
        try (Connection conn = getDataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapBackupRow(rs));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to list config backups", e);
        }
    }

    private com.nova.link.api.ConfigBackup mapBackupRow(ResultSet rs) throws SQLException {
        return new com.nova.link.api.ConfigBackup(
                rs.getLong("id"),
                rs.getString("label"),
                rs.getString("backup_json"),
                rs.getLong("settings_revision"),
                rs.getString("created_by"),
                rs.getLong("created_at")
        );
    }
}
