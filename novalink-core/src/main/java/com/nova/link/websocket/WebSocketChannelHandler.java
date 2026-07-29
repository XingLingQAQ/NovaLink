package com.nova.link.websocket;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty channel handler for WebSocket frames.
 * Handles WebSocket protocol messages and delegates to WebSocketMessageHandler.
 * 
 * Requirements: 24.1
 */
public class WebSocketChannelHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketChannelHandler.class);
    
    public static final AttributeKey<WebSocketSession> SESSION_KEY = 
            AttributeKey.valueOf("websocket.session");
    
    private final WebSocketMessageHandler messageHandler;

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
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        WebSocketSession session = ctx.channel().attr(SESSION_KEY).get();
        logger.error("WebSocket error for session {}: {}", 
                session != null ? session.getSessionId() : "unknown", cause.getMessage());
        ctx.close();
    }
}
