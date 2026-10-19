package com.nova.link.database;

import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import com.nova.link.database.dialect.MigrationDialect;
import com.nova.link.database.dialect.PostgreSQLDialect;
import com.zaxxer.hikari.HikariConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * PostgreSQL implementation of {@link DatabaseProvider} using HikariCP.
 *
 * <p>Extends {@link AbstractJdbcProvider} for connection-pool lifecycle and
 * migration running; this class supplies the PostgreSQL JDBC URL and the
 * PostgreSQL upsert form ({@code INSERT ... ON CONFLICT (pk) DO UPDATE SET
 * col = EXCLUDED.col}).
 *
 * <p>Requirements: 22.1, 22.5
 */
public class PostgreSQLProvider extends AbstractJdbcProvider {

    /** H1b: default JDBC TLS mode when no configuration is supplied. Fail-closed. */
    public static final String DEFAULT_SSL_MODE = "verify-ca";

    // Mirror of the PostgreSQL JDBC driver's supported sslmode values.
    // See https://jdbc.postgresql.org/documentation/ssl/
    private static final java.util.Set<String> SUPPORTED_SSL_MODES = java.util.Set.of(
            "disable", "allow", "prefer", "require", "verify-ca", "verify-full");

    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final String sslMode;
    private final boolean insecureAllowPlaintext;

    private final PostgreSQLDialect dialect = new PostgreSQLDialect();

    /**
     * Legacy constructor — kept for back-compat callers that predate H1b. Uses
     * the fail-closed default (sslMode=verify-ca, plaintext opt-in=false), so
     * any caller that still wants plaintext must migrate to the new constructor
     * and explicitly acknowledge it.
     */
    public PostgreSQLProvider(String host, int port, String database, String username, String password, int poolSize) {
        this(host, port, database, username, password, poolSize, DEFAULT_SSL_MODE, false);
    }

    public PostgreSQLProvider(String host, int port, String database, String username, String password,
                              int poolSize, String sslMode, boolean insecureAllowPlaintext) {
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.poolSize = poolSize;
        // PostgreSQL JDBC driver's sslmode values are lowercase; keep user
        // config case-insensitive by normalising to lowercase here.
        this.sslMode = sslMode == null || sslMode.isBlank()
                ? DEFAULT_SSL_MODE
                : sslMode.trim().toLowerCase(java.util.Locale.ROOT);
        this.insecureAllowPlaintext = insecureAllowPlaintext;
    }

    /**
     * Builds the JDBC URL with the configured {@link #sslMode} applied.
     *
     * <p>Fail-closed: when {@code sslmode=disable}, the operator must also set
     * {@code server.insecure-allow-plaintext: true} — the same opt-in used by
     * AUTH-002 for the TCP/WS listeners and by H1 for the MySQL JDBC layer.
     * Throwing here aborts startup before HikariCP opens any socket, so the
     * plaintext JDBC link cannot come up silently.
     *
     * <p>Note: PostgreSQL's {@code sslmode=prefer} (the driver default) falls
     * back to plaintext if the server does not support SSL, so it is NOT
     * fail-closed and not the default here. {@code verify-ca} is chosen as
     * the default to match H1's MySQL {@code REQUIRED}: it requires TLS and
     * validates the server certificate against the configured CA.
     */
    String buildJdbcUrl() {
        if (!SUPPORTED_SSL_MODES.contains(sslMode)) {
            throw new IllegalStateException("H1b: unsupported database.postgresql.ssl-mode: " + sslMode
                    + " (allowed: " + SUPPORTED_SSL_MODES + ")");
        }
        if ("disable".equals(sslMode) && !insecureAllowPlaintext) {
            throw new IllegalStateException(
                    "AUTH-002/H1b: PostgreSQL JDBC would start in plaintext (database.postgresql.ssl-mode=disable). "
                            + "The DB password and all chat data would traverse the network unencrypted. "
                            + "Either set database.postgresql.ssl-mode to require/verify-ca/verify-full "
                            + "(and provision server certs) or explicitly acknowledge the risk with "
                            + "server.insecure-allow-plaintext: true.");
        }

        StringBuilder url = new StringBuilder("jdbc:postgresql://")
                .append(host).append(':').append(port).append('/').append(database).append('?')
                .append("sslmode=").append(sslMode);
        if ("disable".equals(sslMode)) {
            url.append("&ssl=false");
        } else {
            url.append("&ssl=true");
        }
        return url.toString();
    }

    @Override
    protected HikariConfig buildHikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(buildJdbcUrl());
        // Pin the driver explicitly. Without this HikariCP falls back to
        // DriverManager.getDriver(url), whose driver scan runs once per JVM
        // using the first caller's context classloader -- under a plugin
        // classloader that yields "Failed to get driver instance".
        config.setDriverClassName("org.postgresql.Driver");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(2);
        config.setIdleTimeout(300000);
        config.setConnectionTimeout(10000);
        config.setPoolName("NovaLink-PostgreSQL-Pool");

        // PostgreSQL optimizations
        config.addDataSourceProperty("preparedStatementCacheQueries", "256");
        config.addDataSourceProperty("preparedStatementCacheSizeMiB", "5");
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
            ON CONFLICT (player_id) DO UPDATE SET
                player_name = EXCLUDED.player_name,
                client_id = EXCLUDED.client_id,
                current_world = EXCLUDED.current_world,
                joined_channels = EXCLUDED.joined_channels,
                active_channel = EXCLUDED.active_channel,
                platform = EXCLUDED.platform,
                dm_enabled = EXCLUDED.dm_enabled,
                last_seen = EXCLUDED.last_seen
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

        try (Connection conn = dataSource.getConnection()) {
            return loadPlayerState(conn, playerId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load player state", e);
        }
    }

    /**
     * Single-connection implementation of {@link #loadPlayerState(UUID)}. Both
     * the public entry point and {@link #getAllPlayerStates()} route through
     * here so the mute lookup reuses the caller's connection instead of
     * borrowing a second one — a nested borrow blocks forever on a pool of
     * size 1 until the connection timeout expires.
     */
    private Optional<PlayerState> loadPlayerState(Connection conn, UUID playerId) throws SQLException {
        String sql = "SELECT * FROM players WHERE player_id = ?";

        PlayerState state = null;
        // The player row is fully hydrated and the ResultSet closed before the
        // mute query runs, so this connection never has two open result sets.
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    state = new PlayerState(playerId);
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
                }
            }
        }

        if (state == null) {
            return Optional.empty();
        }

        // Load mutes on the same connection (no nested borrow).
        Map<String, MuteInfo> muteMap = new HashMap<>();
        for (MuteInfo mute : loadMutes(conn, playerId)) {
            muteMap.put(mute.getChannelId() != null ? mute.getChannelId() : GLOBAL_KEY, mute);
        }
        state.setMutes(muteMap);

        return Optional.of(state);
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
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM mutes WHERE player_id = ?")) {
                    stmt.setString(1, playerId.toString());
                    stmt.executeUpdate();
                }

                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM bans WHERE player_id = ?")) {
                    stmt.setString(1, playerId.toString());
                    stmt.executeUpdate();
                }

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

        try (Connection conn = dataSource.getConnection()) {
            List<UUID> playerIds = new ArrayList<>();
            // Collect the ids first and close the cursor before hydrating each
            // row: the per-player hydration runs further queries on this same
            // connection, and a pool of size 1 cannot supply a second one.
            try (PreparedStatement stmt = conn.prepareStatement("SELECT player_id FROM players");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    playerIds.add(UUID.fromString(rs.getString("player_id")));
                }
            }

            for (UUID playerId : playerIds) {
                loadPlayerState(conn, playerId).ifPresent(states::add);
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
            ON CONFLICT (channel_id) DO UPDATE SET
                display_name = EXCLUDED.display_name,
                permission = EXCLUDED.permission,
                max_capacity = EXCLUDED.max_capacity,
                allowed_worlds = EXCLUDED.allowed_worlds,
                password = EXCLUDED.password,
                owner_id = EXCLUDED.owner_id,
                slow_mode_seconds = EXCLUDED.slow_mode_seconds
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

        try (Connection conn = dataSource.getConnection()) {
            return loadChannel(conn, channelId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load channel", e);
        }
    }

    /**
     * Single-connection implementation of {@link #loadChannel(String)}, shared
     * with {@link #getAllChannels()} so hydrating a channel never borrows a
     * second pooled connection while the caller still holds one.
     */
    private Optional<Channel> loadChannel(Connection conn, String channelId) throws SQLException {
        String sql = "SELECT * FROM channels WHERE channel_id = ?";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
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
        }

        return Optional.empty();
    }

    @Override
    public void deleteChannel(String channelId) throws DatabaseException {
        if (channelId == null) {
            return;
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM channels WHERE channel_id = ?")) {

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

        try (Connection conn = dataSource.getConnection()) {
            List<String> channelIds = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement("SELECT channel_id FROM channels");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    channelIds.add(rs.getString("channel_id"));
                }
            }

            for (String channelId : channelIds) {
                loadChannel(conn, channelId).ifPresent(channels::add);
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

        // DELETE + INSERT in one transaction (see MySQLProvider.saveMute for
        // the rationale; the non-atomic delete-then-insert pattern races under
        // concurrency and trips the UNIQUE(player_id, channel_id) constraint).
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

        try (Connection conn = dataSource.getConnection()) {
            return loadMutes(conn, playerId);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load mutes", e);
        }
    }

    /**
     * Single-connection implementation of {@link #loadMutes(UUID)}. Exists so
     * aggregating reads ({@code loadPlayerState}) can reuse the connection they
     * already hold instead of borrowing a second one.
     */
    private List<MuteInfo> loadMutes(Connection conn, UUID playerId) throws SQLException {
        List<MuteInfo> mutes = new ArrayList<>();
        String sql = "SELECT * FROM mutes WHERE player_id = ?";

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerId.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    mutes.add(new MuteInfo(
                            rs.getString("channel_id"),
                            rs.getLong("expire_time"),
                            rs.getString("reason"),
                            parseUuid(rs.getString("operator_id")),
                            rs.getLong("created_at")
                    ));
                }
            }
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
        String sql = "INSERT INTO notifications (title, message, level, created_at, read, recipient) "
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
                ? "SELECT * FROM notifications WHERE read = FALSE ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
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
        // Deletes notification_read children first, then notifications, in one
        // transaction — see AbstractJdbcProvider for why the child-first wipe
        // is required (no FK ON DELETE CASCADE on notification_read).
        return clearAllNotificationsTransactional();
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

    @Override
    public int countNotifications(boolean unreadOnly) throws DatabaseException {
        String sql = unreadOnly
                ? "SELECT COUNT(*) FROM notifications WHERE read = FALSE"
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
    // See SQLiteProvider for the semantic model. PostgreSQL uses ON CONFLICT
    // for upsert, same as SQLite 3.24+.

    private static final String SQL_GET_PER_USER_UNREAD_PG = """
            SELECT n.id, n.title, n.message, n.level, n.created_at, n.read, n.recipient
            FROM notifications n
            LEFT JOIN notification_read nr ON nr.notification_id = n.id AND nr.user_id = ?
            WHERE (n.recipient IS NULL OR LOWER(n.recipient) = LOWER(?))
              AND (n.read = TRUE OR (nr.read IS NOT NULL AND nr.read = TRUE)) = FALSE
            ORDER BY n.created_at DESC, n.id DESC
            LIMIT ? OFFSET ?
            """;

    private static final String SQL_GET_PER_USER_ALL_PG = """
            SELECT n.id, n.title, n.message, n.level, n.created_at,
                   (n.read = TRUE OR (nr.read IS NOT NULL AND nr.read = TRUE)) AS read,
                   n.recipient
            FROM notifications n
            LEFT JOIN notification_read nr ON nr.notification_id = n.id AND nr.user_id = ?
            WHERE n.recipient IS NULL OR LOWER(n.recipient) = LOWER(?)
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
        String sql = unreadOnly ? SQL_GET_PER_USER_UNREAD_PG : SQL_GET_PER_USER_ALL_PG;
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
        String sql = "INSERT INTO notification_read (notification_id, user_id, read, read_at) "
                + "VALUES (?, ?, TRUE, ?) "
                + "ON CONFLICT(notification_id, user_id) DO UPDATE SET read = TRUE, read_at = EXCLUDED.read_at";
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
        String sql = "INSERT INTO notification_read (notification_id, user_id, read, read_at) "
                + "SELECT n.id, ?, TRUE, ? FROM notifications n "
                + "WHERE (n.recipient IS NULL OR LOWER(n.recipient) = LOWER(?)) "
                + "AND NOT EXISTS ("
                + "  SELECT 1 FROM notification_read nr "
                + "  WHERE nr.notification_id = n.id AND nr.user_id = ? AND nr.read = TRUE"
                + ") "
                + "ON CONFLICT(notification_id, user_id) DO UPDATE SET read = TRUE, read_at = EXCLUDED.read_at";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            stmt.setString(1, userId);
            stmt.setLong(2, now);
            stmt.setString(3, userId);
            stmt.setString(4, userId);
            int count = stmt.executeUpdate();
            if (count > 0) {
                logger.debug("Marked {} notifications as read for user {}", count, userId);
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
                + "WHERE (n.recipient IS NULL OR LOWER(n.recipient) = LOWER(?)) "
                + "AND n.read = FALSE "
                + "AND NOT EXISTS ("
                + "  SELECT 1 FROM notification_read nr "
                + "  WHERE nr.notification_id = n.id AND nr.user_id = ? AND nr.read = TRUE"
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
                    + "WHERE (n.recipient IS NULL OR LOWER(n.recipient) = LOWER(?)) "
                    + "AND n.read = FALSE "
                    + "AND NOT EXISTS ("
                    + "  SELECT 1 FROM notification_read nr "
                    + "  WHERE nr.notification_id = n.id AND nr.user_id = ? AND nr.read = TRUE"
                    + ")";
        } else {
            sql = "SELECT COUNT(*) FROM notifications n "
                    + "WHERE n.recipient IS NULL OR LOWER(n.recipient) = LOWER(?)";
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
        // Deletes the directed notifications' read-state children first, then
        // the notifications, in one transaction — see AbstractJdbcProvider.
        // LOWER() on both sides keeps recipient matching case-insensitive,
        // end-to-end with the WS delivery path.
        return clearDirectedNotificationsTransactional(userId);
    }

    @Override
    public int clearBroadcastNotifications() throws DatabaseException {
        // Only broadcast notifications (recipient IS NULL) are deleted. Directed
        // notifications (non-null recipient) are preserved so the SUPER_ADMIN
        // global-retention path does not wipe other admins' inboxes.
        return clearBroadcastNotificationsTransactional();
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
            ON CONFLICT (code) DO UPDATE SET
                channel_id = EXCLUDED.channel_id,
                inviter_id = EXCLUDED.inviter_id,
                expire_time = EXCLUDED.expire_time,
                created_at = EXCLUDED.created_at,
                used = EXCLUDED.used,
                used_by = EXCLUDED.used_by,
                used_at = EXCLUDED.used_at,
                max_uses = EXCLUDED.max_uses,
                used_count = EXCLUDED.used_count,
                revoked_at = EXCLUDED.revoked_at
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
        // becomes TRUE on the first claim) and multi-use paths. PostgreSQL's
        // boolean column accepts a boolean expression directly.
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

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM invitations WHERE code = ?")) {

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
        return "PostgreSQL";
    }
}
