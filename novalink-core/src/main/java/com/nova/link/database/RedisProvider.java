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
    private static final String INVITATION_PREFIX = KEY_PREFIX + "invitation:";
    private static final String PLAYER_INDEX = KEY_PREFIX + "players";
    private static final String CHANNEL_INDEX = KEY_PREFIX + "channels";

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
            // Also delete mutes
            jedis.del(MUTE_PREFIX + playerId.toString());
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
        int count = 0;
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> playerIds = jedis.smembers(PLAYER_INDEX);
            long now = System.currentTimeMillis();
            
            for (String playerId : playerIds) {
                String key = MUTE_PREFIX + playerId;
                Map<String, String> mutes = jedis.hgetAll(key);
                
                for (Map.Entry<String, String> entry : mutes.entrySet()) {
                    MuteInfoDto dto = gson.fromJson(entry.getValue(), MuteInfoDto.class);
                    if (dto.expireTime > 0 && now > dto.expireTime) {
                        jedis.hdel(key, entry.getKey());
                        count++;
                    }
                }
            }
            
            if (count > 0) {
                logger.debug("Cleaned up {} expired mutes", count);
            }
        } catch (Exception e) {
            throw new DatabaseException("Failed to cleanup expired mutes from Redis", e);
        }
        return count;
    }

    // ==================== Invitation Operations ====================

    @Override
    public void saveInvitation(Invitation invitation) throws DatabaseException {
        if (invitation == null || invitation.getCode() == null) {
            throw new DatabaseException("Invitation and code cannot be null");
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String key = INVITATION_PREFIX + invitation.getCode();
            String json = gson.toJson(new InvitationDto(invitation));
            jedis.set(key, json);
            
            // Set TTL based on expiration
            long ttl = (invitation.getExpireTime() - System.currentTimeMillis()) / 1000;
            if (ttl > 0) {
                jedis.expire(key, ttl);
            }
            
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
            String json = jedis.get(key);
            if (json != null) {
                InvitationDto dto = gson.fromJson(json, InvitationDto.class);
                dto.used = true;
                dto.usedBy = usedBy != null ? usedBy.toString() : null;
                dto.usedAt = System.currentTimeMillis();
                jedis.set(key, gson.toJson(dto));
                logger.debug("Marked invitation {} as used by {}", code, usedBy);
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
