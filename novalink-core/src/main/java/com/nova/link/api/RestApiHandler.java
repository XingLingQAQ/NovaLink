package com.nova.link.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.link.auth.AuthManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.MessageRouter;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.websocket.JwtService;
import io.jsonwebtoken.Claims;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
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
 * Requirements: 25.4 - REST API for external integration
 * Requirements: 25.5 - Webhook support
 */
public class RestApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(RestApiHandler.class);
    
    private final JwtService jwtService;
    private final AuthManager authManager;
    private final ChannelManager channelManager;
    private final PlayerStateManager playerStateManager;
    private final MessageRouter messageRouter;
    private final WebhookManager webhookManager;
    private final Gson gson;

    public RestApiHandler(JwtService jwtService, AuthManager authManager,
                          ChannelManager channelManager, PlayerStateManager playerStateManager,
                          MessageRouter messageRouter, WebhookManager webhookManager) {
        this.jwtService = jwtService;
        this.authManager = authManager;
        this.channelManager = channelManager;
        this.playerStateManager = playerStateManager;
        this.messageRouter = messageRouter;
        this.webhookManager = webhookManager;
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
        } else if (path.matches("/api/channels/[^/]+") && method == HttpMethod.GET) {
            String channelId = path.substring("/api/channels/".length());
            handleGetChannel(ctx, request, channelId);
        } else if (path.matches("/api/channels/[^/]+/members") && method == HttpMethod.GET) {
            String channelId = path.substring("/api/channels/".length(), path.lastIndexOf("/members"));
            handleGetChannelMembers(ctx, request, channelId);
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
