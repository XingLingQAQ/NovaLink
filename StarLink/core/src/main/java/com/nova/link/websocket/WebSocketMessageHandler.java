package com.nova.link.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.PanelResourcePolicy;
import com.nova.link.auth.PanelRole;
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
import java.util.concurrent.atomic.AtomicLong;
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
    private final PanelResourcePolicy resourcePolicy;
    private final ChannelManager channelManager;
    private final ServerNetworkHandler networkHandler;
    private final PlayerStateManager playerStateManager;
    private final Gson gson;

    // Session management
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * Server-wide monotonic revision counter (PANEL-008). Every outbound WS
     * snapshot/event payload built by this handler is stamped with
     * {@code revisionCounter.incrementAndGet()}. Clients use it to discard
     * out-of-order/stale updates: any update whose revision is older than the
     * last-applied revision for that entity type is ignored. A single global
     * counter is sufficient because it preserves causal ordering across all
     * entity types and sessions — if payload B was built after payload A, B's
     * revision is greater than A's, so clients always keep the newer state.
     */
    private final AtomicLong revisionCounter = new AtomicLong(0);

    public WebSocketMessageHandler(JwtService jwtService, AuthManager authManager,
                                   ChannelManager channelManager, ServerNetworkHandler networkHandler,
                                   PlayerStateManager playerStateManager) {
        this.jwtService = jwtService;
        this.authManager = authManager;
        this.channelManager = channelManager;
        this.resourcePolicy = new PanelResourcePolicy(authManager, channelManager);
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
        
        PanelRole effectiveRole = resourcePolicy.resolveRole(username, role);
        if (effectiveRole == null) {
            sendAuthResponse(session, false, "Invalid panel role");
            return;
        }

        // Set session as authenticated with the current account role. The
        // policy resolves it again before every resource operation so a later
        // role change also takes effect without reconnecting.
        session.setAuthenticated(userId, username, effectiveRole.name());
        
        logger.info("WebSocket session authenticated: {} as {} ({})", 
                session.getSessionId(), username, effectiveRole);
        
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
        
        if (!json.has("channels") || !json.get("channels").isJsonArray()) {
            sendError(session, "Missing channels array");
            return;
        }

        PanelRole role = effectiveRole(session);
        List<String> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (var element : json.getAsJsonArray("channels")) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                rejected.add("<invalid>");
                continue;
            }
            String channelId = element.getAsString();
            Channel channel = channelManager.getChannel(channelId);
            if (resourcePolicy.canSubscribe(role, channel)) {
                session.subscribe(channelId);
                accepted.add(channelId);
            } else {
                session.unsubscribe(channelId);
                rejected.add(channelId);
            }
        }

        if (!rejected.isEmpty()) {
            logger.warn("Rejected unauthorized channel subscriptions for session {} (user={}): {}",
                    session.getSessionId(), session.getUsername(), rejected);
        }
        logger.debug("Session {} subscribed to channels: {}", session.getSessionId(), accepted);
        
        // Send confirmation
        JsonObject response = new JsonObject();
        response.addProperty("type", "subscribed");
        response.add("channels", gson.toJsonTree(accepted));
        response.add("rejectedChannels", gson.toJsonTree(rejected));
        if (!rejected.isEmpty()) {
            response.addProperty("errorCode", "CHANNEL_NOT_ACCESSIBLE");
        }
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
        
        if (!json.has("channels") || !json.get("channels").isJsonArray()) {
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
        
        session.send(gson.toJson(buildChannelUpdate(session)));
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
        
        session.send(gson.toJson(buildServerStatus(effectiveRole(session))));
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

        session.send(gson.toJson(buildPlayerUpdate(effectiveRole(session))));
    }

    /**
     * Builds the {@code player_update} payload shared by the on-demand
     * {@code get_players} request and the event-triggered broadcast.
     */
    private JsonObject buildPlayerUpdate(PanelRole role) {
        // Collect all players from all channels
        Set<UUID> allPlayers = new HashSet<>();
        Map<UUID, Set<String>> playerChannels = new HashMap<>();

        for (Channel channel : channelManager.getAllChannels()) {
            if (!resourcePolicy.canViewChannel(role, channel)) {
                continue;
            }
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
                if (resourcePolicy.canViewInfrastructureSource(role)) {
                    playerData.put("server", state.getClientId());
                }
                playerData.put("muted", state.getMutes() != null && !state.getMutes().isEmpty());
                playerData.put("platform", state.getPlatform() != null
                        && !state.getPlatform().isEmpty()
                        ? state.getPlatform() : "Java");
            } else {
                playerData.put("name", playerId.toString());
                if (resourcePolicy.canViewInfrastructureSource(role)) {
                    playerData.put("server", null);
                }
                playerData.put("muted", false);
                playerData.put("platform", "Java");
            }
            playerList.add(playerData);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "player_update");
        response.add("players", gson.toJsonTree(playerList));
        response.addProperty("totalPlayers", allPlayers.size());
        response.addProperty("revision", revisionCounter.incrementAndGet());
        response.addProperty("timestamp", System.currentTimeMillis());
        return response;
    }

    /**
     * Broadcasts a {@code player_update} to all authenticated sessions.
     * Triggered on player join/leave so the panel reflects presence changes in
     * real time instead of waiting for the next {@code get_players} request.
     * Skips building the payload entirely when no panel session is listening.
     */
    public void broadcastPlayerUpdate() {
        for (WebSocketSession session : sessions.values()) {
            if (session.isAuthenticated() && session.isActive()) {
                session.send(gson.toJson(buildPlayerUpdate(effectiveRole(session))));
            }
        }
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
        Channel channel = channelManager.getChannel(channelId);
        long timestamp = System.currentTimeMillis();
        // PANEL-008: one revision per broadcast — every recipient sees the
        // same monotonic revision for this message, so a client that receives
        // it out of order (e.g. after a later snapshot via a reordered queue)
        // can still apply the monotonic guard consistently.
        long revision = revisionCounter.incrementAndGet();
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
        final String resolvedSenderClient = senderClient;

        for (WebSocketSession session : sessions.values()) {
            if (!session.isAuthenticated() || !session.isActive() || !session.isSubscribed(channelId)) {
                continue;
            }
            PanelRole role = effectiveRole(session);
            if (!resourcePolicy.canSubscribe(role, channel)) {
                session.unsubscribe(channelId);
                continue;
            }

            JsonObject message = new JsonObject();
            message.addProperty("type", "chat");
            message.addProperty("channelId", channelId);
            message.addProperty("senderId", senderId);
            message.addProperty("senderName", senderName);
            message.addProperty("content", content);
            message.addProperty("revision", revision);
            message.addProperty("timestamp", timestamp);
            if (resourcePolicy.canViewInfrastructureSource(role)) {
                message.addProperty("server", resolvedSenderClient != null ? resolvedSenderClient : "");
            }
            session.send(gson.toJson(message));
        }
    }

    /**
     * Broadcasts a server status update to all authenticated sessions.
     * Requirements: 24.2
     */
    public void broadcastServerStatus() {
        for (WebSocketSession session : sessions.values()) {
            if (session.isAuthenticated() && session.isActive()) {
                session.send(gson.toJson(buildServerStatus(effectiveRole(session))));
            }
        }
    }

    /**
     * Broadcasts a {@code settings_update} event to every authenticated,
     * active panel session (§11.6 Project 20, proposal 10).
     *
     * <p>Mirrors {@link #broadcastServerStatus()}: same session filter, same
     * revision-counter stamping via {@link #revisionCounter} so the PANEL-008
     * stale-discard guard on the client covers this payload too. Fired after
     * successful {@code handleUpdateSettings}, {@code handleRollbackConfig},
     * and {@code handleReload} in {@link RestApiHandler} so panel clients can
     * refresh their cached settings revision and feature flags without polling.
     *
     * <p>The top-level {@code revision} is the per-message monotonic counter
     * (stale-discard); {@code settingsRevision} is the PANEL-010 optimistic-
     * concurrency token the caller echoes back on {@code PUT /api/settings}.
     * Both are included so the client can order messages and decide whether
     * to refetch the full settings object.
     *
     * @param settingsRevision the post-mutation settings revision (from
     *                         {@link com.nova.link.config.ConfigManager#getSettingsRevision()})
     * @param features         the post-mutation feature flags; when {@code null}
     *                         the broadcast is skipped (no NPE) — the live
     *                         config may be mid-reload and its features not yet
     *                         re-read, so we prefer silence over a misleading
     *                         payload that could flip the panel to defaults.
     */
    public void broadcastSettingsUpdate(long settingsRevision,
                                        com.nova.link.config.FeatureConfig features) {
        if (features == null) {
            logger.debug("broadcastSettingsUpdate: features null, skipping broadcast");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "settings_update");
        payload.addProperty("revision", revisionCounter.incrementAndGet());
        payload.addProperty("settingsRevision", settingsRevision);
        payload.addProperty("filterEnabled", features.isFilterEnabled());
        payload.addProperty("messageLogEnabled", features.isMessageLogEnabled());
        payload.addProperty("crossServerChatEnabled", features.isCrossServerChatEnabled());
        payload.addProperty("privateMessagesEnabled", features.isPrivateMessagesEnabled());
        payload.addProperty("messageLogRetentionDays", features.getMessageLogRetentionDays());
        payload.addProperty("timestamp", System.currentTimeMillis());
        String message = gson.toJson(payload);
        for (WebSocketSession session : sessions.values()) {
            if (session.isAuthenticated() && session.isActive()) {
                session.send(message);
            }
        }
    }

    /**
     * Broadcasts a channel update to all authenticated sessions.
     * Requirements: 24.2
     */
    public void broadcastChannelUpdate() {
        for (WebSocketSession session : sessions.values()) {
            if (session.isAuthenticated() && session.isActive()) {
                session.send(gson.toJson(buildChannelUpdate(session)));
            }
        }
    }

    private PanelRole effectiveRole(WebSocketSession session) {
        return resourcePolicy.resolveRole(session.getUsername(), session.getRole());
    }

    private JsonObject buildChannelUpdate(WebSocketSession session) {
        PanelRole role = effectiveRole(session);
        List<Map<String, Object>> channelList = new ArrayList<>();
        List<String> subscribableChannelIds = new ArrayList<>();

        for (Channel channel : channelManager.getAllChannels()) {
            if (!resourcePolicy.canViewChannel(role, channel)) {
                continue;
            }
            Map<String, Object> channelData = new LinkedHashMap<>();
            channelData.put("id", channel.getId());
            channelData.put("displayName", channel.getDisplayName());
            channelData.put("scope", channel.getScope().name());
            // PANEL-003: per-channel provenance + revision so the WS channel_update
            // payload matches the REST channelToJson shape. Without these the
            // frontend adapter defaults source to RUNTIME/revision 0, which
            // overwrites CONFIG-managed channels on every 30s broadcast and
            // makes the read-only badge flicker. Do NOT confuse with the
            // top-level snapshot revision stamped below.
            channelData.put("source", channel.getSource().name());
            channelData.put("revision", channel.getRevision());
            channelData.put("clientId", channel.getClientId());
            channelData.put("memberCount", channel.getMembers().size());
            channelData.put("maxCapacity", channel.getMaxCapacity());
            channelData.put("slowModeSeconds", channel.getSlowModeSeconds());
            channelData.put("subscribable", resourcePolicy.canSubscribe(role, channel));
            channelData.put("sendable", resourcePolicy.canSend(role, channel));
            channelList.add(channelData);
            if (resourcePolicy.canSubscribe(role, channel)) {
                subscribableChannelIds.add(channel.getId());
            }
        }

        for (String subscribed : session.getSubscribedChannels()) {
            if (!subscribableChannelIds.contains(subscribed)) {
                session.unsubscribe(subscribed);
            }
        }

        JsonObject message = new JsonObject();
        message.addProperty("type", "channel_update");
        message.add("channels", gson.toJsonTree(channelList));
        message.add("subscribableChannelIds", gson.toJsonTree(subscribableChannelIds));
        message.addProperty("revision", revisionCounter.incrementAndGet());
        message.addProperty("timestamp", System.currentTimeMillis());
        return message;
    }

    private JsonObject buildServerStatus(PanelRole role) {
        List<Map<String, Object>> clientList = new ArrayList<>();
        for (ClientConnection connection : networkHandler.getConnections()) {
            if (!connection.isAuthenticated()) {
                continue;
            }
            Map<String, Object> clientData = new LinkedHashMap<>();
            clientData.put("id", connection.getClientId());
            if (resourcePolicy.canViewConnectionDetails(role)) {
                clientData.put("connectionId", connection.getConnectionId());
                clientData.put("remoteAddress", connection.getRemoteAddress());
            }
            clientData.put("connectedAt", connection.getConnectedAt());
            clientData.put("active", connection.isActive());
            clientData.put("platform", connection.getPlatform() != null
                    ? connection.getPlatform().name() : "Unknown");
            clientData.put("version", connection.getServerVersion() != null
                    && !connection.getServerVersion().isEmpty()
                    ? connection.getServerVersion() : "-");
            clientData.put("ping", connection.getPing());
            clientData.put("players", countPlayersByClient(connection.getClientId()));
            clientList.add(clientData);
        }

        JsonObject message = new JsonObject();
        message.addProperty("type", "server_status");
        message.add("clients", gson.toJsonTree(clientList));
        message.addProperty("totalConnections", networkHandler.getConnectionCount());
        message.addProperty("revision", revisionCounter.incrementAndGet());
        message.addProperty("timestamp", System.currentTimeMillis());
        return message;
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
        notification.addProperty("revision", revisionCounter.incrementAndGet());
        notification.addProperty("timestamp", System.currentTimeMillis());

        String json = gson.toJson(notification);

        for (WebSocketSession session : sessions.values()) {
            if (session.isAuthenticated() && session.isActive()) {
                session.send(json);
            }
        }
    }

    /**
     * Sends a notification to a single authenticated session matching the
     * given recipient username. Used by the directed-notification delivery path
     * (PANEL-014) so that a notification addressed to one admin reaches that
     * admin only, not every authenticated session.
     *
     * <p>PANEL-014: prior to this method the only WS delivery API was
     * {@link #broadcastNotification(String, String, String)}, which sends to
     * EVERY authenticated session regardless of recipient — a per-user WS
     * isolation defect surfaced by the VERIFY-013 §7 two-user E2E slice. A
     * null or empty {@code recipient} falls back to a plain broadcast (the
     * call site should already branch on recipient, but the fallback keeps
     * the method safe for callers that pass through a raw notification
     * recipient).
     *
     * @param recipient the recipient username (panel username), or null/blank
     *                  to fall back to a broadcast
     * @param title     the notification title
     * @param message   the notification message
     * @param level     the notification level (info, warning, error)
     */
    public void sendDirectedNotification(String recipient, String title, String message, String level) {
        if (recipient == null || recipient.isBlank()) {
            broadcastNotification(title, message, level);
            return;
        }
        JsonObject notification = new JsonObject();
        notification.addProperty("type", "notification");
        notification.addProperty("title", title);
        notification.addProperty("message", message);
        notification.addProperty("level", level);
        notification.addProperty("revision", revisionCounter.incrementAndGet());
        notification.addProperty("timestamp", System.currentTimeMillis());

        String json = gson.toJson(notification);

        for (WebSocketSession session : sessions.values()) {
            if (session.isAuthenticated() && session.isActive()
                    && recipient.equals(session.getUsername())) {
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
