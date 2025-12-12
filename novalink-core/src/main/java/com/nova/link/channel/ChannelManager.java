package com.nova.link.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages channel lifecycle and provides channel lookup operations.
 * Thread-safe implementation using ConcurrentHashMap.
 * 
 * Requirements: 3.1, 3.4
 */
public class ChannelManager {

    private static final Logger logger = LoggerFactory.getLogger(ChannelManager.class);
    
    /** Characters used for generating private channel IDs */
    private static final String ID_CHARS = "0123456789ABCDEF";
    
    /** Prefix for private channel IDs */
    private static final String PRIVATE_ID_PREFIX = "NC-";
    
    /** Length of the random part of private channel IDs */
    private static final int PRIVATE_ID_LENGTH = 4;
    
    /** All channels indexed by ID */
    private final Map<String, Channel> channels;
    
    /** Channels indexed by client ID for fast lookup */
    private final Map<String, Set<String>> channelsByClient;
    
    /** Global channels (no client association) */
    private final Set<String> globalChannels;
    
    /** Random generator for ID generation */
    private final SecureRandom random;

    public ChannelManager() {
        this.channels = new ConcurrentHashMap<>();
        this.channelsByClient = new ConcurrentHashMap<>();
        this.globalChannels = ConcurrentHashMap.newKeySet();
        this.random = new SecureRandom();
    }

    /**
     * Creates a new channel with the given configuration.
     *
     * @param config the channel configuration
     * @return the created channel
     * @throws IllegalArgumentException if a channel with the same ID already exists
     */
    public Channel createChannel(ChannelConfig config) {
        Objects.requireNonNull(config, "Channel config cannot be null");
        
        String channelId = config.getId();
        
        // Generate ID for private channels if not provided
        if (config.getScope() == ChannelScope.PRIVATE && (channelId == null || channelId.isEmpty())) {
            channelId = generatePrivateChannelId();
        }
        
        if (channels.containsKey(channelId)) {
            throw new IllegalArgumentException("Channel with ID '" + channelId + "' already exists");
        }
        
        Channel channel = new Channel(channelId, config.getDisplayName(), config.getScope(), config.getClientId());
        channel.setPermission(config.getPermission());
        channel.setMaxCapacity(config.getMaxCapacity());
        channel.setAllowedWorlds(config.getAllowedWorlds());
        channel.setPassword(config.getPassword());
        channel.setOwnerId(config.getOwnerId());
        
        // Register the channel
        channels.put(channelId, channel);
        
        // Index by scope
        if (config.getScope() == ChannelScope.GLOBAL) {
            globalChannels.add(channelId);
        } else {
            channelsByClient.computeIfAbsent(config.getClientId(), k -> ConcurrentHashMap.newKeySet())
                    .add(channelId);
        }
        
        logger.info("Created channel: {} (scope={}, client={})", channelId, config.getScope(), config.getClientId());
        return channel;
    }

    /**
     * Deletes a channel by ID.
     *
     * @param channelId the channel ID to delete
     * @return true if the channel was deleted, false if not found
     */
    public boolean deleteChannel(String channelId) {
        Channel channel = channels.remove(channelId);
        if (channel == null) {
            return false;
        }
        
        // Remove from indexes
        if (channel.getScope() == ChannelScope.GLOBAL) {
            globalChannels.remove(channelId);
        } else {
            Set<String> clientChannels = channelsByClient.get(channel.getClientId());
            if (clientChannels != null) {
                clientChannels.remove(channelId);
            }
        }
        
        logger.info("Deleted channel: {}", channelId);
        return true;
    }

    /**
     * Gets a channel by ID.
     *
     * @param channelId the channel ID
     * @return the channel, or null if not found
     */
    public Channel getChannel(String channelId) {
        return channels.get(channelId);
    }

    /**
     * Checks if a channel exists.
     *
     * @param channelId the channel ID
     * @return true if the channel exists
     */
    public boolean channelExists(String channelId) {
        return channels.containsKey(channelId);
    }

    /**
     * Gets all channels for a specific client.
     *
     * @param clientId the client ID
     * @return list of channels belonging to the client
     */
    public List<Channel> getChannelsByClient(String clientId) {
        Set<String> channelIds = channelsByClient.get(clientId);
        if (channelIds == null || channelIds.isEmpty()) {
            return Collections.emptyList();
        }
        return channelIds.stream()
                .map(channels::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Gets all global channels.
     *
     * @return list of global channels
     */
    public List<Channel> getGlobalChannels() {
        return globalChannels.stream()
                .map(channels::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Gets all channels.
     *
     * @return unmodifiable collection of all channels
     */
    public Collection<Channel> getAllChannels() {
        return Collections.unmodifiableCollection(channels.values());
    }

    /**
     * Gets the total number of channels.
     *
     * @return channel count
     */
    public int getChannelCount() {
        return channels.size();
    }

    /**
     * Adds a member to a channel.
     *
     * @param channelId the channel ID
     * @param playerId the player UUID
     * @return true if the member was added
     */
    public boolean addMember(String channelId, UUID playerId) {
        Channel channel = channels.get(channelId);
        if (channel == null) {
            return false;
        }
        return channel.addMember(playerId);
    }

    /**
     * Removes a member from a channel.
     *
     * @param channelId the channel ID
     * @param playerId the player UUID
     * @return true if the member was removed
     */
    public boolean removeMember(String channelId, UUID playerId) {
        Channel channel = channels.get(channelId);
        if (channel == null) {
            return false;
        }
        return channel.removeMember(playerId);
    }

    /**
     * Gets all members of a channel.
     *
     * @param channelId the channel ID
     * @return set of member UUIDs, or empty set if channel not found
     */
    public Set<UUID> getChannelMembers(String channelId) {
        Channel channel = channels.get(channelId);
        if (channel == null) {
            return Collections.emptySet();
        }
        return channel.getMembers();
    }

    /**
     * Generates a unique private channel ID in the format NC-XXXX.
     *
     * @return a unique channel ID
     */
    private String generatePrivateChannelId() {
        String id;
        int attempts = 0;
        do {
            StringBuilder sb = new StringBuilder(PRIVATE_ID_PREFIX);
            for (int i = 0; i < PRIVATE_ID_LENGTH; i++) {
                sb.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
            }
            id = sb.toString();
            attempts++;
            
            // Safety check to prevent infinite loop
            if (attempts > 1000) {
                throw new IllegalStateException("Unable to generate unique channel ID after 1000 attempts");
            }
        } while (channels.containsKey(id));
        
        return id;
    }

    /**
     * Generates a random password for private channels.
     *
     * @return a 6-character alphanumeric password
     */
    public String generatePassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    /**
     * Clears all channels. Used for testing.
     */
    public void clear() {
        channels.clear();
        channelsByClient.clear();
        globalChannels.clear();
        logger.info("Cleared all channels");
    }
}
