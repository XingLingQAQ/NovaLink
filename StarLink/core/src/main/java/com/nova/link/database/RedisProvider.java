package com.nova.link.database;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Redis implementation of DatabaseProvider for caching.
 * Uses JSON serialization for complex objects.
 * 
 * Requirements: 22.2 - Redis caching for player online status and channel members
 */
public class RedisProvider implements DatabaseProvider {

    private static final Logger logger = LoggerFactory.getLogger(RedisProvider.class);
    
    private static final String KEY_PREFIX = "novalink:";
    private static final String PLAYER_PREFIX = KEY_PREFIX + "player:";
    private static final String CHANNEL_PREFIX = KEY_PREFIX + "channel:";
    private static final String MUTE_PREFIX = KEY_PREFIX + "mute:";
    private static final String BAN_PREFIX = KEY_PREFIX + "ban:";
    private static final String INVITATION_PREFIX = KEY_PREFIX + "invitation:";
    private static final String NOTIFICATION_PREFIX = KEY_PREFIX + "notification:";
    private static final String NOTIFICATION_INDEX = KEY_PREFIX + "notifications";
    private static final String PLAYER_INDEX = KEY_PREFIX + "players";
    private static final String CHANNEL_INDEX = KEY_PREFIX + "channels";
    private static final String MESSAGE_PREFIX = KEY_PREFIX + "message:";
    private static final String MESSAGE_INDEX = KEY_PREFIX + "messages";
    private static final String MESSAGE_SEQ = KEY_PREFIX + "message:seq";
    private static final String ANNOUNCEMENT_PREFIX = KEY_PREFIX + "announcement:";
    private static final String ANNOUNCEMENT_INDEX = KEY_PREFIX + "announcements";
    private static final String WEBHOOK_PREFIX = KEY_PREFIX + "webhook:";
    private static final String WEBHOOK_INDEX = KEY_PREFIX + "webhooks";

    private final String host;
    private final int port;
    private final String password;
    private final int database;

    private JedisPool jedisPool;
    private final Gson gson;

    public RedisProvider(String host, int port, String password, int database) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.database = database;
        this.gson = new GsonBuilder()
                .enableComplexMapKeySerialization()
                .create();
    }

    public RedisProvider(String host, int port, String password) {
        this(host, port, password, 0);
    }

    @Override
    public void initialize() throws DatabaseException {
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);

            if (password != null && !password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, password, database);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, null, database);
            }

            // Test connection
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }

            logger.info("RedisProvider initialized - connected to {}:{}", host, port);
        } catch (Exception e) {
            throw new DatabaseException("Failed to initialize Redis connection", e);
        }
    }

    @Override
    public void shutdown() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            logger.info("RedisProvider shutdown");
        }
    }

    @Override
    public boolean isConnected() {
        if (jedisPool == null || jedisPool.isClosed()) {
            return false;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Player State Operations ====================

    @Override
    public void savePlayerState(PlayerState state) throws DatabaseException {
        if (state == null || state.getPlayerId() == null) {
            throw new DatabaseException("Player state and player ID cannot be null");
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = PLAYER_PREFIX + state.getPlayerId().toString();
            String json = gson.toJson(new PlayerStateDto(state));
            jedis.set(key, json);
            jedis.sadd(PLAYER_INDEX, state.getPlayerId().toString());
            logger.debug("Saved player state for: {}", state.getPlayerId());
        } catch (Exception e) {
            throw new DatabaseException("Failed to save player state to Redis", e);
        }
    }

    @Override
    public Optional<PlayerState> loadPlayerState(UUID playerId) throws DatabaseException {
        if (playerId == null) {
            return Optional.empty();
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = PLAYER_PREFIX + playerId.toString();
            String json = jedis.get(key);
            if (json == null) {
                return Optional.empty();
            }
            PlayerStateDto dto = gson.fromJson(json, PlayerStateDto.class);
            return Optional.of(dto.toPlayerState());
        } catch (Exception e) {
            throw new DatabaseException("Failed to load player state from Redis", e);
        }
    }

    @Override
    public void deletePlayerState(UUID playerId) throws DatabaseException {
        if (playerId == null) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = PLAYER_PREFIX + playerId.toString();
            jedis.del(key);
            jedis.srem(PLAYER_INDEX, playerId.toString());
            // Also delete mutes and bans
            jedis.del(MUTE_PREFIX + playerId.toString());
            jedis.del(BAN_PREFIX + playerId.toString());
            logger.debug("Deleted player state for: {}", playerId);
        } catch (Exception e) {
            throw new DatabaseException("Failed to delete player state from Redis", e);
        }
    }

    @Override
    public List<PlayerState> getAllPlayerStates() throws DatabaseException {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> playerIds = jedis.smembers(PLAYER_INDEX);
            List<PlayerState> states = new ArrayList<>();
            for (String playerId : playerIds) {
                loadPlayerState(UUID.fromString(playerId)).ifPresent(states::add);
            }
            return states;
        } catch (Exception e) {
            throw new DatabaseException("Failed to load all player states from Redis", e);
        }
    }

    // ==================== Channel Operations ====================

    @Override
    public void saveChannel(Channel channel) throws DatabaseException {
        if (channel == null || channel.getId() == null) {
            throw new DatabaseException("Channel and channel ID cannot be null");
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = CHANNEL_PREFIX + channel.getId();
            String json = gson.toJson(new ChannelDto(channel));
            jedis.set(key, json);
            jedis.sadd(CHANNEL_INDEX, channel.getId());
            logger.debug("Saved channel: {}", channel.getId());
        } catch (Exception e) {
            throw new DatabaseException("Failed to save channel to Redis", e);
        }
    }

    @Override
    public Optional<Channel> loadChannel(String channelId) throws DatabaseException {
        if (channelId == null) {
            return Optional.empty();
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = CHANNEL_PREFIX + channelId;
            String json = jedis.get(key);
            if (json == null) {
                return Optional.empty();
            }
            ChannelDto dto = gson.fromJson(json, ChannelDto.class);
            return Optional.of(dto.toChannel());
        } catch (Exception e) {
            throw new DatabaseException("Failed to load channel from Redis", e);
        }
    }

    @Override
    public void deleteChannel(String channelId) throws DatabaseException {
        if (channelId == null) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = CHANNEL_PREFIX + channelId;
            jedis.del(key);
            jedis.srem(CHANNEL_INDEX, channelId);
            logger.debug("Deleted channel: {}", channelId);
        } catch (Exception e) {
            throw new DatabaseException("Failed to delete channel from Redis", e);
        }
    }

    @Override
    public List<Channel> getAllChannels() throws DatabaseException {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> channelIds = jedis.smembers(CHANNEL_INDEX);
            List<Channel> channels = new ArrayList<>();
            for (String channelId : channelIds) {
                loadChannel(channelId).ifPresent(channels::add);
            }
            return channels;
        } catch (Exception e) {
            throw new DatabaseException("Failed to load all channels from Redis", e);
        }
    }

    // ==================== Mute Operations ====================

    @Override
    public void saveMute(UUID playerId, MuteInfo muteInfo) throws DatabaseException {
        if (playerId == null || muteInfo == null) {
            throw new DatabaseException("Player ID and mute info cannot be null");
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = MUTE_PREFIX + playerId.toString();
            String field = muteInfo.getChannelId() != null ? muteInfo.getChannelId() : "__global__";
            String json = gson.toJson(new MuteInfoDto(muteInfo));
            jedis.hset(key, field, json);
            
            // Set expiration if mute has expire time
            if (muteInfo.getExpireTime() > 0) {
                long ttl = (muteInfo.getExpireTime() - System.currentTimeMillis()) / 1000;
                if (ttl > 0) {
                    // Don't set TTL on the hash, we'll clean up manually
                }
            }
            
            logger.debug("Saved mute for player {} in channel {}", playerId, muteInfo.getChannelId());
        } catch (Exception e) {
            throw new DatabaseException("Failed to save mute to Redis", e);
        }
    }

    @Override
    public List<MuteInfo> loadMutes(UUID playerId) throws DatabaseException {
        if (playerId == null) {
            return Collections.emptyList();
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = MUTE_PREFIX + playerId.toString();
            Map<String, String> mutes = jedis.hgetAll(key);
            return mutes.values().stream()
                    .map(json -> gson.fromJson(json, MuteInfoDto.class).toMuteInfo())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new DatabaseException("Failed to load mutes from Redis", e);
        }
    }

    @Override
    public void deleteMute(UUID playerId, String channelId) throws DatabaseException {
        if (playerId == null) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = MUTE_PREFIX + playerId.toString();
            String field = channelId != null ? channelId : "__global__";
            jedis.hdel(key, field);
            logger.debug("Deleted mute for player {} in channel {}", playerId, channelId);
        } catch (Exception e) {
            throw new DatabaseException("Failed to delete mute from Redis", e);
        }
    }

    @Override
    public int cleanupExpiredMutes() throws DatabaseException {
        // SCAN over the mute hashes rather than PLAYER_INDEX: a mute can exist
        // for a player whose state was never persisted, so the index may miss it.
        // Mirrors the SCAN pattern used by getAllActiveMutes.
        int count = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            long now = System.currentTimeMillis();
            ScanParams params = new ScanParams().match(MUTE_PREFIX + "*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                cursor = scan.getCursor();
                for (String key : scan.getResult()) {
                    UUID playerId;
                    try {
                        playerId = UUID.fromString(key.substring(MUTE_PREFIX.length()));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    Map<String, String> mutes = jedis.hgetAll(key);

                    for (Map.Entry<String, String> entry : mutes.entrySet()) {
                        MuteInfoDto dto = gson.fromJson(entry.getValue(), MuteInfoDto.class);
                        if (dto.expireTime > 0 && now > dto.expireTime) {
                            jedis.hdel(key, entry.getKey());
                            count++;
                        }
                    }
                }
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

            if (count > 0) {
                logger.debug("Cleaned up {} expired mutes", count);
            }
        } catch (Exception e) {
            throw new DatabaseException("Failed to cleanup expired mutes from Redis", e);
        }
        return count;
    }

    @Override
    public Map<UUID, List<MuteInfo>> getAllActiveMutes() throws DatabaseException {
        // SCAN over the mute hashes rather than PLAYER_INDEX: a mute can exist
        // for a player whose state was never persisted, so the index may miss it.
        Map<UUID, List<MuteInfo>> result = new HashMap<>();
        try (Jedis jedis = jedisPool.getResource()) {
            long now = System.currentTimeMillis();
            ScanParams params = new ScanParams().match(MUTE_PREFIX + "*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                cursor = scan.getCursor();
                for (String key : scan.getResult()) {
                    UUID playerId;
                    try {
                        playerId = UUID.fromString(key.substring(MUTE_PREFIX.length()));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    for (String json : jedis.hgetAll(key).values()) {
                        MuteInfo mute = gson.fromJson(json, MuteInfoDto.class).toMuteInfo();
                        if (mute.getExpireTime() <= 0 || mute.getExpireTime() >= now) {
                            result.computeIfAbsent(playerId, k -> new ArrayList<>()).add(mute);
                        }
                    }
                }
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (Exception e) {
            throw new DatabaseException("Failed to load active mutes from Redis", e);
        }
        return result;
    }

    // ==================== Ban Operations ====================

    @Override
    public void saveBan(UUID playerId, BanInfo banInfo) throws DatabaseException {
        if (playerId == null || banInfo == null) {
            throw new DatabaseException("Player ID and ban info cannot be null");
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = BAN_PREFIX + playerId.toString();
            String field = banInfo.getChannelId() != null ? banInfo.getChannelId() : "__global__";
            String json = gson.toJson(new BanInfoDto(banInfo));
            jedis.hset(key, field, json);

            logger.debug("Saved ban for player {} in channel {}", playerId, banInfo.getChannelId());
        } catch (Exception e) {
            throw new DatabaseException("Failed to save ban to Redis", e);
        }
    }

    @Override
    public List<BanInfo> loadBans(UUID playerId) throws DatabaseException {
        if (playerId == null) {
            return Collections.emptyList();
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = BAN_PREFIX + playerId.toString();
            Map<String, String> bans = jedis.hgetAll(key);
            return bans.values().stream()
                    .map(json -> gson.fromJson(json, BanInfoDto.class).toBanInfo())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new DatabaseException("Failed to load bans from Redis", e);
        }
    }

    @Override
    public void deleteBan(UUID playerId, String channelId) throws DatabaseException {
        if (playerId == null) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = BAN_PREFIX + playerId.toString();
            String field = channelId != null ? channelId : "__global__";
            jedis.hdel(key, field);
            logger.debug("Deleted ban for player {} in channel {}", playerId, channelId);
        } catch (Exception e) {
            throw new DatabaseException("Failed to delete ban from Redis", e);
        }
    }

    @Override
    public int cleanupExpiredBans() throws DatabaseException {
        // SCAN over the ban hashes rather than PLAYER_INDEX: a ban can exist
        // for a player whose state was never persisted, so the index may miss it.
        // Mirrors the SCAN pattern used by getAllActiveBans.
        int count = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            long now = System.currentTimeMillis();
            ScanParams params = new ScanParams().match(BAN_PREFIX + "*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                cursor = scan.getCursor();
                for (String key : scan.getResult()) {
                    UUID playerId;
                    try {
                        playerId = UUID.fromString(key.substring(BAN_PREFIX.length()));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    Map<String, String> bans = jedis.hgetAll(key);

                    for (Map.Entry<String, String> entry : bans.entrySet()) {
                        BanInfoDto dto = gson.fromJson(entry.getValue(), BanInfoDto.class);
                        if (dto.expireTime > 0 && now > dto.expireTime) {
                            jedis.hdel(key, entry.getKey());
                            count++;
                        }
                    }
                }
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));

            if (count > 0) {
                logger.debug("Cleaned up {} expired bans", count);
            }
        } catch (Exception e) {
            throw new DatabaseException("Failed to cleanup expired bans from Redis", e);
        }
        return count;
    }

    @Override
    public Map<UUID, List<BanInfo>> getAllActiveBans() throws DatabaseException {
        // Mirrors getAllActiveMutes: SCAN the ban hashes directly.
        Map<UUID, List<BanInfo>> result = new HashMap<>();
        try (Jedis jedis = jedisPool.getResource()) {
            long now = System.currentTimeMillis();
            ScanParams params = new ScanParams().match(BAN_PREFIX + "*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                cursor = scan.getCursor();
                for (String key : scan.getResult()) {
                    UUID playerId;
                    try {
                        playerId = UUID.fromString(key.substring(BAN_PREFIX.length()));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    for (String json : jedis.hgetAll(key).values()) {
                        BanInfo ban = gson.fromJson(json, BanInfoDto.class).toBanInfo();
                        if (ban.getExpireTime() <= 0 || ban.getExpireTime() >= now) {
                            result.computeIfAbsent(playerId, k -> new ArrayList<>()).add(ban);
                        }
                    }
                }
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        } catch (Exception e) {
            throw new DatabaseException("Failed to load active bans from Redis", e);
        }
        return result;
    }

    // ==================== Notification Operations ====================

    @Override
    public void saveNotification(Notification notification) throws DatabaseException {
        if (notification == null) {
            throw new DatabaseException("Notification cannot be null");
        }

        try (Jedis jedis = jedisPool.getResource()) {
            // Generate an id using INCR on a dedicated counter.
            long id = jedis.incr(KEY_PREFIX + "notification:seq");
            try {
                java.lang.reflect.Field f = Notification.class.getDeclaredField("id");
                f.setAccessible(true);
                f.setLong(notification, id);
            } catch (ReflectiveOperationException e) {
                logger.debug("Could not stamp notification id: {}", e.getMessage());
            }

            String key = NOTIFICATION_PREFIX + id;
            String json = gson.toJson(new NotificationDto(notification));
            jedis.set(key, json);
            // Index by createdAt in a ZSET so we can paginate in descending order.
            jedis.zadd(NOTIFICATION_INDEX, notification.getCreatedAt(), String.valueOf(id));
            logger.debug("Saved notification id={} title={}", id, notification.getTitle());
        } catch (Exception e) {
            throw new DatabaseException("Failed to save notification to Redis", e);
        }
    }

    @Override
    public List<Notification> getNotifications(int offset, int limit, boolean unreadOnly) throws DatabaseException {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            // ZREVRANGEBYSCORE gives descending-by-score (createdAt) ordering.
            // Use +inf/-inf to fetch the whole indexed range, then slice in-memory
            // so the unreadOnly filter + offset/limit are honored consistently.
            List<String> ids = jedis.zrevrangeByScore(NOTIFICATION_INDEX, "+inf", "-inf");
            List<Notification> result = new ArrayList<>();
            int collected = 0;
            int skipped = 0;
            for (String idStr : ids) {
                String json = jedis.get(NOTIFICATION_PREFIX + idStr);
                if (json == null) {
                    continue;
                }
                NotificationDto dto = gson.fromJson(json, NotificationDto.class);
                if (unreadOnly && dto.read) {
                    continue;
                }
                if (skipped < offset) {
                    skipped++;
                    continue;
                }
                result.add(dto.toNotification());
                collected++;
                if (collected >= limit) {
                    break;
                }
            }
            return result;
        } catch (Exception e) {
            throw new DatabaseException("Failed to load notifications from Redis", e);
        }
    }

    @Override
    public void markNotificationRead(long id) throws DatabaseException {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = NOTIFICATION_PREFIX + id;
            String json = jedis.get(key);
            if (json != null) {
                NotificationDto dto = gson.fromJson(json, NotificationDto.class);
                dto.read = true;
                jedis.set(key, gson.toJson(dto));
                logger.debug("Marked notification {} as read", id);
            }
        } catch (Exception e) {
            throw new DatabaseException("Failed to mark notification as read in Redis", e);
        }
    }

    @Override
    public void markAllNotificationsRead() throws DatabaseException {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> ids = jedis.zrangeByScore(NOTIFICATION_INDEX, "-inf", "+inf");
            int count = 0;
            for (String idStr : ids) {
                String key = NOTIFICATION_PREFIX + idStr;
                String json = jedis.get(key);
                if (json != null) {
                    NotificationDto dto = gson.fromJson(json, NotificationDto.class);
                    if (!dto.read) {
                        dto.read = true;
                        jedis.set(key, gson.toJson(dto));
                        count++;
                    }
                }
            }
            if (count > 0) {
                logger.debug("Marked {} notifications as read", count);
            }
        } catch (Exception e) {
            throw new DatabaseException("Failed to mark all notifications as read in Redis", e);
        }
    }

    @Override
    public int clearNotifications() throws DatabaseException {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> ids = jedis.zrangeByScore(NOTIFICATION_INDEX, "-inf", "+inf");
            int count = 0;
            for (String idStr : ids) {
                jedis.del(NOTIFICATION_PREFIX + idStr);
                count++;
            }
            jedis.del(NOTIFICATION_INDEX);
            if (count > 0) {
                logger.debug("Cleared {} notifications", count);
            }
            return count;
        } catch (Exception e) {
            throw new DatabaseException("Failed to clear notifications from Redis", e);
        }
    }

    @Override
    public int getUnreadCount() throws DatabaseException {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> ids = jedis.zrangeByScore(NOTIFICATION_INDEX, "-inf", "+inf");
            int count = 0;
            for (String idStr : ids) {
                String json = jedis.get(NOTIFICATION_PREFIX + idStr);
                if (json != null) {
                    NotificationDto dto = gson.fromJson(json, NotificationDto.class);
                    if (!dto.read) {
                        count++;
                    }
                }
            }
            return count;
        } catch (Exception e) {
            throw new DatabaseException("Failed to get unread count from Redis", e);
        }
    }

    @Override
    public int countNotifications(boolean unreadOnly) throws DatabaseException {
        if (unreadOnly) {
            return getUnreadCount();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            return (int) jedis.zcard(NOTIFICATION_INDEX);
        } catch (Exception e) {
            throw new DatabaseException("Failed to count notifications in Redis", e);
        }
    }

    // ==================== Invitation Operations ====================

    @Override
    public void saveInvitation(Invitation invitation) throws DatabaseException {
        if (invitation == null || invitation.getCode() == null) {
            throw new DatabaseException("Invitation and code cannot be null");
        }

        // Compute TTL up front. An already-expired invitation (ttl <= 0) must
        // never be persisted as a non-expiring key — drop it instead.
        long ttl = (invitation.getExpireTime() - System.currentTimeMillis()) / 1000;
        if (ttl <= 0) {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(INVITATION_PREFIX + invitation.getCode());
            } catch (Exception e) {
                throw new DatabaseException("Failed to drop expired invitation from Redis", e);
            }
            logger.debug("Skipped persisting already-expired invitation: {}", invitation.getCode());
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = INVITATION_PREFIX + invitation.getCode();
            String json = gson.toJson(new InvitationDto(invitation));
            jedis.set(key, json);
            jedis.expire(key, ttl);
            logger.debug("Saved invitation: {}", invitation.getCode());
        } catch (Exception e) {
            throw new DatabaseException("Failed to save invitation to Redis", e);
        }
    }

    @Override
    public Optional<Invitation> loadInvitation(String code) throws DatabaseException {
        if (code == null) {
            return Optional.empty();
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = INVITATION_PREFIX + code;
            String json = jedis.get(key);
            if (json == null) {
                return Optional.empty();
            }
            InvitationDto dto = gson.fromJson(json, InvitationDto.class);
            return Optional.of(dto.toInvitation());
        } catch (Exception e) {
            throw new DatabaseException("Failed to load invitation from Redis", e);
        }
    }

    @Override
    public void markInvitationUsed(String code, UUID usedBy) throws DatabaseException {
        if (code == null) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = INVITATION_PREFIX + code;
            // Check-and-set via WATCH/MULTI/EXEC so a concurrent accept cannot
            // both flip used=false -> used=true. If the value changed between
            // watch and commit (another thread marked it first), the EXEC
            // aborts and we treat the invitation as already used.
            jedis.watch(key);
            try {
                String json = jedis.get(key);
                if (json == null) {
                    jedis.unwatch();
                    return;
                }
                InvitationDto dto = gson.fromJson(json, InvitationDto.class);
                if (dto.used) {
                    // Already used by another thread — do not overwrite.
                    jedis.unwatch();
                    logger.debug("Invitation {} already marked used; skipping", code);
                    return;
                }
                dto.used = true;
                dto.usedBy = usedBy != null ? usedBy.toString() : null;
                dto.usedAt = System.currentTimeMillis();
                // Read TTL before entering MULTI mode — commands issued directly
                // on the connection during a transaction are not safely usable.
                long ttl = jedis.ttl(key);
                Transaction tx = jedis.multi();
                tx.set(key, gson.toJson(dto));
                // Preserve the remaining TTL across the rewrite.
                if (ttl > 0) {
                    tx.expire(key, ttl);
                }
                java.util.List<Object> execResult = tx.exec();
                if (execResult == null || execResult.isEmpty()) {
                    // EXEC aborted: the key changed underneath us — another
                    // thread won the race.
                    logger.debug("Concurrent markInvitationUsed on {}; aborted in favor of earlier writer", code);
                    return;
                }
                logger.debug("Marked invitation {} as used by {}", code, usedBy);
            } catch (Exception e) {
                jedis.unwatch();
                throw e;
            }
        } catch (Exception e) {
            throw new DatabaseException("Failed to mark invitation as used in Redis", e);
        }
    }

    @Override
    public void deleteInvitation(String code) throws DatabaseException {
        if (code == null) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = INVITATION_PREFIX + code;
            jedis.del(key);
            logger.debug("Deleted invitation: {}", code);
        } catch (Exception e) {
            throw new DatabaseException("Failed to delete invitation from Redis", e);
        }
    }

    @Override
    public int cleanupExpiredInvitations() throws DatabaseException {
        // Redis TTL handles this automatically for invitations
        return 0;
    }

    // ==================== Message History Operations (schema v5) ====================
    //
    // Messages are stored as JSON strings under novalink:message:<id> and
    // indexed by timestamp in the novalink:messages ZSET. Range scans use
    // ZREVRANGEBYSCORE (newest first); non-time filters are applied in-memory
    // while walking the range, which is acceptable for the panel's page sizes.

    @Override
    public void saveMessage(ChatMessageRecord message) throws DatabaseException {
        if (message == null) {
            throw new DatabaseException("Message cannot be null");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            long id = jedis.incr(MESSAGE_SEQ);
            message.setId(id);
            jedis.set(MESSAGE_PREFIX + id, gson.toJson(new ChatMessageDto(message)));
            jedis.zadd(MESSAGE_INDEX, message.getTimestamp(), String.valueOf(id));
            logger.debug("Saved message id={} channel={}", id, message.getChannelId());
        } catch (Exception e) {
            throw new DatabaseException("Failed to save message to Redis", e);
        }
    }

    @Override
    public List<ChatMessageRecord> searchMessages(MessageFilter filter, int offset, int limit) throws DatabaseException {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        try (Jedis jedis = jedisPool.getResource()) {
            List<ChatMessageRecord> result = new ArrayList<>();
            int skipped = 0;
            for (String idStr : messageIdsNewestFirst(jedis, filter)) {
                ChatMessageRecord record = loadMessageRecord(jedis, idStr);
                if (record == null || !filter.matches(record)) {
                    continue;
                }
                if (skipped < offset) {
                    skipped++;
                    continue;
                }
                result.add(record);
                if (result.size() >= limit) {
                    break;
                }
            }
            return result;
        } catch (Exception e) {
            throw new DatabaseException("Failed to search messages in Redis", e);
        }
    }

    @Override
    public int countMessages(MessageFilter filter) throws DatabaseException {
        try (Jedis jedis = jedisPool.getResource()) {
            int count = 0;
            for (String idStr : messageIdsNewestFirst(jedis, filter)) {
                ChatMessageRecord record = loadMessageRecord(jedis, idStr);
                if (record != null && filter.matches(record)) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            throw new DatabaseException("Failed to count messages in Redis", e);
        }
    }

    @Override
    public int cleanupMessagesBefore(long cutoffTimestamp) throws DatabaseException {
        try (Jedis jedis = jedisPool.getResource()) {
            // "(cutoff" = exclusive upper bound: remove strictly-older rows only.
            List<String> expired = jedis.zrangeByScore(MESSAGE_INDEX, "-inf", "(" + cutoffTimestamp);
            for (String idStr : expired) {
                jedis.del(MESSAGE_PREFIX + idStr);
            }
            if (!expired.isEmpty()) {
                jedis.zremrangeByScore(MESSAGE_INDEX, "-inf", "(" + cutoffTimestamp);
                logger.debug("Cleaned up {} expired messages", expired.size());
            }
            return expired.size();
        } catch (Exception e) {
            throw new DatabaseException("Failed to cleanup expired messages from Redis", e);
        }
    }

    /**
     * Ids from the time index, newest first, pre-narrowed by the filter's
     * from/to bounds so the ZSET does the time filtering.
     */
    private List<String> messageIdsNewestFirst(Jedis jedis, MessageFilter filter) {
        String max = filter.getTo() != null ? String.valueOf(filter.getTo()) : "+inf";
        String min = filter.getFrom() != null ? String.valueOf(filter.getFrom()) : "-inf";
        return jedis.zrevrangeByScore(MESSAGE_INDEX, max, min);
    }

    private ChatMessageRecord loadMessageRecord(Jedis jedis, String idStr) {
        String json = jedis.get(MESSAGE_PREFIX + idStr);
        return json != null ? gson.fromJson(json, ChatMessageDto.class).toRecord() : null;
    }

    // ==================== Announcement Operations (schema v5) ====================

    @Override
    public void saveAnnouncement(com.nova.link.announcement.Announcement announcement) throws DatabaseException {
        if (announcement == null || announcement.getId() == null) {
            throw new DatabaseException("Announcement and ID cannot be null");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(ANNOUNCEMENT_PREFIX + announcement.getId(),
                    gson.toJson(new AnnouncementDto(announcement)));
            jedis.sadd(ANNOUNCEMENT_INDEX, announcement.getId());
            logger.debug("Saved announcement: {}", announcement.getId());
        } catch (Exception e) {
            throw new DatabaseException("Failed to save announcement to Redis", e);
        }
    }

    @Override
    public void deleteAnnouncement(String announcementId) throws DatabaseException {
        if (announcementId == null) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(ANNOUNCEMENT_PREFIX + announcementId);
            jedis.srem(ANNOUNCEMENT_INDEX, announcementId);
            logger.debug("Deleted announcement: {}", announcementId);
        } catch (Exception e) {
            throw new DatabaseException("Failed to delete announcement from Redis", e);
        }
    }

    @Override
    public List<com.nova.link.announcement.Announcement> getAllPersistedAnnouncements() throws DatabaseException {
        try (Jedis jedis = jedisPool.getResource()) {
            List<com.nova.link.announcement.Announcement> result = new ArrayList<>();
            for (String id : jedis.smembers(ANNOUNCEMENT_INDEX)) {
                String json = jedis.get(ANNOUNCEMENT_PREFIX + id);
                if (json != null) {
                    com.nova.link.announcement.Announcement announcement =
                            gson.fromJson(json, AnnouncementDto.class).toAnnouncement();
                    if (announcement != null) {
                        result.add(announcement);
                    }
                }
            }
            result.sort(Comparator.comparingLong(com.nova.link.announcement.Announcement::getCreatedAt));
            return result;
        } catch (Exception e) {
            throw new DatabaseException("Failed to load announcements from Redis", e);
        }
    }

    // ==================== Webhook Operations (schema v5) ====================

    @Override
    public void saveWebhook(com.nova.link.api.Webhook webhook) throws DatabaseException {
        if (webhook == null || webhook.getId() == null) {
            throw new DatabaseException("Webhook and ID cannot be null");
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(WEBHOOK_PREFIX + webhook.getId(), gson.toJson(new WebhookDto(webhook)));
            jedis.sadd(WEBHOOK_INDEX, webhook.getId());
            logger.debug("Saved webhook: {}", webhook.getId());
        } catch (Exception e) {
            throw new DatabaseException("Failed to save webhook to Redis", e);
        }
    }

    @Override
    public void deleteWebhook(String webhookId) throws DatabaseException {
        if (webhookId == null) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(WEBHOOK_PREFIX + webhookId);
            jedis.srem(WEBHOOK_INDEX, webhookId);
            logger.debug("Deleted webhook: {}", webhookId);
        } catch (Exception e) {
            throw new DatabaseException("Failed to delete webhook from Redis", e);
        }
    }

    @Override
    public List<com.nova.link.api.Webhook> getAllPersistedWebhooks() throws DatabaseException {
        try (Jedis jedis = jedisPool.getResource()) {
            List<com.nova.link.api.Webhook> result = new ArrayList<>();
            for (String id : jedis.smembers(WEBHOOK_INDEX)) {
                String json = jedis.get(WEBHOOK_PREFIX + id);
                if (json != null) {
                    result.add(gson.fromJson(json, WebhookDto.class).toWebhook());
                }
            }
            result.sort(Comparator.comparingLong(com.nova.link.api.Webhook::getCreatedAt));
            return result;
        } catch (Exception e) {
            throw new DatabaseException("Failed to load webhooks from Redis", e);
        }
    }

    @Override
    public String getProviderType() {
        return "Redis";
    }

    // ==================== DTO Classes for JSON Serialization ====================

    private static class PlayerStateDto {
        String playerId;
        String playerName;
        String clientId;
        String currentWorld;
        Set<String> joinedChannels;
        String activeChannel;
        String platform;
        Boolean dmEnabled;
        Map<String, MuteInfoDto> mutes;
        long lastSeen;

        PlayerStateDto() {}

        PlayerStateDto(PlayerState state) {
            this.playerId = state.getPlayerId().toString();
            this.playerName = state.getPlayerName();
            this.clientId = state.getClientId();
            this.currentWorld = state.getCurrentWorld();
            this.joinedChannels = new HashSet<>(state.getJoinedChannels());
            this.activeChannel = state.getActiveChannel();
            this.platform = state.getPlatform();
            this.dmEnabled = state.isDmEnabled();
            this.mutes = new HashMap<>();
            for (Map.Entry<String, MuteInfo> entry : state.getMutes().entrySet()) {
                this.mutes.put(entry.getKey(), new MuteInfoDto(entry.getValue()));
            }
            this.lastSeen = state.getLastSeen();
        }

        PlayerState toPlayerState() {
            PlayerState state = new PlayerState(UUID.fromString(playerId));
            state.setPlayerName(playerName);
            state.setClientId(clientId);
            state.setCurrentWorld(currentWorld);
            state.setJoinedChannels(joinedChannels != null ? joinedChannels : new HashSet<>());
            state.setActiveChannel(activeChannel);
            state.setPlatform(platform);
            // Old Redis entries written before dm_enabled was persisted have no
            // dmEnabled field; Gson leaves the boxed Boolean null in that case.
            // Fall back to true (the field default) so existing players keep
            // receiving DMs instead of being silently muted.
            state.setDmEnabled(dmEnabled != null ? dmEnabled : true);
            if (mutes != null) {
                Map<String, MuteInfo> muteMap = new HashMap<>();
                for (Map.Entry<String, MuteInfoDto> entry : mutes.entrySet()) {
                    muteMap.put(entry.getKey(), entry.getValue().toMuteInfo());
                }
                state.setMutes(muteMap);
            }
            state.setLastSeen(lastSeen);
            return state;
        }
    }

    private static class ChannelDto {
        String id;
        String displayName;
        String scope;
        String clientId;
        String permission;
        int maxCapacity;
        List<String> allowedWorlds;
        String password;
        String ownerId;
        long createdAt;

        ChannelDto() {}

        ChannelDto(Channel channel) {
            this.id = channel.getId();
            this.displayName = channel.getDisplayName();
            this.scope = channel.getScope().name();
            this.clientId = channel.getClientId();
            this.permission = channel.getPermission();
            this.maxCapacity = channel.getMaxCapacity();
            this.allowedWorlds = new ArrayList<>(channel.getAllowedWorlds());
            this.password = channel.getPassword();
            this.ownerId = channel.getOwnerId() != null ? channel.getOwnerId().toString() : null;
            this.createdAt = channel.getCreatedAt();
        }

        Channel toChannel() {
            Channel channel = new Channel(id, displayName, ChannelScope.valueOf(scope), clientId);
            channel.setPermission(permission);
            channel.setMaxCapacity(maxCapacity);
            channel.setAllowedWorlds(allowedWorlds != null ? allowedWorlds : new ArrayList<>());
            channel.setPassword(password);
            if (ownerId != null) {
                channel.setOwnerId(UUID.fromString(ownerId));
            }
            return channel;
        }
    }

    private static class MuteInfoDto {
        String channelId;
        long expireTime;
        String reason;
        String operatorId;
        long createdAt;

        MuteInfoDto() {}

        MuteInfoDto(MuteInfo mute) {
            this.channelId = mute.getChannelId();
            this.expireTime = mute.getExpireTime();
            this.reason = mute.getReason();
            this.operatorId = mute.getOperatorId() != null ? mute.getOperatorId().toString() : null;
            this.createdAt = mute.getCreatedAt();
        }

        MuteInfo toMuteInfo() {
            UUID opId = operatorId != null ? UUID.fromString(operatorId) : null;
            return new MuteInfo(channelId, expireTime, reason, opId, createdAt);
        }
    }

    private static class BanInfoDto {
        String channelId;
        long expireTime;
        String reason;
        String operatorId;
        long createdAt;

        BanInfoDto() {}

        BanInfoDto(BanInfo ban) {
            this.channelId = ban.getChannelId();
            this.expireTime = ban.getExpireTime();
            this.reason = ban.getReason();
            this.operatorId = ban.getOperatorId() != null ? ban.getOperatorId().toString() : null;
            this.createdAt = ban.getCreatedAt();
        }

        BanInfo toBanInfo() {
            UUID opId = operatorId != null ? UUID.fromString(operatorId) : null;
            return new BanInfo(channelId, expireTime, reason, opId, createdAt);
        }
    }

    private static class NotificationDto {
        long id;
        String title;
        String message;
        String level;
        long createdAt;
        boolean read;

        NotificationDto() {}

        NotificationDto(Notification n) {
            this.id = n.getId();
            this.title = n.getTitle();
            this.message = n.getMessage();
            this.level = n.getLevel();
            this.createdAt = n.getCreatedAt();
            this.read = n.isRead();
        }

        Notification toNotification() {
            return new Notification(id, title, message, level, createdAt, read);
        }
    }

    private static class ChatMessageDto {
        long id;
        String channelId;
        String senderId;
        String senderName;
        String clientId;
        String content;
        long timestamp;

        ChatMessageDto() {}

        ChatMessageDto(ChatMessageRecord record) {
            this.id = record.getId();
            this.channelId = record.getChannelId();
            this.senderId = record.getSenderId();
            this.senderName = record.getSenderName();
            this.clientId = record.getClientId();
            this.content = record.getContent();
            this.timestamp = record.getTimestamp();
        }

        ChatMessageRecord toRecord() {
            return new ChatMessageRecord(id, channelId, senderId, senderName, clientId, content, timestamp);
        }
    }

    private static class AnnouncementDto {
        String id;
        String type;
        String channelId;
        String content;
        String cron;
        boolean enabled;
        long createdAt;

        AnnouncementDto() {}

        AnnouncementDto(com.nova.link.announcement.Announcement announcement) {
            this.id = announcement.getId();
            this.type = announcement.getType().dbValue();
            this.channelId = announcement.getChannelId();
            this.content = announcement.getContent();
            this.cron = announcement.getCronExpression();
            this.enabled = announcement.isEnabled();
            this.createdAt = announcement.getCreatedAt();
        }

        com.nova.link.announcement.Announcement toAnnouncement() {
            com.nova.link.announcement.AnnouncementType announcementType =
                    com.nova.link.announcement.AnnouncementType.fromDbValue(type);
            if (announcementType == null) {
                return null;
            }
            com.nova.link.announcement.Announcement announcement =
                    new com.nova.link.announcement.Announcement(
                            id, channelId, content, announcementType, null, null, createdAt, enabled);
            announcement.setCronExpression(cron);
            return announcement;
        }
    }

    private static class WebhookDto {
        String id;
        String url;
        String event;
        String secret;
        boolean active;
        long createdAt;
        long lastTriggered;

        WebhookDto() {}

        WebhookDto(com.nova.link.api.Webhook webhook) {
            this.id = webhook.getId();
            this.url = webhook.getUrl();
            this.event = webhook.getEvent();
            this.secret = webhook.getSecret();
            this.active = webhook.isActive();
            this.createdAt = webhook.getCreatedAt();
            this.lastTriggered = webhook.getLastTriggered();
        }

        com.nova.link.api.Webhook toWebhook() {
            return new com.nova.link.api.Webhook(id, url, event, secret, active, createdAt, lastTriggered);
        }
    }

    private static class InvitationDto {
        String code;
        String channelId;
        String inviterId;
        long expireTime;
        long createdAt;
        boolean used;
        String usedBy;
        long usedAt;

        InvitationDto() {}

        InvitationDto(Invitation invitation) {
            this.code = invitation.getCode();
            this.channelId = invitation.getChannelId();
            this.inviterId = invitation.getInviterId().toString();
            this.expireTime = invitation.getExpireTime();
            this.createdAt = invitation.getCreatedAt();
            this.used = invitation.isUsed();
            this.usedBy = invitation.getUsedBy() != null ? invitation.getUsedBy().toString() : null;
            this.usedAt = invitation.getUsedAt();
        }

        Invitation toInvitation() {
            UUID usedById = usedBy != null ? UUID.fromString(usedBy) : null;
            return new Invitation(code, channelId, UUID.fromString(inviterId), expireTime, 
                    createdAt, used, usedById, usedAt);
        }
    }
}
