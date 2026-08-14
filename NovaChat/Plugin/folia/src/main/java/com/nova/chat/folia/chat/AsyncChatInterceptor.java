package com.nova.chat.folia.chat;

import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.itemdisplay.ItemDisplayMessages;
import com.nova.chat.client.itemdisplay.ItemDisplayTokens;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;

import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.command.MessageHelper;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.common.chat.MentionNotifier;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Async chat interceptor for Folia that properly handles region thread concurrency.
 * 
 * <p>This class is designed to work correctly with Folia's regionized multithreading model.
 * Key thread safety considerations:</p>
 * 
 * <ul>
 *   <li>Uses {@link AsyncPlayerChatEvent} which is already called from an async thread</li>
 *   <li>All player state is stored in a {@link ConcurrentHashMap} for thread-safe access</li>
 *   <li>All player operations (sending messages, etc.) are scheduled on the correct region thread
 *       using {@link FoliaSchedulerAdapter#runForPlayer(Player, Runnable)}</li>
 *   <li>The global chat mode is marked volatile for visibility across threads</li>
 * </ul>
 * 
 * <p>In Folia, different regions of the world run on different threads. This means that
 * when we need to interact with a player (send messages, check permissions, etc.), we must
 * ensure we're on the correct thread for that player's region. This class handles this
 * automatically by using the scheduler adapter.</p>
 * 
 * Requirements: 2.3, 2.4
 */
public class AsyncChatInterceptor implements Listener {
    
    private final NovaChatFolia plugin;
    private final NovaChatConfig config;
    private final FoliaSchedulerAdapter scheduler;
    private final MentionNotifier mentionNotifier = new MentionNotifier();
    
    /** Message formatter for color codes and placeholders - thread-safe */
    private final AsyncMessageFormatter messageFormatter;

    /**
     * Shared [item]/[i] token detection + per-player cooldown (client-core).
     * Internally synchronized, safe to touch from any region thread.
     */
    private final ItemDisplayTokens itemDisplayTokens = new ItemDisplayTokens();
    
    /**
     * Player chat states indexed by UUID.
     * Uses the shared PlayerStateStore for thread-safe access from multiple
     * region threads. All operations are atomic and thread-safe.
     */
    private final com.nova.chat.client.state.PlayerStateStore playerStates =
            new com.nova.chat.client.state.PlayerStateStore();

    /** Shared response dispatcher (DUP-3); created in {@link #registerIncomingMessageHandler()}. */
    private ChannelResponseDispatcher dispatcher;
    
    /** 
     * Global chat mode from configuration.
     * Marked volatile to ensure visibility across all region threads.
     */
    private volatile ChatMode globalMode;
    
    /**
     * Creates a new AsyncChatInterceptor.
     *
     * @param plugin the plugin instance
     */
    public AsyncChatInterceptor(NovaChatFolia plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();
        this.scheduler = plugin.getScheduler();
        this.messageFormatter = new AsyncMessageFormatter(plugin);
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        
        // Register handler for incoming chat messages from backend
        registerIncomingMessageHandler();
    }
    
    /**
     * Registers the handler for incoming chat messages from the backend.
     */
    private void registerIncomingMessageHandler() {
        this.dispatcher = new ChannelResponseDispatcher(
                plugin.getNetworkClient().getChannelResponseTracker(),
                new FoliaChannelResponseAdapter());
        plugin.getNetworkClient().registerHandler(ChatMessagePacket.class, this::handleIncomingMessage);
        plugin.getNetworkClient().registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
        plugin.getNetworkClient().registerHandler(MentionPacket.class, this::handleMention);
        plugin.getNetworkClient().registerHandler(TitlePacket.class, this::handleTitle);
        plugin.getNetworkClient().registerHandler(ItemDisplayPacket.class, this::handleItemDisplay);
        plugin.getNetworkClient().registerHandler(
                com.nova.chat.common.protocol.packets.PrivateMessagePacket.class,
                this::handlePrivateMessage);
    }

    /**
     * Handles a completed (S→C) private message: the shared
     * {@link com.nova.chat.client.privatemsg.PrivateMessageService} resolves
     * which local players render which role (sender echo vs received line,
     * receiver-side ignore filter, reply tracking); each line is then sent on
     * the recipient's region thread via
     * {@link FoliaSchedulerAdapter#runForPlayer}, mirroring
     * {@link #handleItemDisplay}.
     */
    private void handlePrivateMessage(com.nova.chat.common.protocol.packets.PrivateMessagePacket packet) {
        var deliveries = plugin.getPrivateMessageService().handleIncoming(
                packet,
                id -> plugin.getServer().getPlayer(id) != null,
                plugin.getIgnoreListService());
        for (com.nova.chat.client.privatemsg.PrivateMessageService.Delivery delivery : deliveries) {
            Player player = plugin.getServer().getPlayer(delivery.getPlayerId());
            if (player == null) {
                continue;
            }
            final String line = messageFormatter.translateColorCodes(delivery.getLine());
            scheduler.runForPlayer(player, () -> {
                if (player.isOnline()) {
                    player.sendMessage(line);
                }
            });
        }
    }

    /**
     * Handles an inbound item display packet ({@code [item]}/{@code [i]} play,
     * packet 0x10) by rendering one hoverable chat line to every player whose
     * active channel matches the packet channel (Requirements 12.3/12.4).
     *
     * <p>Receive-side semantics are "receive = render", matching the Bedrock
     * clients; the backend currently registers no route for this packet.
     * Threading mirrors {@link #handleTitle}: format once off-thread, then hop
     * to each recipient's region thread via
     * {@link FoliaSchedulerAdapter#runForPlayer} for the player API call.
     */
    private void handleItemDisplay(ItemDisplayPacket packet) {
        String channelId = packet.getChannelId();

        // Snapshot online players to avoid concurrent modification during iteration.
        List<Player> playersSnapshot = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        for (Player player : playersSnapshot) {
            if (!player.isOnline()) {
                continue;
            }
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
            final String line = messageFormatter.translateColorCodes(
                    ItemDisplayMessages.formatLine(viewerId, packet.getSenderName(), packet.getItemJson()));
            final String hover = messageFormatter.translateColorCodes(
                    ItemDisplayMessages.formatHoverDetail(viewerId, packet.getItemJson()));
            scheduler.runForPlayer(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                TextComponent component = new TextComponent(TextComponent.fromLegacyText(line));
                component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new Text(TextComponent.fromLegacyText(hover))));
                player.spigot().sendMessage(component);
            });
        }
    }

    /**
     * Handles title packets by displaying them to players whose active channel
     * matches the packet channel (Requirements 15.1, 15.5).
     *
     * <p>Folia is region-threaded, so all player API calls must execute on the
     * recipient's region thread via {@link FoliaSchedulerAdapter#runForPlayer},
     * mirroring {@link #handleIncomingMessage}'s per-player dispatch.
     */
    private void handleTitle(TitlePacket packet) {
        String channelId = packet.getChannelId();
        String title = packet.getTitle();
        String subtitle = packet.getSubtitle();

        // Translate color codes (& and hex) once; the formatter is thread-safe.
        final String renderedTitle = messageFormatter.translateColorCodes(title != null ? title : "");
        final String renderedSubtitle = messageFormatter.translateColorCodes(subtitle != null ? subtitle : "");
        final int fadeIn = packet.getFadeIn();
        final int stay = packet.getStay();
        final int fadeOut = packet.getFadeOut();

        // Snapshot online players to avoid concurrent modification during iteration.
        List<Player> playersSnapshot = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        for (Player player : playersSnapshot) {
            if (!player.isOnline()) {
                continue;
            }
            PlayerChannelState state = getState(player.getUniqueId());
            if (state != null && channelId != null && channelId.equals(state.getActiveChannel())) {
                scheduler.runForPlayer(player, () -> {
                    if (player.isOnline()) {
                        player.sendTitle(renderedTitle, renderedSubtitle, fadeIn, stay, fadeOut);
                    }
                });
            }
        }
    }

    /** Legacy color prefix applied to @name mentions when rendering chat (UX-DESIGN §4.2). */
    static final String MENTION_HIGHLIGHT_COLOR = MentionNotifier.DEFAULT_HIGHLIGHT_COLOR;

    /**
     * Handles asynchronous channel-action responses by delegating the shared
     * "consume pending → route success/failure" skeleton to the shared
     * {@link ChannelResponseDispatcher} (DUP-3). The platform adapter hops onto
     * the player's region thread via {@link FoliaSchedulerAdapter#runForPlayer}
     * before touching state, rendering, or flashing the §7 action bar.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        plugin.debug("Received channel action response: " + packet);
        // Private-message rejections are unsolicited (no pending context in the
        // shared tracker); the dispatcher would drop them, so route them to the
        // PrivateMessageService for player-locale rendering instead.
        if (com.nova.chat.client.privatemsg.PrivateMessageService.isPrivateMessageError(packet)) {
            plugin.getPrivateMessageService()
                    .renderError(packet, id -> plugin.getServer().getPlayer(id) != null)
                    .ifPresent(delivery -> {
                        Player player = plugin.getServer().getPlayer(delivery.getPlayerId());
                        if (player == null) {
                            return;
                        }
                        final String line = messageFormatter.translateColorCodes(delivery.getLine());
                        scheduler.runForPlayer(player, () -> {
                            if (player.isOnline()) {
                                player.sendMessage(line);
                            }
                        });
                    });
            return;
        }
        dispatcher.handle(packet);
    }

    /**
     * Folia-specific {@link ChannelResponseDispatcher.ChannelResponseAdapter}.
     * Every callback resolves the player on the Netty thread (safe read) then
     * hops to the player's region thread before mutating state or rendering;
     * the KICK/MUTE notice uses a title plus an action-bar reinforcement.
     */
    private final class FoliaChannelResponseAdapter implements ChannelResponseDispatcher.ChannelResponseAdapter {

        @Override
        public void setActiveChannel(UUID playerId, String channelId) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            scheduler.runForPlayer(player, () ->
                    plugin.getChatInterceptor().getOrCreateState(player).setActiveChannel(channelId));
        }

        @Override
        public void rollbackJoin(UUID playerId, String attemptedChannel, String previousChannel) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            scheduler.runForPlayer(player, () -> {
                PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
                String current = state.getActiveChannel();
                if (current != null && current.equals(attemptedChannel)) {
                    state.setActiveChannel(previousChannel);
                }
            });
        }

        @Override
        public void sendJoinSuccess(UUID playerId, String channelId) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            scheduler.runForPlayer(player, () -> {
                if (player.isOnline()) {
                    plugin.getMessageHelper().sendSuccess(player, PlayerMessages.joined(playerId, channelId));
                }
            });
        }

        @Override
        public void sendLeaveSuccess(UUID playerId, String channelId) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            scheduler.runForPlayer(player, () -> {
                if (player.isOnline()) {
                    plugin.getMessageHelper().sendSuccess(player,
                            PlayerMessages.left(channelId, config.getDefaultChannel()));
                }
            });
        }

        @Override
        public void sendJoinChannelStatusBar(UUID playerId, String channelId) {
            if (channelId == null || channelId.isEmpty()) {
                return;
            }
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            scheduler.runForPlayer(player, () -> sendChannelStatusBar(player, channelId));
        }

        @Override
        public void sendLeaveChannelStatusBar(UUID playerId) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            scheduler.runForPlayer(player, () ->
                    sendChannelStatusBar(player, plugin.getChatInterceptor().getOrCreateState(player).getActiveChannel()));
        }

        @Override
        public void sendErrorMessage(UUID playerId, String text) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            scheduler.runForPlayer(player, () -> plugin.getMessageHelper().sendError(player, text));
        }

        @Override
        public void sendWhoResult(UUID playerId, String channelId, String displayName,
                                  String membersCsv, String memberCount) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                return;
            }
            scheduler.runForPlayer(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                String text = WhoCommandService.formatMemberList(
                        playerId, channelId, displayName, membersCsv, memberCount);
                for (String line : text.split("\n")) {
                    if (!line.isEmpty()) {
                        player.sendMessage(MessageHelper.colorize(line));
                    }
                }
            });
        }

        @Override
        public void notifyKickMuteTarget(ChannelResponseDispatcher.KickMuteNotice notice) {
            final String operator = notice.getOperator();
            final String durationText = notice.getDurationText();
            Player target = plugin.getServer().getPlayer(notice.getTargetId());
            if (target == null) {
                return; // not on this server
            }
            scheduler.runForPlayer(target, () -> {
                if (!target.isOnline()) {
                    return;
                }
                try {
                    String channelId = notice.getChannelId();
                    if (notice.getAction() == ChannelAction.KICK) {
                        String title = MessageHelper.colorize(I18n.tr(notice.getTargetId(), "chat.notice.kick_title"));
                        String subtitle = MessageHelper.colorize(
                                I18n.tr(notice.getTargetId(), "chat.notice.kick_subtitle", operator, channelId));
                        target.sendTitle(title, subtitle,
                                MentionNotifier.DEFAULT_FADE_IN, MentionNotifier.DEFAULT_STAY, MentionNotifier.DEFAULT_FADE_OUT);
                        target.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                                new TextComponent(MessageHelper.colorize(
                                        I18n.tr(notice.getTargetId(), "chat.notice.kick_actionbar", operator, channelId))));
                        return;
                    }
                    // MUTE
                    String title = MessageHelper.colorize(I18n.tr(notice.getTargetId(), "chat.notice.mute_title"));
                    String subtitle = MessageHelper.colorize(
                            I18n.tr(notice.getTargetId(), "chat.notice.mute_subtitle", channelId, durationText));
                    target.sendTitle(title, subtitle,
                            MentionNotifier.DEFAULT_FADE_IN, MentionNotifier.DEFAULT_STAY, MentionNotifier.DEFAULT_FADE_OUT);
                    target.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                            new TextComponent(MessageHelper.colorize(
                                    I18n.tr(notice.getTargetId(), "chat.notice.mute_actionbar", durationText, channelId))));
                } catch (Exception e) {
                    plugin.debug("Failed to notify kick/mute target: " + e.getMessage(), e);
                }
            });
        }
    }

    /**
     * Flashes a one-shot action bar with the player's current channel and chat
     * mode after a successful join/leave (UX-DESIGN §7). Vanilla action-bar
     * fade handles the ~3s dismissal; no polling. Must be called on the player's
     * region thread (the handler already hops there).
     *
     * @param player the recipient
     * @param channelId the channel to display; if null/blank, nothing is sent
     */
    private void sendChannelStatusBar(Player player, String channelId) {
        if (channelId == null || channelId.isEmpty() || !player.isOnline()) {
            return;
        }
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        ChatMode mode = state.getChatMode();
        String text = com.nova.chat.client.command.PlayerMessages.currentChannelBar(player.getUniqueId(), channelId, mode);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                new TextComponent(MessageHelper.colorize(text)));
    }

    /**
     * Handles a mention notification packet by playing a sound and showing a
     * title to the mentioned player on their region thread
     * (UX-DESIGN §4.1, Requirements 11.2).
     *
     * <p>Folia is region-threaded, so all player API calls must execute on the
     * recipient's region thread via {@link FoliaSchedulerAdapter#runForPlayer}.
     */
    private void handleMention(MentionPacket packet) {
        UUID mentionedId = packet.getMentionedId();
        UUID mentionerId = packet.getMentionerId();
        if (mentionedId == null || mentionerId == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(mentionedId);
        if (player == null) {
            return; // not on this server
        }
        // Ignored mentioner: no bell, no title (/nc ignore)
        com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
        if (ignoreService != null && ignoreService.isIgnored(mentionedId, packet.getMentionerName())) {
            return;
        }
        scheduler.runForPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            try {
                mentionNotifier.notifyOrSkip(mentionedId, mentionerId, () -> {
                    String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
                    String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
                    String title = messageFormatter.translateColorCodes("&e" + mentioner);
                    String subtitle = messageFormatter.translateColorCodes(
                            I18n.tr(mentionedId, "chat.mention.subtitle", channelId));
                    player.sendTitle(title, subtitle,
                            MentionNotifier.DEFAULT_FADE_IN,
                            MentionNotifier.DEFAULT_STAY,
                            MentionNotifier.DEFAULT_FADE_OUT);
                    playMentionSound(player);
                });
            } catch (Exception e) {
                plugin.debug("Failed to handle MentionPacket: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Plays the default mention notification sound to a player on their region thread.
     * Assumes the caller is already on the player's region thread.
     */
    private void playMentionSound(Player player) {
        // The common DEFAULT_SOUND constant names the ENTITY_EXPERIENCE_ORB_PICKUP
        // enum, which we resolve directly here to avoid the deprecated
        // Sound.valueOf(String) removal API.
        player.playSound(player.getLocation(),
                org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
    }
    
    /**
     * Handles incoming chat messages from the backend.
     * Dispatches messages to players on their correct region threads.
     * 
     * <p>Thread Safety: This method can be called from any thread (typically Netty's event loop
     * or an async scheduler thread). It safely iterates over online players and schedules
     * message delivery on each player's correct region thread.</p>
     * 
     * <p>In Folia, each player may be in a different region running on a different thread.
     * We must schedule the message sending operation on the correct thread for each player
     * to avoid concurrent modification issues.</p>
     *
     * Requirements: 2.3
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
        
        // Create a snapshot of online players to avoid concurrent modification
        // This is important because the player list can change while we're iterating
        Collection<? extends Player> onlinePlayers = plugin.getServer().getOnlinePlayers();
        List<Player> playersSnapshot = new ArrayList<>(onlinePlayers);
        
        // Dispatch messages to each player on their correct region thread
        // This is critical for Folia - we must send messages on the correct thread
        for (Player player : playersSnapshot) {
            // Skip if player is no longer online (may have disconnected during iteration)
            if (!player.isOnline()) {
                continue;
            }

            // Skip senders the recipient has ignored (/nc ignore) — the shared
            // service is thread-safe, so this Netty-thread read is fine.
            com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
            if (ignoreService != null && ignoreService.isIgnored(player.getUniqueId(), senderName)) {
                continue;
            }

            // Get player state (thread-safe read from ConcurrentHashMap)
            PlayerChannelState state = getState(player.getUniqueId());
            if (state != null && channelId.equals(state.getActiveChannel())) {
                // Capture variables for lambda (effectively final)
                final String finalChannelName = channelName;
                
                // Schedule message sending on the player's region thread
                // This ensures we're on the correct thread for Bukkit API calls
                scheduler.runForPlayer(player, () -> {
                    // Double-check player is still online when task executes
                    if (!player.isOnline()) {
                        return;
                    }
                    
                    String formattedMessage = messageFormatter.formatChatMessage(
                        player, channelId, finalChannelName, senderName,
                        MentionNotifier.highlightMentions(content, MENTION_HIGHLIGHT_COLOR),
                        placeholders
                    );
                    player.sendMessage(formattedMessage);
                });
            }
        }
    }
    
    /**
     * Handles player chat events using AsyncPlayerChatEvent.
     * 
     * <p>Thread Safety: AsyncPlayerChatEvent is already called from an async thread,
     * making it inherently safe for Folia's regionized model. The event is NOT called
     * from any region thread, so we don't need to worry about region thread conflicts
     * when handling this event.</p>
     * 
     * <p>However, when we need to send messages back to the player (e.g., error messages),
     * we must schedule those operations on the player's correct region thread.</p>
     * 
     * <p>In REPLACE mode, cancels the event and forwards to the channel.
     * In HYBRID mode, allows vanilla chat to proceed normally.</p>
     *
     * Requirements: 2.3, 2.4
     *
     * @param event the chat event
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Get or create player state (thread-safe operation via ConcurrentHashMap)
        PlayerChannelState state = getOrCreateState(player);
        
        // Read volatile field once for consistency within this method
        ChatMode currentGlobalMode = globalMode;
        ChatMode effectiveMode = state.isModeOverridden() ? state.getChatMode() : currentGlobalMode;
        
        plugin.debug("Player " + player.getName() + " chat event, mode: " + effectiveMode + 
                    ", channel: " + state.getActiveChannel());
        
        // In HYBRID mode, let vanilla chat proceed
        if (effectiveMode == ChatMode.HYBRID) {
            plugin.debug("HYBRID mode: allowing vanilla chat for " + player.getName());
            return;
        }
        
        // In REPLACE mode, cancel vanilla chat and forward to channel
        event.setCancelled(true);
        
        // Check if connected to backend
        if (!plugin.getNetworkClient().isAuthenticated()) {
            // Send error message on player's region thread
            // This is necessary because sendMessage() may interact with player state
            scheduler.runForPlayer(player, () -> {
                if (player.isOnline()) {
                    player.sendMessage(formatError(I18n.tr(playerId, "chat.network.not_connected_retry")));
                }
            });
            return;
        }
        
        // Capture message before forwarding (event data should be accessed in event handler)
        String message = event.getMessage();

        // Channel-prefix routing (e.g. "!hi" -> global) before the
        // active-channel send; escape/unknown-prefix cases fall through with
        // the resolver-produced message (UX: prefix = /nc <channel> shorthand).
        com.nova.chat.client.channel.ChannelPrefixResolver.Resolution resolution =
                com.nova.chat.client.channel.ChannelPrefixResolver.resolve(
                        config.getChannelPrefixes(), message,
                        plugin.getKnownChannelRegistry() != null
                                ? plugin.getKnownChannelRegistry().getAll() : null);
        String targetChannel = resolution.isRedirect()
                ? resolution.getChannelId() : state.getActiveChannel();

        // Forward message to backend (async operation, safe from any thread)
        // The network client handles its own thread safety
        sendToChannel(player, targetChannel, resolution.getMessage());
    }
    
    /**
     * Handles player join events to initialize chat state.
     * 
     * <p>Thread Safety: PlayerJoinEvent is called on the player's region thread in Folia.
     * This means we're already on the correct thread for this player, so we can safely
     * access player data and initialize state.</p>
     * 
     * <p>The state initialization itself is thread-safe because we use ConcurrentHashMap.</p>
     *
     * Requirements: 2.3
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Initialize state (thread-safe via ConcurrentHashMap.computeIfAbsent)
        // This is safe to call from the player's region thread
        getOrCreateState(player);
        plugin.debug("Initialized chat state for " + player.getName());
    }
    
    /**
     * Handles player quit events to clean up chat state.
     * 
     * <p>Thread Safety: PlayerQuitEvent is called on the player's region thread in Folia.
     * The removal from ConcurrentHashMap is atomic and thread-safe.</p>
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        // Atomic removal from ConcurrentHashMap
        PlayerChannelState removedState = playerStates.remove(playerId);
        // Reply-target cleanup for /nc r (private messages); thread-safe map.
        if (plugin.getPrivateMessageService() != null) {
            plugin.getPrivateMessageService().onPlayerQuit(playerId);
        }
        if (removedState != null) {
            plugin.debug("Removed chat state for " + event.getPlayer().getName());
        }
    }
    
    /**
     * Sends a message to a specific channel.
     * 
     * <p>Thread Safety: This method is thread-safe and can be called from any thread.
     * The network client handles its own thread safety for packet sending.
     * Player data access (getName, getDisplayName, getWorld) should ideally be done
     * on the player's region thread, but these are generally safe read operations.</p>
     * 
     * <p>For maximum safety in Folia, we capture player data that might change and
     * send the packet asynchronously.</p>
     *
     * @param player the sending player
     * @param channelId the target channel ID
     * @param message the message content
     */
    public void sendToChannel(Player player, String channelId, String message) {
        if (!plugin.getNetworkClient().isAuthenticated()) {
            scheduler.runForPlayer(player, () -> {
                if (player.isOnline()) {
                    player.sendMessage(formatError(I18n.tr(player.getUniqueId(), "chat.network.not_connected")));
                }
            });
            return;
        }
        
        // Capture player data that we need for the packet
        // These reads are generally safe but we capture them to avoid issues
        // if player state changes during packet construction
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        String displayName = player.getDisplayName();
        String worldName = player.getWorld().getName();
        
        ChatMessagePacket packet = new ChatMessagePacket(
            playerId,
            playerName,
            config.getUsername(),
            channelId,
            message
        );
        
        // Add basic placeholders with captured data
        packet.addPlaceholder("player", playerName);
        packet.addPlaceholder("display_name", displayName);
        packet.addPlaceholder("world", worldName);
        
        // Send packet (async operation via Netty, thread-safe)
        plugin.getNetworkClient().sendPacket(packet);
        plugin.debug("Sent message to channel " + channelId + ": " + message);

        // [item]/[i] display play: piggybacks on the outbound path (UX spec §4).
        maybeSendItemDisplay(player, channelId, message);
    }

    /**
     * Sends an {@link ItemDisplayPacket} when the outbound message carries an
     * {@code [item]}/{@code [i]} token.
     *
     * <p>Semantics aligned with the Bedrock reference (pmmp/endstone):
     * case-insensitive {@code \[(item|i)\]} token, the shared
     * {@code novachat.feature.item} permission gate, and an empty hand still
     * sends the air payload. The per-player cooldown lives in the shared
     * {@link ItemDisplayTokens}. Only display fields (id/count/custom name)
     * are serialized — never full NBT.
     *
     * <p>Thread Safety: token matching and the cooldown map are thread-safe;
     * the inventory read is a plain read of the caller's snapshot, consistent
     * with the other captured player data in {@link #sendToChannel}.
     */
    private void maybeSendItemDisplay(Player player, String channelId, String message) {
        try {
            if (!ItemDisplayTokens.hasItemToken(message)) {
                return;
            }
            if (!player.hasPermission(ItemDisplayTokens.PERMISSION_ITEM)) {
                return; // without permission the token stays plain text
            }
            if (!itemDisplayTokens.tryAcquire(player.getUniqueId())) {
                return; // rate-limited: token stays plain text
            }
            String itemJson = buildMainHandItemJson(player);
            plugin.getNetworkClient().sendPacket(ItemDisplayTokens.buildPacket(
                    player.getUniqueId(), player.getName(), channelId, itemJson));
            plugin.debug("Sent item display to channel " + channelId + ": " + itemJson);
        } catch (Exception e) {
            plugin.debug("Failed to send item display: " + e.getMessage());
        }
    }

    /**
     * Extracts the display fields (id / count / custom name) of the player's
     * main-hand item. Empty hand → air payload, matching the Bedrock
     * renderers' "Empty" placeholder behavior.
     */
    private String buildMainHandItemJson(Player player) {
        org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == org.bukkit.Material.AIR || hand.getAmount() <= 0) {
            return ItemDisplayTokens.emptyHandJson();
        }
        String customName = null;
        org.bukkit.inventory.meta.ItemMeta meta = hand.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            customName = meta.getDisplayName();
        }
        return ItemDisplayTokens.buildItemJson(
                hand.getType().getKey().toString(), hand.getAmount(), customName);
    }
    
    /**
     * Gets or creates a player's chat state.
     * 
     * <p>Thread Safety: This method is thread-safe. It uses ConcurrentHashMap.computeIfAbsent
     * which is atomic - if multiple threads call this simultaneously for the same player,
     * only one state will be created and all threads will receive the same instance.</p>
     *
     * @param player the player
     * @return the player's chat state (never null)
     */
    public PlayerChannelState getOrCreateState(Player player) {
        // Read volatile field once for consistency
        ChatMode currentGlobalMode = globalMode;
        return playerStates.getOrCreate(player.getUniqueId(),
            config.getDefaultChannel(), currentGlobalMode);
    }

    /**
     * Gets a player's chat state if it exists.
     *
     * <p>Thread Safety: This is a simple read from the shared store, which is thread-safe.</p>
     *
     * @param playerId the player's UUID
     * @return the player's chat state, or null if not found
     */
    public PlayerChannelState getState(UUID playerId) {
        return playerStates.get(playerId);
    }

    /**
     * Gets a player's chat state if it exists.
     *
     * <p>Thread Safety: This is a simple read from the shared store, which is thread-safe.</p>
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
     * <p>Thread Safety: this is a simple write to the shared store, which is thread-safe.</p>
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
     * <p>Thread Safety: The globalMode field is volatile, so this write is immediately
     * visible to all threads. This is typically called from the main/global thread
     * during configuration reload.</p>
     *
     * @param mode the new global mode
     */
    public void setGlobalMode(ChatMode mode) {
        this.globalMode = mode;
        plugin.debug("Global chat mode set to: " + mode);
    }
    
    /**
     * Gets the global chat mode.
     * 
     * <p>Thread Safety: The globalMode field is volatile, so this read always
     * returns the most recent value.</p>
     *
     * @return the global chat mode
     */
    public ChatMode getGlobalMode() {
        return globalMode;
    }
    
    /**
     * Toggles a player's chat mode.
     * 
     * <p>Thread Safety: The toggle operation on PlayerChannelState is synchronized,
     * and the state retrieval is thread-safe via ConcurrentHashMap.</p>
     *
     * @param player the player
     * @return the new chat mode
     */
    public ChatMode togglePlayerMode(Player player) {
        PlayerChannelState state = getOrCreateState(player);
        ChatMode newMode = state.toggleMode();
        plugin.debug("Player " + player.getName() + " chat mode toggled to: " + newMode);
        return newMode;
    }
    
    /**
     * Sets a player's active channel.
     * 
     * <p>Thread Safety: The channel field in PlayerChannelState is volatile,
     * and the state retrieval is thread-safe via ConcurrentHashMap.</p>
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(Player player, String channelId) {
        PlayerChannelState state = getOrCreateState(player);
        state.setActiveChannel(channelId);
        plugin.debug("Player " + player.getName() + " channel set to: " + channelId);
    }
    
    /**
     * Gets a player's active channel.
     * 
     * <p>Thread Safety: The channel field in PlayerChannelState is volatile,
     * and the state retrieval is thread-safe via ConcurrentHashMap.</p>
     *
     * @param player the player
     * @return the active channel ID
     */
    public String getPlayerChannel(Player player) {
        PlayerChannelState state = getOrCreateState(player);
        return state.getActiveChannel();
    }
    
    /**
     * Reloads configuration settings.
     * 
     * <p>Thread Safety: This should be called from the global region thread or
     * during plugin reload. The volatile write to globalMode ensures visibility.</p>
     */
    public void reload() {
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        this.messageFormatter.reload();
        plugin.debug("AsyncChatInterceptor reloaded, global mode: " + globalMode);
    }
    
    /**
     * Gets the message formatter.
     *
     * @return the message formatter
     */
    public AsyncMessageFormatter getMessageFormatter() {
        return messageFormatter;
    }
    
    /**
     * Gets the number of tracked player states.
     * Useful for debugging and monitoring.
     *
     * @return the number of player states
     */
    public int getPlayerStateCount() {
        return playerStates.size();
    }

    /**
     * Clears all player states.
     * Should only be called during plugin disable or reload.
     */
    public void clearAllStates() {
        playerStates.clear();
        plugin.debug("Cleared all player chat states");
    }
    
    /**
     * Formats an error message with the plugin prefix.
     * This method is thread-safe as it only uses immutable config values.
     *
     * @param message the error message
     * @return the formatted message
     */
    private String formatError(String message) {
        return config.getPrefix() + config.getErrorFormat().replace("{message}", message);
    }
    
    /**
     * Formats a success message with the plugin prefix.
     * This method is thread-safe as it only uses immutable config values.
     *
     * @param message the success message
     * @return the formatted message
     */
    private String formatSuccess(String message) {
        return config.getPrefix() + config.getSuccessFormat().replace("{message}", message);
    }
    
    /**
     * Sends a formatted error message to a player on their region thread.
     * 
     * <p>Thread Safety: This method schedules the message on the player's
     * correct region thread, making it safe to call from any thread.</p>
     *
     * @param player the player to send the message to
     * @param message the error message
     */
    public void sendError(Player player, String message) {
        scheduler.runForPlayer(player, () -> {
            if (player.isOnline()) {
                player.sendMessage(formatError(message));
            }
        });
    }
    
    /**
     * Sends a formatted success message to a player on their region thread.
     * 
     * <p>Thread Safety: This method schedules the message on the player's
     * correct region thread, making it safe to call from any thread.</p>
     *
     * @param player the player to send the message to
     * @param message the success message
     */
    public void sendSuccess(Player player, String message) {
        scheduler.runForPlayer(player, () -> {
            if (player.isOnline()) {
                player.sendMessage(formatSuccess(message));
            }
        });
    }
}
