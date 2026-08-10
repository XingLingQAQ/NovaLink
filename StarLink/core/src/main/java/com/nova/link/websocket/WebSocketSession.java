package com.nova.link.websocket;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a WebSocket session for a web panel connection.
 * Tracks authentication state and channel subscriptions.
 * 
 * Requirements: 24.1, 24.4
 */
public class WebSocketSession {

    private final String sessionId;
    private final Channel channel;
    private final long connectedAt;
    
    // Authentication state
    private volatile boolean authenticated = false;
    private volatile String userId;
    private volatile String username;
    private volatile String role;
    
    // Channel subscriptions
    private final Set<String> subscribedChannels;

    /**
     * Creates a new WebSocket session.
     *
     * @param channel the Netty channel
     */
    public WebSocketSession(Channel channel) {
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        this.channel = channel;
        this.connectedAt = System.currentTimeMillis();
        this.subscribedChannels = ConcurrentHashMap.newKeySet();
    }

    /**
     * Sends a JSON message to this session.
     *
     * @param json the JSON message to send
     */
    public void send(String json) {
        if (channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    /**
     * Closes this session.
     */
    public void close() {
        if (channel.isActive()) {
            channel.close();
        }
    }

    /**
     * Checks if this session is active.
     *
     * @return true if active
     */
    public boolean isActive() {
        return channel.isActive();
    }

    /**
     * Gets the session ID.
     *
     * @return the session ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Gets the underlying Netty channel.
     *
     * @return the channel
     */
    public Channel getChannel() {
        return channel;
    }

    /**
     * Gets the connection timestamp.
     *
     * @return the timestamp in milliseconds
     */
    public long getConnectedAt() {
        return connectedAt;
    }

    /**
     * Checks if this session is authenticated.
     *
     * @return true if authenticated
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Sets the authentication state.
     *
     * @param userId   the user ID
     * @param username the username
     * @param role     the user role
     */
    public void setAuthenticated(String userId, String username, String role) {
        this.authenticated = true;
        this.userId = userId;
        this.username = username;
        this.role = role;
    }

    /**
     * Gets the user ID.
     *
     * @return the user ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Gets the username.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Gets the user role.
     *
     * @return the role
     */
    public String getRole() {
        return role;
    }

    /**
     * Subscribes to a channel.
     *
     * @param channelId the channel ID
     */
    public void subscribe(String channelId) {
        subscribedChannels.add(channelId);
    }

    /**
     * Unsubscribes from a channel.
     *
     * @param channelId the channel ID
     */
    public void unsubscribe(String channelId) {
        subscribedChannels.remove(channelId);
    }

    /**
     * Checks if subscribed to a channel.
     *
     * @param channelId the channel ID
     * @return true if subscribed
     */
    public boolean isSubscribed(String channelId) {
        return subscribedChannels.contains(channelId);
    }

    /**
     * Gets all subscribed channels.
     *
     * @return set of channel IDs
     */
    public Set<String> getSubscribedChannels() {
        return Set.copyOf(subscribedChannels);
    }

    /**
     * Clears all subscriptions.
     */
    public void clearSubscriptions() {
        subscribedChannels.clear();
    }

    /**
     * Gets the remote address.
     *
     * @return the remote address as string
     */
    public String getRemoteAddress() {
        if (channel.remoteAddress() != null) {
            return channel.remoteAddress().toString();
        }
        return "unknown";
    }

    @Override
    public String toString() {
        return "WebSocketSession{" +
                "sessionId='" + sessionId + '\'' +
                ", authenticated=" + authenticated +
                ", username='" + username + '\'' +
                ", role='" + role + '\'' +
                ", subscriptions=" + subscribedChannels.size() +
                '}';
    }
}
