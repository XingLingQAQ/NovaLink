package com.nova.link.channel;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.spy.SpyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiPredicate;

/**
 * Routes chat messages to appropriate recipients based on channel scope.
 * Enforces client boundary isolation for SERVER and PRIVATE scoped channels.
 * Also forwards messages to super admins in spy mode.
 * 
 * Requirements: 3.2, 3.5, 4.2, 4.3, 5.3, 17.2
 * - Route messages based on channel scope (GLOBAL/SERVER/PRIVATE)
 * - Enforce client boundary isolation for SERVER scope
 * - SERVER channel data flow SHALL never cross server boundaries
 * - Route GLOBAL messages to all connected clients with permission filtering
 * - Forward messages to super admins monitoring channels (spy mode)
 */
public class MessageRouter {

    private static final Logger logger = LoggerFactory.getLogger(MessageRouter.class);

    private final ChannelManager channelManager;
    private final ServerNetworkHandler networkHandler;
    
    /**
     * Permission checker function: (clientId, permission) -> hasPermission
     * Used to filter recipients for global channels based on permission nodes.
     */
    private BiPredicate<String, String> permissionChecker;
    
    /**
     * SpyManager for forwarding messages to monitoring super admins.
     * Requirements: 17.2
     */
    private SpyManager spyManager;
    
    /**
     * WebSocket message handler for real-time streaming to web panel.
     * Requirements: 24.2
     */
    private WebSocketBroadcaster webSocketBroadcaster;

    public MessageRouter(ChannelManager channelManager, ServerNetworkHandler networkHandler) {
        this.channelManager = Objects.requireNonNull(channelManager, "ChannelManager cannot be null");
        this.networkHandler = Objects.requireNonNull(networkHandler, "ServerNetworkHandler cannot be null");
        // Default permission checker allows all (permission check delegated to client)
        this.permissionChecker = (clientId, permission) -> true;
    }
    
    /**
     * Sets the permission checker for filtering global channel recipients.
     * 
     * @param permissionChecker function that checks if a client has a permission
     */
    public void setPermissionChecker(BiPredicate<String, String> permissionChecker) {
        this.permissionChecker = permissionChecker != null ? permissionChecker : (c, p) -> true;
    }

    /**
     * Sets the SpyManager for forwarding messages to monitoring super admins.
     * 
     * Requirements: 17.2
     *
     * @param spyManager the spy manager
     */
    public void setSpyManager(SpyManager spyManager) {
        this.spyManager = spyManager;
    }

    /**
     * Sets the WebSocket broadcaster for real-time streaming to web panel.
     * 
     * Requirements: 24.2
     *
     * @param webSocketBroadcaster the WebSocket broadcaster
     */
    public void setWebSocketBroadcaster(WebSocketBroadcaster webSocketBroadcaster) {
        this.webSocketBroadcaster = webSocketBroadcaster;
    }
    
    /**
     * Functional interface for WebSocket broadcasting.
     * Requirements: 24.2
     */
    @FunctionalInterface
    public interface WebSocketBroadcaster {
        void broadcastChatMessage(String channelId, String senderId, String senderName, String content);
    }

    /**
     * Routes a chat message to all eligible recipients based on channel scope.
     * 
     * Routing rules:
     * - GLOBAL: Message is sent to all connected clients
     * - SERVER: Message is sent only to the client that owns the channel
     * - PRIVATE: Message is sent only to the client that owns the channel
     *
     * @param message the chat message packet to route
     * @return the set of client IDs that received the message
     */
    public Set<String> routeMessage(ChatMessagePacket message) {
        Objects.requireNonNull(message, "Message cannot be null");
        
        String channelId = message.getChannelId();
        Channel channel = channelManager.getChannel(channelId);
        
        if (channel == null) {
            logger.warn("Cannot route message: channel '{}' not found", channelId);
            return Collections.emptySet();
        }
        
        return routeToChannel(channel, message);
    }

    /**
     * Routes a message to a specific channel.
     * Also forwards the message to any super admins monitoring this channel.
     *
     * @param channel the target channel
     * @param message the message to route
     * @return the set of client IDs that received the message
     */
    public Set<String> routeToChannel(Channel channel, ChatMessagePacket message) {
        Objects.requireNonNull(channel, "Channel cannot be null");
        Objects.requireNonNull(message, "Message cannot be null");
        
        Set<String> recipientClients = new HashSet<>();
        
        switch (channel.getScope()) {
            case GLOBAL:
                recipientClients = routeGlobalMessage(channel, message);
                break;
            case SERVER:
                recipientClients = routeServerMessage(channel, message);
                break;
            case PRIVATE:
                recipientClients = routePrivateMessage(channel, message);
                break;
        }
        
        // Forward to super admins in spy mode (Requirements: 17.2)
        forwardToSpies(message);
        
        // Forward to web panel via WebSocket (Requirements: 24.2)
        forwardToWebSocket(message);
        
        logger.debug("Routed message to channel '{}' (scope={}): {} recipient client(s)",
                channel.getId(), channel.getScope(), recipientClients.size());
        
        return recipientClients;
    }

    /**
     * Forwards a message to web panel clients via WebSocket.
     * 
     * Requirements: 24.2
     *
     * @param message the message to forward
     */
    private void forwardToWebSocket(ChatMessagePacket message) {
        if (webSocketBroadcaster != null) {
            webSocketBroadcaster.broadcastChatMessage(
                    message.getChannelId(),
                    message.getSenderId() != null ? message.getSenderId().toString() : null,
                    message.getSenderName(),
                    message.getContent()
            );
        }
    }

    /**
     * Forwards a message to super admins monitoring the channel.
     * 
     * Requirements: 17.2
     *
     * @param message the message to forward
     */
    private void forwardToSpies(ChatMessagePacket message) {
        if (spyManager != null) {
            spyManager.forwardToSpies(message);
        }
    }

    /**
     * Routes a GLOBAL scoped message to all connected clients.
     * Global messages cross all client boundaries.
     * 
     * Requirements: 4.2, 4.3
     * - Check player's MC permission node before routing
     * - Route to all clients with all qualified online members
     *
     * @param channel the global channel
     * @param message the message to route
     * @return set of client IDs that received the message
     */
    private Set<String> routeGlobalMessage(Channel channel, ChatMessagePacket message) {
        Set<String> recipientClients = new HashSet<>();
        String requiredPermission = channel.getPermission();
        
        // Send to all authenticated connections that have permission
        for (ClientConnection connection : networkHandler.getConnections()) {
            if (connection.isAuthenticated() && connection.isActive()) {
                String clientId = connection.getClientId();
                
                // Check permission if required
                if (requiredPermission != null && !requiredPermission.isEmpty()) {
                    if (!permissionChecker.test(clientId, requiredPermission)) {
                        logger.debug("Client '{}' filtered out from global channel '{}' - missing permission '{}'",
                                clientId, channel.getId(), requiredPermission);
                        continue;
                    }
                }
                
                connection.sendPacket(message);
                if (clientId != null) {
                    recipientClients.add(clientId);
                }
            }
        }
        
        logger.debug("Routed global message to {} client(s) for channel '{}'", 
                recipientClients.size(), channel.getId());
        
        return recipientClients;
    }

    /**
     * Routes a SERVER scoped message only to the owning client.
     * SERVER messages NEVER cross client boundaries (physical isolation).
     *
     * @param channel the server channel
     * @param message the message to route
     * @return set of client IDs that received the message (at most one)
     */
    private Set<String> routeServerMessage(Channel channel, ChatMessagePacket message) {
        Set<String> recipientClients = new HashSet<>();
        String targetClientId = channel.getClientId();
        
        if (targetClientId == null) {
            logger.error("SERVER channel '{}' has no clientId - cannot route", channel.getId());
            return recipientClients;
        }
        
        // Find the connection for this client and send only to it
        ClientConnection targetConnection = networkHandler.findByClientId(targetClientId);
        if (targetConnection != null && targetConnection.isActive()) {
            targetConnection.sendPacket(message);
            recipientClients.add(targetClientId);
        }
        
        return recipientClients;
    }

    /**
     * Routes a PRIVATE scoped message only to the owning client.
     * PRIVATE messages are isolated to the client where the channel was created.
     *
     * @param channel the private channel
     * @param message the message to route
     * @return set of client IDs that received the message (at most one)
     */
    private Set<String> routePrivateMessage(Channel channel, ChatMessagePacket message) {
        // Private channels have the same routing as server channels - 
        // they are bound to a single client
        return routeServerMessage(channel, message);
    }

    /**
     * Determines which clients should receive a message for a given channel.
     * This method does NOT send the message, only calculates recipients.
     * Useful for testing and validation.
     * 
     * Requirements: 4.2, 4.3
     * - For GLOBAL channels, filter by permission node
     *
     * @param channel the target channel
     * @param senderClientId the client ID of the sender
     * @return set of client IDs that would receive the message
     */
    public Set<String> calculateRecipients(Channel channel, String senderClientId) {
        Objects.requireNonNull(channel, "Channel cannot be null");
        
        Set<String> recipients = new HashSet<>();
        
        switch (channel.getScope()) {
            case GLOBAL:
                // Global channels reach all connected clients with permission
                String requiredPermission = channel.getPermission();
                for (ClientConnection connection : networkHandler.getConnections()) {
                    if (connection.isAuthenticated() && connection.getClientId() != null) {
                        String clientId = connection.getClientId();
                        
                        // Check permission if required
                        if (requiredPermission != null && !requiredPermission.isEmpty()) {
                            if (!permissionChecker.test(clientId, requiredPermission)) {
                                continue;
                            }
                        }
                        
                        recipients.add(clientId);
                    }
                }
                break;
                
            case SERVER:
            case PRIVATE:
                // Server and private channels are isolated to their owning client
                String channelClientId = channel.getClientId();
                if (channelClientId != null) {
                    // Only add if the client is actually connected
                    ClientConnection connection = networkHandler.findByClientId(channelClientId);
                    if (connection != null && connection.isAuthenticated()) {
                        recipients.add(channelClientId);
                    }
                }
                break;
        }
        
        return recipients;
    }

    /**
     * Validates that a message sender is allowed to send to a channel.
     * For SERVER and PRIVATE channels, the sender must be from the same client.
     *
     * @param channel the target channel
     * @param senderClientId the client ID of the sender
     * @return true if the sender can send to this channel
     */
    public boolean canSendToChannel(Channel channel, String senderClientId) {
        Objects.requireNonNull(channel, "Channel cannot be null");
        
        switch (channel.getScope()) {
            case GLOBAL:
                // Anyone can send to global channels (permission check is separate)
                return true;
                
            case SERVER:
            case PRIVATE:
                // Must be from the same client
                return channel.getClientId() != null && 
                       channel.getClientId().equals(senderClientId);
        }
        
        return false;
    }

    /**
     * Routes a message to a channel by channel ID.
     * Convenience method for REST API integration.
     * 
     * Requirements: 25.4 - REST API for external integration
     *
     * @param channelId the target channel ID
     * @param senderId the sender UUID
     * @param senderName the sender display name
     * @param content the message content
     * @param placeholders additional placeholders
     * @return the set of client IDs that received the message
     */
    public Set<String> routeMessage(String channelId, UUID senderId, String senderName, 
                                     String content, Map<String, String> placeholders) {
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            logger.warn("Cannot route message: channel '{}' not found", channelId);
            return Collections.emptySet();
        }

        ChatMessagePacket message = new ChatMessagePacket(
            senderId,
            senderName,
            channel.getClientId() != null ? channel.getClientId() : "API",
            channelId,
            content
        );

        if (placeholders != null) {
            message.setPlaceholders(placeholders);
        }

        return routeToChannel(channel, message);
    }
}
