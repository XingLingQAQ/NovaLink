package com.nova.link.network;

import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.link.auth.AuthResult;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.MessageRouter;
import com.nova.link.config.ConfigException;
import com.nova.link.config.ConfigManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.notification.NotificationStore;
import com.nova.link.spy.SpyManager;
import com.nova.link.spy.SpyResult;
import com.nova.link.spy.SpySession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handles admin action packets for super admin authentication and other admin operations.
 * 
 * Requirements:
 * - 2.2: Super admin authentication via `/nc auth <password>`
 * - 2.7: Returns NC-403 for insufficient permissions
 * - 17.1-17.5: Super admin remote monitoring (spy mode)
 */
public class AdminActionHandler {

    private static final Logger logger = LoggerFactory.getLogger(AdminActionHandler.class);

    private final PermissionManager permissionManager;
    private SpyManager spyManager;

    // IP ban manager for rate-limiting brute-force attempts on super-admin auth.
    // When null, IP ban checks are skipped (backwards-compatible default).
    private volatile IpBanManager ipBanManager;

    // Optional integrations for STATUS sub-actions (announce/title) and richer responses
    private volatile ChannelManager channelManager;
    private volatile ServerNetworkHandler networkHandler;
    private volatile MessageRouter messageRouter;
    private volatile ConfigManager configManager;
    private volatile NotificationStore notificationStore;

    public AdminActionHandler(PermissionManager permissionManager) {
        this.permissionManager = permissionManager;
    }

    /**
     * Sets the SpyManager for handling spy mode operations.
     *
     * @param spyManager the spy manager
     */
    public void setSpyManager(SpyManager spyManager) {
        this.spyManager = spyManager;
    }

    /**
     * Sets the ChannelManager for resolving channels for admin actions (announce/title).
     */
    public void setChannelManager(ChannelManager channelManager) {
        this.channelManager = channelManager;
    }

    /**
     * Sets the ServerNetworkHandler for sending packets to clients (title).
     */
    public void setNetworkHandler(ServerNetworkHandler networkHandler) {
        this.networkHandler = networkHandler;
    }

    /**
     * Sets the MessageRouter for routing messages (announce) and web panel forwarding.
     */
    public void setMessageRouter(MessageRouter messageRouter) {
        this.messageRouter = messageRouter;
    }

    /**
     * Sets the ConfigManager for handling reload actions.
     */
    public void setConfigManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * Sets the optional notification store so admin announcements are surfaced
     * to the web panel notification feed.
     */
    public void setNotificationStore(NotificationStore notificationStore) {
        this.notificationStore = notificationStore;
    }

    /**
     * Sets the IP ban manager used to rate-limit super-admin authentication
     * brute-force attempts. When set, {@code handleAuth} mirrors the
     * {@link AuthManager#authenticate} pattern: banned IPs are rejected before
     * the password check, and every failed attempt is recorded.
     *
     * @param ipBanManager the IP ban manager (may be {@code null} to disable)
     */
    public void setIpBanManager(IpBanManager ipBanManager) {
        this.ipBanManager = ipBanManager;
    }

    /**
     * Handles an admin action packet.
     *
     * @param packet the admin action packet
     * @return the response packet
     */
    public AdminActionResponsePacket handle(AdminActionPacket packet) {
        return handle(packet, null);
    }

    /**
     * Handles an admin action packet, optionally carrying the remote IP address
     * of the originating connection. The remote address is used by the AUTH
     * action to enforce IP-based rate limiting / banning via {@link IpBanManager}.
     *
     * @param packet       the admin action packet
     * @param remoteAddress the remote IP of the originating connection, or {@code null}
     * @return the response packet
     */
    public AdminActionResponsePacket handle(AdminActionPacket packet, String remoteAddress) {
        if (packet == null || packet.getAction() == null) {
            return AdminActionResponsePacket.failure(null, "NC-400", "Invalid admin action packet");
        }

        AdminAction action = packet.getAction();
        UUID playerId = packet.getPlayerId();

        logger.debug("Handling admin action: {} for player: {}", action, playerId);

        switch (action) {
            case AUTH:
                return handleAuth(packet, remoteAddress);
            case LOGOUT:
                return handleLogout(packet);
            case SPY_START:
                return handleSpyStart(packet);
            case SPY_STOP:
                return handleSpyStop(packet);
            case RELOAD:
                return handleReload(packet);
            case STATUS:
                return handleStatus(packet);
            default:
                return AdminActionResponsePacket.failure(action, "NC-400", "Unknown admin action");
        }
    }

    /**
     * Handles super admin authentication.
     * 
     * Requirements:
     * - 2.2: Super admin authentication via `/nc auth <password>`
     *
     * @param packet the auth packet
     * @return the response packet
     */
    private AdminActionResponsePacket handleAuth(AdminActionPacket packet, String remoteAddress) {
        UUID playerId = packet.getPlayerId();
        String passwordHash = packet.getPasswordHash();

        // Mirror AuthManager.authenticate: check IP ban BEFORE any password work.
        IpBanManager banManager = this.ipBanManager;
        if (banManager != null && remoteAddress != null && banManager.isBanned(remoteAddress)) {
            long remainingSeconds = banManager.getRemainingBanTime(remoteAddress) / 1000;
            logger.warn("Super admin auth attempt from banned IP: {}", remoteAddress);
            return AdminActionResponsePacket.failure(AdminAction.AUTH, "NC-403",
                "IP temporarily banned. Try again in " + remainingSeconds + " seconds.");
        }

        if (playerId == null) {
            if (banManager != null && remoteAddress != null) {
                banManager.recordFailure(remoteAddress);
            }
            return AdminActionResponsePacket.failure(AdminAction.AUTH, "NC-400", "Player ID is required");
        }

        if (passwordHash == null || passwordHash.isEmpty()) {
            if (banManager != null && remoteAddress != null) {
                banManager.recordFailure(remoteAddress);
            }
            return AdminActionResponsePacket.failure(AdminAction.AUTH, "NC-400", "Password is required");
        }

        AuthResult result = permissionManager.authenticateSuperAdmin(playerId, passwordHash);

        if (result.isSuccess()) {
            if (banManager != null && remoteAddress != null) {
                banManager.clearFailures(remoteAddress);
            }
            logger.info("Super admin authentication successful for player: {}", playerId);
            return AdminActionResponsePacket.success(AdminAction.AUTH, "Super admin authentication successful");
        } else {
            if (banManager != null && remoteAddress != null) {
                banManager.recordFailure(remoteAddress);
            }
            logger.warn("Super admin authentication failed for player: {} from IP: {}", playerId, remoteAddress);
            return AdminActionResponsePacket.failure(AdminAction.AUTH, result.getErrorCode(), result.getMessage());
        }
    }

    /**
     * Handles super admin logout.
     *
     * @param packet the logout packet
     * @return the response packet
     */
    private AdminActionResponsePacket handleLogout(AdminActionPacket packet) {
        UUID playerId = packet.getPlayerId();

        if (playerId == null) {
            return AdminActionResponsePacket.failure(AdminAction.LOGOUT, "NC-400", "Player ID is required");
        }

        permissionManager.revokeSuperAdminSession(playerId);
        logger.info("Super admin session revoked for player: {}", playerId);
        return AdminActionResponsePacket.success(AdminAction.LOGOUT, "Super admin session revoked");
    }

    /**
     * Handles spy mode start.
     * 
     * Requirements:
     * - 17.1: Super admin can execute `/nc admin spy <server_name> <channel_id>` to start monitoring
     * - 17.2: Forward target channel messages to super admin
     *
     * @param packet the spy start packet
     * @return the response packet
     */
    private AdminActionResponsePacket handleSpyStart(AdminActionPacket packet) {
        UUID playerId = packet.getPlayerId();
        String channelId = packet.getTarget();
        String targetClientId = packet.getExtra("clientId");

        if (playerId == null) {
            return AdminActionResponsePacket.failure(AdminAction.SPY_START, "NC-400", "Player ID is required");
        }

        // Check if player has super admin session
        if (!permissionManager.hasSuperAdminSession(playerId)) {
            return AdminActionResponsePacket.failure(AdminAction.SPY_START, "NC-403", 
                "Super admin authentication required for spy mode");
        }

        if (channelId == null || channelId.isEmpty()) {
            return AdminActionResponsePacket.failure(AdminAction.SPY_START, "NC-400", "Channel ID is required");
        }

        if (spyManager == null) {
            logger.error("SpyManager not initialized");
            return AdminActionResponsePacket.failure(AdminAction.SPY_START, "NC-500", 
                "Spy mode not available");
        }

        SpyResult result = spyManager.startSpying(playerId, channelId, targetClientId);
        
        if (result.isSuccess()) {
            // Include current spy sessions in response
            Set<SpySession> sessions = spyManager.getSpySessions(playerId);
            String sessionList = sessions.stream()
                    .map(SpySession::getChannelId)
                    .collect(Collectors.joining(", "));
            
            logger.info("Spy mode started for player {} on channel {}", playerId, channelId);
            return AdminActionResponsePacket.success(AdminAction.SPY_START, 
                result.getMessage() + " (Active sessions: " + sessionList + ")");
        } else {
            return AdminActionResponsePacket.failure(AdminAction.SPY_START, 
                result.getErrorCode(), result.getMessage());
        }
    }

    /**
     * Handles spy mode stop.
     * 
     * Requirements:
     * - 17.5: Super admin can execute `/nc admin spy off` to stop all monitoring
     *
     * @param packet the spy stop packet
     * @return the response packet
     */
    private AdminActionResponsePacket handleSpyStop(AdminActionPacket packet) {
        UUID playerId = packet.getPlayerId();
        String channelId = packet.getTarget(); // Optional: specific channel to stop

        if (playerId == null) {
            return AdminActionResponsePacket.failure(AdminAction.SPY_STOP, "NC-400", "Player ID is required");
        }

        // Check if player has super admin session
        if (!permissionManager.hasSuperAdminSession(playerId)) {
            return AdminActionResponsePacket.failure(AdminAction.SPY_STOP, "NC-403", 
                "Super admin authentication required");
        }

        if (spyManager == null) {
            logger.error("SpyManager not initialized");
            return AdminActionResponsePacket.failure(AdminAction.SPY_STOP, "NC-500", 
                "Spy mode not available");
        }

        SpyResult result;
        if (channelId != null && !channelId.isEmpty()) {
            // Stop spying on specific channel
            result = spyManager.stopSpying(playerId, channelId);
        } else {
            // Stop all spy sessions
            result = spyManager.stopAllSpying(playerId);
        }

        if (result.isSuccess()) {
            logger.info("Spy mode stopped for player {}", playerId);
            return AdminActionResponsePacket.success(AdminAction.SPY_STOP, result.getMessage());
        } else {
            return AdminActionResponsePacket.failure(AdminAction.SPY_STOP, 
                result.getErrorCode(), result.getMessage());
        }
    }

    /**
     * Handles configuration reload.
     *
     * @param packet the reload packet
     * @return the response packet
     */
    private AdminActionResponsePacket handleReload(AdminActionPacket packet) {
        UUID playerId = packet.getPlayerId();

        if (playerId == null) {
            return AdminActionResponsePacket.failure(AdminAction.RELOAD, "NC-400", "Player ID is required");
        }

        // Check if player has super admin session
        if (!permissionManager.hasSuperAdminSession(playerId)) {
            return AdminActionResponsePacket.failure(AdminAction.RELOAD, "NC-403", 
                "Super admin authentication required for reload");
        }

        if (configManager == null) {
            logger.error("ConfigManager not initialized");
            return AdminActionResponsePacket.failure(AdminAction.RELOAD, "NC-500", "Config reload not available");
        }

        try {
            configManager.triggerReload();
            logger.info("Configuration reloaded (requested by player {})", playerId);
            return AdminActionResponsePacket.success(AdminAction.RELOAD, "Configuration reloaded");
        } catch (ConfigException e) {
            logger.error("Configuration reload failed (requested by player {})", playerId, e);
            return AdminActionResponsePacket.failure(AdminAction.RELOAD, "NC-500", "Configuration reload failed: " + e.getMessage());
        }
    }

    /**
     * Handles status request.
     *
     * @param packet the status packet
     * @return the response packet
     */
    private AdminActionResponsePacket handleStatus(AdminActionPacket packet) {
        UUID playerId = packet.getPlayerId();

        if (playerId == null) {
            return AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-400", "Player ID is required");
        }

        // All STATUS subtypes (including ANNOUNCE/TITLE broadcasts) require an
        // active super-admin session. Previously ANNOUNCE/TITLE were dispatched
        // BEFORE this check, allowing any authenticated game server to broadcast
        // arbitrary announcements/titles network-wide without authorization.
        if (!permissionManager.hasSuperAdminSession(playerId)) {
            return AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-403",
                "Super admin authentication required for status");
        }

        // ANNOUNCE/TITLE subtypes reuse the STATUS action for transport. They are
        // now gated by the super-admin session check above, so only authenticated
        // super admins can trigger network-wide announcements / titles.
        String type = packet.getExtra("type");
        if (type != null && !type.isEmpty()) {
            String normalized = type.trim().toUpperCase(Locale.ROOT);
            switch (normalized) {
                case "ANNOUNCE":
                    return handleAnnounce(packet);
                case "TITLE":
                    return handleTitle(packet);
                default:
                    // Unknown subtype -> fall through to the plain status path
                    break;
            }
        }

        // Basic status response (can be extended later)
        logger.info("Status requested by player {}", playerId);
        return AdminActionResponsePacket.success(AdminAction.STATUS, "System status: OK");
    }

    private AdminActionResponsePacket handleAnnounce(AdminActionPacket packet) {
        String channelId = packet.getTarget();
        String content = packet.getExtra("content");
        String operatorName = packet.getExtra("operatorName");

        if (channelId == null || channelId.isEmpty()) {
            return AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-400", "Channel ID is required");
        }
        if (content == null || content.isEmpty()) {
            return AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-400", "Announcement content is required");
        }
        if (channelManager == null || messageRouter == null) {
            logger.error("Announce requested but ChannelManager/MessageRouter not initialized");
            return AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-500", "Announcement not available");
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-404", "Channel not found: " + channelId);
        }

        String senderName = (operatorName != null && !operatorName.isEmpty()) ? operatorName : "公告";
        String message = "【公告】 " + content;
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("_announcement", "true");
        placeholders.put("_operator", senderName);

        // The trusted routeMessage overload (channelId, ...) intentionally does NOT
        // re-enforce the sender-client boundary. This is acceptable here because
        // handleAnnounce is now only reachable by an authenticated super admin
        // (super-admin session check in handleStatus), who is authorized to
        // broadcast to any channel including cross-client PRIVATE/SERVER scopes.
        // Non-super-admin callers can never reach this path.
        messageRouter.routeMessage(channelId, packet.getPlayerId(), senderName, message, placeholders);
        logger.info("Announcement sent to channel {} by {}", channelId, senderName);
        // Surface the announcement to the web panel notification feed.
        if (notificationStore != null) {
            try {
                notificationStore.createNotification(
                        "Announcement",
                        "Announcement sent to channel " + channelId + ": " + content,
                        "info");
            } catch (Exception ignored) {
                // non-fatal
            }
        }
        return AdminActionResponsePacket.success(AdminAction.STATUS, "Announcement sent");
    }

    private AdminActionResponsePacket handleTitle(AdminActionPacket packet) {
        String channelId = packet.getTarget();
        String title = packet.getExtra("title");
        String subtitle = packet.getExtra("subtitle");

        if (channelId == null || channelId.isEmpty()) {
            return AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-400", "Channel ID is required");
        }
        if (title == null || title.isEmpty()) {
            return AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-400", "Title is required");
        }
        if (channelManager == null || networkHandler == null) {
            logger.error("Title requested but ChannelManager/ServerNetworkHandler not initialized");
            return AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-500", "Title not available");
        }

        Channel channel = channelManager.getChannel(channelId);
        if (channel == null) {
            return AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-404", "Channel not found: " + channelId);
        }

        TitlePacket titlePacket = new TitlePacket(channelId, title, subtitle, packet.getPlayerId());
        titlePacket.setRequestId(packet.getRequestId());

        // Optional timing overrides (ticks)
        applyIntExtra(packet, "fadeIn", titlePacket::setFadeIn);
        applyIntExtra(packet, "stay", titlePacket::setStay);
        applyIntExtra(packet, "fadeOut", titlePacket::setFadeOut);

        if (channel.getScope() == ChannelScope.GLOBAL) {
            networkHandler.broadcastAuthenticated(titlePacket);
        } else {
            String targetClientId = channel.getClientId();
            ClientConnection target = targetClientId != null ? networkHandler.findByClientId(targetClientId) : null;
            if (target == null || !target.isActive() || !target.isAuthenticated()) {
                return AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-503", "Target client is not connected");
            }
            target.sendPacket(titlePacket);
        }

        logger.info("Title sent to channel {}", channelId);
        return AdminActionResponsePacket.success(AdminAction.STATUS, "Title sent");
    }

    private void applyIntExtra(AdminActionPacket packet, String key, java.util.function.IntConsumer setter) {
        String raw = packet.getExtra(key);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value >= 0) {
                setter.accept(value);
            }
        } catch (NumberFormatException ignored) {
            // ignore invalid override
        }
    }
}
