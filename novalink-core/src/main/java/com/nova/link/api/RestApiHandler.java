package com.nova.link.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.config.ConfigManager;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.database.Invitation;
import com.nova.link.database.MuteInfo;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.mute.MuteManager;
import com.nova.link.mute.MuteResult;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.websocket.JwtService;
import io.jsonwebtoken.Claims;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * REST API handler for NovaLink external integration.
 * Provides HTTP endpoints for external systems to interact with NovaLink.
 *
 * <p>Marked {@link ChannelHandler.Sharable @Sharable} because a single instance is
 * shared across every connection's pipeline (see {@code WebSocketServer.initChannel}).
 * The handler holds no per-channel state — only immutable service dependencies — so
 * sharing it is safe. Without {@code @Sharable}, Netty rejects the second connection
 * with {@code ChannelPipelineException: ... is not a @Sharable handler}.
 *
 * Requirements: 25.4 - REST API for external integration
 * Requirements: 25.5 - Webhook support
 */
@ChannelHandler.Sharable
public class RestApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(RestApiHandler.class);

    /** Sentinel operator UUID for admin-originated REST actions (console sentinel). */
    private static final UUID CONSOLE_SENTINEL = new UUID(0L, 0L);

    /** Commands that must never be executed via the REST console endpoint. */
    private static final Set<String> CONSOLE_BLACKLIST = Set.of("stop", "shutdown");

    private final JwtService jwtService;
    private final AuthManager authManager;
    private final ChannelManager channelManager;
    private final PlayerStateManager playerStateManager;
    private final MessageRouter messageRouter;
    private final WebhookManager webhookManager;
    private final MuteManager muteManager;
    private final InvitationManager invitationManager;
    private final ConfigManager configManager;
    private final ServerNetworkHandler networkHandler;
    private final ConsoleCommandHandler consoleCommandHandler;
    private final Gson gson;

    public RestApiHandler(JwtService jwtService, AuthManager authManager,
                          ChannelManager channelManager, PlayerStateManager playerStateManager,
                          MessageRouter messageRouter, WebhookManager webhookManager,
                          MuteManager muteManager, InvitationManager invitationManager,
                          ConfigManager configManager, ServerNetworkHandler networkHandler,
                          ConsoleCommandHandler consoleCommandHandler) {
        this.jwtService = jwtService;
        this.authManager = authManager;
        this.channelManager = channelManager;
        this.playerStateManager = playerStateManager;
        this.messageRouter = messageRouter;
        this.webhookManager = webhookManager;
        this.muteManager = muteManager;
        this.invitationManager = invitationManager;
        this.configManager = configManager;
        this.networkHandler = networkHandler;
        this.consoleCommandHandler = consoleCommandHandler;
        this.gson = new Gson();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        String uri = request.uri();
        HttpMethod method = request.method();
        
        // Handle CORS preflight
        if (method == HttpMethod.OPTIONS) {
            sendCorsResponse(ctx, request);
            return;
        }
        
        // Check if this is an API request
        if (!uri.startsWith("/api/")) {
            // Pass to next handler (WebSocket, etc.)
            ctx.fireChannelRead(request.retain());
            return;
        }
        
        // Validate authentication for non-auth endpoints
        if (!uri.startsWith("/api/auth/")) {
            String authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION);
            if (!validateAuth(authHeader)) {
                sendJsonError(ctx, request, HttpResponseStatus.UNAUTHORIZED, "Invalid or missing authorization");
                return;
            }
        }
        
        // Route API requests
        try {
            routeRequest(ctx, request, uri, method);
        } catch (Exception e) {
            logger.error("API error for {} {}", method, uri, e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        }
    }

    /**
     * Routes API requests to appropriate handlers.
     */
    private void routeRequest(ChannelHandlerContext ctx, FullHttpRequest request,
                              String uri, HttpMethod method) {
        // Remove query parameters for routing
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf("?")) : uri;

        // Channel endpoints
        if (path.equals("/api/channels") && method == HttpMethod.GET) {
            handleGetChannels(ctx, request);
        } else if (path.equals("/api/channels") && method == HttpMethod.POST) {
            handleCreateChannel(ctx, request);
        } else if (path.matches("/api/channels/[^/]+") && method == HttpMethod.GET) {
            String channelId = path.substring("/api/channels/".length());
            handleGetChannel(ctx, request, channelId);
        } else if (path.matches("/api/channels/[^/]+") && method == HttpMethod.DELETE) {
            String channelId = path.substring("/api/channels/".length());
            handleDeleteChannel(ctx, request, channelId);
        } else if (path.matches("/api/channels/[^/]+") && method == HttpMethod.PUT) {
            String channelId = path.substring("/api/channels/".length());
            handleUpdateChannel(ctx, request, channelId);
        } else if (path.matches("/api/channels/[^/]+/members") && method == HttpMethod.GET) {
            String channelId = path.substring("/api/channels/".length(), path.lastIndexOf("/members"));
            handleGetChannelMembers(ctx, request, channelId);
        } else if (path.matches("/api/channels/[^/]+/invite") && method == HttpMethod.POST) {
            String channelId = path.substring("/api/channels/".length(), path.lastIndexOf("/invite"));
            handleInviteChannel(ctx, request, channelId);
        }
        // Message endpoints
        else if (path.equals("/api/messages") && method == HttpMethod.POST) {
            handleSendMessage(ctx, request);
        }
        // Player endpoints
        else if (path.equals("/api/players") && method == HttpMethod.GET) {
            handleGetPlayers(ctx, request);
        } else if (path.matches("/api/players/[^/]+") && method == HttpMethod.GET) {
            String playerId = path.substring("/api/players/".length());
            handleGetPlayer(ctx, request, playerId);
        } else if (path.matches("/api/players/[^/]+/mute") && method == HttpMethod.POST) {
            String playerId = path.substring("/api/players/".length(), path.lastIndexOf("/mute"));
            handleMutePlayer(ctx, request, playerId);
        } else if (path.matches("/api/players/[^/]+/unmute") && method == HttpMethod.POST) {
            String playerId = path.substring("/api/players/".length(), path.lastIndexOf("/unmute"));
            handleUnmutePlayer(ctx, request, playerId);
        } else if (path.matches("/api/players/[^/]+/kick") && method == HttpMethod.POST) {
            String playerId = path.substring("/api/players/".length(), path.lastIndexOf("/kick"));
            handleKickPlayer(ctx, request, playerId);
        }
        // Mutes listing endpoint
        else if (path.equals("/api/mutes") && method == HttpMethod.GET) {
            handleGetMutes(ctx, request);
        }
        // Webhook endpoints
        else if (path.equals("/api/webhooks") && method == HttpMethod.GET) {
            handleGetWebhooks(ctx, request);
        } else if (path.equals("/api/webhooks") && method == HttpMethod.POST) {
            handleCreateWebhook(ctx, request);
        } else if (path.matches("/api/webhooks/[^/]+") && method == HttpMethod.DELETE) {
            String webhookId = path.substring("/api/webhooks/".length());
            handleDeleteWebhook(ctx, request, webhookId);
        }
        // Client disconnect endpoint
        else if (path.matches("/api/clients/[^/]+") && method == HttpMethod.DELETE) {
            String clientId = path.substring("/api/clients/".length());
            handleDisconnectClient(ctx, request, clientId);
        }
        // Config reload endpoint
        else if (path.equals("/api/reload") && method == HttpMethod.POST) {
            handleReload(ctx, request);
        }
        // Console command execution endpoint
        else if (path.equals("/api/console") && method == HttpMethod.POST) {
            handleConsoleCommand(ctx, request);
        }
        // Status endpoint
        else if (path.equals("/api/status") && method == HttpMethod.GET) {
            handleGetStatus(ctx, request);
        }
        // Auth endpoints (handled by HttpAuthHandler, but we pass through)
        else if (path.startsWith("/api/auth/")) {
            ctx.fireChannelRead(request.retain());
        }
        // Not found
        else {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Endpoint not found");
        }
    }

    /**
     * Validates the authorization header.
     */
    private boolean validateAuth(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authHeader.substring(7);
        Claims claims = jwtService.validateToken(token);
        if (claims == null) {
            return false;
        }
        // Refresh tokens must NOT be accepted as API bearer tokens.
        String tokenType = claims.get("type", String.class);
        if ("refresh".equals(tokenType)) {
            return false;
        }
        // Basic claim sanity (helps reject tokens from other issuers/tools).
        String subject = claims.getSubject();
        String username = claims.get("username", String.class);
        String role = claims.get("role", String.class);
        return subject != null && !subject.isBlank()
                && username != null && !username.isBlank()
                && role != null && !role.isBlank();
    }

    /**
     * GET /api/channels - List all channels
     * Requirements: 25.4
     */
    private void handleGetChannels(ChannelHandlerContext ctx, FullHttpRequest request) {
        JsonArray channels = new JsonArray();
        for (Channel channel : channelManager.getAllChannels()) {
            channels.add(channelToJson(channel));
        }
        
        JsonObject response = new JsonObject();
        response.add("channels", channels);
        response.addProperty("total", channels.size());
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/channels/{id} - Get channel details
     * Requirements: 25.4
     */
    private void handleGetChannel(ChannelHandlerContext ctx, FullHttpRequest request, String channelId) {
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, channelToJson(channel));
    }

    /**
     * GET /api/channels/{id}/members - Get channel members
     * Requirements: 25.4
     */
    private void handleGetChannelMembers(ChannelHandlerContext ctx, FullHttpRequest request, String channelId) {
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }
        
        JsonArray members = new JsonArray();
        for (UUID memberId : channel.getMembers()) {
            JsonObject member = new JsonObject();
            member.addProperty("uuid", memberId.toString());
            
            // Try to get player name from state manager
            PlayerState state = playerStateManager.getPlayerState(memberId);
            if (state != null) {
                member.addProperty("name", state.getPlayerName());
            }
            members.add(member);
        }
        
        JsonObject response = new JsonObject();
        response.add("members", members);
        response.addProperty("total", members.size());
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/channels - Create a new channel (admin operation).
     *
     * <p>REST channel creation is a super-admin operation and is not restricted
     * to private channels (unlike {@code ChannelActionHandler.handleCreate}).
     * Private channels without an explicit id get an auto-generated NC-XXXX id.
     * Requirements: 25.4
     */
    private void handleCreateChannel(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            String displayName = json.has("displayName") && !json.get("displayName").isJsonNull()
                    ? json.get("displayName").getAsString() : null;
            String scopeRaw = json.has("scope") && !json.get("scope").isJsonNull()
                    ? json.get("scope").getAsString() : "global";
            String id = json.has("id") && !json.get("id").isJsonNull()
                    ? json.get("id").getAsString() : null;
            int maxCapacity = json.has("maxCapacity") && !json.get("maxCapacity").isJsonNull()
                    ? json.get("maxCapacity").getAsInt() : 100;
            String permission = json.has("permission") && !json.get("permission").isJsonNull()
                    ? json.get("permission").getAsString() : null;

            ChannelScope scope;
            try {
                scope = ChannelScope.valueOf(scopeRaw.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid scope: " + scopeRaw);
                return;
            }

            // PRIVATE channels require a clientId; for REST admin creation of a
            // private channel, bind it to a synthetic "console" client (mirrors
            // ConsoleCommandHandler.handleCreate private branch). For PRIVATE
            // channels, ChannelManager.createChannel auto-generates an NC-XXXX id
            // when none is supplied. For GLOBAL/SERVER scopes the Channel ctor
            // rejects a null id, so auto-generate one here as well — REST admin
            // creation should not require a caller-supplied id for any scope.
            String clientId = null;
            if (scope == ChannelScope.PRIVATE) {
                clientId = "console";
            } else if (id == null || id.isEmpty()) {
                id = generateRestChannelId();
            }

            // Fall back to the generated/id-derived name when no display name given.
            String effectiveDisplayName = displayName != null ? displayName : id;

            ChannelConfig config = ChannelConfig.builder()
                    .id(id)
                    .displayName(effectiveDisplayName)
                    .scope(scope)
                    .clientId(clientId)
                    .maxCapacity(maxCapacity)
                    .permission(permission)
                    .build();

            Channel channel = channelManager.createChannel(config);

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("channel", channelToJson(channel));

            sendJsonResponse(ctx, request, HttpResponseStatus.CREATED, response);

        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            logger.error("Error creating channel via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
        }
    }

    /**
     * DELETE /api/channels/{id} - Delete a channel (admin operation).
     *
     * <p>Removes all members first (clears membership side-effects), mirroring
     * {@code ConsoleCommandHandler.handleDelete}.
     * Requirements: 25.4
     */
    private void handleDeleteChannel(ChannelHandlerContext ctx, FullHttpRequest request, String channelId) {
        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        // Remove all members first (clear membership side-effects).
        for (UUID m : new java.util.ArrayList<>(channelManager.getChannelMembers(channelId))) {
            channelManager.removeMember(channelId, m);
            try {
                playerStateManager.leaveChannel(m, channelId);
            } catch (Exception ignored) {
                // non-fatal
            }
        }

        boolean deleted = channelManager.deleteChannel(channelId);
        if (!deleted) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Channel deleted successfully");
        response.addProperty("channelId", channelId);

        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * PUT /api/channels/{id} - Update a channel's mutable properties.
     *
     * <p>Only non-null body fields are applied. Mirrors
     * {@code NovaLinkMain.upsertConfiguredChannel} field-by-field update.
     * Requirements: 25.4
     */
    private void handleUpdateChannel(ChannelHandlerContext ctx, FullHttpRequest request, String channelId) {
        Channel existing = channelManager.getChannel(channelId);
        if (existing == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            String displayName = json.has("displayName") && !json.get("displayName").isJsonNull()
                    ? json.get("displayName").getAsString() : null;
            Integer maxCapacity = null;
            if (json.has("maxCapacity") && !json.get("maxCapacity").isJsonNull()) {
                maxCapacity = json.get("maxCapacity").getAsInt();
            }
            String permission = json.has("permission") && !json.get("permission").isJsonNull()
                    ? json.get("permission").getAsString() : null;

            Channel updated = channelManager.updateChannel(channelId, displayName, maxCapacity, permission);
            if (updated == null) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
                return;
            }

            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("channel", channelToJson(updated));

            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);

        } catch (Exception e) {
            logger.error("Error updating channel via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
        }
    }

    /**
     * POST /api/channels/{id}/invite - Create an invitation for a channel.
     *
     * <p>Uses the console sentinel as the inviter. Returns the generated code.
     * Requirements: 25.4
     */
    private void handleInviteChannel(ChannelHandlerContext ctx, FullHttpRequest request, String channelId) {
        if (invitationManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Invitations not enabled");
            return;
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        long ttlMillis = InvitationManager.DEFAULT_TTL_MILLIS;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("ttlMillis") && !json.get("ttlMillis").isJsonNull()) {
                    ttlMillis = json.get("ttlMillis").getAsLong();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        try {
            Invitation invitation = invitationManager.createInvitation(channelId, CONSOLE_SENTINEL, ttlMillis);
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("code", invitation.getCode());
            response.addProperty("channelId", invitation.getChannelId());
            response.addProperty("expireTime", invitation.getExpireTime());
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            logger.error("Error creating invitation via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "NC-510: Database error");
        }
    }

    /**
     * POST /api/players/{uuid}/mute - Mute a player.
     *
     * <p>Uses the console sentinel as operator (bypasses permission checks in
     * {@link MuteManager}). Mirrors {@code ConsoleCommandHandler.handleMute}.
     * Requirements: 25.4
     */
    private void handleMutePlayer(ChannelHandlerContext ctx, FullHttpRequest request, String playerId) {
        if (muteManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Mute system not enabled");
            return;
        }

        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(playerId);
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        String channelId = null;
        long durationMs = 0;
        String reason = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("channelId") && !json.get("channelId").isJsonNull()) {
                    channelId = json.get("channelId").getAsString();
                }
                if (json.has("durationMs") && !json.get("durationMs").isJsonNull()) {
                    durationMs = json.get("durationMs").getAsLong();
                }
                if (json.has("reason") && !json.get("reason").isJsonNull()) {
                    reason = json.get("reason").getAsString();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        if (channelId != null && !channelManager.channelExists(channelId)) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        MuteResult result = muteManager.mutePlayer(CONSOLE_SENTINEL, targetUuid, channelId, durationMs, reason, null);
        if (!result.isSuccess()) {
            HttpResponseStatus status = "NC-404".equals(result.getErrorCode())
                    ? HttpResponseStatus.NOT_FOUND
                    : ("NC-403".equals(result.getErrorCode())
                        ? HttpResponseStatus.FORBIDDEN
                        : HttpResponseStatus.BAD_REQUEST);
            sendJsonError(ctx, request, status,
                    result.getErrorCode() != null ? result.getErrorCode() + ": " + result.getMessage()
                            : result.getMessage());
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Player muted successfully");
        response.addProperty("playerId", targetUuid.toString());
        if (channelId != null) {
            response.addProperty("channelId", channelId);
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/players/{uuid}/unmute - Unmute a player.
     *
     * <p>Uses the console sentinel as operator. Mirrors
     * {@code ConsoleCommandHandler.handleUnmute}.
     * Requirements: 25.4
     */
    private void handleUnmutePlayer(ChannelHandlerContext ctx, FullHttpRequest request, String playerId) {
        if (muteManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Mute system not enabled");
            return;
        }

        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(playerId);
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        String channelId = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("channelId") && !json.get("channelId").isJsonNull()) {
                    channelId = json.get("channelId").getAsString();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        MuteResult result = muteManager.unmutePlayer(CONSOLE_SENTINEL, targetUuid, channelId, null);
        if (!result.isSuccess()) {
            HttpResponseStatus status = "NC-404".equals(result.getErrorCode())
                    ? HttpResponseStatus.NOT_FOUND
                    : ("NC-403".equals(result.getErrorCode())
                        ? HttpResponseStatus.FORBIDDEN
                        : HttpResponseStatus.BAD_REQUEST);
            sendJsonError(ctx, request, status,
                    result.getErrorCode() != null ? result.getErrorCode() + ": " + result.getMessage()
                            : result.getMessage());
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Player unmuted successfully");
        response.addProperty("playerId", targetUuid.toString());
        if (channelId != null) {
            response.addProperty("channelId", channelId);
        }
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/mutes - List all active mutes across online players.
     *
     * <p>MuteManager has no list-all; aggregate across online player states,
     * mirroring {@code ConsoleCommandHandler.handleMutes}.
     * Requirements: 25.4
     */
    private void handleGetMutes(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (muteManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Mute system not enabled");
            return;
        }

        JsonArray mutes = new JsonArray();
        for (PlayerState state : playerStateManager.getAllPlayerStates()) {
            List<MuteInfo> playerMutes = muteManager.getActiveMutes(state.getPlayerId());
            for (MuteInfo m : playerMutes) {
                JsonObject entry = new JsonObject();
                entry.addProperty("playerId", state.getPlayerId().toString());
                entry.addProperty("playerName", state.getPlayerName());
                entry.addProperty("channelId", m.getChannelId() != null ? m.getChannelId() : "(global)");
                entry.addProperty("reason", m.getReason());
                entry.addProperty("expireTime", m.getExpireTime());
                entry.addProperty("remainingMs", m.getRemainingTime());
                entry.addProperty("permanent", m.isPermanent());
                mutes.add(entry);
            }
        }

        JsonObject response = new JsonObject();
        response.add("mutes", mutes);
        response.addProperty("total", mutes.size());

        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/players/{uuid}/kick - Kick a player from a channel.
     *
     * <p>Mirrors {@code ChannelActionHandler.handleKick}: remove member + update
     * player state. The REST layer is admin-originated (console sentinel), so
     * no moderation permission check is needed.
     * Requirements: 25.4
     */
    private void handleKickPlayer(ChannelHandlerContext ctx, FullHttpRequest request, String playerId) {
        UUID targetUuid;
        try {
            targetUuid = UUID.fromString(playerId);
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid UUID format");
            return;
        }

        String channelId = null;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            if (!body.isBlank()) {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("channelId") && !json.get("channelId").isJsonNull()) {
                    channelId = json.get("channelId").getAsString();
                }
            }
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        if (channelId == null || channelId.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "channelId is required");
            return;
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
            return;
        }

        if (!channel.isMember(targetUuid)) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "NC-433: Target is not in channel");
            return;
        }

        channelManager.removeMember(channelId, targetUuid);
        try {
            playerStateManager.leaveChannel(targetUuid, channelId);
        } catch (Exception e) {
            logger.debug("Failed to update player state after kick for {}: {}", targetUuid, e.getMessage());
        }

        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Player kicked");
        response.addProperty("playerId", targetUuid.toString());
        response.addProperty("channelId", channelId);

        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * DELETE /api/clients/{clientId} - Disconnect a game-server TCP client.
     *
     * <p>Finds the connection by clientId and closes it. WS panel sessions are
     * not tracked by clientId here, so only game-server TCP clients are affected.
     * Requirements: 25.4
     */
    private void handleDisconnectClient(ChannelHandlerContext ctx, FullHttpRequest request, String clientId) {
        if (networkHandler == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Network handler not available");
            return;
        }

        ClientConnection connection = networkHandler.findByClientId(clientId);
        if (connection == null) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Client not found");
            return;
        }

        connection.close();
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Client disconnect initiated");
        response.addProperty("clientId", clientId);

        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/reload - Trigger a configuration reload.
     *
     * <p>Mirrors {@code ConsoleCommandHandler.handleReload}.
     * Requirements: 25.4
     */
    private void handleReload(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (configManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Config manager not available");
            return;
        }

        try {
            configManager.triggerReload();
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Configuration reloaded successfully");
            response.addProperty("reloadCount", configManager.getReloadCount());
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
        } catch (Exception e) {
            logger.error("Error reloading config via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "NC-500: Reload failed: " + e.getMessage());
        }
    }

    /**
     * POST /api/console - Execute a console command remotely.
     *
     * <p>Delegates to {@link ConsoleCommandHandler#dispatch}. The {@code stop}
     * and {@code shutdown} commands are blacklisted (they would halt the
     * backend) and rejected with 400.
     * Requirements: 25.4
     */
    private void handleConsoleCommand(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (consoleCommandHandler == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Console handler not available");
            return;
        }

        String command;
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            command = json.has("command") && !json.get("command").isJsonNull()
                    ? json.get("command").getAsString() : null;
        } catch (Exception e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
            return;
        }

        if (command == null || command.isBlank()) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "command is required");
            return;
        }

        // Blacklist stop/shutdown: executing them via REST would halt the backend.
        String trimmed = command.trim();
        String firstToken = trimmed.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        if (CONSOLE_BLACKLIST.contains(firstToken)) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST,
                    "NC-400: Command '" + firstToken + "' is blacklisted via REST");
            return;
        }

        String output = consoleCommandHandler.dispatch(trimmed);
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("output", output != null ? output : "");
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/messages - Send a message to a channel
     * Requirements: 25.4
     */
    private void handleSendMessage(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            
            String channelId = json.has("channelId") ? json.get("channelId").getAsString() : null;
            String senderName = json.has("senderName") ? json.get("senderName").getAsString() : "API";
            String content = json.has("content") ? json.get("content").getAsString() : null;
            
            if (channelId == null || content == null) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Missing channelId or content");
                return;
            }
            
            Channel channel = channelManager.getChannel(channelId);
            if (channel == null) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Channel not found");
                return;
            }
            
            // Create a system UUID for API messages
            UUID senderId = new UUID(0, 0);
            
            // Route the message
            messageRouter.routeMessage(channelId, senderId, senderName, content, new HashMap<>());
            
            // Trigger webhook for message sent
            if (webhookManager != null) {
                webhookManager.triggerWebhook("message.sent", createMessageWebhookPayload(
                    channelId, senderId.toString(), senderName, content));
            }
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("message", "Message sent successfully");
            
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
            
        } catch (Exception e) {
            logger.error("Error sending message via API", e);
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
        }
    }

    /**
     * GET /api/players - List online players
     * Requirements: 25.4
     */
    private void handleGetPlayers(ChannelHandlerContext ctx, FullHttpRequest request) {
        JsonArray players = new JsonArray();
        
        // Get all player states from the state manager
        Collection<PlayerState> states = playerStateManager.getAllPlayerStates();
        for (PlayerState state : states) {
            players.add(playerStateToJson(state));
        }
        
        JsonObject response = new JsonObject();
        response.add("players", players);
        response.addProperty("total", players.size());
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/players/{uuid} - Get player details
     * Requirements: 25.4
     */
    private void handleGetPlayer(ChannelHandlerContext ctx, FullHttpRequest request, String playerId) {
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerState state = playerStateManager.getPlayerState(uuid);
            
            if (state == null) {
                sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Player not found");
                return;
            }
            
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, playerStateToJson(state));
            
        } catch (IllegalArgumentException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid UUID format");
        }
    }

    /**
     * GET /api/webhooks - List all webhooks
     * Requirements: 25.5
     */
    private void handleGetWebhooks(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (webhookManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Webhooks not enabled");
            return;
        }
        
        JsonArray webhooks = new JsonArray();
        for (Webhook webhook : webhookManager.getAllWebhooks()) {
            webhooks.add(webhookToJson(webhook));
        }
        
        JsonObject response = new JsonObject();
        response.add("webhooks", webhooks);
        response.addProperty("total", webhooks.size());
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * POST /api/webhooks - Create a new webhook
     * Requirements: 25.5
     */
    private void handleCreateWebhook(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (webhookManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Webhooks not enabled");
            return;
        }
        
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            
            String url = json.has("url") ? json.get("url").getAsString() : null;
            String event = json.has("event") ? json.get("event").getAsString() : null;
            String secret = json.has("secret") ? json.get("secret").getAsString() : null;
            
            if (url == null || event == null) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Missing url or event");
                return;
            }
            
            Webhook webhook = webhookManager.createWebhook(url, event, secret);
            
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.add("webhook", webhookToJson(webhook));
            
            sendJsonResponse(ctx, request, HttpResponseStatus.CREATED, response);
            
        } catch (Exception e) {
            logger.error("Error creating webhook", e);
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
        }
    }

    /**
     * DELETE /api/webhooks/{id} - Delete a webhook
     * Requirements: 25.5
     */
    private void handleDeleteWebhook(ChannelHandlerContext ctx, FullHttpRequest request, String webhookId) {
        if (webhookManager == null) {
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE, "Webhooks not enabled");
            return;
        }
        
        boolean deleted = webhookManager.deleteWebhook(webhookId);
        
        if (!deleted) {
            sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Webhook not found");
            return;
        }
        
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", "Webhook deleted successfully");
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * GET /api/status - Get server status
     * Requirements: 25.4
     */
    private void handleGetStatus(ChannelHandlerContext ctx, FullHttpRequest request) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "online");
        response.addProperty("version", "1.0.0");
        response.addProperty("channelCount", channelManager.getChannelCount());
        response.addProperty("playerCount", playerStateManager.getAllPlayerStates().size());
        response.addProperty("timestamp", System.currentTimeMillis());
        
        sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
    }

    /**
     * Generates a unique channel id for REST-created GLOBAL/SERVER channels when
     * the caller did not supply one. Format: {@code ch-XXXX} (4 hex chars),
     * distinct from the {@code NC-XXXX} prefix reserved for private channels.
     * Retries until the id is free (bounded).
     */
    private String generateRestChannelId() {
        java.security.SecureRandom rng = new java.security.SecureRandom();
        String chars = "0123456789ABCDEF";
        for (int attempts = 0; attempts < 1000; attempts++) {
            StringBuilder sb = new StringBuilder("ch-");
            for (int i = 0; i < 4; i++) {
                sb.append(chars.charAt(rng.nextInt(chars.length())));
            }
            String candidate = sb.toString();
            if (!channelManager.channelExists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate unique REST channel id after 1000 attempts");
    }

    /**
     * Converts a Channel to JSON.
     */
    private JsonObject channelToJson(Channel channel) {
        JsonObject json = new JsonObject();
        json.addProperty("id", channel.getId());
        json.addProperty("displayName", channel.getDisplayName());
        json.addProperty("scope", channel.getScope().name());
        json.addProperty("clientId", channel.getClientId());
        json.addProperty("memberCount", channel.getMembers().size());
        json.addProperty("maxCapacity", channel.getMaxCapacity());
        
        if (channel.getPermission() != null) {
            json.addProperty("permission", channel.getPermission());
        }
        
        if (channel.getAllowedWorlds() != null && !channel.getAllowedWorlds().isEmpty()) {
            JsonArray worlds = new JsonArray();
            channel.getAllowedWorlds().forEach(worlds::add);
            json.add("allowedWorlds", worlds);
        }
        
        return json;
    }

    /**
     * Converts a PlayerState to JSON.
     */
    private JsonObject playerStateToJson(PlayerState state) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", state.getPlayerId().toString());
        json.addProperty("name", state.getPlayerName());
        json.addProperty("clientId", state.getClientId());
        json.addProperty("currentWorld", state.getCurrentWorld());
        json.addProperty("activeChannel", state.getActiveChannel());
        
        JsonArray channels = new JsonArray();
        state.getJoinedChannels().forEach(channels::add);
        json.add("joinedChannels", channels);
        
        return json;
    }

    /**
     * Converts a Webhook to JSON.
     */
    private JsonObject webhookToJson(Webhook webhook) {
        JsonObject json = new JsonObject();
        json.addProperty("id", webhook.getId());
        json.addProperty("url", webhook.getUrl());
        json.addProperty("event", webhook.getEvent());
        json.addProperty("createdAt", webhook.getCreatedAt());
        json.addProperty("lastTriggered", webhook.getLastTriggered());
        return json;
    }

    /**
     * Creates a webhook payload for message events.
     */
    private JsonObject createMessageWebhookPayload(String channelId, String senderId, 
                                                    String senderName, String content) {
        JsonObject payload = new JsonObject();
        payload.addProperty("channelId", channelId);
        payload.addProperty("senderId", senderId);
        payload.addProperty("senderName", senderName);
        payload.addProperty("content", content);
        payload.addProperty("timestamp", System.currentTimeMillis());
        return payload;
    }

    /**
     * Sends a JSON response.
     */
    private void sendJsonResponse(ChannelHandlerContext ctx, FullHttpRequest request, 
                                  HttpResponseStatus status, JsonObject json) {
        String content = gson.toJson(json);
        ByteBuf buf = Unpooled.copiedBuffer(content, CharsetUtil.UTF_8);
        
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, buf);
        
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        addCorsHeaders(response);
        
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Sends a JSON error response.
     */
    private void sendJsonError(ChannelHandlerContext ctx, FullHttpRequest request,
                               HttpResponseStatus status, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("error", message);
        // Keep `message` for frontend compatibility; `error` kept for backward compatibility.
        json.addProperty("message", message);
        json.addProperty("status", status.code());
        sendJsonResponse(ctx, request, status, json);
    }

    /**
     * Sends a CORS preflight response.
     */
    private void sendCorsResponse(ChannelHandlerContext ctx, FullHttpRequest request) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        addCorsHeaders(response);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Adds CORS headers to response.
     */
    private void addCorsHeaders(FullHttpResponse response) {
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, 
                "Content-Type, Authorization");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "86400");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("REST API handler error", cause);
        ctx.close();
    }
}
