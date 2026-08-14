package com.nova.link.channel;

import com.nova.link.database.DatabaseException;
import com.nova.link.database.DatabaseProvider;
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
 * <p><b>Persistence (P0-3):</b> when a {@link DatabaseProvider} is wired via
 * {@link #setDatabaseProvider(DatabaseProvider)} (or the accepting constructor),
 * every {@link #createChannel}, {@link #deleteChannel} and {@link #updateChannel}
 * write-through to the database so the REST and TCP paths stay consistent and
 * channels survive a backend restart. DB failures are logged at WARN and never
 * propagate: the in-memory operation still succeeds (the channel remains
 * available for this process lifetime even if the sink is temporarily down).
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

    /** Optional write-through persistence sink; null = memory-only (tests/legacy). */
    private volatile DatabaseProvider databaseProvider;

    public ChannelManager() {
        this.channels = new ConcurrentHashMap<>();
        this.channelsByClient = new ConcurrentHashMap<>();
        this.globalChannels = ConcurrentHashMap.newKeySet();
        this.random = new SecureRandom();
    }

    /**
     * Accepting constructor: wires the persistence sink so create/delete/update
     * write-through to the database. Use {@link #ChannelManager()} + later
     * {@link #setDatabaseProvider(DatabaseProvider)} when the provider is built
     * after the manager (NovaLinkMain wiring order).
     *
     * @param databaseProvider nullable; when null, behaves memory-only
     */
    public ChannelManager(DatabaseProvider databaseProvider) {
        this();
        this.databaseProvider = databaseProvider;
    }

    /**
     * Late-binds the database provider so create/delete/update persist.
     * Called once at startup after the provider is initialized. Idempotent.
     */
    public void setDatabaseProvider(DatabaseProvider databaseProvider) {
        this.databaseProvider = databaseProvider;
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
        channel.setSlowModeSeconds(config.getSlowModeSeconds());
        
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
        persistChannel(channel);
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
        deletePersistedChannel(channelId);
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
     * Updates an existing channel's mutable properties (displayName, maxCapacity,
     * permission). Only non-null/non-default arguments are applied; null fields
     * leave the existing value untouched. Mirrors the field-by-field update in
     * {@code NovaLinkMain.upsertConfiguredChannel}.
     *
     * @param channelId the channel ID to update
     * @param displayName the new display name (null to keep existing)
     * @param maxCapacity the new max capacity (<=0 to keep existing)
     * @param permission the new permission node (null to keep existing)
     * @return the updated channel, or null if the channel was not found
     */
    public Channel updateChannel(String channelId, String displayName,
                                 Integer maxCapacity, String permission) {
        Channel channel = channels.get(channelId);
        if (channel == null) {
            return null;
        }
        if (displayName != null) {
            channel.setDisplayName(displayName);
        }
        if (maxCapacity != null && maxCapacity > 0) {
            channel.setMaxCapacity(maxCapacity);
        }
        if (permission != null) {
            channel.setPermission(permission);
        }
        logger.info("Updated channel: {} (displayName={}, maxCapacity={}, permission={})",
                channelId, displayName, maxCapacity, permission);
        persistChannel(channel);
        return channel;
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

    // ==================== Persistence helpers (P0-3) ====================

    /**
     * Write-through save of a channel to the database, if a provider is wired.
     * Failures are logged at WARN and swallowed so the in-memory operation
     * still succeeds — a temporarily-down sink must not break channel lifecycle.
     */
    private void persistChannel(Channel channel) {
        DatabaseProvider db = this.databaseProvider;
        if (db == null || channel == null) {
            return;
        }
        try {
            db.saveChannel(channel);
        } catch (DatabaseException e) {
            logger.warn("Failed to persist channel '{}': {}", channel.getId(), e.getMessage());
        } catch (Exception e) {
            logger.warn("Unexpected error persisting channel '{}': {}", channel.getId(), e.getMessage());
        }
    }

    /**
     * Write-through delete of a channel from the database, if a provider is wired.
     * Failures are logged at WARN and swallowed (same rationale as {@link #persistChannel}).
     */
    private void deletePersistedChannel(String channelId) {
        DatabaseProvider db = this.databaseProvider;
        if (db == null || channelId == null) {
            return;
        }
        try {
            db.deleteChannel(channelId);
        } catch (DatabaseException e) {
            logger.warn("Failed to delete persisted channel '{}': {}", channelId, e.getMessage());
        } catch (Exception e) {
            logger.warn("Unexpected error deleting persisted channel '{}': {}", channelId, e.getMessage());
        }
    }
}
