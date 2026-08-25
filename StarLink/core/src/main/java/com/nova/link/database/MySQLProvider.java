package com.nova.link.database;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.dialect.MigrationDialect;
import com.nova.link.database.dialect.MySQLDialect;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * MySQL/MariaDB implementation of {@link DatabaseProvider} using HikariCP.
 *
 * <p>Extends {@link AbstractJdbcProvider} for connection-pool lifecycle and
 * migration running; this class supplies the MySQL-specific JDBC URL, prepared
 * statement cache tuning, and the MySQL upsert form ({@code ON DUPLICATE KEY
 * UPDATE}).
 *
 * <p>Requirements: 22.1, 22.5
 */
public class MySQLProvider extends AbstractJdbcProvider {

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;

    private final MySQLDialect dialect = new MySQLDialect();

    public MySQLProvider(String host, int port, String database, String username, String password, int poolSize) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
    }

    @Override
    protected HikariConfig buildHikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database +
                "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(2);
        config.setIdleTimeout(300000);
        config.setConnectionTimeout(10000);
        config.setPoolName("NovaLink-MySQL-Pool");

        // MySQL specific optimizations
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        return config;
    }

    @Override
    protected MigrationDialect dialect() {
        return dialect;
    }

    // ==================== Player State Operations ====================

    @Override
    public void savePlayerState(PlayerState state) throws DatabaseException {
        if (state == null || state.getPlayerId() == null) {
            throw new DatabaseException("Player state and player ID cannot be null");
        }

        String sql = """
            INSERT INTO players (player_id, player_name, client_id, current_world, joined_channels, active_channel, platform, dm_enabled, last_seen)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                player_name = VALUES(player_name),
                client_id = VALUES(client_id),
                current_world = VALUES(current_world),
                joined_channels = VALUES(joined_channels),
                active_channel = VALUES(active_channel),
                platform = VALUES(platform),
                dm_enabled = VALUES(dm_enabled),
                last_seen = VALUES(last_seen)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, state.getPlayerId().toString());
            stmt.setString(2, state.getPlayerName());
            stmt.setString(3, state.getClientId());
            stmt.setString(4, state.getCurrentWorld());
            stmt.setString(5, String.join(",", state.getJoinedChannels()));
            stmt.setString(6, state.getActiveChannel());
            stmt.setString(7, state.getPlatform());
            stmt.setBoolean(8, state.isDmEnabled());
            stmt.setLong(9, state.getLastSeen());

            stmt.executeUpdate();
            logger.debug("Saved player state for: {}", state.getPlayerId());
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save player state", e);
        }
    }

    @Override
    public Optional<PlayerState> loadPlayerState(UUID playerId) throws DatabaseException {
        if (playerId == null) {
            return Optional.empty();
        }

        String sql = "SELECT * FROM players WHERE player_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    PlayerState state = new PlayerState(playerId);
                    state.setPlayerName(rs.getString("player_name"));
                    state.setClientId(rs.getString("client_id"));
                    state.setCurrentWorld(rs.getString("current_world"));

                    String joinedChannels = rs.getString("joined_channels");
                    if (joinedChannels != null && !joinedChannels.isEmpty()) {
                        state.setJoinedChannels(new HashSet<>(Arrays.asList(joinedChannels.split(","))));
                    }

                    state.setActiveChannel(rs.getString("active_channel"));
                    state.setPlatform(rs.getString("platform"));
                    state.setDmEnabled(rs.getBoolean("dm_enabled"));
                    state.setLastSeen(rs.getLong("last_seen"));

                    // Load mutes
                    List<MuteInfo> mutes = loadMutes(playerId);
                    Map<String, MuteInfo> muteMap = new HashMap<>();
                    for (MuteInfo mute : mutes) {
                        muteMap.put(mute.getChannelId() != null ? mute.getChannelId() : "__global__", mute);
                    }
                    state.setMutes(muteMap);

                    return Optional.of(state);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load player state", e);
        }

        return Optional.empty();
    }

    @Override
    public void deletePlayerState(UUID playerId) throws DatabaseException {
        if (playerId == null) {
            return;
        }

        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // Delete mutes first
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM mutes WHERE player_id = ?")) {
                    stmt.setString(1, playerId.toString());
                    stmt.executeUpdate();
                }

                // Delete bans
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM bans WHERE player_id = ?")) {
                    stmt.setString(1, playerId.toString());
                    stmt.executeUpdate();
                }

                // Delete player
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM players WHERE player_id = ?")) {
                    stmt.setString(1, playerId.toString());
                    stmt.executeUpdate();
                }

                conn.commit();
                logger.debug("Deleted player state for: {}", playerId);
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete player state", e);
        }
    }

    @Override
    public List<PlayerState> getAllPlayerStates() throws DatabaseException {
        List<PlayerState> states = new ArrayList<>();
        String sql = "SELECT player_id FROM players";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                UUID playerId = UUID.fromString(rs.getString("player_id"));
                loadPlayerState(playerId).ifPresent(states::add);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load all player states", e);
        }

        return states;
    }

    // ==================== Channel Operations ====================

    @Override
    public void saveChannel(Channel channel) throws DatabaseException {
        if (channel == null || channel.getId() == null) {
            throw new DatabaseException("Channel and channel ID cannot be null");
        }

        String sql = """
            INSERT INTO channels (channel_id, display_name, scope, client_id, permission, max_capacity, allowed_worlds, password, owner_id, slow_mode_seconds, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                display_name = VALUES(display_name),
                permission = VALUES(permission),
                max_capacity = VALUES(max_capacity),
                allowed_worlds = VALUES(allowed_worlds),
                password = VALUES(password),
                owner_id = VALUES(owner_id),
                slow_mode_seconds = VALUES(slow_mode_seconds)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, channel.getId());
            stmt.setString(2, channel.getDisplayName());
            stmt.setString(3, channel.getScope().name());
            stmt.setString(4, channel.getClientId());
            stmt.setString(5, channel.getPermission());
            stmt.setInt(6, channel.getMaxCapacity());
            stmt.setString(7, String.join(",", channel.getAllowedWorlds()));
            stmt.setString(8, channel.getPassword());
            stmt.setString(9, channel.getOwnerId() != null ? channel.getOwnerId().toString() : null);
            stmt.setInt(10, channel.getSlowModeSeconds());
            stmt.setLong(11, channel.getCreatedAt());

            stmt.executeUpdate();
            logger.debug("Saved channel: {}", channel.getId());
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save channel", e);
        }
    }

    @Override
    public Optional<Channel> loadChannel(String channelId) throws DatabaseException {
        if (channelId == null) {
            return Optional.empty();
        }

        String sql = "SELECT * FROM channels WHERE channel_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, channelId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    ChannelScope scope = ChannelScope.valueOf(rs.getString("scope"));
                    Channel channel = new Channel(
                            rs.getString("channel_id"),
                            rs.getString("display_name"),
                            scope,
                            rs.getString("client_id")
                    );

                    channel.setPermission(rs.getString("permission"));
                    channel.setMaxCapacity(rs.getInt("max_capacity"));

                    String allowedWorlds = rs.getString("allowed_worlds");
                    if (allowedWorlds != null && !allowedWorlds.isEmpty()) {
                        channel.setAllowedWorlds(Arrays.asList(allowedWorlds.split(",")));
                    }

                    channel.setPassword(rs.getString("password"));

                    String ownerId = rs.getString("owner_id");
                    if (ownerId != null) {
                        channel.setOwnerId(UUID.fromString(ownerId));
                    }

                    channel.setSlowModeSeconds(rs.getInt("slow_mode_seconds"));

                    return Optional.of(channel);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load channel", e);
        }

        return Optional.empty();
    }

    @Override
    public void deleteChannel(String channelId) throws DatabaseException {
        if (channelId == null) {
            return;
        }

        String sql = "DELETE FROM channels WHERE channel_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, channelId);
            stmt.executeUpdate();
            logger.debug("Deleted channel: {}", channelId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete channel", e);
        }
    }

    @Override
    public List<Channel> getAllChannels() throws DatabaseException {
        List<Channel> channels = new ArrayList<>();
        String sql = "SELECT channel_id FROM channels";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                loadChannel(rs.getString("channel_id")).ifPresent(channels::add);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load all channels", e);
        }

        return channels;
    }

    // ==================== Mute Operations ====================

    @Override
    public void saveMute(UUID playerId, MuteInfo muteInfo) throws DatabaseException {
        if (playerId == null || muteInfo == null) {
            throw new DatabaseException("Player ID and mute info cannot be null");
        }

        // DELETE + INSERT in one transaction. Doing the delete as a separate
        // connectionless call (deleteMute opens its own connection) leaves a
        // window where two concurrent saves can both delete, then both insert,
        // hitting the UNIQUE(player_id, channel_id) constraint. Keeping both
        // statements on the same connection under autoCommit=false makes the
        // replace atomic and serialised by the row lock taken by the DELETE.
        String deleteSql = muteInfo.getChannelId() != null
                ? "DELETE FROM mutes WHERE player_id = ? AND channel_id = ?"
                : "DELETE FROM mutes WHERE player_id = ? AND channel_id IS NULL";
        String insertSql = "INSERT INTO mutes (player_id, channel_id, expire_time, reason, operator_id, created_at) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement(deleteSql)) {
                    delete.setString(1, playerId.toString());
                    if (muteInfo.getChannelId() != null) {
                        delete.setString(2, muteInfo.getChannelId());
                    }
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                    insert.setString(1, playerId.toString());
                    insert.setString(2, muteInfo.getChannelId());
                    insert.setLong(3, muteInfo.getExpireTime());
                    insert.setString(4, muteInfo.getReason());
                    insert.setString(5, muteInfo.getOperatorId() != null ? muteInfo.getOperatorId().toString() : null);
                    insert.setLong(6, muteInfo.getCreatedAt());
                    insert.executeUpdate();
                }
                conn.commit();
                logger.debug("Saved mute for player {} in channel {}", playerId, muteInfo.getChannelId());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save mute", e);
        }
    }

    @Override
    public List<MuteInfo> loadMutes(UUID playerId) throws DatabaseException {
        if (playerId == null) {
            return Collections.emptyList();
        }

        List<MuteInfo> mutes = new ArrayList<>();
        String sql = "SELECT * FROM mutes WHERE player_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    MuteInfo mute = new MuteInfo(
                            rs.getString("channel_id"),
                            rs.getLong("expire_time"),
                            rs.getString("reason"),
                            parseUuid(rs.getString("operator_id")),
                            rs.getLong("created_at")
                    );
                    mutes.add(mute);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load mutes", e);
        }

        return mutes;
    }

    @Override
    public void deleteMute(UUID playerId, String channelId) throws DatabaseException {
        if (playerId == null) {
            return;
        }

        String sql = channelId != null
                ? "DELETE FROM mutes WHERE player_id = ? AND channel_id = ?"
                : "DELETE FROM mutes WHERE player_id = ? AND channel_id IS NULL";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerId.toString());
            if (channelId != null) {
                stmt.setString(2, channelId);
            }

            stmt.executeUpdate();
            logger.debug("Deleted mute for player {} in channel {}", playerId, channelId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete mute", e);
        }
    }

    @Override
    public int cleanupExpiredMutes() throws DatabaseException {
        String sql = "DELETE FROM mutes WHERE expire_time > 0 AND expire_time < ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, System.currentTimeMillis());
            int count = stmt.executeUpdate();

            if (count > 0) {
                logger.debug("Cleaned up {} expired mutes", count);
            }
            return count;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to cleanup expired mutes", e);
        }
    }

    @Override
    public Map<UUID, List<MuteInfo>> getAllActiveMutes() throws DatabaseException {
        Map<UUID, List<MuteInfo>> result = new HashMap<>();
        String sql = "SELECT * FROM mutes WHERE expire_time <= 0 OR expire_time >= ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, System.currentTimeMillis());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    UUID playerId = parseUuid(rs.getString("player_id"));
                    if (playerId == null) {
                        continue;
                    }
                    MuteInfo mute = new MuteInfo(
                            rs.getString("channel_id"),
                            rs.getLong("expire_time"),
                            rs.getString("reason"),
                            parseUuid(rs.getString("operator_id")),
                            rs.getLong("created_at")
                    );
                    result.computeIfAbsent(playerId, k -> new ArrayList<>()).add(mute);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load active mutes", e);
        }

        return result;
    }

    // ==================== Ban Operations ====================

    @Override
    public void saveBan(UUID playerId, BanInfo banInfo) throws DatabaseException {
        if (playerId == null || banInfo == null) {
            throw new DatabaseException("Player ID and ban info cannot be null");
        }

        // DELETE + INSERT in one transaction (see saveMute for the rationale).
        String deleteSql = banInfo.getChannelId() != null
                ? "DELETE FROM bans WHERE player_id = ? AND channel_id = ?"
                : "DELETE FROM bans WHERE player_id = ? AND channel_id IS NULL";
        String insertSql = "INSERT INTO bans (player_id, channel_id, expire_time, reason, operator_id, created_at) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement(deleteSql)) {
                    delete.setString(1, playerId.toString());
                    if (banInfo.getChannelId() != null) {
                        delete.setString(2, banInfo.getChannelId());
                    }
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                    insert.setString(1, playerId.toString());
                    insert.setString(2, banInfo.getChannelId());
                    insert.setLong(3, banInfo.getExpireTime());
                    insert.setString(4, banInfo.getReason());
                    insert.setString(5, banInfo.getOperatorId() != null ? banInfo.getOperatorId().toString() : null);
                    insert.setLong(6, banInfo.getCreatedAt());
                    insert.executeUpdate();
                }
                conn.commit();
                logger.debug("Saved ban for player {} in channel {}", playerId, banInfo.getChannelId());
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save ban", e);
        }
    }

    @Override
    public List<BanInfo> loadBans(UUID playerId) throws DatabaseException {
        if (playerId == null) {
            return Collections.emptyList();
        }

        List<BanInfo> bans = new ArrayList<>();
        String sql = "SELECT * FROM bans WHERE player_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    BanInfo ban = new BanInfo(
                            rs.getString("channel_id"),
                            rs.getLong("expire_time"),
                            rs.getString("reason"),
                            parseUuid(rs.getString("operator_id")),
                            rs.getLong("created_at")
                    );
                    bans.add(ban);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load bans", e);
        }

        return bans;
    }

    @Override
    public void deleteBan(UUID playerId, String channelId) throws DatabaseException {
        if (playerId == null) {
            return;
        }

        String sql = channelId != null
                ? "DELETE FROM bans WHERE player_id = ? AND channel_id = ?"
                : "DELETE FROM bans WHERE player_id = ? AND channel_id IS NULL";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerId.toString());
            if (channelId != null) {
                stmt.setString(2, channelId);
            }

            stmt.executeUpdate();
            logger.debug("Deleted ban for player {} in channel {}", playerId, channelId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete ban", e);
        }
    }

    @Override
    public int cleanupExpiredBans() throws DatabaseException {
        String sql = "DELETE FROM bans WHERE expire_time > 0 AND expire_time < ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, System.currentTimeMillis());
            int count = stmt.executeUpdate();

            if (count > 0) {
                logger.debug("Cleaned up {} expired bans", count);
            }
            return count;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to cleanup expired bans", e);
        }
    }

    @Override
    public Map<UUID, List<BanInfo>> getAllActiveBans() throws DatabaseException {
        Map<UUID, List<BanInfo>> result = new HashMap<>();
        String sql = "SELECT * FROM bans WHERE expire_time <= 0 OR expire_time >= ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, System.currentTimeMillis());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    UUID playerId = parseUuid(rs.getString("player_id"));
                    if (playerId == null) {
                        continue;
                    }
                    BanInfo ban = new BanInfo(
                            rs.getString("channel_id"),
                            rs.getLong("expire_time"),
                            rs.getString("reason"),
                            parseUuid(rs.getString("operator_id")),
                            rs.getLong("created_at")
                    );
                    result.computeIfAbsent(playerId, k -> new ArrayList<>()).add(ban);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load active bans", e);
        }

        return result;
    }

    // ==================== Notification Operations ====================

    @Override
    public void saveNotification(Notification notification) throws DatabaseException {
        if (notification == null) {
            throw new DatabaseException("Notification cannot be null");
        }

        // PANEL-014: recipient column added in schema v10. NULL = broadcast.
        // `read` is backtick-quoted because it is a MySQL reserved word.
        String sql = "INSERT INTO notifications (title, message, level, created_at, `read`, recipient) "
                + "VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, notification.getTitle());
            stmt.setString(2, notification.getMessage());
            stmt.setString(3, notification.getLevel());
            stmt.setLong(4, notification.getCreatedAt());
            stmt.setBoolean(5, notification.isRead());
            stmt.setString(6, notification.getRecipient());

            stmt.executeUpdate();

            // Reflect the generated id back onto the object so callers
            // can reference the persisted row (e.g. mark-read by id).
            stampGeneratedId(stmt, notification);
            logger.debug("Saved notification: {}", notification.getTitle());
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save notification", e);
        }
    }

    @Override
    public List<Notification> getNotifications(int offset, int limit, boolean unreadOnly) throws DatabaseException {
        List<Notification> notifications = new ArrayList<>();
        String sql = unreadOnly
                ? "SELECT * FROM notifications WHERE `read` = FALSE ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM notifications ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, Math.max(0, limit));
            stmt.setInt(2, Math.max(0, offset));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    notifications.add(new Notification(
                            rs.getLong("id"),
                            rs.getString("title"),
                            rs.getString("message"),
                            rs.getString("level"),
                            rs.getLong("created_at"),
                            rs.getBoolean("read")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load notifications", e);
        }

        return notifications;
    }

    @Override
    public void markNotificationRead(long id) throws DatabaseException {
        String sql = "UPDATE notifications SET `read` = TRUE WHERE id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, id);
            stmt.executeUpdate();
            logger.debug("Marked notification {} as read", id);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to mark notification as read", e);
        }
    }

    @Override
    public void markAllNotificationsRead() throws DatabaseException {
        String sql = "UPDATE notifications SET `read` = TRUE WHERE `read` = FALSE";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int count = stmt.executeUpdate();
            if (count > 0) {
                logger.debug("Marked {} notifications as read", count);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to mark all notifications as read", e);
        }
    }

    @Override
    public int clearNotifications() throws DatabaseException {
        String sql = "DELETE FROM notifications";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            int count = stmt.executeUpdate();
            if (count > 0) {
                logger.debug("Cleared {} notifications", count);
            }
            return count;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to clear notifications", e);
        }
    }

    @Override
    public int getUnreadCount() throws DatabaseException {
        String sql = "SELECT COUNT(*) FROM notifications WHERE `read` = FALSE";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to get unread notification count", e);
        }
    }

    @Override
    public int countNotifications(boolean unreadOnly) throws DatabaseException {
        String sql = unreadOnly
                ? "SELECT COUNT(*) FROM notifications WHERE `read` = FALSE"
                : "SELECT COUNT(*) FROM notifications";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to count notifications", e);
        }
    }

    // --- Per-user notification state (PANEL-014) ---
    // See SQLiteProvider for the semantic model. MySQL uses ON DUPLICATE KEY
    // UPDATE for upsert (the primary key of notification_read is
    // (notification_id, user_id), so a duplicate on either column triggers the
    // update). `read` is backtick-quoted because it is a MySQL reserved word.

    private static final String SQL_GET_PER_USER_UNREAD_MYSQL = """
            SELECT n.id, n.title, n.message, n.level, n.created_at, n.`read` AS `read`, n.recipient
            FROM notifications n
            LEFT JOIN notification_read nr ON nr.notification_id = n.id AND nr.user_id = ?
            WHERE (n.recipient IS NULL OR n.recipient = ?)
              AND (n.`read` = TRUE OR (nr.`read` IS NOT NULL AND nr.`read` = TRUE)) = FALSE
            ORDER BY n.created_at DESC, n.id DESC
            LIMIT ? OFFSET ?
            """;

    private static final String SQL_GET_PER_USER_ALL_MYSQL = """
            SELECT n.id, n.title, n.message, n.level, n.created_at,
                   (n.`read` = TRUE OR (nr.`read` IS NOT NULL AND nr.`read` = TRUE)) AS `read`,
                   n.recipient
            FROM notifications n
            LEFT JOIN notification_read nr ON nr.notification_id = n.id AND nr.user_id = ?
            WHERE n.recipient IS NULL OR n.recipient = ?
            ORDER BY n.created_at DESC, n.id DESC
            LIMIT ? OFFSET ?
            """;

    private static Notification mapNotification(ResultSet rs) throws SQLException {
        return new Notification(
                rs.getLong("id"),
                rs.getString("title"),
                rs.getString("message"),
                rs.getString("level"),
                rs.getLong("created_at"),
                rs.getBoolean("read"),
                rs.getString("recipient")
        );
    }

    @Override
    public List<Notification> getNotifications(int offset, int limit, boolean unreadOnly, String userId)
            throws DatabaseException {
        if (userId == null) {
            return getNotifications(offset, limit, unreadOnly);
        }
        List<Notification> notifications = new ArrayList<>();
        String sql = unreadOnly ? SQL_GET_PER_USER_UNREAD_MYSQL : SQL_GET_PER_USER_ALL_MYSQL;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setString(2, userId);
            stmt.setInt(3, Math.max(0, limit));
            stmt.setInt(4, Math.max(0, offset));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    notifications.add(mapNotification(rs));
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load per-user notifications", e);
        }
        return notifications;
    }

    @Override
    public void markNotificationRead(long id, String userId) throws DatabaseException {
        if (userId == null) {
            markNotificationRead(id);
            return;
        }
        String sql = "INSERT INTO notification_read (notification_id, user_id, `read`, read_at) "
                + "VALUES (?, ?, TRUE, ?) "
                + "ON DUPLICATE KEY UPDATE `read` = TRUE, read_at = VALUES(read_at)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, id);
            stmt.setString(2, userId);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
            logger.debug("Marked notification {} as read for user {}", id, userId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to mark per-user notification as read", e);
        }
    }

    @Override
    public void markAllNotificationsRead(String userId) throws DatabaseException {
        if (userId == null) {
            markAllNotificationsRead();
            return;
        }
        // MySQL does not allow INSERT ... SELECT ... ON DUPLICATE KEY UPDATE
        // referencing the same target table in the SELECT without a derived
        // table, so we first select candidate ids, then upsert per row. For
        // simplicity and bounded work, we load the visible-unread ids and
        // upsert them in a single batched statement.
        String selectSql = "SELECT n.id FROM notifications n "
                + "WHERE (n.recipient IS NULL OR n.recipient = ?) "
                + "AND n.`read` = FALSE "
                + "AND NOT EXISTS ("
                + "  SELECT 1 FROM notification_read nr "
                + "  WHERE nr.notification_id = n.id AND nr.user_id = ? AND nr.`read` = TRUE"
                + ")";
        String upsertSql = "INSERT INTO notification_read (notification_id, user_id, `read`, read_at) "
                + "VALUES (?, ?, TRUE, ?) "
                + "ON DUPLICATE KEY UPDATE `read` = TRUE, read_at = VALUES(read_at)";
        try (Connection conn = dataSource.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                List<Long> ids = new ArrayList<>();
                try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                    selectStmt.setString(1, userId);
                    selectStmt.setString(2, userId);
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        while (rs.next()) {
                            ids.add(rs.getLong(1));
                        }
                    }
                }
                if (ids.isEmpty()) {
                    return;
                }
                long now = System.currentTimeMillis();
                try (PreparedStatement upsertStmt = conn.prepareStatement(upsertSql)) {
                    for (Long id : ids) {
                        upsertStmt.setLong(1, id);
                        upsertStmt.setString(2, userId);
                        upsertStmt.setLong(3, now);
                        upsertStmt.addBatch();
                    }
                    int[] counts = upsertStmt.executeBatch();
                    int total = 0;
                    for (int c : counts) {
                        if (c > 0) {
                            total += c;
                        }
                    }
                    if (total > 0) {
                        logger.debug("Marked {} notifications as read for user {}", total, userId);
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
            throw new DatabaseException("Failed to mark all per-user notifications as read", e);
        }
    }

    @Override
    public int getUnreadCount(String userId) throws DatabaseException {
        if (userId == null) {
            return getUnreadCount();
        }
        String sql = "SELECT COUNT(*) FROM notifications n "
                + "WHERE (n.recipient IS NULL OR n.recipient = ?) "
                + "AND n.`read` = FALSE "
                + "AND NOT EXISTS ("
                + "  SELECT 1 FROM notification_read nr "
                + "  WHERE nr.notification_id = n.id AND nr.user_id = ? AND nr.`read` = TRUE"
                + ")";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setString(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to get per-user unread count", e);
        }
    }

    @Override
    public int countNotifications(boolean unreadOnly, String userId) throws DatabaseException {
        if (userId == null) {
            return countNotifications(unreadOnly);
        }
        String sql;
        if (unreadOnly) {
            sql = "SELECT COUNT(*) FROM notifications n "
                    + "WHERE (n.recipient IS NULL OR n.recipient = ?) "
                    + "AND n.`read` = FALSE "
                    + "AND NOT EXISTS ("
                    + "  SELECT 1 FROM notification_read nr "
                    + "  WHERE nr.notification_id = n.id AND nr.user_id = ? AND nr.`read` = TRUE"
                    + ")";
        } else {
            sql = "SELECT COUNT(*) FROM notifications n "
                    + "WHERE n.recipient IS NULL OR n.recipient = ?";
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            if (unreadOnly) {
                stmt.setString(2, userId);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to count per-user notifications", e);
        }
    }

    @Override
    public int clearNotifications(String userId) throws DatabaseException {
        if (userId == null) {
            return clearNotifications();
        }
        String sql = "DELETE FROM notifications WHERE recipient = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            int count = stmt.executeUpdate();
            if (count > 0) {
                logger.debug("Cleared {} directed notifications for user {}", count, userId);
            }
            return count;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to clear per-user notifications", e);
        }
    }

    @Override
    public int clearBroadcastNotifications() throws DatabaseException {
        // Only broadcast notifications (recipient IS NULL) are deleted. Directed
        // notifications (non-null recipient) are preserved so the SUPER_ADMIN
        // global-retention path does not wipe other admins' inboxes.
        //
        // notification_read has no FK ON DELETE CASCADE (its PK is
        // (notification_id, user_id)), so the per-user read-state rows for the
        // purged broadcast notifications must be deleted explicitly. Otherwise
        // they are orphaned forever, growing the table without bound and leaving
        // stale rows that later JOIN-based visibility/counts would surface.
        // Order matters: delete the child table (notification_read) before the
        // parent (notifications). Both statements run on the same Connection for
        // a consistent view.
        String deleteReadSql = "DELETE FROM notification_read "
                + "WHERE notification_id IN (SELECT id FROM notifications WHERE recipient IS NULL)";
        String deleteNotificationsSql = "DELETE FROM notifications WHERE recipient IS NULL";
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement readStmt = conn.prepareStatement(deleteReadSql)) {
                readStmt.executeUpdate();
            }
            try (PreparedStatement notifStmt = conn.prepareStatement(deleteNotificationsSql)) {
                int count = notifStmt.executeUpdate();
                if (count > 0) {
                    logger.debug("Cleared {} broadcast notifications", count);
                }
                return count;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to clear broadcast notifications", e);
        }
    }

    // ==================== Invitation Operations ====================

    @Override
    public void saveInvitation(Invitation invitation) throws DatabaseException {
        if (invitation == null || invitation.getCode() == null) {
            throw new DatabaseException("Invitation and code cannot be null");
        }

        String sql = """
            INSERT INTO invitations (code, channel_id, inviter_id, expire_time, created_at, used, used_by, used_at,
                                    max_uses, used_count, revoked_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                channel_id = VALUES(channel_id),
                inviter_id = VALUES(inviter_id),
                expire_time = VALUES(expire_time),
                created_at = VALUES(created_at),
                used = VALUES(used),
                used_by = VALUES(used_by),
                used_at = VALUES(used_at),
                max_uses = VALUES(max_uses),
                used_count = VALUES(used_count),
                revoked_at = VALUES(revoked_at)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, invitation.getCode());
            stmt.setString(2, invitation.getChannelId());
            stmt.setString(3, invitation.getInviterId().toString());
            stmt.setLong(4, invitation.getExpireTime());
            stmt.setLong(5, invitation.getCreatedAt());
            stmt.setBoolean(6, invitation.isUsed());
            stmt.setString(7, invitation.getUsedBy() != null ? invitation.getUsedBy().toString() : null);
            stmt.setLong(8, invitation.getUsedAt());
            stmt.setInt(9, invitation.getMaxUses());
            stmt.setInt(10, invitation.getUsedCount());
            if (invitation.getRevokedAt() != null) {
                stmt.setLong(11, invitation.getRevokedAt());
            } else {
                stmt.setNull(11, java.sql.Types.BIGINT);
            }

            stmt.executeUpdate();
            logger.debug("Saved invitation: {}", invitation.getCode());
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save invitation", e);
        }
    }

    @Override
    public Optional<Invitation> loadInvitation(String code) throws DatabaseException {
        if (code == null) {
            return Optional.empty();
        }

        String sql = "SELECT * FROM invitations WHERE code = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, code);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Invitation invitation = new Invitation(
                            rs.getString("code"),
                            rs.getString("channel_id"),
                            UUID.fromString(rs.getString("inviter_id")),
                            rs.getLong("expire_time"),
                            rs.getLong("created_at"),
                            rs.getBoolean("used"),
                            parseUuid(rs.getString("used_by")),
                            rs.getLong("used_at"),
                            rs.getInt("max_uses"),
                            rs.getInt("used_count"),
                            rs.getObject("revoked_at") != null ? rs.getLong("revoked_at") : null
                    );
                    return Optional.of(invitation);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load invitation", e);
        }

        return Optional.empty();
    }

    @Override
    public boolean markInvitationUsed(String code, UUID usedBy) throws DatabaseException {
        if (code == null) {
            return false;
        }

        // Guard with `AND used = FALSE` so two concurrent accepts cannot both
        // flip used=false -> true. If 0 rows are updated, another thread won
        // the race and already consumed the invitation.
        String sql = "UPDATE invitations SET used = TRUE, used_by = ?, used_at = ? WHERE code = ? AND used = FALSE";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usedBy != null ? usedBy.toString() : null);
            stmt.setLong(2, System.currentTimeMillis());
            stmt.setString(3, code);

            int affected = stmt.executeUpdate();
            if (affected == 0) {
                logger.warn("markInvitationUsed({}) affected 0 rows — already used by another thread", code);
            } else {
                logger.debug("Marked invitation {} as used by {}", code, usedBy);
            }
            return affected > 0;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to mark invitation as used", e);
        }
    }

    @Override
    public int claimInvitationUse(String code, UUID playerId, long now) throws DatabaseException {
        if (code == null) {
            return 0;
        }

        // Single atomic UPDATE that claims one quota slot. The WHERE clause is
        // the race guard: used = FALSE (not exhausted), revoked_at IS NULL (not
        // revoked), and used_count < max_uses (quota remaining). On success,
        // used_count advances and used flips to TRUE exactly when the new
        // used_count reaches max_uses — unifying single-use (maxUses=1, used
        // becomes TRUE on the first claim) and multi-use paths. MySQL evaluates
        // the boolean expression to 1/0, which maps onto TINYINT(1).
        String sql = "UPDATE invitations SET used_count = used_count + 1, used_by = ?, used_at = ?, "
                + "used = (used_count + 1 >= max_uses) "
                + "WHERE code = ? AND used = FALSE AND revoked_at IS NULL AND used_count < max_uses";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerId != null ? playerId.toString() : null);
            stmt.setLong(2, now);
            stmt.setString(3, code);

            int affected = stmt.executeUpdate();
            if (affected == 0) {
                logger.debug("claimInvitationUse({}) affected 0 rows — exhausted/revoked/missing or raced", code);
            } else {
                logger.debug("Claimed one use of invitation {} for player {}", code, playerId);
            }
            return affected;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to claim invitation use", e);
        }
    }

    @Override
    public void deleteInvitation(String code) throws DatabaseException {
        if (code == null) {
            return;
        }

        String sql = "DELETE FROM invitations WHERE code = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, code);
            stmt.executeUpdate();
            logger.debug("Deleted invitation: {}", code);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete invitation", e);
        }
    }

    @Override
    public int cleanupExpiredInvitations() throws DatabaseException {
        String sql = "DELETE FROM invitations WHERE expire_time < ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, System.currentTimeMillis());
            int count = stmt.executeUpdate();

            if (count > 0) {
                logger.debug("Cleaned up {} expired invitations", count);
            }
            return count;
        } catch (SQLException e) {
            throw new DatabaseException("Failed to cleanup expired invitations", e);
        }
    }

    @Override
    public String getProviderType() {
        return "MySQL";
    }
}
