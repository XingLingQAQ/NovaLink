package com.nova.link.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.AuthResult;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Handles WebSocket messages from web panel clients.
 * Processes authentication, subscriptions, and data requests.
 * 
 * Requirements: 24.1, 24.2, 24.4
 */
public class WebSocketMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketMessageHandler.class);
    
    private final JwtService jwtService;
    private final AuthManager authManager;
    private final ChannelManager channelManager;
    private final ServerNetworkHandler networkHandler;
    private final PlayerStateManager playerStateManager;
    private final Gson gson;

    // Session management
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public WebSocketMessageHandler(JwtService jwtService, AuthManager authManager,
                                   ChannelManager channelManager, ServerNetworkHandler networkHandler,
                                   PlayerStateManager playerStateManager) {
        this.jwtService = jwtService;
        this.authManager = authManager;
        this.channelManager = channelManager;
        this.networkHandler = networkHandler;
        this.playerStateManager = playerStateManager;
        this.gson = new Gson();
    }

    /**
     * Registers a new WebSocket session.
     *
     * @param session the session to register
     */
    public void registerSession(WebSocketSession session) {
        sessions.put(session.getSessionId(), session);
        logger.info("WebSocket session registered: {} (total: {})", 
                session.getSessionId(), sessions.size());
    }

    /**
     * Unregisters a WebSocket session.
     *
     * @param session the session to unregister
     */
    public void unregisterSession(WebSocketSession session) {
        sessions.remove(session.getSessionId());
        logger.info("WebSocket session unregistered: {} (total: {})", 
                session.getSessionId(), sessions.size());
    }

    /**
     * Handles an incoming WebSocket message.
     *
     * @param session the session that sent the message
     * @param message the JSON message
     */
    public void handleMessage(WebSocketSession session, String message) {
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.has("type") ? json.get("type").getAsString() : null;
            
            if (type == null) {
                sendError(session, "Missing message type");
                return;
            }
            
            switch (type) {
                case "auth":
                    handleAuth(session, json);
                    break;
                case "subscribe":
                    handleSubscribe(session, json);
                    break;
                case "unsubscribe":
                    handleUnsubscribe(session, json);
                    break;
                case "ping":
                    handlePing(session, json);
                    break;
                case "get_channels":
                    handleGetChannels(session);
                    break;
                case "get_clients":
                    handleGetClients(session);
                    break;
                case "get_players":
                    handleGetPlayers(session);
                    break;
                default:
                    sendError(session, "Unknown message type: " + type);
            }
        } catch (Exception e) {
            logger.error("Error handling WebSocket message", e);
            sendError(session, "Invalid message format");
        }
    }

    /**
     * Handles authentication request.
     * Requirements: 24.4
     */
    private void handleAuth(WebSocketSession session, JsonObject json) {
        String token = json.has("token") ? json.get("token").getAsString() : null;
        
        if (token == null || token.isEmpty()) {
            sendAuthResponse(session, false, "Missing token");
            return;
        }
        
        // Validate JWT token
        Claims claims = jwtService.validateToken(token);
        if (claims == null) {
            sendAuthResponse(session, false, "Invalid or expired token");
            return;
        }

        // Refresh tokens must NOT be accepted for WebSocket auth.
        String tokenType = claims.get("type", String.class);
        if ("refresh".equals(tokenType)) {
            sendAuthResponse(session, false, "Refresh token is not allowed for WebSocket authentication");
            return;
        }
        
        String userId = claims.getSubject();
        String username = claims.get("username", String.class);
        String role = claims.get("role", String.class);

        if (userId == null || userId.isBlank() || username == null || username.isBlank() || role == null || role.isBlank()) {
            sendAuthResponse(session, false, "Invalid token claims");
            return;
        }
        
        // Set session as authenticated
        session.setAuthenticated(userId, username, role);
        
        logger.info("WebSocket session authenticated: {} as {} ({})", 
                session.getSessionId(), username, role);
        
        sendAuthResponse(session, true, null);
    }

    /**
     * Handles channel subscription request.
     * Requirements: 24.2
     */
    private void handleSubscribe(WebSocketSession session, JsonObject json) {
        if (!session.isAuthenticated()) {
            sendError(session, "Not authenticated");
            return;
        }
        
        if (!json.has("channels")) {
            sendError(session, "Missing channels array");
            return;
        }
        
        List<String> channels = new ArrayList<>();
        json.getAsJsonArray("channels").forEach(e -> channels.add(e.getAsString()));
        
        for (String channelId : channels) {
            session.subscribe(channelId);
        }
        
        logger.debug("Session {} subscribed to channels: {}", session.getSessionId(), channels);
        
        // Send confirmation
        JsonObject response = new JsonObject();
        response.addProperty("type", "subscribed");
        response.add("channels", gson.toJsonTree(channels));
        session.send(gson.toJson(response));
    }

    /**
     * Handles channel unsubscription request.
     */
    private void handleUnsubscribe(WebSocketSession session, JsonObject json) {
        if (!session.isAuthenticated()) {
            sendError(session, "Not authenticated");
            return;
        }
        
        if (!json.has("channels")) {
            sendError(session, "Missing channels array");
            return;
        }
        
        List<String> channels = new ArrayList<>();
        json.getAsJsonArray("channels").forEach(e -> channels.add(e.getAsString()));
        
        for (String channelId : channels) {
            session.unsubscribe(channelId);
        }
        
        logger.debug("Session {} unsubscribed from channels: {}", session.getSessionId(), channels);
        
        // Send confirmation
        JsonObject response = new JsonObject();
        response.addProperty("type", "unsubscribed");
        response.add("channels", gson.toJsonTree(channels));
        session.send(gson.toJson(response));
    }

    /**
     * Handles ping request.
     */
    private void handlePing(WebSocketSession session, JsonObject json) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "pong");
        response.addProperty("timestamp", System.currentTimeMillis());
        session.send(gson.toJson(response));
    }

    /**
     * Handles get channels request.
     * Requirements: 24.3
     */
    private void handleGetChannels(WebSocketSession session) {
        if (!session.isAuthenticated()) {
            sendError(session, "Not authenticated");
            return;
        }
        
        List<Map<String, Object>> channelList = new ArrayList<>();
        for (Channel channel : channelManager.getAllChannels()) {
            Map<String, Object> channelData = new HashMap<>();
            channelData.put("id", channel.getId());
            channelData.put("displayName", channel.getDisplayName());
            channelData.put("scope", channel.getScope().name());
            channelData.put("clientId", channel.getClientId());
            channelData.put("memberCount", channel.getMembers().size());
            channelData.put("maxCapacity", channel.getMaxCapacity());
            channelList.add(channelData);
        }
        
        JsonObject response = new JsonObject();
        response.addProperty("type", "channel_update");
        response.add("channels", gson.toJsonTree(channelList));
        response.addProperty("timestamp", System.currentTimeMillis());
        session.send(gson.toJson(response));
    }

    /**
     * Handles get clients request.
     * Requirements: 24.3
     */
    private void handleGetClients(WebSocketSession session) {
        if (!session.isAuthenticated()) {
            sendError(session, "Not authenticated");
            return;
        }
        
        List<Map<String, Object>> clientList = new ArrayList<>();
        for (ClientConnection connection : networkHandler.getConnections()) {
            if (connection.isAuthenticated()) {
                Map<String, Object> clientData = new HashMap<>();
                clientData.put("id", connection.getClientId());
                clientData.put("connectionId", connection.getConnectionId());
                clientData.put("remoteAddress", connection.getRemoteAddress());
                clientData.put("connectedAt", connection.getConnectedAt());
                clientData.put("active", connection.isActive());
                clientData.put("platform", connection.getPlatform() != null
                        ? connection.getPlatform().name() : "Unknown");
                clientData.put("ping", connection.getPing());
                clientData.put("players", countPlayersByClient(connection.getClientId()));
                clientList.add(clientData);
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "server_status");
        response.add("clients", gson.toJsonTree(clientList));
        response.addProperty("totalConnections", networkHandler.getConnectionCount());
        response.addProperty("timestamp", System.currentTimeMillis());
        session.send(gson.toJson(response));
    }

    /**
     * Counts the cached player states whose originating client id matches the
     * given game-server client. Used to populate the per-server online-player
     * count in the server-status broadcast.
     */
    private int countPlayersByClient(String clientId) {
        if (clientId == null || playerStateManager == null) {
            return 0;
        }
        int count = 0;
        for (PlayerState state : playerStateManager.getAllPlayerStates()) {
            if (clientId.equals(state.getClientId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Handles get players request.
     * Requirements: 24.3
     */
    private void handleGetPlayers(WebSocketSession session) {
        if (!session.isAuthenticated()) {
            sendError(session, "Not authenticated");
            return;
        }
        
        // Collect all players from all channels
        Set<UUID> allPlayers = new HashSet<>();
        Map<UUID, Set<String>> playerChannels = new HashMap<>();

        for (Channel channel : channelManager.getAllChannels()) {
            for (UUID playerId : channel.getMembers()) {
                allPlayers.add(playerId);
                playerChannels.computeIfAbsent(playerId, k -> new HashSet<>())
                        .add(channel.getId());
            }
        }

        List<Map<String, Object>> playerList = new ArrayList<>();
        for (UUID playerId : allPlayers) {
            Map<String, Object> playerData = new HashMap<>();
            playerData.put("uuid", playerId.toString());
            playerData.put("channels", playerChannels.get(playerId));
            // Enrich with real state from the cache so the panel can show
            // name / originating server / mute status instead of placeholders.
            PlayerState state = playerStateManager != null
                    ? playerStateManager.getPlayerState(playerId) : null;
            if (state != null) {
                playerData.put("name", state.getPlayerName() != null
                        ? state.getPlayerName() : playerId.toString());
                playerData.put("server", state.getClientId());
                playerData.put("muted", state.getMutes() != null && !state.getMutes().isEmpty());
            } else {
                playerData.put("name", playerId.toString());
                playerData.put("server", null);
                playerData.put("muted", false);
            }
            playerList.add(playerData);
        }
        
        JsonObject response = new JsonObject();
        response.addProperty("type", "player_update");
        response.add("players", gson.toJsonTree(playerList));
        response.addProperty("totalPlayers", allPlayers.size());
        response.addProperty("timestamp", System.currentTimeMillis());
        session.send(gson.toJson(response));
    }

    /**
     * Sends an authentication response.
     */
    private void sendAuthResponse(WebSocketSession session, boolean success, String error) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "auth_response");
        response.addProperty("success", success);
        if (error != null) {
            response.addProperty("error", error);
        }
        if (success) {
            response.addProperty("userId", session.getUserId());
            response.addProperty("username", session.getUsername());
            response.addProperty("role", session.getRole());
        }
        response.addProperty("timestamp", System.currentTimeMillis());
        session.send(gson.toJson(response));
    }

    /**
     * Sends an error message.
     */
    private void sendError(WebSocketSession session, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "error");
        response.addProperty("error", message);
        response.addProperty("timestamp", System.currentTimeMillis());
        session.send(gson.toJson(response));
    }

    /**
     * Broadcasts a chat message to subscribed sessions.
     * Requirements: 24.2
     *
     * @param channelId   the channel ID
     * @param senderId    the sender UUID
     * @param senderName  the sender name
     * @param content     the message content
     */
    public void broadcastChatMessage(String channelId, String senderId, String senderName, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "chat");
        message.addProperty("channelId", channelId);
        message.addProperty("senderId", senderId);
        message.addProperty("senderName", senderName);
        message.addProperty("content", content);
        message.addProperty("timestamp", System.currentTimeMillis());
        // Attach the originating server (client id) so the panel can show
        // which game server the message came from.
        String senderClient = null;
        if (senderId != null && playerStateManager != null) {
            try {
                UUID senderUuid = UUID.fromString(senderId);
                PlayerState state = playerStateManager.getPlayerState(senderUuid);
                if (state != null) {
                    senderClient = state.getClientId();
                }
            } catch (IllegalArgumentException ignored) {
                // senderId not a UUID — leave server null
            }
        }
        message.addProperty("server", senderClient != null ? senderClient : "");
        
        String json = gson.toJson(message);
        
        for (WebSocketSession session : sessions.values()) {
            if (session.isAuthenticated() && session.isActive() && session.isSubscribed(channelId)) {
                session.send(json);
            }
        }
    }

    /**
     * Broadcasts a server status update to all authenticated sessions.
     * Requirements: 24.2
     */
    public void broadcastServerStatus() {
        List<Map<String, Object>> clientList = new ArrayList<>();
        for (ClientConnection connection : networkHandler.getConnections()) {
            if (connection.isAuthenticated()) {
                Map<String, Object> clientData = new HashMap<>();
                clientData.put("id", connection.getClientId());
                clientData.put("connectionId", connection.getConnectionId());
                clientData.put("remoteAddress", connection.getRemoteAddress());
                clientData.put("connectedAt", connection.getConnectedAt());
                clientData.put("active", connection.isActive());
                clientData.put("platform", connection.getPlatform() != null
                        ? connection.getPlatform().name() : "Unknown");
                clientData.put("ping", connection.getPing());
                clientData.put("players", countPlayersByClient(connection.getClientId()));
                clientList.add(clientData);
            }
        }
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "server_status");
        message.add("clients", gson.toJsonTree(clientList));
        message.addProperty("totalConnections", networkHandler.getConnectionCount());
        message.addProperty("timestamp", System.currentTimeMillis());
        
        String json = gson.toJson(message);
        
        for (WebSocketSession session : sessions.values()) {
            if (session.isAuthenticated() && session.isActive()) {
                session.send(json);
            }
        }
    }

    /**
     * Broadcasts a channel update to all authenticated sessions.
     * Requirements: 24.2
     */
    public void broadcastChannelUpdate() {
        List<Map<String, Object>> channelList = new ArrayList<>();
        for (Channel channel : channelManager.getAllChannels()) {
            Map<String, Object> channelData = new HashMap<>();
            channelData.put("id", channel.getId());
            channelData.put("displayName", channel.getDisplayName());
            channelData.put("scope", channel.getScope().name());
            channelData.put("clientId", channel.getClientId());
            channelData.put("memberCount", channel.getMembers().size());
            channelData.put("maxCapacity", channel.getMaxCapacity());
            channelList.add(channelData);
        }
        
        JsonObject message = new JsonObject();
        message.addProperty("type", "channel_update");
        message.add("channels", gson.toJsonTree(channelList));
        message.addProperty("timestamp", System.currentTimeMillis());
        
        String json = gson.toJson(message);
        
        for (WebSocketSession session : sessions.values()) {
            if (session.isAuthenticated() && session.isActive()) {
                session.send(json);
            }
        }
    }

    /**
     * Sends a notification to all authenticated sessions.
     *
     * @param title   the notification title
     * @param message the notification message
     * @param level   the notification level (info, warning, error)
     */
    public void broadcastNotification(String title, String message, String level) {
        JsonObject notification = new JsonObject();
        notification.addProperty("type", "notification");
        notification.addProperty("title", title);
        notification.addProperty("message", message);
        notification.addProperty("level", level);
        notification.addProperty("timestamp", System.currentTimeMillis());
        
        String json = gson.toJson(notification);
        
        for (WebSocketSession session : sessions.values()) {
            if (session.isAuthenticated() && session.isActive()) {
                session.send(json);
            }
        }
    }

    /**
     * Gets the number of active sessions.
     *
     * @return the session count
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * Gets all active sessions.
     *
     * @return collection of sessions
     */
    public Collection<WebSocketSession> getSessions() {
        return Collections.unmodifiableCollection(sessions.values());
    }
}
