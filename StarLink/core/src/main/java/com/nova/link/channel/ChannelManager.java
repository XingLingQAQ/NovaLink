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
 * channels survive a backend restart. The in-memory mutation is applied first,
 * then the DB write; on DB failure the in-memory mutation is rolled back and an
 * {@link IllegalStateException} is propagated to the caller so the user sees an
 * error instead of a silent success that leaves the registry ahead of the sink.
 * When no provider is wired the helpers early-return and behave memory-only.
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
     * Creates a new channel with the given configuration, tagged as
     * {@link ChannelSource#RUNTIME}. Equivalent to
     * {@link #createChannel(ChannelConfig, ChannelSource)} with {@code RUNTIME}.
     *
     * @param config the channel configuration
     * @return the created channel
     * @throws IllegalArgumentException if a channel with the same ID already exists
     */
    public Channel createChannel(ChannelConfig config) {
        return createChannel(config, ChannelSource.RUNTIME);
    }

    /**
     * Creates a new channel with the given configuration and provenance tag.
     *
     * <p>The {@code source} tag governs reload semantics: config reload skips
     * channels whose source is not {@link ChannelSource#CONFIG}, and the Panel
     * treats {@code CONFIG} channels as read-only.</p>
     *
     * @param config the channel configuration
     * @param source provenance tag (CONFIG/DATABASE/RUNTIME)
     * @return the created channel
     * @throws IllegalArgumentException if a channel with the same ID already exists
     */
    public Channel createChannel(ChannelConfig config, ChannelSource source) {
        Objects.requireNonNull(config, "Channel config cannot be null");

        String channelId = config.getId();

        // Generate ID for private channels if not provided
        if (config.getScope() == ChannelScope.PRIVATE && (channelId == null || channelId.isEmpty())) {
            channelId = generatePrivateChannelId();
        }

        Channel channel = new Channel(channelId, config.getDisplayName(), config.getScope(),
                config.getClientId(), source != null ? source : ChannelSource.RUNTIME);
        channel.setPermission(config.getPermission());
        channel.setMaxCapacity(config.getMaxCapacity());
        channel.setAllowedWorlds(config.getAllowedWorlds());
        channel.setPassword(config.getPassword());
        channel.setOwnerId(config.getOwnerId());
        channel.setSlowModeSeconds(config.getSlowModeSeconds());

        // Register the channel atomically: putIfAbsent returns null only when
        // the key was absent. A non-null return means another thread won the
        // race, so we reject instead of silently overwriting the existing channel.
        if (channels.putIfAbsent(channelId, channel) != null) {
            throw new IllegalArgumentException("Channel with ID '" + channelId + "' already exists");
        }

        // Index by scope
        if (config.getScope() == ChannelScope.GLOBAL) {
            globalChannels.add(channelId);
        } else {
            channelsByClient.computeIfAbsent(config.getClientId(), k -> ConcurrentHashMap.newKeySet())
                    .add(channelId);
        }

        logger.info("Created channel: {} (scope={}, client={}, source={})",
                channelId, config.getScope(), config.getClientId(), channel.getSource());
        try {
            persistChannel(channel);
        } catch (RuntimeException | DatabaseException e) {
            // Roll back the in-memory mutation so the registry does not get
            // ahead of the DB, then surface the failure to the caller.
            channels.remove(channelId);
            if (config.getScope() == ChannelScope.GLOBAL) {
                globalChannels.remove(channelId);
            } else {
                Set<String> clientChannels = channelsByClient.get(config.getClientId());
                if (clientChannels != null) {
                    clientChannels.remove(channelId);
                }
            }
            logger.warn("Rolled back in-memory create of channel '{}' after persistence failure: {}",
                    channelId, e.getMessage());
            throw new IllegalStateException(
                    "Failed to persist channel '" + channelId + "': " + e.getMessage(), e);
        }
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
        try {
            deletePersistedChannel(channelId);
        } catch (RuntimeException | DatabaseException e) {
            // Restore the channel and its indexes so the registry does not
            // get ahead of the DB, then surface the failure to the caller.
            channels.put(channelId, channel);
            if (channel.getScope() == ChannelScope.GLOBAL) {
                globalChannels.add(channelId);
            } else {
                channelsByClient.computeIfAbsent(channel.getClientId(), k -> ConcurrentHashMap.newKeySet())
                        .add(channelId);
            }
            logger.warn("Rolled back in-memory delete of channel '{}' after persistence failure: {}",
                    channelId, e.getMessage());
            throw new IllegalStateException(
                    "Failed to delete persisted channel '" + channelId + "': " + e.getMessage(), e);
        }
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
     * leave the existing value untouched. To explicitly <b>clear</b> the
     * permission (set it to null), use
     * {@link #updateChannel(String, String, Integer, String, boolean)} with
     * {@code permissionPresent=true} and {@code permission=null}. Mirrors the
     * field-by-field update in {@code NovaLinkMain.upsertConfiguredChannel}.
     *
     * <p>This overload treats a {@code null} permission as "leave untouched"
     * (backward compatible with the 4-arg contract). The revision is bumped
     * only when at least one field actually changes.</p>
     *
     * @param channelId the channel ID to update
     * @param displayName the new display name (null to keep existing)
     * @param maxCapacity the new max capacity (null or &lt;=0 to keep existing)
     * @param permission the new permission node (null to keep existing)
     * @return the updated channel, or null if the channel was not found
     */
    public Channel updateChannel(String channelId, String displayName,
                                 Integer maxCapacity, String permission) {
        return updateChannel(channelId, displayName, maxCapacity, permission, permission != null);
    }

    /**
     * Updates an existing channel's mutable properties with explicit control
     * over whether the {@code permission} field is being set (including cleared
     * to null). When {@code permissionPresent} is {@code false}, the permission
     * is left untouched regardless of the {@code permission} argument; when
     * {@code true}, the permission is set to {@code permission} (which may be
     * null, clearing it). The revision is bumped when any field changes.
     *
     * @param channelId the channel ID to update
     * @param displayName the new display name (null to keep existing)
     * @param maxCapacity the new max capacity (null or &lt;=0 to keep existing)
     * @param permission the new permission node (ignored when permissionPresent is false)
     * @param permissionPresent when true, apply {@code permission} (may clear it); when false, leave untouched
     * @return the updated channel, or null if the channel was not found
     * @throws IllegalArgumentException when {@code maxCapacity} is a non-null, non-positive value
     */
    public Channel updateChannel(String channelId, String displayName,
                                 Integer maxCapacity, String permission, boolean permissionPresent) {
        Channel channel = channels.get(channelId);
        if (channel == null) {
            return null;
        }
        // Snapshot the mutable fields so we can roll back on persistence failure
        // and keep the registry consistent with the DB.
        String prevDisplayName = channel.getDisplayName();
        int prevMaxCapacity = channel.getMaxCapacity();
        String prevPermission = channel.getPermission();
        long prevRevision = channel.getRevision();
        boolean changed = false;
        if (displayName != null) {
            channel.setDisplayName(displayName);
            changed = true;
        }
        if (maxCapacity != null) {
            if (maxCapacity <= 0) {
                // Non-positive is an explicit error for a supplied value — do not
                // silently ignore, since callers expect the supplied capacity to apply.
                throw new IllegalArgumentException("maxCapacity must be a positive integer, got " + maxCapacity);
            }
            channel.setMaxCapacity(maxCapacity);
            changed = true;
        }
        if (permissionPresent) {
            channel.setPermission(permission);
            changed = true;
        }
        if (changed) {
            channel.bumpRevision();
        }
        logger.info("Updated channel: {} (displayName={}, maxCapacity={}, permission={}, permissionPresent={})",
                channelId, displayName, maxCapacity, permission, permissionPresent);
        try {
            persistChannel(channel);
        } catch (RuntimeException | DatabaseException e) {
            // Restore the pre-update state so the registry does not get ahead
            // of the DB, then surface the failure to the caller.
            channel.setDisplayName(prevDisplayName);
            channel.setMaxCapacity(prevMaxCapacity);
            channel.setPermission(prevPermission);
            channel.setRevision(prevRevision);
            logger.warn("Rolled back in-memory update of channel '{}' after persistence failure: {}",
                    channelId, e.getMessage());
            throw new IllegalStateException(
                    "Failed to persist update of channel '" + channelId + "': " + e.getMessage(), e);
        }
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
     * Propagates {@link DatabaseException} (and any runtime exception) so the
     * caller can roll back the in-memory mutation and surface the failure — a
     * temporarily-down sink must not leave the registry ahead of the DB.
     */
    private void persistChannel(Channel channel) throws DatabaseException {
        DatabaseProvider db = this.databaseProvider;
        if (db == null || channel == null) {
            return;
        }
        db.saveChannel(channel);
    }

    /**
     * Write-through delete of a channel from the database, if a provider is wired.
     * Propagates {@link DatabaseException} (and any runtime exception) so the
     * caller can roll back the in-memory mutation (same rationale as {@link #persistChannel}).
     */
    private void deletePersistedChannel(String channelId) throws DatabaseException {
        DatabaseProvider db = this.databaseProvider;
        if (db == null || channelId == null) {
            return;
        }
        db.deleteChannel(channelId);
    }
}
