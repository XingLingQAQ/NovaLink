package com.nova.link.database;

import com.nova.link.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of DatabaseProvider.
 * Data is lost when the server restarts.
 * 
 * Requirements: 22.3 - Support no-database mode
 */
public class MemoryProvider implements DatabaseProvider {

    private static final Logger logger = LoggerFactory.getLogger(MemoryProvider.class);

    private final Map<UUID, PlayerState> playerStates = new ConcurrentHashMap<>();
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, MuteInfo>> mutes = new ConcurrentHashMap<>();
    private final Map<String, Invitation> invitations = new ConcurrentHashMap<>();

    private volatile boolean connected = false;

    @Override
    public void initialize() throws DatabaseException {
        connected = true;
        logger.info("MemoryProvider initialized - data will not persist across restarts");
    }

    @Override
    public void shutdown() {
        connected = false;
        playerStates.clear();
        channels.clear();
        mutes.clear();
        invitations.clear();
        logger.info("MemoryProvider shutdown - all data cleared");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    // ==================== Player State Operations ====================

    @Override
    public void savePlayerState(PlayerState state) throws DatabaseException {
        checkConnection();
        if (state == null || state.getPlayerId() == null) {
            throw new DatabaseException("Player state and player ID cannot be null");
        }
        // Store a copy to prevent external modifications
        playerStates.put(state.getPlayerId(), new PlayerState(state));
        logger.debug("Saved player state for: {}", state.getPlayerId());
    }

    @Override
    public Optional<PlayerState> loadPlayerState(UUID playerId) throws DatabaseException {
        checkConnection();
        if (playerId == null) {
            return Optional.empty();
        }
        PlayerState state = playerStates.get(playerId);
        // Return a copy to prevent external modifications
        return state != null ? Optional.of(new PlayerState(state)) : Optional.empty();
    }

    @Override
    public void deletePlayerState(UUID playerId) throws DatabaseException {
        checkConnection();
        if (playerId != null) {
            playerStates.remove(playerId);
            mutes.remove(playerId);
            logger.debug("Deleted player state for: {}", playerId);
        }
    }

    @Override
    public List<PlayerState> getAllPlayerStates() throws DatabaseException {
        checkConnection();
        return playerStates.values().stream()
                .map(PlayerState::new)
                .collect(Collectors.toList());
    }

    // ==================== Channel Operations ====================

    @Override
    public void saveChannel(Channel channel) throws DatabaseException {
        checkConnection();
        if (channel == null || channel.getId() == null) {
            throw new DatabaseException("Channel and channel ID cannot be null");
        }
        channels.put(channel.getId(), channel);
        logger.debug("Saved channel: {}", channel.getId());
    }

    @Override
    public Optional<Channel> loadChannel(String channelId) throws DatabaseException {
        checkConnection();
        if (channelId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(channels.get(channelId));
    }

    @Override
    public void deleteChannel(String channelId) throws DatabaseException {
        checkConnection();
        if (channelId != null) {
            channels.remove(channelId);
            logger.debug("Deleted channel: {}", channelId);
        }
    }

    @Override
    public List<Channel> getAllChannels() throws DatabaseException {
        checkConnection();
        return new ArrayList<>(channels.values());
    }

    // ==================== Mute Operations ====================

    @Override
    public void saveMute(UUID playerId, MuteInfo muteInfo) throws DatabaseException {
        checkConnection();
        if (playerId == null || muteInfo == null) {
            throw new DatabaseException("Player ID and mute info cannot be null");
        }
        mutes.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                .put(muteInfo.getChannelId() != null ? muteInfo.getChannelId() : "__global__", muteInfo);
        logger.debug("Saved mute for player {} in channel {}", playerId, muteInfo.getChannelId());
    }

    @Override
    public List<MuteInfo> loadMutes(UUID playerId) throws DatabaseException {
        checkConnection();
        if (playerId == null) {
            return Collections.emptyList();
        }
        Map<String, MuteInfo> playerMutes = mutes.get(playerId);
        if (playerMutes == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(playerMutes.values());
    }

    @Override
    public void deleteMute(UUID playerId, String channelId) throws DatabaseException {
        checkConnection();
        if (playerId == null) {
            return;
        }
        Map<String, MuteInfo> playerMutes = mutes.get(playerId);
        if (playerMutes != null) {
            playerMutes.remove(channelId != null ? channelId : "__global__");
            logger.debug("Deleted mute for player {} in channel {}", playerId, channelId);
        }
    }

    @Override
    public int cleanupExpiredMutes() throws DatabaseException {
        checkConnection();
        int count = 0;
        long now = System.currentTimeMillis();
        for (Map<String, MuteInfo> playerMutes : mutes.values()) {
            Iterator<MuteInfo> iterator = playerMutes.values().iterator();
            while (iterator.hasNext()) {
                MuteInfo mute = iterator.next();
                if (mute.getExpireTime() > 0 && now > mute.getExpireTime()) {
                    iterator.remove();
                    count++;
                }
            }
        }
        if (count > 0) {
            logger.debug("Cleaned up {} expired mutes", count);
        }
        return count;
    }

    // ==================== Invitation Operations ====================

    @Override
    public void saveInvitation(Invitation invitation) throws DatabaseException {
        checkConnection();
        if (invitation == null || invitation.getCode() == null) {
            throw new DatabaseException("Invitation and code cannot be null");
        }
        invitations.put(invitation.getCode(), invitation);
        logger.debug("Saved invitation: {}", invitation.getCode());
    }

    @Override
    public Optional<Invitation> loadInvitation(String code) throws DatabaseException {
        checkConnection();
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(invitations.get(code));
    }

    @Override
    public void markInvitationUsed(String code, UUID usedBy) throws DatabaseException {
        checkConnection();
        Invitation invitation = invitations.get(code);
        if (invitation != null) {
            invitation.markUsed(usedBy);
            logger.debug("Marked invitation {} as used by {}", code, usedBy);
        }
    }

    @Override
    public void deleteInvitation(String code) throws DatabaseException {
        checkConnection();
        if (code != null) {
            invitations.remove(code);
            logger.debug("Deleted invitation: {}", code);
        }
    }

    @Override
    public int cleanupExpiredInvitations() throws DatabaseException {
        checkConnection();
        int count = 0;
        long now = System.currentTimeMillis();
        Iterator<Invitation> iterator = invitations.values().iterator();
        while (iterator.hasNext()) {
            Invitation invitation = iterator.next();
            if (now > invitation.getExpireTime()) {
                iterator.remove();
                count++;
            }
        }
        if (count > 0) {
            logger.debug("Cleaned up {} expired invitations", count);
        }
        return count;
    }

    @Override
    public String getProviderType() {
        return "Memory";
    }

    private void checkConnection() throws DatabaseException {
        if (!connected) {
            throw new DatabaseException("MemoryProvider is not initialized");
        }
    }

    /**
     * Gets the current count of stored player states (for testing).
     */
    public int getPlayerStateCount() {
        return playerStates.size();
    }

    /**
     * Gets the current count of stored channels (for testing).
     */
    public int getChannelCount() {
        return channels.size();
    }

    /**
     * Gets the current count of stored invitations (for testing).
     */
    public int getInvitationCount() {
        return invitations.size();
    }
}
