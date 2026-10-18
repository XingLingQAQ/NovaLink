package com.nova.chat.bungee.chat;

import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.itemdisplay.ItemDisplayMessages;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.common.chat.MentionNotifier;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for player chat events on BungeeCord proxy.
 * 
 * In BungeeCord, chat messages can be intercepted at the proxy level.
 * When we intercept and modify messages, we cancel the original
 * and handle routing through the NovaLink backend.
 * 
 * Requirements: 23.3 - BungeeCord proxy support
 */
public class ChatListener implements Listener {
    
    private final NovaChatBungee plugin;

    /**
     * Latest immutable config snapshot; volatile — proxy event thread reads,
     * command thread writes via {@link #setConfig} / {@link #reload}.
     */
    private volatile NovaChatConfig config;

    private final MessageFormatter messageFormatter;
    private final MentionNotifier mentionNotifier = new MentionNotifier();
    
    /** Player chat states indexed by UUID (shared store). */
    private final com.nova.chat.client.state.PlayerStateStore playerStates =
            new com.nova.chat.client.state.PlayerStateStore();

    /** Shared response dispatcher (DUP-3); created in {@link #rebindIncomingHandlers()}. */
    private ChannelResponseDispatcher dispatcher;

    /**
     * UUIDs of players already shown the first-join welcome line this proxy
     * session (UX-DESIGN §8.1). BungeeCord has no hasPlayedBefore, so the
     * welcome is gated by this memory set and cleared on disconnect.
     */
    private final java.util.Set<UUID> welcomedPlayers = ConcurrentHashMap.newKeySet();

    /**
     * Players whose first server connection this proxy session has already been
     * processed for by {@link com.nova.chat.bungee.listener.ServerSwitchHandler}
     * (default-channel JOIN). Cleared on disconnect. This is the authoritative
     * first-connection signal; {@code PlayerChannelState#getCurrentServer()} is
     * not, because both listeners write it and Bungee's {@code EventBus} does not
     * guarantee their relative order.
     */
    private final java.util.Set<UUID> firstConnections = ConcurrentHashMap.newKeySet();

    /**
     * Global chat mode from configuration.
     *
     * <p>Marked {@code volatile} for cross-thread visibility: it is read in
     * {@link #onPlayerChat} (proxy event thread) and written from
     * {@link #setGlobalMode} / {@link #reload} (command thread), matching the
     * Folia adapter's {@code volatile} declaration.
     */
    private volatile ChatMode globalMode;
    
    /**
     * Creates a new ChatListener.
     *
     * @param plugin the plugin instance
     */
    public ChatListener(NovaChatBungee plugin) {
        this.plugin = plugin;
        this.config = plugin.getPluginConfig();
        this.messageFormatter = new MessageFormatter(plugin);
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        
        // Register handler for incoming chat messages from backend
        rebindIncomingHandlers();
    }

    /**
     * Registers the handler for incoming chat messages from the backend.
     *
     * <p>Public so that {@code reload} can re-bind to the freshly created
     * NetworkClient after a network reconnect without reconstructing this
     * listener (H6 fix).
     */
    public void rebindIncomingHandlers() {
        if (plugin.getNetworkClient() != null) {
            this.dispatcher = new ChannelResponseDispatcher(
                    plugin.getNetworkClient().getChannelResponseTracker(),
                    new BungeeChannelResponseAdapter());
            plugin.getNetworkClient().registerHandler(ChatMessagePacket.class, this::handleIncomingMessage);
            plugin.getNetworkClient().registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
            plugin.getNetworkClient().registerHandler(MentionPacket.class, this::handleMention);
            plugin.getNetworkClient().registerHandler(TitlePacket.class, this::handleTitle);
            plugin.getNetworkClient().registerHandler(ItemDisplayPacket.class, this::handleItemDisplay);
            plugin.getNetworkClient().registerHandler(
                    com.nova.chat.common.protocol.packets.PrivateMessagePacket.class,
                    this::handlePrivateMessage);
        }
    }

    /**
     * Handles a completed (S→C) private message: the shared
     * {@link com.nova.chat.client.privatemsg.PrivateMessageService} resolves
     * which local players render which role (sender echo vs received line,
     * receiver-side ignore filter, reply tracking). Like {@link #handleTitle},
     * no scheduler hop is needed: BungeeCord's player send path is thread-safe
     * from the Netty callback.
     */
    private void handlePrivateMessage(com.nova.chat.common.protocol.packets.PrivateMessagePacket packet) {
        var deliveries = plugin.getPrivateMessageService().handleIncoming(
                packet,
                id -> plugin.getProxy().getPlayer(id) != null,
                plugin.getIgnoreListService());
        for (com.nova.chat.client.privatemsg.PrivateMessageService.Delivery delivery : deliveries) {
            ProxiedPlayer player = plugin.getProxy().getPlayer(delivery.getPlayerId());
            if (player != null) {
                player.sendMessage(messageFormatter.parseColors(delivery.getLine()));
            }
        }
    }

    /**
     * Handles an inbound item display packet ({@code [item]}/{@code [i]} play,
     * packet 0x10) by rendering one hoverable chat line to every player whose
     * active channel matches the packet channel.
     *
     * <p>Receive-side semantics are "receive = render", matching the Bedrock
     * clients; the backend currently registers no route for this packet. Like
     * {@link #handleTitle}, no scheduler hop is needed: BungeeCord's player
     * send path is thread-safe from the Netty callback. The hover detail uses
     * the md_5 chat API's {@code SHOW_TEXT} content. The line is formatted per
     * viewer because the copy is locale-dependent.
     *
     * <p>Send side is intentionally absent on the proxy: BungeeCord has no
     * access to the player's held item, so {@code [item]} tokens typed here
     * pass through as plain text.
     */
    private void handleItemDisplay(ItemDisplayPacket packet) {
        String channelId = packet.getChannelId();
        for (ProxiedPlayer player : plugin.getProxy().getPlayers()) {
            PlayerChannelState state = getState(player.getUniqueId());
            if (state == null || channelId == null || !channelId.equals(state.getActiveChannel())) {
                continue;
            }
            UUID viewerId = player.getUniqueId();
            // Skip senders the viewer has ignored (/nc ignore)
            com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
            if (ignoreService != null && ignoreService.isIgnored(viewerId, packet.getSenderName())) {
                continue;
            }
            BaseComponent[] line = messageFormatter.parseColors(
                    ItemDisplayMessages.formatLine(viewerId, packet.getSenderName(), packet.getItemJson()));
            BaseComponent[] hover = messageFormatter.parseColors(
                    ItemDisplayMessages.formatHoverDetail(viewerId, packet.getItemJson()));

            TextComponent component = new TextComponent(line);
            component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover)));
            player.sendMessage(component);
        }
    }

    /**
     * Handles title packets by displaying them to players whose active channel
     * matches the packet channel (Requirements 15.1, 15.5).
     *
     * <p>Uses BungeeCord's native proxy title API
     * ({@code ProxyServer#createTitle()}), which writes the title packets
     * straight to the downstream client. Like {@link #handleIncomingMessage},
     * no scheduler hop is needed: BungeeCord's player send path is thread-safe
     * from the Netty callback.
     */
    private void handleTitle(TitlePacket packet) {
        String channelId = packet.getChannelId();
        BaseComponent[] title = messageFormatter.parseColors(
                packet.getTitle() != null ? packet.getTitle() : "");
        BaseComponent[] subtitle = messageFormatter.parseColors(
                packet.getSubtitle() != null ? packet.getSubtitle() : "");

        Title bungeeTitle = plugin.getProxy().createTitle()
                .title(title)
                .subTitle(subtitle)
                .fadeIn(packet.getFadeIn())
                .stay(packet.getStay())
                .fadeOut(packet.getFadeOut());

        for (ProxiedPlayer player : plugin.getProxy().getPlayers()) {
            PlayerChannelState state = getState(player.getUniqueId());
            if (state != null && channelId != null && channelId.equals(state.getActiveChannel())) {
                bungeeTitle.send(player);
            }
        }
    }

    /** Legacy color prefix applied to @name mentions when rendering chat (UX-DESIGN §4.2). */
    static final String MENTION_HIGHLIGHT_COLOR = MentionNotifier.DEFAULT_HIGHLIGHT_COLOR;
    
    /**
     * Handles channel action responses from the backend by delegating the
     * shared "consume pending → route success/failure" skeleton to the shared
     * {@link ChannelResponseDispatcher} (DUP-3). The platform adapter owns player
     * lookup, rendering and the {@code ChatMessageType.ACTION_BAR} status line.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        plugin.debug("Received channel action response: " + packet);
        // Private-message rejections are unsolicited (no pending context in the
        // shared tracker); the dispatcher would drop them, so route them to the
        // PrivateMessageService for player-locale rendering instead.
        if (com.nova.chat.client.privatemsg.PrivateMessageService.isPrivateMessageError(packet)) {
            plugin.getPrivateMessageService()
                    .renderError(packet, id -> plugin.getProxy().getPlayer(id) != null)
                    .ifPresent(delivery -> {
                        ProxiedPlayer player = plugin.getProxy().getPlayer(delivery.getPlayerId());
                        if (player != null) {
                            player.sendMessage(messageFormatter.parseColors(delivery.getLine()));
                        }
                    });
            return;
        }
        dispatcher.handle(packet);
    }

    /**
     * Bungee-specific {@link ChannelResponseDispatcher.ChannelResponseAdapter}.
     * The §7 status bar uses {@code sendMessage(ChatMessageType.ACTION_BAR, ...)};
     * the KICK/MUTE notice is a title plus an action-bar reinforcement.
     *
     * <p>GAP-3 fix (mirrors the Velocity {@code VelocityChannelResponseAdapter}):
     * every callback now hops off the Netty event loop via
     * {@code plugin.getProxy().getScheduler().runAsync(plugin, ...)} before
     * touching any {@link ProxiedPlayer} API, the shared {@link #playerStates}
     * store, or the {@link #welcomedPlayers} set. The shared dispatcher fires
     * these from the Netty event loop; without the hop the mutations race with
     * {@link #onPlayerDisconnect} and the {@code PlayerChannelState} reads done
     * on other threads, and the {@code playerStates}/{@code welcomedPlayers}
     * collections (both {@link ConcurrentHashMap}-backed but not atomic across
     * read-modify-write sequences) could see lost updates. This mirrors how
     * {@code BukkitChannelResponseAdapter} hops to the Bukkit main thread via
     * {@code getScheduler().runTask(plugin, ...)} on every callback.
     *
     * <p>BungeeCord has no single "main thread" like Bukkit/Velocity; the
     * plugin scheduler's {@code runAsync} runs on BungeeCord's cached async
     * thread pool, which is the idiomatic BungeeCord hop off the I/O event
     * loop (see also {@code BungeeSchedulerBridge#runAsync}). That decouples
     * the callbacks from the Netty thread that received the packet, which is
     * the actual concurrency hazard the GAP-3 fix targets.
     */
    private final class BungeeChannelResponseAdapter implements ChannelResponseDispatcher.ChannelResponseAdapter {

        @Override
        public void setActiveChannel(UUID playerId, String channelId) {
            plugin.getProxy().getScheduler().runAsync(plugin, () -> {
                PlayerChannelState state = getState(playerId);
                if (state != null) {
                    state.setActiveChannel(channelId);
                }
            });
        }

        @Override
        public void rollbackJoin(UUID playerId, String attemptedChannel, String previousChannel) {
            plugin.getProxy().getScheduler().runAsync(plugin, () -> {
                PlayerChannelState state = getState(playerId);
                if (state == null) {
                    return;
                }
                String current = state.getActiveChannel();
                if (current != null && current.equals(attemptedChannel)) {
                    state.setActiveChannel(previousChannel);
                }
            });
        }

        @Override
        public void sendJoinSuccess(UUID playerId, String channelId) {
            plugin.getProxy().getScheduler().runAsync(plugin, () -> {
                ProxiedPlayer player = plugin.getProxy().getPlayer(playerId);
                if (player == null) {
                    return;
                }
                player.sendMessage(messageFormatter.formatSuccess(PlayerMessages.joined(playerId, channelId)));
            });
        }

        @Override
        public void sendLeaveSuccess(UUID playerId, String channelId) {
            plugin.getProxy().getScheduler().runAsync(plugin, () -> {
                ProxiedPlayer player = plugin.getProxy().getPlayer(playerId);
                if (player == null) {
                    return;
                }
                player.sendMessage(messageFormatter.formatSuccess(
                        PlayerMessages.left(playerId, channelId, config.getDefaultChannel())));
            });
        }

        @Override
        public void sendJoinChannelStatusBar(UUID playerId, String channelId) {
            // §7 status ("当前频道：X（模式）"): one-shot action bar via
            // sendMessage(ChatMessageType.ACTION_BAR, ...), which BungeeCord writes
            // straight to the downstream client. No polling; vanilla fades it.
            if (channelId == null || channelId.isEmpty()) {
                return;
            }
            plugin.getProxy().getScheduler().runAsync(plugin, () -> {
                ProxiedPlayer player = plugin.getProxy().getPlayer(playerId);
                if (player == null) {
                    return;
                }
                PlayerChannelState state = getState(playerId);
                ChatMode mode = state != null ? state.getChatMode() : ChatMode.HYBRID;
                player.sendMessage(ChatMessageType.ACTION_BAR, messageFormatter.parseColors(
                        PlayerMessages.currentChannelBar(playerId, channelId, mode)));
            });
        }

        @Override
        public void sendLeaveChannelStatusBar(UUID playerId) {
            // After a successful leave the active channel falls back locally
            // (ChannelCommandService.leave → PlayerChannelState#leaveChannel), so
            // resolve the current channel here and flash the same bar. Nothing to
            // name when the player joined nothing else — skip.
            plugin.getProxy().getScheduler().runAsync(plugin, () -> {
                ProxiedPlayer player = plugin.getProxy().getPlayer(playerId);
                if (player == null) {
                    return;
                }
                PlayerChannelState state = getState(playerId);
                String channelId = state != null ? state.getActiveChannel() : null;
                if (channelId == null || channelId.isEmpty()) {
                    return;
                }
                ChatMode mode = state.getChatMode();
                player.sendMessage(ChatMessageType.ACTION_BAR, messageFormatter.parseColors(
                        PlayerMessages.currentChannelBar(playerId, channelId, mode)));
            });
        }

        @Override
        public void sendErrorMessage(UUID playerId, String text) {
            plugin.getProxy().getScheduler().runAsync(plugin, () -> {
                ProxiedPlayer player = plugin.getProxy().getPlayer(playerId);
                if (player == null) {
                    return;
                }
                player.sendMessage(messageFormatter.formatError(text));
            });
        }

        @Override
        public void sendWhoResult(UUID playerId, String channelId, String displayName,
                                  String membersCsv, String memberCount) {
            plugin.getProxy().getScheduler().runAsync(plugin, () -> {
                ProxiedPlayer player = plugin.getProxy().getPlayer(playerId);
                if (player == null) {
                    return;
                }
                String text = WhoCommandService.formatMemberList(
                        playerId, channelId, displayName, membersCsv, memberCount);
                for (String line : text.split("\n")) {
                    if (!line.isEmpty()) {
                        player.sendMessage(messageFormatter.parseColors(line));
                    }
                }
            });
        }

        @Override
        public void notifyKickMuteTarget(ChannelResponseDispatcher.KickMuteNotice notice) {
            plugin.getProxy().getScheduler().runAsync(plugin, () -> {
                ProxiedPlayer target = plugin.getProxy().getPlayer(notice.getTargetId());
                if (target == null) {
                    return; // not on this proxy
                }
                UUID targetId = notice.getTargetId();
                String channelId = notice.getChannelId();
                String operator = notice.getOperator();
                if (notice.getAction() == ChannelAction.KICK) {
                    showNotice(target, targetId, "chat.notice.kick_title",
                            I18n.tr(targetId, "chat.notice.kick_subtitle", operator, channelId),
                            I18n.tr(targetId, "chat.notice.kick_actionbar", operator, channelId));
                    return;
                }
                String durationText = notice.getDurationText();
                showNotice(target, targetId, "chat.notice.mute_title",
                        I18n.tr(targetId, "chat.notice.mute_subtitle", channelId, durationText),
                        I18n.tr(targetId, "chat.notice.mute_actionbar", durationText, channelId));
            });
        }
    }

    /**
     * Renders the target-side KICK/MUTE notice: a proxy-native title plus the
     * action-bar line carrying the same text.
     *
     * @param target        the affected player
     * @param targetId      the affected player's UUID (locale lookup)
     * @param titleKey      i18n key for the title line
     * @param subtitle      already-resolved subtitle text
     * @param actionBarText already-resolved action-bar text
     */
    private void showNotice(ProxiedPlayer target, UUID targetId, String titleKey,
                            String subtitle, String actionBarText) {
        plugin.getProxy().createTitle()
                .title(messageFormatter.parseColors(I18n.tr(targetId, titleKey)))
                .subTitle(messageFormatter.parseColors(subtitle))
                .fadeIn(MentionNotifier.DEFAULT_FADE_IN)
                .stay(MentionNotifier.DEFAULT_STAY)
                .fadeOut(MentionNotifier.DEFAULT_FADE_OUT)
                .send(target);
        target.sendMessage(ChatMessageType.ACTION_BAR, messageFormatter.parseColors(actionBarText));
    }

    /**
     * Handles incoming chat messages from the backend.
     * Broadcasts to all players in the channel.
     *
     * @param packet the chat message packet
     */
    private void handleIncomingMessage(ChatMessagePacket packet) {
        plugin.debug("Received chat message from backend: " + packet);
        
        String channelId = packet.getChannelId();
        String senderName = packet.getSenderName();
        String content = packet.getContent();
        Map<String, String> placeholders = packet.getPlaceholders();
        
        // Get channel display name from placeholders or use channel ID
        String channelName = placeholders.getOrDefault("channel_name", channelId);
        
        // Broadcast to all players in this channel
        for (ProxiedPlayer player : plugin.getProxy().getPlayers()) {
            // Skip senders the recipient has ignored (/nc ignore)
            com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
            if (ignoreService != null && ignoreService.isIgnored(player.getUniqueId(), senderName)) {
                continue;
            }
            PlayerChannelState state = getState(player.getUniqueId());
            if (state != null && channelId.equals(state.getActiveChannel())) {
                BaseComponent[] formattedMessage = messageFormatter.formatChatMessage(
                    player, channelId, channelName, senderName,
                    MentionNotifier.highlightMentions(content, MENTION_HIGHLIGHT_COLOR),
                    placeholders
                );
                player.sendMessage(formattedMessage);
            }
        }
    }

    /**
     * Handles a mention notification packet (UX-DESIGN §4.1, Requirements 11.2).
     *
     * <p>Renders the same title + action-bar pair as the other platforms: the
     * proxy-native {@link Title} API ({@code ProxyServer#createTitle()}) for the
     * title, and {@code sendMessage(ChatMessageType.ACTION_BAR, ...)} for the
     * bar. Both write packets straight to the downstream client; no internal
     * protocol classes are touched.
     */
    private void handleMention(MentionPacket packet) {
        UUID mentionedId = packet.getMentionedId();
        UUID mentionerId = packet.getMentionerId();
        if (mentionedId == null || mentionerId == null) {
            return;
        }
        ProxiedPlayer player = plugin.getProxy().getPlayer(mentionedId);
        if (player == null) {
            return;
        }
        // Ignored mentioner: no notification (/nc ignore)
        com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
        if (ignoreService != null && ignoreService.isIgnored(mentionedId, packet.getMentionerName())) {
            return;
        }
        mentionNotifier.notifyOrSkip(mentionedId, mentionerId, () -> {
            String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
            String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
            BaseComponent[] title = messageFormatter.parseColors("&e" + mentioner);
            BaseComponent[] subtitle = messageFormatter.parseColors(
                    I18n.tr(mentionedId, "chat.mention.subtitle", channelId));

            plugin.getProxy().createTitle()
                    .title(title)
                    .subTitle(subtitle)
                    .fadeIn(MentionNotifier.DEFAULT_FADE_IN)
                    .stay(MentionNotifier.DEFAULT_STAY)
                    .fadeOut(MentionNotifier.DEFAULT_FADE_OUT)
                    .send(player);

            player.sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    messageFormatter.parseColors(
                            "&e" + mentioner + " " + I18n.tr(mentionedId, "chat.mention.subtitle", channelId)));
        });
    }

    
    /**
     * Handles player chat events.
     * 
     * In REPLACE mode:
     * 1. Cancel the original chat event
     * 2. Forward the message to the NovaLink backend
     * 3. Backend broadcasts to all channel members
     * 
     * In HYBRID mode:
     * - Let vanilla chat proceed normally
     *
     * @param event the chat event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerChat(ChatEvent event) {
        // Ignore commands
        if (event.isCommand() || event.isProxyCommand()) {
            return;
        }
        
        if (!(event.getSender() instanceof ProxiedPlayer player)) {
            return;
        }
        
        UUID playerId = player.getUniqueId();
        
        // Get or create player state
        PlayerChannelState state = getOrCreateState(player);
        ChatMode effectiveMode = state.isModeOverridden() ? state.getChatMode() : globalMode;
        
        plugin.debug("Player " + player.getName() + " chat event, mode: " + effectiveMode + 
                    ", channel: " + state.getActiveChannel());
        
        // In HYBRID mode, let vanilla chat proceed
        if (effectiveMode == ChatMode.HYBRID) {
            plugin.debug("HYBRID mode: allowing vanilla chat for " + player.getName());
            return;
        }
        
        // In REPLACE mode, cancel the event and forward to channel
        event.setCancelled(true);
        
        // Check if connected to backend
        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(messageFormatter.formatError(I18n.tr(playerId, "chat.network.not_connected_retry")));
            return;
        }
        
        // Channel-prefix routing (e.g. "!hi" -> global) before the
        // active-channel send; escape/unknown-prefix cases fall through with
        // the resolver-produced message (UX: prefix = /nc <channel> shorthand).
        com.nova.chat.client.channel.ChannelPrefixResolver.Resolution resolution =
                com.nova.chat.client.channel.ChannelPrefixResolver.resolve(
                        config.getChannelPrefixes(), event.getMessage(),
                        plugin.getKnownChannelRegistry() != null
                                ? plugin.getKnownChannelRegistry().getAll() : null);
        String targetChannel = resolution.isRedirect()
                ? resolution.getChannelId() : state.getActiveChannel();

        // Forward message to backend
        sendToChannel(player, targetChannel, resolution.getMessage());
    }
    
    /**
     * Handles player server switch events.
     * Updates player state with new server information.
     *
     * @param event the server connected event
     */
    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        PlayerChannelState state = getOrCreateState(player);
        
        String serverName = event.getServer().getInfo().getName();
        state.setCurrentServer(serverName);
        
        plugin.debug("Player " + player.getName() + " connected to server: " + serverName);
    }
    
    /**
     * Handles player disconnect events.
     * Cleans up player state and tells the backend the player is gone.
     *
     * <p>The backend keeps every player it has seen in its online-state cache and
     * only clears it on shutdown, so without a LEAVE the proxy player looks
     * online forever: private messages are routed back to this proxy, which
     * renders only the sender echo, and the sender sees a false "sent". The LEAVE
     * is sent before the local state is dropped so it can name the channel the
     * player is actually in.
     *
     * @param event the disconnect event
     */
    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        PlayerChannelState state = playerStates.get(playerId);
        notifyBackendDisconnect(event.getPlayer(), state);
        playerStates.remove(playerId);
        welcomedPlayers.remove(playerId);
        firstConnections.remove(playerId);
        // Reply-target cleanup for /nc r (private messages); thread-safe map.
        if (plugin.getPrivateMessageService() != null) {
            plugin.getPrivateMessageService().onPlayerQuit(playerId);
        }
        plugin.debug("Removed chat state for " + event.getPlayer().getName());
    }

    /**
     * Sends a LEAVE for every channel the departing player had joined, so the
     * backend drops both the membership and the cached player state.
     *
     * <p>One packet per joined channel is required: {@code ChannelActionHandler}
     * rejects a blank channel id (NC-400), and a player that joined several
     * channels would otherwise stay a member of the rest.
     *
     * @param player the disconnecting player
     * @param state  the player's chat state, or {@code null} if it was never created
     */
    private void notifyBackendDisconnect(ProxiedPlayer player, PlayerChannelState state) {
        if (state == null || plugin.getNetworkClient() == null
                || !plugin.getNetworkClient().isAuthenticated()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        java.util.Set<String> channels = new java.util.LinkedHashSet<>(state.getJoinedChannels());
        if (state.getActiveChannel() != null) {
            channels.add(state.getActiveChannel());
        }
        for (String channelId : channels) {
            if (channelId == null || channelId.isBlank()) {
                continue;
            }
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.LEAVE, channelId);
            packet.addExtra("playerId", playerId.toString());
            packet.addExtra("playerName", playerName);
            packet.addExtra("platform", PlatformType.BUNGEECORD.name());
            // Distinguishes "player is logging off this network" from a plain
            // /nc leave. The backend currently ignores this extra (see the
            // companion change required in ChannelActionHandler#handleLeave);
            // it is carried here so the cache eviction can be keyed on it.
            packet.addExtra("logout", "true");
            plugin.getNetworkClient().sendPacket(packet);
            plugin.debug("Notified backend that " + playerName + " left channel " + channelId);
        }
    }

    /**
     * Atomically claims the player's first server connection of this proxy
     * session.
     *
     * <p>Used by {@link com.nova.chat.bungee.listener.ServerSwitchHandler} so the
     * "first join" decision never depends on listener execution order or on
     * {@code PlayerChannelState#getCurrentServer()}, which both listeners write.
     *
     * @param playerId the connecting player
     * @return {@code true} the first time this player connects in this session
     */
    public boolean claimFirstConnection(UUID playerId) {
        return firstConnections.add(playerId);
    }

    /**
     * Pushes the shared first-join welcome line once per proxy session to the
     * given player (UX-DESIGN §8.1). No-op if already welcomed this session.
     * Called from {@code ServerSwitchHandler} on the player's first server
     * connection (as decided by {@link #claimFirstConnection(UUID)}).
     *
     * @param player the freshly connected player
     */
    public void pushWelcomeIfFirst(ProxiedPlayer player) {
        if (welcomedPlayers.add(player.getUniqueId())) {
            player.sendMessage(plugin.getChatListener().getMessageFormatter().parseColors(
                    com.nova.chat.client.command.WelcomeMessageService.getWelcomeLine()));
            plugin.debug("Sent first-join welcome to " + player.getName());
        }
    }
    
    /**
     * Sends a message to a specific channel.
     *
     * @param player the sending player
     * @param channelId the target channel ID
     * @param message the message content
     */
    public void sendToChannel(ProxiedPlayer player, String channelId, String message) {
        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(messageFormatter.formatError(I18n.tr(player.getUniqueId(), "chat.network.not_connected")));
            return;
        }
        
        ChatMessagePacket packet = new ChatMessagePacket(
            player.getUniqueId(),
            player.getName(),
            config.getUsername(), // Client ID
            channelId,
            message
        );
        
        // Add basic placeholders
        packet.addPlaceholder("player", player.getName());
        packet.addPlaceholder("display_name", player.getDisplayName());
        
        // Add server info if available
        if (player.getServer() != null) {
            packet.addPlaceholder("server", player.getServer().getInfo().getName());
        }
        
        plugin.getNetworkClient().sendPacket(packet);
        plugin.debug("Sent message to channel " + channelId + ": " + message);
    }
    
    /**
     * Gets or creates a player's chat state.
     *
     * @param player the player
     * @return the player's chat state
     */
    public PlayerChannelState getOrCreateState(ProxiedPlayer player) {
        return playerStates.getOrCreate(player.getUniqueId(),
            config.getDefaultChannel(), globalMode);
    }

    /**
     * Gets a player's chat state if it exists.
     *
     * @param playerId the player's UUID
     * @return the player's chat state, or null if not found
     */
    public PlayerChannelState getState(UUID playerId) {
        return playerStates.get(playerId);
    }

    /**
     * Gets a player's chat state if it exists.
     * Alias for getState() for command compatibility.
     *
     * @param playerId the player's UUID
     * @return the player's chat state, or null if not found
     */
    public PlayerChannelState getPlayerState(UUID playerId) {
        return playerStates.getPlayer(playerId);
    }

    /**
     * Sets a player's chat state.
     *
     * @param playerId the player's UUID
     * @param state the chat state to set
     */
    public void setPlayerState(UUID playerId, PlayerChannelState state) {
        playerStates.set(playerId, state);
    }
    
    /**
     * Sets the global chat mode.
     *
     * @param mode the new global mode
     */
    public void setGlobalMode(ChatMode mode) {
        this.globalMode = mode;
    }
    
    /**
     * Gets the global chat mode.
     *
     * @return the global chat mode
     */
    public ChatMode getGlobalMode() {
        return globalMode;
    }
    
    /**
     * Toggles a player's chat mode.
     *
     * @param player the player
     * @return the new chat mode
     */
    public ChatMode togglePlayerMode(ProxiedPlayer player) {
        PlayerChannelState state = getOrCreateState(player);
        return state.toggleMode();
    }
    
    /**
     * Sets a player's active channel.
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(ProxiedPlayer player, String channelId) {
        PlayerChannelState state = getOrCreateState(player);
        state.setActiveChannel(channelId);
    }
    
    /**
     * Gets a player's active channel.
     *
     * @param player the player
     * @return the active channel ID
     */
    public String getPlayerChannel(ProxiedPlayer player) {
        PlayerChannelState state = getOrCreateState(player);
        return state.getActiveChannel();
    }
    
    /**
     * Reloads configuration settings using the plugin's latest config snapshot.
     */
    public void reload() {
        setConfig(plugin.getPluginConfig());
    }

    /**
     * Installs a new {@link NovaChatConfig} snapshot into this listener and
     * cascades it to the message formatter. Called by the plugin main class
     * after {@code loadConfiguration()} succeeds so subsequent hot-path lookups
     * resolve against the freshly loaded values instead of a stale reference.
     *
     * @param newConfig the freshly loaded config snapshot
     */
    public void setConfig(NovaChatConfig newConfig) {
        if (newConfig == null) {
            return;
        }
        this.config = newConfig;
        this.globalMode = newConfig.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        this.messageFormatter.setConfig(newConfig);
        plugin.debug("ChatListener reloaded, global mode: " + globalMode);
    }
    
    /**
     * Gets the message formatter.
     *
     * @return the message formatter
     */
    public MessageFormatter getMessageFormatter() {
        return messageFormatter;
    }
}
