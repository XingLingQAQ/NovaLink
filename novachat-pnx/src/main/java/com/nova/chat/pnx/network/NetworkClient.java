package com.nova.chat.pnx.network;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.format.DurationFormatter;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.config.NovaChatConfig;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * PNX NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Preserves the historical public API used by the rest of the PNX plugin. Netty
 * bootstrap, handshake, keepalive, handler map, and reconnect policy live in
 * client-core; this class only supplies PNX scheduler/logger adapters and registers
 * PNX-specific chat/title inbound handlers that historically lived in the
 * hard-coded {@code ClientChannelHandler}.
 *
 * <p>Architecture B: plugin-only. No backend changes.
 */
public class NetworkClient {

    private final NovaChatPNX plugin;
    private final CoreNetworkClient core;

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
        this.core = new CoreNetworkClient(
                connectionConfig,
                PlatformType.POWERNUKKITX,
                scheduler,
                logger,
                "config.yml",
                java.util.function.Function.identity()
        );

        // Preserve PNX chat/title handling that previously lived hard-coded in
        // ClientChannelHandler. CoreNetworkClient owns HandshakeResponse/KeepAlive.
        registerPnxHandlers();
    }

    /**
     * Connects to the NovaLink backend.
     *
     * @param host the backend host
     * @param port the backend port
     * @return a future that completes with true if connection and authentication succeed
     */
    public CompletableFuture<Boolean> connect(String host, int port) {
        return core.connect(host, port);
    }

    /**
     * Disconnects from the backend.
     */
    public void disconnect() {
        core.disconnect();
    }

    /**
     * Sends a packet to the backend. Channel-action correlation tracking is
     * handled inside {@link CoreNetworkClient#sendPacket} (single-entry contract).
     *
     * @param packet the packet to send
     */
    public void sendPacket(Packet packet) {
        core.sendPacket(packet);
    }

    /**
     * @return the tracker mapping in-flight channel-action request ids to players,
     *         used by the platform's {@code ChannelActionResponsePacket} handler
     */
    public ChannelResponseTracker getChannelResponseTracker() {
        return core.getChannelResponseTracker();
    }

    /**
     * Registers a packet handler.
     *
     * @param packetClass the packet class to handle
     * @param handler the handler function
     * @param <T> the packet type
     */
    public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
        core.registerHandler(packetClass, handler);
    }

    /**
     * Checks if the client is connected.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return core.isConnected();
    }

    /**
     * Checks if the client is authenticated.
     *
     * @return true if authenticated
     */
    public boolean isAuthenticated() {
        return core.isAuthenticated();
    }

    /**
     * Gets the packet registry.
     *
     * @return the packet registry
     */
    public PacketRegistry getPacketRegistry() {
        return core.getPacketRegistry();
    }

    /**
     * Package-visible for tests / advanced adapters.
     */
    CoreNetworkClient core() {
        return core;
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
        if (mentionedId == null) {
            return;
        }
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            cn.nukkit.Player player = plugin.getServer().getOnlinePlayers().get(mentionedId);
            if (player == null) {
                return;
            }
            String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
            String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
            String title = plugin.getMessageFormatter().colorize("&e" + mentioner);
            String subtitle = plugin.getMessageFormatter().colorize(
                    "&7在频道 &b" + channelId + " &7提到了你");
            player.sendTitle(title, subtitle,
                    MentionNotifier.DEFAULT_FADE_IN,
                    MentionNotifier.DEFAULT_STAY,
                    MentionNotifier.DEFAULT_FADE_OUT);
            // Action-bar reinforcement (works even if title display is overridden).
            player.sendActionBar(plugin.getMessageFormatter().colorize(
                    "&e" + mentioner + " &7在频道 &b" + channelId + " &7提到了你"));
        });
    }

    /**
     * Correlates an asynchronous channel-action response back to its originating
     * player via the shared {@link ChannelResponseTracker} and, on failure, renders
     * an actionable error via the shared {@link ErrorCode} system. NC-503 is
     * suppressed (already reported at send time). Runs on the PNX main thread for
     * safe player lookup / messaging.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        ChannelResponseTracker.PendingChannelAction pending =
                core.getChannelResponseTracker().consume(packet.getRequestId());

        // UX-DESIGN §5: KICK/MUTE target-side notification. BUG-H1: the operator
        // name and mute duration are never echoed on the response, so prefer the
        // values captured at send time on the pending context. For backend pushes
        // with no local pending (operator on another server), pending is null and
        // we fall back to the response extras (which the backend does not write
        // either, so the "管理员"/"一段时间" fallbacks apply).
        if (packet.isSuccess() && (packet.getAction() == ChannelAction.KICK
                || packet.getAction() == ChannelAction.MUTE)) {
            notifyKickMuteTarget(packet, pending);
        }

        if (pending == null || pending.getPlayerId() == null) {
            return;
        }

        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            cn.nukkit.Player player = plugin.getServer().getPlayer(pending.getPlayerId()).orElse(null);
            if (player == null) {
                return;
            }
            if (packet.isSuccess()) {
                // §7: the immediate join receipt is the optimistic "正在加入频道 X…";
                // confirm here once the backend accepts, then flash a short action
                // bar so the player sees the active channel + current mode.
                if (packet.getAction() == ChannelAction.JOIN) {
                    String confirmedChannel = (packet.getChannelId() != null && !packet.getChannelId().isEmpty())
                            ? packet.getChannelId()
                            : pending.getChannelId();
                    PlayerChannelState pnxState = plugin.getChatInterceptor().getOrCreateState(player).getChannelState();
                    if (packet.getChannelId() != null && !packet.getChannelId().isEmpty()) {
                        pnxState.setActiveChannel(packet.getChannelId());
                    }
                    if (confirmedChannel != null && !confirmedChannel.isEmpty()) {
                        player.sendMessage(plugin.getMessageFormatter().formatSuccess("已加入频道 " + confirmedChannel));
                    }
                    sendChannelStatusBar(player, confirmedChannel);
                } else if (packet.getAction() == ChannelAction.LEAVE) {
                    PlayerChannelState pnxState = plugin.getChatInterceptor().getOrCreateState(player).getChannelState();
                    sendChannelStatusBar(player, pnxState.getActiveChannel());
                }
                return;
            }
            String code = packet.getErrorCode();
            // BUG-H2: backend rejected the JOIN — roll back the optimistic
            // active-channel switch ChannelCommandService.join made at send time.
            if (packet.getAction() == ChannelAction.JOIN) {
                String previousChannel = pending.getPreviousChannel();
                if (previousChannel != null && !previousChannel.isEmpty()) {
                    PlayerChannelState pnxState = plugin.getChatInterceptor()
                            .getOrCreateState(player).getChannelState();
                    String current = pnxState.getActiveChannel();
                    if (current != null && current.equals(pending.getChannelId())) {
                        pnxState.setActiveChannel(previousChannel);
                    }
                }
            }
            if (code == null || code.isEmpty() || ErrorCode.SERVICE_UNAVAILABLE.getCode().equals(code)) {
                return;
            }
            NovaChatConfig cfg = plugin.getNovaChatConfig();
            String prefix = cfg.getFormatPrefix();
            String format = cfg.getFormatError();
            String text = prefix + format.replace("{message}", ErrorMessageFormatter.format(code));
            player.sendMessage(cn.nukkit.utils.TextFormat.colorize('&', text));
        });
    }

    /**
     * Sends a personalized kick/mute notice to the affected player
     * (UX-DESIGN §5). Runs on the PNX main thread for safe player lookup.
     * Falls back silently when the target is offline or the response lacks the
     * {@code targetId} extra (TODO logged).
     *
     * <p>BUG-H1: the operator name and mute duration are read from the pending
     * context captured at send time (the backend never echoes them); the
     * response extras are only consulted as a fallback for backend pushes with
     * no local pending (operator on another server), in which case the
     * "管理员"/"一段时间" fallbacks intentionally apply. Resolved before the
     * scheduler hop so the lambda captures plain strings.
     */
    private void notifyKickMuteTarget(ChannelActionResponsePacket packet,
                                      ChannelResponseTracker.PendingChannelAction pending) {
        String targetIdRaw = packet.getExtra("targetId");
        if (targetIdRaw == null || targetIdRaw.isEmpty()) {
            plugin.debug("KICK/MUTE response without targetId extra — cannot notify target: " + packet);
            return;
        }
        java.util.UUID targetId;
        try {
            targetId = java.util.UUID.fromString(targetIdRaw);
        } catch (IllegalArgumentException e) {
            plugin.debug("KICK/MUTE response has invalid targetId: " + targetIdRaw);
            return;
        }
        String operator = pending != null ? pending.getOperatorName() : null;
        if (operator == null || operator.isEmpty()) {
            operator = packet.getExtra("operatorName");
        }
        if (operator == null || operator.isEmpty()) {
            operator = "管理员";
        }
        String durationSeconds = pending != null ? pending.getDurationSeconds() : null;
        if (durationSeconds == null || durationSeconds.isEmpty()) {
            durationSeconds = packet.getExtra("duration");
        }
        final String operatorResolved = operator;
        final String durationText = DurationFormatter.formatSeconds(durationSeconds);

        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            cn.nukkit.Player target = plugin.getServer().getOnlinePlayers().get(targetId);
            if (target == null) {
                return; // not on this server
            }
            String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
            if (packet.getAction() == ChannelAction.KICK) {
                String title = plugin.getMessageFormatter().colorize("&c你已被踢出频道");
                String subtitle = plugin.getMessageFormatter().colorize(
                        "&7被 &e" + operatorResolved + " &7踢出频道 &b" + channelId);
                target.sendTitle(title, subtitle,
                        MentionNotifier.DEFAULT_FADE_IN, MentionNotifier.DEFAULT_STAY, MentionNotifier.DEFAULT_FADE_OUT);
                target.sendActionBar(plugin.getMessageFormatter().colorize(
                        "&c你已被 " + operatorResolved + " 踢出频道 " + channelId));
                return;
            }
            // MUTE
            String title = plugin.getMessageFormatter().colorize("&c你已被禁言");
            String subtitle = plugin.getMessageFormatter().colorize(
                    "&7在频道 &b" + channelId + " &7持续 &e" + durationText);
            target.sendTitle(title, subtitle,
                    MentionNotifier.DEFAULT_FADE_IN, MentionNotifier.DEFAULT_STAY, MentionNotifier.DEFAULT_FADE_OUT);
            target.sendActionBar(plugin.getMessageFormatter().colorize(
                    "&c你已被禁言 " + durationText + "（频道 " + channelId + "）"));
        });
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
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player).getChannelState();
        ChatMode mode = state.getChatMode();
        String modeName = (mode == ChatMode.REPLACE) ? "频道模式" : "混合模式";
        String text = "&7当前频道：&b" + channelId + " &7（" + modeName + "）";
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
