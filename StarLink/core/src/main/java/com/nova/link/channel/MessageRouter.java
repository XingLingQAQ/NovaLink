package com.nova.link.channel;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.ban.BanManager;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.log.ChatLogger;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.spy.SpyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Routes chat messages to appropriate recipients based on channel scope.
 * <p>
 * Implementation delegates ordered processing (validate → mute → filter → fan-out
 * → spy/ws) to {@link MessagePipeline} so every ingress path shares the same stages.
 * <p>
 * Ingress conventions:
 * <ul>
 *   <li>{@link #routeMessage(ChatMessagePacket)} — untrusted TCP path; full pipeline
 *       with sender-client boundary enforcement ON.</li>
 *   <li>{@link #routeToChannel(Channel, ChatMessagePacket)} / REST
 *       {@link #routeMessage(String, UUID, String, String, Map)} — trusted helpers;
 *       channel already resolved, boundary not re-checked.</li>
 * </ul>
 * Mute and sensitive-word filter run exactly once inside the pipeline for either path.
 *
 * Requirements: 3.2, 3.5, 4.2, 4.3, 5.3, 12.x, 13.2, 17.2
 */
public class MessageRouter {

    private static final Logger logger = LoggerFactory.getLogger(MessageRouter.class);

    private final ChannelManager channelManager;
    private final ServerNetworkHandler networkHandler;
    private final MessagePipeline pipeline;

    public MessageRouter(ChannelManager channelManager, ServerNetworkHandler networkHandler) {
        this.channelManager = Objects.requireNonNull(channelManager, "ChannelManager cannot be null");
        this.networkHandler = Objects.requireNonNull(networkHandler, "ServerNetworkHandler cannot be null");
        this.pipeline = new MessagePipeline(channelManager, networkHandler);
    }

    /**
     * Exposes the underlying pipeline for advanced wiring / tests.
     */
    public MessagePipeline getPipeline() {
        return pipeline;
    }

    public void setPermissionChecker(BiPredicate<String, String> permissionChecker) {
        pipeline.setPermissionChecker(permissionChecker);
    }

    public void setSpyManager(SpyManager spyManager) {
        pipeline.setSpyManager(spyManager);
    }

    public void setWebSocketBroadcaster(WebSocketBroadcaster webSocketBroadcaster) {
        pipeline.setWebSocketBroadcaster(webSocketBroadcaster);
    }

    public void setMuteManager(MuteManager muteManager) {
        pipeline.setMuteManager(muteManager);
    }

    public void setBanManager(BanManager banManager) {
        pipeline.setBanManager(banManager);
    }

    public void setSensitiveWordFilter(SensitiveWordFilter sensitiveWordFilter) {
        pipeline.setSensitiveWordFilter(sensitiveWordFilter);
    }

    public void setChatLogger(ChatLogger chatLogger) {
        pipeline.setChatLogger(chatLogger);
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
     * Routes a chat message through the full pipeline (TCP / untrusted ingress).
     * Always enforces SERVER/PRIVATE sender-client boundary.
     *
     * @param message the chat message packet to route
     * @return the set of client IDs that received the message
     */
    public Set<String> routeMessage(ChatMessagePacket message) {
        Objects.requireNonNull(message, "Message cannot be null");
        MessagePipelineResult result = pipeline.process(message);
        if (!result.isDelivered()) {
            logger.debug("routeMessage dropped: {}", result.getDropReason());
            return Collections.emptySet();
        }
        return result.getRecipients();
    }

    /**
     * Routes a message to a specific already-resolved channel (trusted/internal path).
     * Boundary is not re-checked; mute/filter/fan-out still run once via the pipeline.
     * Also forwards the message to any super admins monitoring this channel.
     *
     * @param channel the target channel
     * @param message the message to route
     * @return the set of client IDs that received the message
     */
    public Set<String> routeToChannel(Channel channel, ChatMessagePacket message) {
        Objects.requireNonNull(channel, "Channel cannot be null");
        Objects.requireNonNull(message, "Message cannot be null");
        MessagePipelineResult result = pipeline.processForChannel(channel, message);
        if (!result.isDelivered()) {
            logger.debug("routeToChannel dropped: {}", result.getDropReason());
            return Collections.emptySet();
        }
        return result.getRecipients();
    }

    /**
     * Determines which clients should receive a message for a given channel.
     * This method does NOT send the message, only calculates recipients.
     */
    public Set<String> calculateRecipients(Channel channel, String senderClientId) {
        Objects.requireNonNull(channel, "Channel cannot be null");
        return pipeline.calculateRecipients(channel);
    }

    /**
     * Validates that a message sender is allowed to send to a channel.
     * For SERVER and PRIVATE channels, the sender must be from the same client.
     * Prefer {@link #routeMessage(ChatMessagePacket)} for TCP ingress — it enforces
     * the same rule inside the pipeline without a separate pre-check.
     */
    public boolean canSendToChannel(Channel channel, String senderClientId) {
        Objects.requireNonNull(channel, "Channel cannot be null");

        switch (channel.getScope()) {
            case GLOBAL:
                return true;
            case SERVER:
            case PRIVATE:
                return channel.getClientId() != null
                        && channel.getClientId().equals(senderClientId);
            default:
                return false;
        }
    }

    /**
     * Routes a message to a channel by channel ID.
     * Convenience method for REST API integration (trusted path, no boundary re-check).
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
