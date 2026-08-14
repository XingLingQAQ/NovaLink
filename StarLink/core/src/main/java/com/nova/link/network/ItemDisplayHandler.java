package com.nova.link.network;

import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.i18n.I18n;
import com.nova.link.mute.MuteManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;

/**
 * Routes {@link ItemDisplayPacket}s (0x10, item showcase feature) to every
 * client connection that serves the target channel, mirroring the ChatMessage
 * fan-out rules:
 * <ul>
 *   <li>sender must be authenticated;</li>
 *   <li>sender must not be muted/banned in the channel;</li>
 *   <li>the channel must exist and the sender's client must satisfy the
 *       channel boundary (GLOBAL: any client; SERVER/PRIVATE: only the owning
 *       client);</li>
 *   <li>GLOBAL fan-out honors {@code features.cross-server-chat-enabled} and
 *       the channel's permission node;</li>
 *   <li>the packet counts against the same per-connection token bucket as
 *       chat messages.</li>
 * </ul>
 * Fan-out includes the source client itself so other members on the sender's
 * server see the item too (same as chat). Rejections are answered with a
 * {@link ChannelActionResponsePacket} failure echoing the request id.
 * The web panel does not implement item display, so no WS mirroring happens.
 */
public class ItemDisplayHandler implements PacketHandler<ItemDisplayPacket> {

    private static final Logger logger = LoggerFactory.getLogger(ItemDisplayHandler.class);

    private final ChannelManager channelManager;
    private final ServerNetworkHandler networkHandler;
    private final MuteManager muteManager;
    private final BanManager banManager;
    private final RateLimiter rateLimiter;
    private final BiPredicate<String, String> permissionChecker;
    private final BooleanSupplier crossServerChatEnabled;

    /**
     * @param channelManager         channel registry
     * @param networkHandler         connection registry for fan-out
     * @param muteManager            nullable; mute lookups skipped when null
     * @param banManager             nullable; ban lookups skipped when null
     * @param rateLimiter            nullable; the shared per-connection token
     *                               bucket (same instance as the chat handler)
     * @param permissionChecker      GLOBAL channel permission gate (clientId, node)
     * @param crossServerChatEnabled live view of features.cross-server-chat-enabled
     */
    public ItemDisplayHandler(ChannelManager channelManager,
                              ServerNetworkHandler networkHandler,
                              MuteManager muteManager,
                              BanManager banManager,
                              RateLimiter rateLimiter,
                              BiPredicate<String, String> permissionChecker,
                              BooleanSupplier crossServerChatEnabled) {
        this.channelManager = channelManager;
        this.networkHandler = networkHandler;
        this.muteManager = muteManager;
        this.banManager = banManager;
        this.rateLimiter = rateLimiter;
        this.permissionChecker = permissionChecker != null ? permissionChecker : (c, p) -> true;
        this.crossServerChatEnabled = crossServerChatEnabled != null ? crossServerChatEnabled : () -> true;
    }

    @Override
    public void handle(ClientConnection connection, ItemDisplayPacket packet) {
        if (!connection.isAuthenticated()) {
            logger.debug("Dropping ItemDisplayPacket from unauthenticated connection {}",
                    connection.getRemoteAddress());
            return;
        }

        // Shared token bucket with chat messages (throttled error/log on excess).
        if (rateLimiter != null && rateLimiter.isEnabled()
                && !rateLimiter.tryAcquire(connection.getConnectionId())) {
            if (rateLimiter.shouldNotify(connection.getConnectionId())) {
                logger.warn("Rate limit exceeded for client {} (item display dropped)",
                        connection.getClientId());
                sendError(connection, packet, "NC-429", I18n.tr("network.error.rate_limited"));
            }
            return;
        }

        String channelId = packet.getChannelId();
        Channel channel = (channelId == null || channelId.isBlank())
                ? null
                : channelManager.getChannel(channelId);
        if (channel == null) {
            logger.debug("ItemDisplay drop: channel '{}' not found (client {})",
                    channelId, connection.getClientId());
            sendError(connection, packet, "NC-404",
                    I18n.tr("network.error.channel_not_found", channelId != null ? channelId : ""));
            return;
        }

        // Channel boundary: SERVER/PRIVATE accept the owning client only.
        if (channel.getScope() == ChannelScope.SERVER || channel.getScope() == ChannelScope.PRIVATE) {
            if (channel.getClientId() == null || !channel.getClientId().equals(connection.getClientId())) {
                logger.warn("ItemDisplay drop CROSS_CLIENT senderClient={} channel={} owner={}",
                        connection.getClientId(), channel.getId(), channel.getClientId());
                sendError(connection, packet, "NC-403",
                        I18n.tr("network.error.cross_client_denied", channel.getId()));
                return;
            }
        }

        // Mute / ban checks (same managers as the chat pipeline).
        if (muteManager != null && packet.getSenderId() != null
                && muteManager.isMuted(packet.getSenderId(), channel.getId())) {
            logger.debug("ItemDisplay drop: sender {} muted in channel {}",
                    packet.getSenderId(), channel.getId());
            sendError(connection, packet, "NC-403",
                    I18n.tr("network.error.muted", channel.getId()));
            return;
        }
        if (banManager != null && packet.getSenderId() != null
                && banManager.isBanned(packet.getSenderId(), channel.getId())) {
            logger.debug("ItemDisplay drop: sender {} banned from channel {}",
                    packet.getSenderId(), channel.getId());
            sendError(connection, packet, "NC-403",
                    I18n.tr("network.error.banned", channel.getId()));
            return;
        }

        fanOut(channel, packet);
    }

    private void fanOut(Channel channel, ItemDisplayPacket packet) {
        if (channel.getScope() == ChannelScope.GLOBAL) {
            // Mirror the chat pipeline: when cross-server chat is disabled,
            // GLOBAL delivery is suppressed silently (config decision, not a
            // sender error).
            if (!crossServerChatEnabled.getAsBoolean()) {
                logger.debug("ItemDisplay GLOBAL fan-out suppressed (crossServerChatEnabled=false) channel={}",
                        channel.getId());
                return;
            }
            String requiredPermission = channel.getPermission();
            int sent = 0;
            for (ClientConnection target : networkHandler.getConnections()) {
                if (!target.isAuthenticated() || !target.isActive()) {
                    continue;
                }
                if (requiredPermission != null && !requiredPermission.isEmpty()
                        && !permissionChecker.test(target.getClientId(), requiredPermission)) {
                    continue;
                }
                target.sendPacket(packet);
                sent++;
            }
            logger.debug("ItemDisplay fan-out channel={} scope=GLOBAL clients={}", channel.getId(), sent);
        } else {
            // SERVER/PRIVATE: the owning client is the single recipient — this
            // includes the source client, so same-server members see the item.
            ClientConnection target = networkHandler.findByClientId(channel.getClientId());
            if (target != null && target.isActive()) {
                target.sendPacket(packet);
                logger.debug("ItemDisplay fan-out channel={} scope={} client={}",
                        channel.getId(), channel.getScope(), channel.getClientId());
            }
        }
    }

    /**
     * Sends a failure response to the sender. ChannelActionResponsePacket is
     * the protocol's generic error carrier (server → client, supports error
     * code + message + extras); the {@code reason} extra disambiguates these
     * unsolicited item-display failures from channel-action responses.
     */
    private void sendError(ClientConnection connection, ItemDisplayPacket packet,
                           String errorCode, String message) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, null, packet.getChannelId() != null ? packet.getChannelId() : "",
                errorCode, message);
        response.setRequestId(packet.getRequestId());
        response.addExtra("reason", "item_display");
        connection.sendPacket(response);
    }
}
