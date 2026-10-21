package com.nova.chat.pnx.network;

import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.itemdisplay.ItemDisplayMessages;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.config.NovaChatConfig;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * PNX NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Preserves the historical public API used by the rest of the PNX plugin. Netty
 * bootstrap, handshake, keepalive, handler map, and reconnect policy live in
 * client-core (inherited via {@link AbstractPlatformNetworkClient}); this class
 * only supplies PNX scheduler/logger adapters and registers PNX-specific
 * chat/title inbound handlers that historically lived in the hard-coded
 * {@code ClientChannelHandler}.
 *
 * <p>Architecture B: plugin-only. No backend changes.
 */
public class NetworkClient extends AbstractPlatformNetworkClient {

    private final NovaChatPNX plugin;

    /** Shared response dispatcher (DUP-3); created in the constructor. */
    private final ChannelResponseDispatcher dispatcher;

    /**
     * Correlates an in-flight {@link AdminActionPacket} with the sender it
     * belongs to, so the asynchronous {@code AdminActionResponsePacket} can be
     * rendered back to that sender (player, or the console sentinel).
     *
     * <p>Admin actions are outside the shared
     * {@link com.nova.chat.client.network.ChannelResponseTracker}, which only
     * tracks {@code ChannelActionPacket}s, so the correlation lives here —
     * the PNX counterpart of Bukkit's {@code NetworkClient#pendingAdminRequests}.
     */
    private final ConcurrentMap<UUID, UUID> pendingAdminRequests = new ConcurrentHashMap<>();

    /**
     * Creates a new NetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public NetworkClient(NovaChatPNX plugin, NovaChatConfig config) {
        this.plugin = plugin;
        ClientConnectionConfig connectionConfig = config.toClientConnectionConfig();
        SchedulerBridge scheduler = new PNXSchedulerBridge(plugin);
        ClientLogger logger = new PNXClientLogger(plugin);
        String serverVersion = plugin.getServer().getVersion();
        initCore(
                connectionConfig,
                PlatformType.POWERNUKKITX,
                scheduler,
                logger,
                "config.yml",
                Function.identity(),
                serverVersion
        );

        // Preserve PNX chat/title handling that previously lived hard-coded in
        // ClientChannelHandler. CoreNetworkClient owns HandshakeResponse/KeepAlive.
        this.dispatcher = new ChannelResponseDispatcher(
                getChannelResponseTracker(),
                new PNXChannelResponseAdapter());
        registerPnxHandlers();
    }

    /**
     * Registers PNX-specific inbound handlers (chat/title) on the core client.
     *
     * <p>These previously lived hard-coded in {@code ClientChannelHandler.channelRead0}.
     * They hop to the PNX main thread via {@code scheduleTask} for thread safety,
     * matching the historical behaviour exactly.
     */
    private void registerPnxHandlers() {
        registerHandler(ChatMessagePacket.class, this::handleChatMessage);
        registerHandler(TitlePacket.class, this::handleTitleMessage);
        registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
        registerHandler(MentionPacket.class, this::handleMentionMessage);
        registerHandler(ItemDisplayPacket.class, this::handleItemDisplayMessage);
        registerHandler(com.nova.chat.common.protocol.packets.PrivateMessagePacket.class,
                this::handlePrivateMessage);
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
     * PNX main thread first. A console/RCON sender has no {@code Player} behind
     * the all-zeros sentinel, so its outcome goes to the server log instead of
     * being dropped.
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
                player.sendMessage(plugin.getMessageFormatter().formatSuccess(successText));
                return;
            }
            player.sendMessage(plugin.getMessageFormatter().formatError(
                    superAdminRequired ? guidanceText : errorText));
            if (superAdminRequired) {
                player.sendMessage(plugin.getMessageFormatter().colorize(
                        "  &7" + I18n.tr(senderId, "error.suggestion_prefix")
                                + " &f" + suggestionText));
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
     * Handles a completed (S→C) private message: the shared
     * {@link com.nova.chat.client.privatemsg.PrivateMessageService} resolves
     * which local players render which role (sender echo vs received line,
     * receiver-side ignore filter, reply tracking). Hops to the PNX main
     * thread before touching the player API, mirroring
     * {@link #handleItemDisplayMessage}.
     */
    private void handlePrivateMessage(com.nova.chat.common.protocol.packets.PrivateMessagePacket packet) {
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            var deliveries = plugin.getPrivateMessageService().handleIncoming(
                    packet,
                    id -> plugin.getServer().getPlayer(id).orElse(null) != null,
                    plugin.getIgnoreListService());
            for (com.nova.chat.client.privatemsg.PrivateMessageService.Delivery delivery : deliveries) {
                cn.nukkit.Player player = plugin.getServer().getPlayer(delivery.getPlayerId()).orElse(null);
                if (player != null) {
                    player.sendMessage(plugin.getMessageFormatter().colorize(delivery.getLine()));
                }
            }
        });
    }

    /**
     * Handles an inbound item display packet ({@code [item]}/{@code [i]} play,
     * packet 0x10) by rendering one chat line to every player whose active
     * channel matches the packet channel.
     *
     * <p>Receive-side semantics are "receive = render", matching the Bedrock
     * clients (pmmp/endstone/levilamina); the backend currently registers no
     * route for this packet. Hops to the PNX main thread via
     * {@code scheduleTask} and applies color codes with the shared formatter,
     * mirroring {@link #handleTitleMessage}. Bedrock clients have no hover
     * component, so the line is plain (color-coded) text.
     */
    private void handleItemDisplayMessage(ItemDisplayPacket packet) {
        String channelId = packet.getChannelId();
        String senderName = packet.getSenderName();
        String itemJson = packet.getItemJson();
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            for (cn.nukkit.Player player : plugin.getServer().getOnlinePlayers().values()) {
                // Skip senders the viewer has ignored (/nc ignore)
                com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
                if (ignoreService != null && ignoreService.isIgnored(player.getUniqueId(), senderName)) {
                    continue;
                }
                PlayerChannelState state = plugin.getChatInterceptor().getState(player.getUniqueId());
                if (state != null && channelId != null && channelId.equals(state.getActiveChannel())) {
                    player.sendMessage(plugin.getMessageFormatter().colorize(
                            ItemDisplayMessages.formatLine(player.getUniqueId(), senderName, itemJson)));
                }
            }
        });
    }

    /**
     * Forwards an incoming chat message to the chat interceptor on the main thread.
     */
    private void handleChatMessage(ChatMessagePacket packet) {
        String senderName = packet.getSenderName();
        String channelId = packet.getChannelId();
        String content = packet.getContent();
        plugin.getServer().getScheduler().scheduleTask(plugin, () ->
                plugin.getChatInterceptor().displayIncomingMessage(
                        senderName,
                        channelId,
                        content,
                        packet.getPlaceholders()
                ));
    }

    /**
     * Broadcasts an incoming title to all online players on the main thread.
     */
    private void handleTitleMessage(TitlePacket packet) {
        String title = packet.getTitle();
        String subtitle = packet.getSubtitle();
        int fadeIn = packet.getFadeIn();
        int stay = packet.getStay();
        int fadeOut = packet.getFadeOut();
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            String coloredTitle = plugin.getMessageFormatter().colorize(title);
            String coloredSubtitle = plugin.getMessageFormatter().colorize(subtitle);
            plugin.getServer().getOnlinePlayers().values().forEach(player ->
                    player.sendTitle(coloredTitle, coloredSubtitle, fadeIn, stay, fadeOut));
        });
    }

    /**
     * Notifies a mentioned player with a title (and action-bar fallback) on the
     * PNX main thread (UX-DESIGN §4.1, Requirements 11.2).
     *
     * <p>Sound is intentionally omitted here: the PNX/Nukkit compile API surface
     * used by this module has no stable {@code playSound} entry point and there is
     * no existing in-repo precedent. The title + action-bar pair provides a clear,
     * non-spammy notification. A future revision can add a
     * {@code LevelSoundEventPacket} once the exact PNX version's API is pinned.
     */
    private void handleMentionMessage(MentionPacket packet) {
        java.util.UUID mentionedId = packet.getMentionedId();
        java.util.UUID mentionerId = packet.getMentionerId();
        if (mentionedId == null || mentionerId == null) {
            return;
        }
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            cn.nukkit.Player player = plugin.getServer().getOnlinePlayers().get(mentionedId);
            if (player == null
                    || !plugin.getChatInterceptor().shouldNotifyMention(mentionedId, mentionerId)) {
                return;
            }
            // Ignored mentioner: no title, no action bar (/nc ignore)
            com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
            if (ignoreService != null && ignoreService.isIgnored(mentionedId, packet.getMentionerName())) {
                return;
            }
            String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
            String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
            String title = plugin.getMessageFormatter().colorize("&e" + mentioner);
            String subtitle = plugin.getMessageFormatter().colorize(
                    I18n.tr(mentionedId, "chat.mention.subtitle", channelId));
            player.sendTitle(title, subtitle,
                    MentionNotifier.DEFAULT_FADE_IN,
                    MentionNotifier.DEFAULT_STAY,
                    MentionNotifier.DEFAULT_FADE_OUT);
            // Action-bar reinforcement (works even if title display is overridden).
            player.sendActionBar(plugin.getMessageFormatter().colorize(
                    "&e" + mentioner + " " + I18n.tr(mentionedId, "chat.mention.subtitle", channelId)));
        });
    }

    /**
     * Correlates an asynchronous channel-action response by delegating the shared
     * "consume pending → route success/failure" skeleton to the shared
     * {@link ChannelResponseDispatcher} (DUP-3). The platform adapter owns the
     * PNX main-thread hops, rendering and the §7 action-bar flash.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        // Private-message rejections are unsolicited (no pending context in the
        // shared tracker); the dispatcher would drop them, so route them to the
        // PrivateMessageService for player-locale rendering instead.
        if (com.nova.chat.client.privatemsg.PrivateMessageService.isPrivateMessageError(packet)) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () ->
                    plugin.getPrivateMessageService()
                            .renderError(packet, id -> plugin.getServer().getPlayer(id).orElse(null) != null)
                            .ifPresent(delivery -> {
                                cn.nukkit.Player player =
                                        plugin.getServer().getPlayer(delivery.getPlayerId()).orElse(null);
                                if (player != null) {
                                    player.sendMessage(plugin.getMessageFormatter().colorize(delivery.getLine()));
                                }
                            }));
            return;
        }
        dispatcher.handle(packet);
    }

    /**
     * PNX-specific {@link ChannelResponseDispatcher.ChannelResponseAdapter}.
     * Player lookup / message / title sending hop to the main thread; the
     * KICK/MUTE notice uses a title plus an action-bar reinforcement. Uses the
     * PNX {@code ChatInterceptor}'s lazily-created state.
     */
    private final class PNXChannelResponseAdapter implements ChannelResponseDispatcher.ChannelResponseAdapter {

        @Override
        public void setActiveChannel(java.util.UUID playerId, String channelId) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                cn.nukkit.Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                plugin.getChatInterceptor().getOrCreateState(player).setActiveChannel(channelId);
            });
        }

        @Override
        public void rollbackJoin(java.util.UUID playerId, String attemptedChannel, String previousChannel) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                cn.nukkit.Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                PlayerChannelState pnxState = plugin.getChatInterceptor().getOrCreateState(player);
                // Drops the optimistic membership too: a refused JOIN must not
                // leave the channel marked joined for /nc list.
                pnxState.rollbackJoin(attemptedChannel, previousChannel);
            });
        }

        @Override
        public void sendJoinSuccess(java.util.UUID playerId, String channelId) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                cn.nukkit.Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                player.sendMessage(plugin.getMessageFormatter().formatSuccess(
                        PlayerMessages.joined(playerId, channelId)));
            });
        }

        @Override
        public void sendLeaveSuccess(java.util.UUID playerId, String channelId) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                cn.nukkit.Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                player.sendMessage(plugin.getMessageFormatter().formatSuccess(
                        PlayerMessages.left(playerId, channelId, plugin.getNovaChatConfig().getDefaultChannel())));
            });
        }

        @Override
        public void sendJoinChannelStatusBar(java.util.UUID playerId, String channelId) {
            if (channelId == null || channelId.isEmpty()) {
                return;
            }
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                cn.nukkit.Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                sendChannelStatusBar(player, channelId);
            });
        }

        @Override
        public void sendLeaveChannelStatusBar(java.util.UUID playerId) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                cn.nukkit.Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                PlayerChannelState pnxState = plugin.getChatInterceptor().getOrCreateState(player);
                sendChannelStatusBar(player, pnxState.getActiveChannel());
            });
        }

        @Override
        public void sendErrorMessage(java.util.UUID playerId, String text) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                cn.nukkit.Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                NovaChatConfig cfg = plugin.getNovaChatConfig();
                String full = cfg.getFormatPrefix() + cfg.getFormatError().replace("{message}", text);
                player.sendMessage(cn.nukkit.utils.TextFormat.colorize('&', full));
            });
        }

        @Override
        public void sendWhoResult(java.util.UUID playerId, String channelId, String displayName,
                                  String membersCsv, String memberCount) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                cn.nukkit.Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                String text = WhoCommandService.formatMemberList(
                        playerId, channelId, displayName, membersCsv, memberCount);
                for (String line : text.split("\n")) {
                    if (!line.isEmpty()) {
                        player.sendMessage(plugin.getMessageFormatter().colorize(line));
                    }
                }
            });
        }

        @Override
        public boolean supportsTargetNotice(ChannelAction action) {
            return action == ChannelAction.KICK || action == ChannelAction.MUTE
                    || action == ChannelAction.BAN || action == ChannelAction.UNBAN;
        }

        @Override
        public void notifyKickMuteTarget(ChannelResponseDispatcher.KickMuteNotice notice) {
            final String operator = notice.getOperator();
            final String durationText = notice.getDurationText();
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                cn.nukkit.Player target = plugin.getServer().getOnlinePlayers().get(notice.getTargetId());
                if (target == null) {
                    return; // not on this server
                }
                java.util.UUID targetId = target.getUniqueId();
                String channelId = notice.getChannelId();
                if (notice.getAction() == ChannelAction.KICK) {
                    String title = plugin.getMessageFormatter().colorize(
                            I18n.tr(targetId, "chat.notice.kick_title"));
                    String subtitle = plugin.getMessageFormatter().colorize(
                            I18n.tr(targetId, "chat.notice.kick_subtitle", operator, channelId));
                    target.sendTitle(title, subtitle,
                            MentionNotifier.DEFAULT_FADE_IN, MentionNotifier.DEFAULT_STAY, MentionNotifier.DEFAULT_FADE_OUT);
                    target.sendActionBar(plugin.getMessageFormatter().colorize(
                            I18n.tr(targetId, "chat.notice.kick_actionbar", operator, channelId)));
                    return;
                }
                if (notice.getAction() == ChannelAction.BAN) {
                    showTargetNotice(target, targetId, "chat.notice.ban_title",
                            I18n.tr(targetId, "chat.notice.ban_subtitle",
                                    operator, channelId, durationText),
                            I18n.tr(targetId, "chat.notice.ban_actionbar",
                                    operator, channelId, durationText));
                    return;
                }
                if (notice.getAction() == ChannelAction.UNBAN) {
                    showTargetNotice(target, targetId, "chat.notice.unban_title",
                            I18n.tr(targetId, "chat.notice.unban_subtitle", operator, channelId),
                            I18n.tr(targetId, "chat.notice.unban_actionbar", operator, channelId));
                    return;
                }
                // MUTE
                String title = plugin.getMessageFormatter().colorize(
                        I18n.tr(targetId, "chat.notice.mute_title"));
                String subtitle = plugin.getMessageFormatter().colorize(
                        I18n.tr(targetId, "chat.notice.mute_subtitle", channelId, durationText));
                target.sendTitle(title, subtitle,
                        MentionNotifier.DEFAULT_FADE_IN, MentionNotifier.DEFAULT_STAY, MentionNotifier.DEFAULT_FADE_OUT);
                target.sendActionBar(plugin.getMessageFormatter().colorize(
                        I18n.tr(targetId, "chat.notice.mute_actionbar", durationText, channelId)));
            });
        }

        /**
         * Renders the BAN/UNBAN shape: a title plus the action bar repeating the
         * same event. Placeholder order copies the Bukkit/Folia arms.
         *
         * @param target        the local player
         * @param targetId      their UUID (locale lookup)
         * @param titleKey      i18n key for the title line
         * @param subtitle      already-resolved subtitle text (&amp;-coded)
         * @param actionBarText already-resolved action-bar text (&amp;-coded)
         */
        private void showTargetNotice(cn.nukkit.Player target, java.util.UUID targetId,
                                      String titleKey, String subtitle, String actionBarText) {
            target.sendTitle(plugin.getMessageFormatter().colorize(I18n.tr(targetId, titleKey)),
                    plugin.getMessageFormatter().colorize(subtitle),
                    MentionNotifier.DEFAULT_FADE_IN, MentionNotifier.DEFAULT_STAY,
                    MentionNotifier.DEFAULT_FADE_OUT);
            target.sendActionBar(plugin.getMessageFormatter().colorize(actionBarText));
        }
    }

    /**
     * Flashes a one-shot action bar with the player's current channel and chat
     * mode after a successful join/leave (UX-DESIGN §7). Vanilla action-bar
     * fade handles the ~3s dismissal; no polling.
     *
     * @param player the recipient
     * @param channelId the channel to display; if null/blank, nothing is sent
     */
    private void sendChannelStatusBar(cn.nukkit.Player player, String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return;
        }
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        ChatMode mode = state.getChatMode();
        String text = PlayerMessages.currentChannelBar(player.getUniqueId(), channelId, mode);
        player.sendActionBar(plugin.getMessageFormatter().colorize(text));
    }

    /**
     * PNX scheduler adapter: seconds-based delays via the Nukkit/PNX scheduler.
     *
     * <p>{@code runLater} converts seconds to ticks ({@code delay * 20}). Async hops
     * use {@code scheduleAsyncTask}; reconnect itself re-enters {@code connect} from
     * the core, which is safe off-thread.
     */
    static final class PNXSchedulerBridge implements SchedulerBridge {
        private final NovaChatPNX plugin;

        PNXSchedulerBridge(NovaChatPNX plugin) {
            this.plugin = plugin;
        }

        @Override
        public void runAsync(Runnable task) {
            plugin.getServer().getScheduler().scheduleAsyncTask(plugin, new cn.nukkit.scheduler.AsyncTask() {
                @Override
                public void onRun() {
                    task.run();
                }
            });
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            int ticks = (int) Math.max(0, delaySeconds) * 20;
            plugin.getServer().getScheduler().scheduleDelayedTask(plugin, task::run, ticks);
        }
    }

    /**
     * Maps the PNX SLF-style logger + plugin debug gate onto {@link ClientLogger}.
     */
    static final class PNXClientLogger implements ClientLogger {
        private final NovaChatPNX plugin;

        PNXClientLogger(NovaChatPNX plugin) {
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
                plugin.getLogger().error(message, cause);
            }
        }
    }
}
