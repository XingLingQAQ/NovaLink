package com.nova.link.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.nova.link.api.RestApiHandler;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.PanelAuthResult;
import com.nova.link.i18n.I18n;
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

import java.net.InetSocketAddress;
import java.util.List;

/**
 * HTTP handler for authentication endpoints.
 * Provides REST API for login, token refresh (with rotation) and logout
 * (token revocation).
 *
 * <p>Panel logins are served exclusively from the panel credential pool
 * ({@code super-admins} + {@code panel-users}); game-server client credentials
 * are always rejected. The JWT subject is the panel username so every
 * subsequent operation is attributable.
 *
 * <p>Marked {@link ChannelHandler.Sharable @Sharable} because a single instance is
 * shared across every connection's pipeline (see {@code WebSocketServer.initChannel}).
 * The handler holds no per-channel state — only immutable service dependencies — so
 * sharing it is safe. Without {@code @Sharable}, Netty rejects the second connection
 * with {@code ChannelPipelineException: ... is not a @Sharable handler}.
 *
 * Requirements: 24.4 - JWT authentication
 */
@ChannelHandler.Sharable
public class HttpAuthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger logger = LoggerFactory.getLogger(HttpAuthHandler.class);
    
    private final JwtService jwtService;
    private final AuthManager authManager;
    private final List<String> corsAllowedOrigins;
    private final Gson gson;

    /** Backward-compatible constructor: CORS allows all origins ("*"). */
    public HttpAuthHandler(JwtService jwtService, AuthManager authManager) {
        this(jwtService, authManager, List.of("*"));
    }

    public HttpAuthHandler(JwtService jwtService, AuthManager authManager,
                           List<String> corsAllowedOrigins) {
        this.jwtService = jwtService;
        this.authManager = authManager;
        this.corsAllowedOrigins = (corsAllowedOrigins != null && !corsAllowedOrigins.isEmpty())
                ? List.copyOf(corsAllowedOrigins)
                : List.of("*");
        this.gson = new Gson();
    }

    /**
     * Worker pool for auth business logic (BCrypt verification is CPU-heavy
     * and must not run on the Netty IO thread). {@code null} (the default)
     * runs inline — the synchronous mode used by unit tests. Production
     * shares the REST worker pool created by
     * {@link RestApiHandler#newWorkerPool}.
     */
    private volatile java.util.concurrent.Executor workerExecutor;

    /** Injects the worker executor (used by NovaLinkMain; tests keep the inline default). */
    public void setWorkerExecutor(java.util.concurrent.Executor workerExecutor) {
        this.workerExecutor = workerExecutor;
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
            offload(ctx, request, () -> handleLogin(ctx, request));
        } else if (uri.equals("/api/auth/refresh") && method == HttpMethod.POST) {
            offload(ctx, request, () -> handleRefresh(ctx, request));
        } else if (uri.equals("/api/auth/logout") && method == HttpMethod.POST) {
            offload(ctx, request, () -> handleLogout(ctx, request));
        } else if (uri.startsWith("/ws")) {
            // Pass WebSocket upgrade requests to next handler (must stay on
            // the IO thread to preserve pipeline ordering)
            ctx.fireChannelRead(request.retain());
        } else {
            sendNotFound(ctx, request);
        }
    }

    /**
     * Runs the given auth task on the worker pool (retaining the request for
     * the duration), or inline when no executor is configured. A saturated
     * pool answers 503 immediately.
     */
    private void offload(ChannelHandlerContext ctx, FullHttpRequest request, Runnable task) {
        java.util.concurrent.Executor executor = workerExecutor;
        if (executor == null) {
            task.run();
            return;
        }
        request.retain();
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    request.release();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            request.release();
            logger.warn("REST worker pool saturated; rejecting auth request {}", request.uri());
            sendJsonError(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    I18n.tr("api.error.server_busy"));
        }
    }

    /**
     * Handles login request. Only panel accounts (super-admins with role
     * SUPER_ADMIN, panel-users with role ADMIN/VIEWER) can log in; game-server
     * client credentials get 401. The JWT subject is the username (stable
     * operator attribution).
     *
     * <p>Response contract: {@code {token, refreshToken, user:{username, role}}}
     * with role one of VIEWER | ADMIN | SUPER_ADMIN.
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
            
            // Authenticate against the PANEL credential pool only (track bans
            // by real remote IP). Game-server client accounts are rejected.
            String ipAddress = getRemoteIp(ctx);
            PanelAuthResult result = authManager.authenticatePanelUser(username, password, ipAddress);
            
            if (!result.isSuccess()) {
                logger.warn("Panel login failed for user '{}': {}", username, result.getErrorCode());
                String message = "NC-429".equals(result.getErrorCode())
                        ? I18n.tr("auth.login.ip_banned",
                                authManager.getIpBanManager().getRemainingBanTime(ipAddress) / 1000)
                        : I18n.tr("auth.login.invalid_credentials");
                sendJsonError(ctx, request, HttpResponseStatus.UNAUTHORIZED, message);
                return;
            }
            
            String role = result.getCredentials().getRole().name();
            // Subject is the username: stable operator attribution across sessions.
            String token = jwtService.generateToken(username, username, role);
            String refreshToken = jwtService.generateRefreshToken(username, username, role);
            
            // Build response (contract: {token, refreshToken, user:{username, role}}).
            JsonObject response = new JsonObject();
            response.addProperty("token", token);
            response.addProperty("refreshToken", refreshToken);
            
            JsonObject user = new JsonObject();
            user.addProperty("id", username);
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
     * Handles token refresh with rotation: returns a NEW access token AND a
     * NEW refresh token, and revokes the submitted refresh token's jti so it
     * cannot be replayed.
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
                sendJsonError(ctx, request, HttpResponseStatus.UNAUTHORIZED,
                        I18n.tr("auth.refresh.invalid"));
                return;
            }
            
            // Validate refresh token type
            String type = claims.get("type", String.class);
            if (!"refresh".equals(type)) {
                sendJsonError(ctx, request, HttpResponseStatus.UNAUTHORIZED,
                        I18n.tr("auth.refresh.invalid"));
                return;
            }
            
            String userId = claims.getSubject();
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);
            
            if (userId == null || username == null || role == null) {
                sendJsonError(ctx, request, HttpResponseStatus.UNAUTHORIZED,
                        I18n.tr("auth.refresh.invalid"));
                return;
            }
            
            String newToken = jwtService.generateToken(userId, username, role);
            String newRefreshToken = jwtService.generateRefreshToken(userId, username, role);

            // Rotation: the old refresh token must not be usable again.
            jwtService.revokeToken(refreshToken);
            
            JsonObject response = new JsonObject();
            response.addProperty("token", newToken);
            response.addProperty("refreshToken", newRefreshToken);
            
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);
            
        } catch (JsonSyntaxException | IllegalStateException e) {
            sendJsonError(ctx, request, HttpResponseStatus.BAD_REQUEST, "Invalid request body");
        } catch (Exception e) {
            logger.error("Token refresh error", e);
            sendJsonError(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        }
    }

    /**
     * Handles logout: revokes the presented access token (Authorization
     * header) and, when supplied in the body, the refresh token as well.
     * Both revocations use the in-memory jti blacklist in {@link JwtService}.
     */
    private void handleLogout(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            String authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION);
            String accessToken = authHeader != null && authHeader.startsWith("Bearer ")
                    ? authHeader.substring(7)
                    : null;
            Claims claims = accessToken != null ? jwtService.validateToken(accessToken) : null;
            if (claims == null || "refresh".equals(claims.get("type", String.class))) {
                sendJsonError(ctx, request, HttpResponseStatus.UNAUTHORIZED,
                        I18n.tr("api.error.unauthorized"));
                return;
            }

            jwtService.revokeToken(accessToken);

            // Optionally revoke the submitted refresh token too.
            try {
                String body = request.content().toString(CharsetUtil.UTF_8);
                if (body != null && !body.isBlank()) {
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    if (json.has("refreshToken") && !json.get("refreshToken").isJsonNull()) {
                        jwtService.revokeToken(json.get("refreshToken").getAsString());
                    }
                }
            } catch (Exception e) {
                logger.debug("Ignoring malformed logout body: {}", e.getMessage());
            }

            logger.info("User '{}' logged out (token revoked)", claims.get("username", String.class));
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, response);

        } catch (Exception e) {
            logger.error("Logout error", e);
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
        addCorsHeaders(request, response);
        
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
        addCorsHeaders(request, response);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Adds CORS headers according to the configured origin whitelist (same
     * semantics as {@link RestApiHandler}): default {@code ["*"]} allows all;
     * an explicit list echoes only matching request origins, non-matching
     * origins get no CORS headers.
     */
    private void addCorsHeaders(FullHttpRequest request, FullHttpResponse response) {
        String allowedOrigin = RestApiHandler.resolveCorsOrigin(corsAllowedOrigins,
                request != null ? request.headers().get(HttpHeaderNames.ORIGIN) : null);
        if (allowedOrigin == null) {
            return;
        }
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allowedOrigin);
        if (!"*".equals(allowedOrigin)) {
            response.headers().add(HttpHeaderNames.VARY, "Origin");
        }
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
