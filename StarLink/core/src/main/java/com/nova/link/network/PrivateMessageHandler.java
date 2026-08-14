package com.nova.link.network;

import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.i18n.I18n;
import com.nova.link.log.ChatLogger;
import com.nova.link.mute.MuteManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Routes {@link PrivateMessagePacket}s (0x14, cross-server /msg + /reply)
 * following the ItemDisplayHandler validation-chain pattern:
 * <ul>
 *   <li>sender must be authenticated (silent drop otherwise);</li>
 *   <li>the packet counts against the same per-connection token bucket as
 *       chat messages / item displays (NC-429, throttled);</li>
 *   <li>{@code features.private-messages-enabled} must be on (NC-403);</li>
 *   <li>the sender must not be globally muted (NC-403);</li>
 *   <li>the target is resolved by name (case-insensitive exact match) across
 *       the whole network via {@link PlayerStateManager}; an unknown name or
 *       an offline target answers NC-404;</li>
 *   <li>self-messaging is rejected (NC-403).</li>
 * </ul>
 *
 * <p><b>Privacy (P0-5):</b> after the target is resolved, the backend checks
 * the target's per-player DM toggle ({@link PlayerState#isDmEnabled()}). When
 * the target has disabled DMs the message is rejected with NC-403 detail
 * {@code target_dm_disabled} before any delivery — the sender is notified,
 * the target is never disturbed. This is independent of (and in addition to)
 * the global {@code features.private-messages-enabled} toggle.
 *
 * <p>On success the backend completes the packet (real {@code targetId},
 * authoritative {@code senderClientId} + server timestamp) and delivers it to
 * the target player's client connection <em>and</em> echoes it back to the
 * sender's client — receiving plugins render the sent/received wording based
 * on which local player matches senderId/targetId. When both players share a
 * connection the packet is sent once (the plugin renders both roles).
 *
 * <p>Privacy boundary: private messages are audited via
 * {@link ChatLogger#logPrivateMessage} ({@code [DM]} marker) only. They are
 * never written to the {@code messages} history table and never mirrored to
 * the web-panel WebSocket feed.
 *
 * <p>Rejections are answered with a {@link ChannelActionResponsePacket}
 * failure echoing the request id, with {@code extra.reason=private_message}.
 */
public class PrivateMessageHandler implements PacketHandler<PrivateMessagePacket> {

    private static final Logger logger = LoggerFactory.getLogger(PrivateMessageHandler.class);

    /** extra.reason value that disambiguates these errors on the client. */
    public static final String ERROR_REASON = "private_message";

    private final ServerNetworkHandler networkHandler;
    private final PlayerStateManager playerStateManager;
    private final MuteManager muteManager;
    private final RateLimiter rateLimiter;
    private final BooleanSupplier privateMessagesEnabled;
    private final ChatLogger chatLogger;

    /**
     * @param networkHandler         connection registry for delivery
     * @param playerStateManager     network-wide player cache (name -> state)
     * @param muteManager            nullable; global-mute lookups skipped when null
     * @param rateLimiter            nullable; the shared per-connection token
     *                               bucket (same instance as the chat handler)
     * @param privateMessagesEnabled live view of features.private-messages-enabled
     * @param chatLogger             nullable; [DM] audit sink
     */
    public PrivateMessageHandler(ServerNetworkHandler networkHandler,
                                 PlayerStateManager playerStateManager,
                                 MuteManager muteManager,
                                 RateLimiter rateLimiter,
                                 BooleanSupplier privateMessagesEnabled,
                                 ChatLogger chatLogger) {
        this.networkHandler = networkHandler;
        this.playerStateManager = playerStateManager;
        this.muteManager = muteManager;
        this.rateLimiter = rateLimiter;
        this.privateMessagesEnabled = privateMessagesEnabled != null ? privateMessagesEnabled : () -> true;
        this.chatLogger = chatLogger;
    }

    @Override
    public void handle(ClientConnection connection, PrivateMessagePacket packet) {
        if (!connection.isAuthenticated()) {
            logger.debug("Dropping PrivateMessagePacket from unauthenticated connection {}",
                    connection.getRemoteAddress());
            return;
        }

        // Shared token bucket with chat messages (throttled error/log on excess).
        if (rateLimiter != null && rateLimiter.isEnabled()
                && !rateLimiter.tryAcquire(connection.getConnectionId())) {
            if (rateLimiter.shouldNotify(connection.getConnectionId())) {
                logger.warn("Rate limit exceeded for client {} (private message dropped)",
                        connection.getClientId());
                sendError(connection, packet, "NC-429",
                        I18n.tr("network.error.rate_limited"), "rate_limited");
            }
            return;
        }

        // Feature toggle (features.private-messages-enabled, hot-reloadable).
        if (!privateMessagesEnabled.getAsBoolean()) {
            logger.debug("PrivateMessage drop: feature disabled (client {})", connection.getClientId());
            sendError(connection, packet, "NC-403",
                    I18n.tr("network.error.private_messages_disabled"), "disabled");
            return;
        }

        // Globally muted senders cannot whisper (channel mutes do not apply here).
        if (muteManager != null && packet.getSenderId() != null
                && muteManager.isMuted(packet.getSenderId(), null)) {
            logger.debug("PrivateMessage drop: sender {} globally muted", packet.getSenderId());
            sendError(connection, packet, "NC-403",
                    I18n.tr("network.error.muted_global"), "muted");
            return;
        }

        // Resolve the target by name across the whole network (case-insensitive
        // exact match). Unknown name -> NC-404.
        String targetName = packet.getTargetName();
        PlayerState targetState = findPlayerByName(targetName);
        if (targetState == null) {
            logger.debug("PrivateMessage drop: target '{}' not found (client {})",
                    targetName, connection.getClientId());
            sendError(connection, packet, "NC-404",
                    I18n.tr("network.error.player_not_online", targetName != null ? targetName : ""),
                    "not_online");
            return;
        }

        // Per-player DM toggle (P0-5): the target may have opted out of DMs.
        // Reject before delivery — the sender is notified, the target is never
        // disturbed. This is independent of the global feature toggle above.
        if (!targetState.isDmEnabled()) {
            logger.debug("PrivateMessage drop: target '{}' has DMs disabled (client {})",
                    targetName, connection.getClientId());
            sendError(connection, packet, "NC-403",
                    I18n.tr("network.error.target_dm_disabled",
                            targetName != null ? targetName : ""),
                    "target_dm_disabled");
            return;
        }

        // Self-messaging is pointless: reject with a hint.
        if (targetState.getPlayerId().equals(packet.getSenderId())) {
            sendError(connection, packet, "NC-403",
                    I18n.tr("network.error.private_message_self"), "self");
            return;
        }

        // The target's client must be connected; otherwise the player is offline.
        String targetClientId = targetState.getClientId();
        ClientConnection targetConnection = (targetClientId == null || targetClientId.isEmpty())
                ? null
                : networkHandler.findByClientId(targetClientId);
        if (targetConnection == null || !targetConnection.isActive()
                || !targetConnection.isAuthenticated()) {
            logger.debug("PrivateMessage drop: target '{}' offline (client {} not connected)",
                    targetName, targetClientId);
            sendError(connection, packet, "NC-404",
                    I18n.tr("network.error.player_not_online", targetName != null ? targetName : ""),
                    "not_online");
            return;
        }

        // Complete the packet: real targetId + canonical target name, the
        // authenticated sender client id, and the server-authoritative timestamp.
        packet.setTargetId(targetState.getPlayerId());
        if (targetState.getPlayerName() != null && !targetState.getPlayerName().isEmpty()) {
            packet.setTargetName(targetState.getPlayerName());
        }
        packet.setSenderClientId(connection.getClientId());
        packet.setTimestamp(System.currentTimeMillis());

        // Dual delivery: target's client + echo to the sender's client. When
        // both share the connection, one packet carries both render roles.
        targetConnection.sendPacket(packet);
        if (targetConnection != connection) {
            connection.sendPacket(packet);
        }
        logger.debug("PrivateMessage delivered {} -> {} (clients {} -> {})",
                packet.getSenderName(), packet.getTargetName(),
                connection.getClientId(), targetClientId);

        // [DM] audit only — never the messages history table, never the panel.
        if (chatLogger != null) {
            try {
                chatLogger.logPrivateMessage(
                        packet.getSenderId() != null ? packet.getSenderId().toString() : null,
                        packet.getSenderName(),
                        packet.getTargetId() != null ? packet.getTargetId().toString() : null,
                        packet.getTargetName(),
                        packet.getContent());
            } catch (Exception e) {
                logger.debug("ChatLogger failed: {}", e.getMessage());
            }
        }
    }

    /** Case-insensitive exact name match over the network-wide player cache. */
    private PlayerState findPlayerByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (PlayerState state : playerStateManager.getAllPlayerStates()) {
            if (state.getPlayerName() != null && state.getPlayerName().equalsIgnoreCase(name)) {
                return state;
            }
        }
        return null;
    }

    /**
     * Sends a failure response to the sender. ChannelActionResponsePacket is
     * the protocol's generic error carrier; the {@code reason} extra
     * disambiguates these unsolicited private-message failures from
     * channel-action responses, and {@code detail} + {@code senderId} +
     * {@code targetName} let the client render a precise player-locale line.
     */
    private void sendError(ClientConnection connection, PrivateMessagePacket packet,
                           String errorCode, String message, String detail) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, null, "", errorCode, message);
        response.setRequestId(packet.getRequestId());
        response.addExtra("reason", ERROR_REASON);
        response.addExtra("detail", detail);
        if (packet.getSenderId() != null) {
            response.addExtra("senderId", packet.getSenderId().toString());
        }
        if (packet.getTargetName() != null) {
            response.addExtra("targetName", packet.getTargetName());
        }
        connection.sendPacket(response);
    }
}
