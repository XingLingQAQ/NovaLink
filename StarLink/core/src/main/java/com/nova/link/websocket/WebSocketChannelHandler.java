package com.nova.link.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty channel handler for WebSocket frames.
 * Handles WebSocket protocol messages and delegates to WebSocketMessageHandler.
 *
 * <p>Two-phase idle handling (paired with the {@code IdleStateHandler} in
 * {@link WebSocketServer}): the first read-idle sends a WS ping frame to give
 * the client one chance to prove liveness; a second consecutive read-idle
 * closes the session. Any inbound frame (including pong) resets the phase.
 * The panel sends an application-level ping every 30s, so a healthy client
 * never reaches the first idle event (60s).</p>
 *
 * Requirements: 24.1
 */
public class WebSocketChannelHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketChannelHandler.class);
    
    public static final AttributeKey<WebSocketSession> SESSION_KEY = 
            AttributeKey.valueOf("websocket.session");
    
    private final WebSocketMessageHandler messageHandler;

    // Per-connection state (this handler is instantiated per channel).
    private boolean handshakeComplete;
    private boolean idlePingSent;

    public WebSocketChannelHandler(WebSocketMessageHandler messageHandler) {
        this.messageHandler = messageHandler;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // Create and register session
        WebSocketSession session = new WebSocketSession(ctx.channel());
        ctx.channel().attr(SESSION_KEY).set(session);
        messageHandler.registerSession(session);
        
        logger.debug("WebSocket channel active: {}", session.getSessionId());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Unregister session
        WebSocketSession session = ctx.channel().attr(SESSION_KEY).get();
        if (session != null) {
            messageHandler.unregisterSession(session);
            logger.debug("WebSocket channel inactive: {}", session.getSessionId());
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        WebSocketSession session = ctx.channel().attr(SESSION_KEY).get();

        // Any inbound frame proves the connection is alive: the WS upgrade has
        // happened and a pending idle-ping (if any) is considered answered.
        handshakeComplete = true;
        idlePingSent = false;

        if (frame instanceof TextWebSocketFrame) {
            // Handle text message
            String text = ((TextWebSocketFrame) frame).text();
            if (logger.isDebugEnabled()) {
                String sessionId = session != null ? session.getSessionId() : "unknown";
                String type = null;
                try {
                    JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                    type = json.has("type") ? json.get("type").getAsString() : null;
                } catch (Exception ignored) {
                    // best-effort: avoid logging raw message to prevent token leakage
                }
                logger.debug("Received WebSocket message from {} (type={}, bytes={})",
                        sessionId, type != null ? type : "<unknown>", text.length());
            }
            
            if (session != null) {
                messageHandler.handleMessage(session, text);
            }
        } else if (frame instanceof PingWebSocketFrame) {
            // Respond to ping with pong
            ctx.channel().writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
        } else if (frame instanceof PongWebSocketFrame) {
            // Pong received, connection is alive
            logger.trace("Received pong from {}", session != null ? session.getSessionId() : "unknown");
        } else if (frame instanceof CloseWebSocketFrame) {
            // Close frame received
            logger.debug("Received close frame from {}", session != null ? session.getSessionId() : "unknown");
            ctx.channel().close();
        } else {
            logger.warn("Unsupported WebSocket frame type: {}", frame.getClass().getName());
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            handshakeComplete = true;
            super.userEventTriggered(ctx, evt);
            return;
        }
        if (evt instanceof IdleStateEvent idleEvent) {
            if (idleEvent.state() == IdleState.READER_IDLE) {
                WebSocketSession session = ctx.channel().attr(SESSION_KEY).get();
                String sessionId = session != null ? session.getSessionId() : "unknown";
                if (!handshakeComplete) {
                    // Still in the HTTP handshake phase — a WS ping frame cannot
                    // be sent yet, so just drop the stale connection.
                    logger.info("Closing idle WebSocket connection {} (handshake never completed)",
                            sessionId);
                    ctx.close();
                } else if (!idlePingSent) {
                    idlePingSent = true;
                    logger.debug("WebSocket session {} read-idle; sending ping frame", sessionId);
                    ctx.writeAndFlush(new PingWebSocketFrame(Unpooled.EMPTY_BUFFER));
                } else {
                    logger.info("Closing idle WebSocket session {} (no response to idle ping)",
                            sessionId);
                    ctx.close();
                }
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        WebSocketSession session = ctx.channel().attr(SESSION_KEY).get();
        logger.error("WebSocket error for session {}: {}", 
                session != null ? session.getSessionId() : "unknown", cause.getMessage());
        ctx.close();
    }
}
