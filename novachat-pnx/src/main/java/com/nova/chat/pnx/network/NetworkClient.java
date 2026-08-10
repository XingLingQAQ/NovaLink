package com.nova.chat.pnx.network;

import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.config.NovaChatConfig;

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
                plugin.getChatInterceptor().getOrCreateState(player).getChannelState().setActiveChannel(channelId);
            });
        }

        @Override
        public void rollbackJoin(java.util.UUID playerId, String attemptedChannel, String previousChannel) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                cn.nukkit.Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                PlayerChannelState pnxState = plugin.getChatInterceptor().getOrCreateState(player).getChannelState();
                String current = pnxState.getActiveChannel();
                if (current != null && current.equals(attemptedChannel)) {
                    pnxState.setActiveChannel(previousChannel);
                }
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
                PlayerChannelState pnxState = plugin.getChatInterceptor().getOrCreateState(player).getChannelState();
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
        if (mode == null) {
            mode = ChatMode.HYBRID;
        }
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
