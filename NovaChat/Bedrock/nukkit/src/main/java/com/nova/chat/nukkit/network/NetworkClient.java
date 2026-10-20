package com.nova.chat.nukkit.network;

import cn.nukkit.scheduler.AsyncTask;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.config.NovaChatConfig;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Nukkit NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Preserves the historical public API used by the rest of the Nukkit plugin.
 * Netty bootstrap, handshake, keepalive, handler map, and reconnect policy live in
 * client-core; this class only supplies Nukkit scheduler/logger adapters and the
 * admin-action correlation described below.
 */
public class NetworkClient extends AbstractPlatformNetworkClient {

    private final NovaChatNukkit plugin;

    /**
     * Correlates an in-flight {@link AdminActionPacket} with the sender it
     * belongs to, so the asynchronous {@code AdminActionResponsePacket} can be
     * rendered back to that sender (player, or the console sentinel).
     *
     * <p>Admin actions are outside the shared
     * {@link com.nova.chat.client.network.ChannelResponseTracker}, which only
     * tracks {@code ChannelActionPacket}s, so the correlation lives here —
     * the Nukkit counterpart of Bukkit's {@code NetworkClient#pendingAdminRequests}.
     */
    private final ConcurrentMap<UUID, UUID> pendingAdminRequests = new ConcurrentHashMap<>();

    /**
     * Creates a new NetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public NetworkClient(NovaChatNukkit plugin, NovaChatConfig config) {
        this.plugin = plugin;
        ClientConnectionConfig connectionConfig = config.toClientConnectionConfig();
        SchedulerBridge scheduler = new NukkitSchedulerBridge(plugin);
        ClientLogger logger = new NukkitClientLogger(plugin);
        String serverVersion = plugin.getServer().getVersion();
        initCore(
                connectionConfig,
                PlatformType.NUKKIT,
                scheduler,
                logger,
                "config.yml",
                Function.identity(),
                serverVersion
        );
        registerHandler(AdminActionResponsePacket.class, this::handleAdminActionResponse);
    }

    /**
     * Sends a packet to the backend. For {@link AdminActionPacket}s this records
     * the {@code requestId -> senderId} correlation before the wire send, so the
     * asynchronous {@link AdminActionResponsePacket} can be routed back to the
     * originating sender. Everything else delegates straight to the core, which
     * owns channel-action correlation.
     *
     * @param packet the packet to send
     */
    @Override
    public void sendPacket(Packet packet) {
        if (packet instanceof AdminActionPacket adminActionPacket
                && adminActionPacket.getPlayerId() != null
                && adminActionPacket.getRequestId() != null) {
            pendingAdminRequests.put(adminActionPacket.getRequestId(),
                    adminActionPacket.getPlayerId());
        }
        super.sendPacket(packet);
    }

    /**
     * Removes and returns the sender id recorded for an admin-action request id.
     * Removal (not just lookup) is deliberate: a response is rendered exactly
     * once, so an unsolicited response reads as "no pending request".
     *
     * @param requestId the response's request id
     * @return the originating sender id, or {@code null} when nothing is pending
     */
    public UUID consumePendingAdminRequest(UUID requestId) {
        if (requestId == null) {
            return null;
        }
        return pendingAdminRequests.remove(requestId);
    }

    /**
     * @return the number of in-flight admin actions awaiting a response
     */
    public int pendingAdminRequestCount() {
        return pendingAdminRequests.size();
    }

    /**
     * Renders an {@link AdminActionResponsePacket} back to whoever issued the
     * command ({@code /nc auth}, {@code /nc announce}, {@code /nc title}).
     *
     * <p>Runs on the Netty event loop, so the player-facing output hops to the
     * Nukkit main thread first. A console/RCON sender has no {@code Player}
     * behind the all-zeros sentinel, so its outcome goes to the server log
     * instead of being dropped.
     *
     * @param response the backend's answer
     */
    private void handleAdminActionResponse(AdminActionResponsePacket response) {
        UUID senderId = consumePendingAdminRequest(response.getRequestId());
        if (senderId == null) {
            plugin.debug("Received AdminActionResponsePacket with no pending request: " + response);
            return;
        }

        final boolean superAdminRequired = isSuperAdminRequired(response);
        final String successText = response.getMessage() != null && !response.getMessage().isEmpty()
                ? response.getMessage()
                : I18n.tr(senderId, "chat.action.success");
        final String errorText = errorText(response, senderId);
        final String guidanceText = I18n.tr(senderId, "chat.error.super_admin_required");
        final String suggestionText = I18n.tr(senderId, "chat.error.super_admin_required_suggestion");

        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            if (NovaConstants.CONSOLE_SENTINEL_UUID.equals(senderId.toString())) {
                if (response.isSuccess()) {
                    plugin.getLogger().info("[NovaChat console] " + successText);
                } else if (superAdminRequired) {
                    plugin.getLogger().warning("[NovaChat console] " + guidanceText);
                    plugin.getLogger().warning("[NovaChat console] " + suggestionText);
                } else {
                    plugin.getLogger().warning("[NovaChat console] " + errorText);
                }
                return;
            }

            cn.nukkit.Player player = plugin.getServer().getPlayer(senderId).orElse(null);
            if (player == null) {
                plugin.debug("AdminActionResponsePacket target player not online: " + senderId);
                return;
            }
            if (response.isSuccess()) {
                plugin.getMessageHelper().sendSuccess(player, successText);
                return;
            }
            plugin.getMessageHelper().sendError(player, superAdminRequired ? guidanceText : errorText);
            if (superAdminRequired) {
                plugin.getMessageHelper().sendSuggestion(player, suggestionText);
            }
        });
    }

    /**
     * Error copy: the shared {@link ErrorCode} text (with the backend's message
     * substituted when supplied) for a known code, otherwise the raw code.
     *
     * @param response the failed response
     * @param senderId the originating sender (for locale resolution)
     * @return the rendered error text
     */
    private static String errorText(AdminActionResponsePacket response, UUID senderId) {
        String code = response.getErrorCode();
        String message = response.getMessage();
        boolean hasMessage = message != null && !message.isEmpty();
        if (code == null || code.isEmpty()) {
            return hasMessage ? message : I18n.tr(senderId, "chat.action.failed");
        }
        ErrorCode resolved = ErrorCode.fromCode(code);
        if (!resolved.getCode().equals(code)) {
            // Unknown to the shared enum: keep the raw backend code visible.
            return "[" + code + "] "
                    + (hasMessage ? message : I18n.tr(senderId, "chat.action.failed"));
        }
        return hasMessage
                ? ErrorMessageFormatter.format(resolved, message)
                : ErrorMessageFormatter.format(resolved);
    }

    /**
     * True when the backend rejected a STATUS (ANNOUNCE/TITLE) request with
     * NC-403, which is how it signals that the sender lacks an active
     * super-admin session ({@code AdminActionHandler#handleStatus}). The caller
     * renders the "run /nc auth" guidance instead of the generic FORBIDDEN text.
     *
     * @param response the admin action response
     * @return true if this is the super-admin-session-required path
     */
    static boolean isSuperAdminRequired(AdminActionResponsePacket response) {
        return !response.isSuccess()
                && response.getAction() == AdminAction.STATUS
                && "NC-403".equals(response.getErrorCode());
    }

    /**
     * Nukkit scheduler adapter: seconds-based delays converted to ticks (20/second),
     * async hops via {@link AsyncTask}.
     */
    static final class NukkitSchedulerBridge implements SchedulerBridge {
        private final NovaChatNukkit plugin;

        NukkitSchedulerBridge(NovaChatNukkit plugin) {
            this.plugin = plugin;
        }

        @Override
        public void runAsync(Runnable task) {
            plugin.getServer().getScheduler().scheduleAsyncTask(plugin, new AsyncTask() {
                @Override
                public void onRun() {
                    task.run();
                }
            });
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            long delayTicks = Math.max(0L, delaySeconds) * 20L;
            plugin.getServer().getScheduler().scheduleDelayedTask(plugin, task::run, (int) delayTicks);
        }
    }

    /**
     * Maps the Nukkit plugin logger + plugin debug gate onto {@link ClientLogger}.
     */
    static final class NukkitClientLogger implements ClientLogger {
        private final NovaChatNukkit plugin;

        NukkitClientLogger(NovaChatNukkit plugin) {
            this.plugin = plugin;
        }

        @Override
        public void info(String message) {
            plugin.getLogger().info(message);
        }

        @Override
        public void warn(String message) {
            plugin.getLogger().warning(message);
        }

        @Override
        public void debug(String message) {
            plugin.debug(message);
        }

        @Override
        public void error(String message) {
            plugin.getLogger().error(message);
        }

        @Override
        public void error(String message, Throwable cause) {
            if (cause == null) {
                plugin.getLogger().error(message);
            } else {
                plugin.getLogger().error(message + ": " + cause.getMessage());
            }
        }
    }
}
