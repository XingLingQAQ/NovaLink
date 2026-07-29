package com.nova.link.spy;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages spy mode for super admins to remotely monitor channels.
 * 
 * Requirements: 17.1-17.5
 * - 17.1: Super admin can execute `/nc admin spy <server_name> <channel_id>` to start monitoring
 * - 17.2: Forward target channel messages to super admin
 * - 17.3: Super admin can send messages to monitored channel while in spy mode
 * - 17.4: Super admin can monitor multiple channels simultaneously
 * - 17.5: Super admin can execute `/nc admin spy off` to stop all monitoring
 */
public class SpyManager {

    private static final Logger logger = LoggerFactory.getLogger(SpyManager.class);

    /** Map of admin UUID to their active spy sessions */
    private final Map<UUID, Set<SpySession>> spySessions;
    
    /** Reverse index: channelId -> set of admin UUIDs spying on it */
    private final Map<String, Set<UUID>> channelSpies;
    
    private final PermissionManager permissionManager;
    private final ChannelManager channelManager;
    private final ServerNetworkHandler networkHandler;

    public SpyManager(PermissionManager permissionManager, ChannelManager channelManager, 
                      ServerNetworkHandler networkHandler) {
        this.permissionManager = Objects.requireNonNull(permissionManager);
        this.channelManager = Objects.requireNonNull(channelManager);
        this.networkHandler = Objects.requireNonNull(networkHandler);
        this.spySessions = new ConcurrentHashMap<>();
        this.channelSpies = new ConcurrentHashMap<>();
    }

    /**
     * Starts spy mode for a super admin on a specific channel.
     * 
     * Requirements: 17.1, 17.2
     *
     * @param adminId       the super admin's UUID
     * @param channelId     the channel to monitor
     * @param targetClientId optional: specific client to monitor (null = any client with this channel)
     * @return the result of the operation
     */
    public SpyResult startSpying(UUID adminId, String channelId, String targetClientId) {
        if (adminId == null) {
            return SpyResult.failure("NC-400", "Admin ID is required");
        }
        if (channelId == null || channelId.isEmpty()) {
            return SpyResult.failure("NC-400", "Channel ID is required");
        }

        // Verify super admin session
        if (!permissionManager.hasSuperAdminSession(adminId)) {
            return SpyResult.failure("NC-403", "Super admin authentication required for spy mode");
        }

        // Verify channel exists
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return SpyResult.failure("NC-404", "Channel not found: " + channelId);
        }

        // If targetClientId is specified, verify it matches the channel's client
        if (targetClientId != null && !targetClientId.isEmpty()) {
            String channelClientId = channel.getClientId();
            if (channelClientId != null && !channelClientId.equals(targetClientId)) {
                return SpyResult.failure("NC-404", 
                    "Channel '" + channelId + "' not found on client '" + targetClientId + "'");
            }
        }

        // Create spy session
        SpySession session = new SpySession(adminId, channelId, targetClientId);
        
        // Add to admin's sessions
        spySessions.computeIfAbsent(adminId, k -> ConcurrentHashMap.newKeySet()).add(session);
        
        // Add to channel's spies index
        channelSpies.computeIfAbsent(channelId, k -> ConcurrentHashMap.newKeySet()).add(adminId);

        logger.info("Super admin {} started spying on channel {} (client: {})", 
                adminId, channelId, targetClientId != null ? targetClientId : "all");

        return SpyResult.success("Now monitoring channel: " + channelId);
    }

    /**
     * Stops spy mode for a super admin on a specific channel.
     *
     * @param adminId   the super admin's UUID
     * @param channelId the channel to stop monitoring
     * @return the result of the operation
     */
    public SpyResult stopSpying(UUID adminId, String channelId) {
        if (adminId == null) {
            return SpyResult.failure("NC-400", "Admin ID is required");
        }
        if (channelId == null || channelId.isEmpty()) {
            return SpyResult.failure("NC-400", "Channel ID is required");
        }

        Set<SpySession> sessions = spySessions.get(adminId);
        if (sessions == null || sessions.isEmpty()) {
            return SpyResult.failure("NC-404", "No active spy sessions");
        }

        // Find and remove the session for this channel
        boolean removed = sessions.removeIf(s -> s.getChannelId().equals(channelId));
        
        if (!removed) {
            return SpyResult.failure("NC-404", "Not monitoring channel: " + channelId);
        }

        // Update channel spies index
        Set<UUID> spies = channelSpies.get(channelId);
        if (spies != null) {
            spies.remove(adminId);
            if (spies.isEmpty()) {
                channelSpies.remove(channelId);
            }
        }

        // Clean up empty session sets
        if (sessions.isEmpty()) {
            spySessions.remove(adminId);
        }

        logger.info("Super admin {} stopped spying on channel {}", adminId, channelId);

        return SpyResult.success("Stopped monitoring channel: " + channelId);
    }

    /**
     * Stops all spy sessions for a super admin.
     * 
     * Requirements: 17.5
     *
     * @param adminId the super admin's UUID
     * @return the result of the operation
     */
    public SpyResult stopAllSpying(UUID adminId) {
        if (adminId == null) {
            return SpyResult.failure("NC-400", "Admin ID is required");
        }

        Set<SpySession> sessions = spySessions.remove(adminId);
        if (sessions == null || sessions.isEmpty()) {
            return SpyResult.success("No active spy sessions to stop");
        }

        // Remove from all channel spy indexes
        for (SpySession session : sessions) {
            Set<UUID> spies = channelSpies.get(session.getChannelId());
            if (spies != null) {
                spies.remove(adminId);
                if (spies.isEmpty()) {
                    channelSpies.remove(session.getChannelId());
                }
            }
        }

        int count = sessions.size();
        logger.info("Super admin {} stopped all {} spy session(s)", adminId, count);

        return SpyResult.success("Stopped monitoring " + count + " channel(s)");
    }

    /**
     * Gets all active spy sessions for a super admin.
     * 
     * Requirements: 17.4
     *
     * @param adminId the super admin's UUID
     * @return set of active spy sessions
     */
    public Set<SpySession> getSpySessions(UUID adminId) {
        Set<SpySession> sessions = spySessions.get(adminId);
        return sessions != null ? Collections.unmodifiableSet(sessions) : Collections.emptySet();
    }

    /**
     * Gets all admin UUIDs currently spying on a channel.
     *
     * @param channelId the channel ID
     * @return set of admin UUIDs
     */
    public Set<UUID> getChannelSpies(String channelId) {
        Set<UUID> spies = channelSpies.get(channelId);
        return spies != null ? Collections.unmodifiableSet(spies) : Collections.emptySet();
    }

    /**
     * Checks if a super admin is spying on a specific channel.
     *
     * @param adminId   the super admin's UUID
     * @param channelId the channel ID
     * @return true if the admin is spying on the channel
     */
    public boolean isSpying(UUID adminId, String channelId) {
        Set<SpySession> sessions = spySessions.get(adminId);
        if (sessions == null) {
            return false;
        }
        return sessions.stream().anyMatch(s -> s.getChannelId().equals(channelId));
    }

    /**
     * Checks if a super admin has any active spy sessions.
     *
     * @param adminId the super admin's UUID
     * @return true if the admin has active spy sessions
     */
    public boolean hasActiveSpySessions(UUID adminId) {
        Set<SpySession> sessions = spySessions.get(adminId);
        return sessions != null && !sessions.isEmpty();
    }

    /**
     * Forwards a chat message to all super admins spying on the channel.
     * 
     * Requirements: 17.2
     *
     * @param message the chat message to forward
     */
    public void forwardToSpies(ChatMessagePacket message) {
        if (message == null) {
            return;
        }

        String channelId = message.getChannelId();
        Set<UUID> spies = channelSpies.get(channelId);
        
        if (spies == null || spies.isEmpty()) {
            return;
        }

        // Create a spy-prefixed message to indicate it's from monitoring
        ChatMessagePacket spyMessage = createSpyMessage(message);

        for (UUID adminId : spies) {
            // Find the connection for this admin
            ClientConnection adminConnection = findAdminConnection(adminId);
            if (adminConnection != null && adminConnection.isActive()) {
                adminConnection.sendPacket(spyMessage);
                logger.debug("Forwarded message from channel {} to spy admin {}", channelId, adminId);
            }
        }
    }

    /**
     * Creates a spy-prefixed version of a chat message.
     *
     * @param original the original message
     * @return the spy-prefixed message
     */
    private ChatMessagePacket createSpyMessage(ChatMessagePacket original) {
        ChatMessagePacket spyMessage = new ChatMessagePacket(
                original.getSenderId(),
                original.getSenderName(),
                original.getClientId(),
                original.getChannelId(),
                original.getContent()
        );
        spyMessage.setPlaceholders(original.getPlaceholders());
        // Add a placeholder to indicate this is a spy message
        spyMessage.addPlaceholder("_spy", "true");
        spyMessage.addPlaceholder("_spy_source_client", 
                original.getClientId() != null ? original.getClientId() : "unknown");
        return spyMessage;
    }

    /**
     * Finds the connection for a super admin.
     *
     * @param adminId the admin's UUID
     * @return the connection, or null if not found
     */
    private ClientConnection findAdminConnection(UUID adminId) {
        for (ClientConnection connection : networkHandler.getConnections()) {
            if (adminId.equals(connection.getSuperAdminUuid())) {
                return connection;
            }
        }
        return null;
    }

    /**
     * Sends a message from a spying admin to the monitored channel.
     * 
     * Requirements: 17.3
     *
     * @param adminId   the super admin's UUID
     * @param channelId the channel to send to
     * @param content   the message content
     * @return the result of the operation
     */
    public SpyResult sendToMonitoredChannel(UUID adminId, String channelId, String content) {
        if (adminId == null) {
            return SpyResult.failure("NC-400", "Admin ID is required");
        }
        if (channelId == null || channelId.isEmpty()) {
            return SpyResult.failure("NC-400", "Channel ID is required");
        }
        if (content == null || content.isEmpty()) {
            return SpyResult.failure("NC-400", "Message content is required");
        }

        // Verify admin is spying on this channel
        if (!isSpying(adminId, channelId)) {
            return SpyResult.failure("NC-403", "Not monitoring channel: " + channelId);
        }

        // Get the spy session to check if sending is allowed
        SpySession session = getSpySession(adminId, channelId);
        if (session == null || !session.canSend()) {
            return SpyResult.failure("NC-403", "Sending is disabled for this spy session");
        }

        // Verify channel exists
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return SpyResult.failure("NC-404", "Channel not found: " + channelId);
        }

        // Create and send the message
        ChatMessagePacket message = new ChatMessagePacket(
                adminId,
                "[SPY]", // Sender name indicates spy mode
                channel.getClientId(),
                channelId,
                content
        );
        message.addPlaceholder("_spy_sender", "true");

        // Find the target client connection and send
        String targetClientId = channel.getClientId();
        if (targetClientId != null) {
            ClientConnection targetConnection = networkHandler.findByClientId(targetClientId);
            if (targetConnection != null && targetConnection.isActive()) {
                targetConnection.sendPacket(message);
                logger.info("Super admin {} sent message to monitored channel {}", adminId, channelId);
                return SpyResult.success("Message sent to channel: " + channelId);
            } else {
                return SpyResult.failure("NC-503", "Target client is not connected");
            }
        } else {
            // Global channel - broadcast to all clients
            networkHandler.broadcastAuthenticated(message);
            logger.info("Super admin {} sent message to global channel {}", adminId, channelId);
            return SpyResult.success("Message sent to global channel: " + channelId);
        }
    }

    /**
     * Gets a specific spy session.
     *
     * @param adminId   the admin's UUID
     * @param channelId the channel ID
     * @return the spy session, or null if not found
     */
    private SpySession getSpySession(UUID adminId, String channelId) {
        Set<SpySession> sessions = spySessions.get(adminId);
        if (sessions == null) {
            return null;
        }
        return sessions.stream()
                .filter(s -> s.getChannelId().equals(channelId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets a list of all channels being monitored by any admin.
     *
     * @return list of channel IDs
     */
    public List<String> getAllMonitoredChannels() {
        return new ArrayList<>(channelSpies.keySet());
    }

    /**
     * Gets the total number of active spy sessions across all admins.
     *
     * @return total session count
     */
    public int getTotalSpySessionCount() {
        return spySessions.values().stream()
                .mapToInt(Set::size)
                .sum();
    }

    /**
     * Clears all spy sessions. Used for testing or shutdown.
     */
    public void clear() {
        spySessions.clear();
        channelSpies.clear();
        logger.info("Cleared all spy sessions");
    }
}
