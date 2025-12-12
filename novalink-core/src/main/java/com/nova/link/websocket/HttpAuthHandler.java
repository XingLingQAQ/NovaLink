package com.nova.link.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.AuthResult;
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

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * HTTP handler for authentication endpoints.
 * Provides REST API for login and token refresh.
 * 
 * Requirements: 24.4 - JWT authentication
 */
public class HttpAuthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(HttpAuthHandler.class);
    
    private final JwtService jwtService;
    private final AuthManager authManager;
    private final Gson gson;

    public HttpAuthHandler(JwtService jwtService, AuthManager authManager) {
        this.jwtService = jwtService;
        this.authManager = authManager;
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
        
        // Route requests
        if (uri.equals("/api/auth/login") && method == HttpMethod.POST) {
            handleLogin(ctx, request);
        } else if (uri.equals("/api/auth/refresh") && method == HttpMethod.POST) {
            handleRefresh(ctx, request);
        } else if (uri.startsWith("/ws")) {
            // Pass WebSocket upgrade requests to next handler
            ctx.fireChannelRead(request.retain());
        } else {
            sendNotFound(ctx, request);
        }
    }

    /**
     * Handles login request.
     * Requirements: 24.4
     */
    private void handleLogin(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            
            String username = json.has("username") ? json.get("username").getAsString() : null;
            String password = json.has("password") ? json.get("password").getAsString() : null;
            
            if (username == null || password == null) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Missing username or password");
                return;
            }
            
            // Authenticate using AuthManager (track bans by real remote IP)
            String ipAddress = getRemoteIp(ctx);
            AuthResult result = authManager.authenticateWithPlainPassword(username, password, ipAddress);
            
            if (!result.isSuccess()) {
                logger.warn("Login failed for user '{}': {}", username, result.getErrorCode());
                sendJsonError(ctx, request, HttpResponseStatus.UNAUTHORIZED, 
                        result.getMessage() != null ? result.getMessage() : "Authentication failed");
                return;
            }
            
            // Generate tokens
            String userId = UUID.randomUUID().toString();
            String role = "CLIENT_ADMIN"; // Default role for authenticated clients
            
            // Check if super admin
            if (authManager.isSuperAdmin(username)) {
                role = "SUPER_ADMIN";
            }
            
            String token = jwtService.generateToken(userId, username, role);
            String refreshToken = jwtService.generateRefreshToken(userId, username, role);
            
            // Build response
            JsonObject response = new JsonObject();
            response.addProperty("token", token);
            response.addProperty("refreshToken", refreshToken);
            
            JsonObject user = new JsonObject();
            user.addProperty("id", userId);
            user.addProperty("username", username);
            user.addProperty("role", role);
            response.add("user", user);
            
            logger.info("User '{}' logged in successfully with role '{}'", username, role);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
            
        } catch (JsonSyntaxException | IllegalStateException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
        } catch (Exception e) {
            logger.error("Login error", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        }
    }

    private String getRemoteIp(ChannelHandlerContext ctx) {
        try {
            if (ctx != null && ctx.channel() != null && ctx.channel().remoteAddress() instanceof InetSocketAddress) {
                InetSocketAddress address = (InetSocketAddress) ctx.channel().remoteAddress();
                if (address.getAddress() != null) {
                    return address.getAddress().getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // ignore and fall back
        }
        return "web-panel";
    }

    /**
     * Handles token refresh request.
     * Requirements: 24.4
     */
    private void handleRefresh(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            String body = request.content().toString(CharsetUtil.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            
            String refreshToken = json.has("refreshToken") ? json.get("refreshToken").getAsString() : null;
            
            if (refreshToken == null) {
                sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Missing refresh token");
                return;
            }
            
            Claims claims = jwtService.validateToken(refreshToken);
            if (claims == null) {
                sendJsonError(ctx, request, HttpResponseStatus.UNAUTHORIZED, "Invalid or expired refresh token");
                return;
            }
            
            // Validate refresh token type
            String type = claims.get("type", String.class);
            if (!"refresh".equals(type)) {
                sendJsonError(ctx, request, HttpResponseStatus.UNAUTHORIZED, "Invalid refresh token");
                return;
            }
            
            String userId = claims.getSubject();
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);
            
            if (userId == null || username == null || role == null) {
                sendJsonError(ctx, request, HttpResponseStatus.UNAUTHORIZED, "Invalid refresh token");
                return;
            }
            
            String newToken = jwtService.generateToken(userId, username, role);
            
            JsonObject response = new JsonObject();
            response.addProperty("token", newToken);
            
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
            
        } catch (JsonSyntaxException | IllegalStateException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
        } catch (Exception e) {
            logger.error("Token refresh error", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        }
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
        // Frontend expects `message` for displaying errors; keep `error` for backward compatibility.
        json.addProperty("message", message);
        json.addProperty("status", status.code());
        sendJsonResponse(ctx, request, status, json);
    }

    /**
     * Sends a 404 Not Found response.
     */
    private void sendNotFound(ChannelHandlerContext ctx, FullHttpRequest request) {
        sendJsonError(ctx, request, HttpResponseStatus.NOT_FOUND, "Not found");
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
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, 
                "Content-Type, Authorization");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "86400");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("HTTP handler error", cause);
        ctx.close();
    }
}
