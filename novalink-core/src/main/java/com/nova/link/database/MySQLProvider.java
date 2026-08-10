package com.nova.link.database;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * MySQL implementation of DatabaseProvider using HikariCP connection pool.
 * 
 * Requirements: 22.1, 22.5
 */
public class MySQLProvider implements DatabaseProvider {

    private static final Logger logger = LoggerFactory.getLogger(MySQLProvider.class);

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;

    private HikariDataSource dataSource;

    public MySQLProvider(String host, int port, String database, String username, String password, int poolSize) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
    }

    @Override
    public void initialize() throws DatabaseException {
        try {
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

            dataSource = new HikariDataSource(config);
            
            // Run migrations
            DatabaseMigration migration = new DatabaseMigration(dataSource);
            migration.migrate();
            
            logger.info("MySQLProvider initialized with pool size: {}", poolSize);
        } catch (Exception e) {
            throw new DatabaseException("Failed to initialize MySQL connection", e);
        }
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("MySQLProvider shutdown");
        }
    }

    @Override
    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    // ==================== Player State Operations ====================

    @Override
    public void savePlayerState(PlayerState state) throws DatabaseException {
        if (state == null || state.getPlayerId() == null) {
            throw new DatabaseException("Player state and player ID cannot be null");
        }

        String sql = """
            INSERT INTO players (player_id, player_name, client_id, current_world, joined_channels, active_channel, platform, last_seen)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                player_name = VALUES(player_name),
                client_id = VALUES(client_id),
                current_world = VALUES(current_world),
                joined_channels = VALUES(joined_channels),
                active_channel = VALUES(active_channel),
                platform = VALUES(platform),
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
            stmt.setLong(8, state.getLastSeen());
            
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
            
            logger.debug("Deleted player state for: {}", playerId);
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
            INSERT INTO channels (channel_id, display_name, scope, client_id, permission, max_capacity, allowed_worlds, password, owner_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                display_name = VALUES(display_name),
                permission = VALUES(permission),
                max_capacity = VALUES(max_capacity),
                allowed_worlds = VALUES(allowed_worlds),
                password = VALUES(password)
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
            stmt.setLong(10, channel.getCreatedAt());
            
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

        // First delete any existing mute for this player/channel combination
        deleteMute(playerId, muteInfo.getChannelId());

        String sql = "INSERT INTO mutes (player_id, channel_id, expire_time, reason, operator_id, created_at) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, playerId.toString());
            stmt.setString(2, muteInfo.getChannelId());
            stmt.setLong(3, muteInfo.getExpireTime());
            stmt.setString(4, muteInfo.getReason());
            stmt.setString(5, muteInfo.getOperatorId() != null ? muteInfo.getOperatorId().toString() : null);
            stmt.setLong(6, muteInfo.getCreatedAt());
            
            stmt.executeUpdate();
            logger.debug("Saved mute for player {} in channel {}", playerId, muteInfo.getChannelId());
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
                    String operatorIdStr = rs.getString("operator_id");
                    UUID operatorId = operatorIdStr != null ? UUID.fromString(operatorIdStr) : null;
                    
                    MuteInfo mute = new MuteInfo(
                            rs.getString("channel_id"),
                            rs.getLong("expire_time"),
                            rs.getString("reason"),
                            operatorId,
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

    // ==================== Ban Operations ====================

    @Override
    public void saveBan(UUID playerId, BanInfo banInfo) throws DatabaseException {
        if (playerId == null || banInfo == null) {
            throw new DatabaseException("Player ID and ban info cannot be null");
        }

        // First delete any existing ban for this player/channel combination
        deleteBan(playerId, banInfo.getChannelId());

        String sql = "INSERT INTO bans (player_id, channel_id, expire_time, reason, operator_id, created_at) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, playerId.toString());
            stmt.setString(2, banInfo.getChannelId());
            stmt.setLong(3, banInfo.getExpireTime());
            stmt.setString(4, banInfo.getReason());
            stmt.setString(5, banInfo.getOperatorId() != null ? banInfo.getOperatorId().toString() : null);
            stmt.setLong(6, banInfo.getCreatedAt());

            stmt.executeUpdate();
            logger.debug("Saved ban for player {} in channel {}", playerId, banInfo.getChannelId());
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
                    String operatorIdStr = rs.getString("operator_id");
                    UUID operatorId = operatorIdStr != null ? UUID.fromString(operatorIdStr) : null;

                    BanInfo ban = new BanInfo(
                            rs.getString("channel_id"),
                            rs.getLong("expire_time"),
                            rs.getString("reason"),
                            operatorId,
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

    // ==================== Notification Operations ====================

    @Override
    public void saveNotification(Notification notification) throws DatabaseException {
        if (notification == null) {
            throw new DatabaseException("Notification cannot be null");
        }

        String sql = "INSERT INTO notifications (title, message, level, created_at, read) VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, notification.getTitle());
            stmt.setString(2, notification.getMessage());
            stmt.setString(3, notification.getLevel());
            stmt.setLong(4, notification.getCreatedAt());
            stmt.setBoolean(5, notification.isRead());

            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    // Reflect the generated id back onto the object so callers
                    // can reference the persisted row (e.g. mark-read by id).
                    long generatedId = rs.getLong(1);
                    try {
                    java.lang.reflect.Field f = Notification.class.getDeclaredField("id");
                    f.setAccessible(true);
                    f.setLong(notification, generatedId);
                    } catch (ReflectiveOperationException e) {
                        logger.debug("Could not stamp generated notification id: {}", e.getMessage());
                    }
                }
            }
            logger.debug("Saved notification: {}", notification.getTitle());
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save notification", e);
        }
    }

    @Override
    public List<Notification> getNotifications(int offset, int limit, boolean unreadOnly) throws DatabaseException {
        List<Notification> notifications = new ArrayList<>();
        String sql = unreadOnly
                ? "SELECT * FROM notifications WHERE read = FALSE ORDER BY created_at DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM notifications ORDER BY created_at DESC LIMIT ? OFFSET ?";

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
        String sql = "UPDATE notifications SET read = TRUE WHERE id = ?";

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
        String sql = "UPDATE notifications SET read = TRUE WHERE read = FALSE";

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
        String sql = "SELECT COUNT(*) FROM notifications WHERE read = FALSE";

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

    // ==================== Invitation Operations ====================

    @Override
    public void saveInvitation(Invitation invitation) throws DatabaseException {
        if (invitation == null || invitation.getCode() == null) {
            throw new DatabaseException("Invitation and code cannot be null");
        }

        String sql = """
            INSERT INTO invitations (code, channel_id, inviter_id, expire_time, created_at, used, used_by, used_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                used = VALUES(used),
                used_by = VALUES(used_by),
                used_at = VALUES(used_at)
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
                    String usedByStr = rs.getString("used_by");
                    UUID usedBy = usedByStr != null ? UUID.fromString(usedByStr) : null;
                    
                    Invitation invitation = new Invitation(
                            rs.getString("code"),
                            rs.getString("channel_id"),
                            UUID.fromString(rs.getString("inviter_id")),
                            rs.getLong("expire_time"),
                            rs.getLong("created_at"),
                            rs.getBoolean("used"),
                            usedBy,
                            rs.getLong("used_at")
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
    public void markInvitationUsed(String code, UUID usedBy) throws DatabaseException {
        if (code == null) {
            return;
        }

        String sql = "UPDATE invitations SET used = TRUE, used_by = ?, used_at = ? WHERE code = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, usedBy != null ? usedBy.toString() : null);
            stmt.setLong(2, System.currentTimeMillis());
            stmt.setString(3, code);
            
            stmt.executeUpdate();
            logger.debug("Marked invitation {} as used by {}", code, usedBy);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to mark invitation as used", e);
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
