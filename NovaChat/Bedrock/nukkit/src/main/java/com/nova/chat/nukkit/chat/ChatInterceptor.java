package com.nova.chat.nukkit.chat;

import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.itemdisplay.ItemDisplayMessages;
import com.nova.chat.client.itemdisplay.ItemDisplayTokens;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.config.NovaChatConfig;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.common.chat.MentionNotifier;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts player chat events and forwards messages to the NovaLink backend.
 * Supports HYBRID and REPLACE modes for vanilla chat compatibility.
 * 
 * Adapted from Bukkit version for Nukkit API.
 * 
 * Requirements: 11.1, 11.2, 23.4
 */
public class ChatInterceptor implements Listener {
    
    private final NovaChatNukkit plugin;
    private final NovaChatConfig config;
    private final MentionNotifier mentionNotifier = new MentionNotifier();
    
    /** Message formatter for color codes and placeholders */
    private final MessageFormatter messageFormatter;

    /** Shared [item]/[i] token detection + per-player cooldown (client-core). */
    private final ItemDisplayTokens itemDisplayTokens = new ItemDisplayTokens();
    
    /** Player chat states indexed by UUID (shared store). */
    private final com.nova.chat.client.state.PlayerStateStore playerStates =
            new com.nova.chat.client.state.PlayerStateStore();

    /** Shared response dispatcher (DUP-3); created in {@link #registerIncomingMessageHandler()}. */
    private ChannelResponseDispatcher dispatcher;

    /**
     * UUIDs of players already shown the first-join welcome line this session
     * (UX-DESIGN §8.1). Nukkit exposes no reliable {@code hasPlayedBefore}, so
     * we track "welcomed this session" in memory and clear it on quit.
     */
    private final java.util.Set<UUID> welcomedPlayers = ConcurrentHashMap.newKeySet();
    
    /** Global chat mode from configuration */
    private volatile ChatMode globalMode;
    
    /**
     * Creates a new ChatInterceptor.
     *
     * @param plugin the plugin instance
     */
    public ChatInterceptor(NovaChatNukkit plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();
        this.messageFormatter = new MessageFormatter(plugin);
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
                new NukkitChannelResponseAdapter());
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
     * receiver-side ignore filter, reply tracking). Hops to the Nukkit main
     * thread before touching the player API, mirroring
     * {@link #handleItemDisplay}.
     */
    private void handlePrivateMessage(com.nova.chat.common.protocol.packets.PrivateMessagePacket packet) {
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            var deliveries = plugin.getPrivateMessageService().handleIncoming(
                    packet,
                    id -> findOnlinePlayer(id) != null,
                    plugin.getIgnoreListService());
            for (com.nova.chat.client.privatemsg.PrivateMessageService.Delivery delivery : deliveries) {
                Player player = findOnlinePlayer(delivery.getPlayerId());
                if (player != null) {
                    player.sendMessage(messageFormatter.translateColorCodes(delivery.getLine()));
                }
            }
        });
    }

    /** Finds an online player by UUID (Nukkit has no direct UUID lookup on Server). */
    private Player findOnlinePlayer(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        for (Player player : plugin.getServer().getOnlinePlayers().values()) {
            if (playerId.equals(player.getUniqueId())) {
                return player;
            }
        }
        return null;
    }

    /**
     * Handles an inbound item display packet ({@code [item]}/{@code [i]} play,
     * packet 0x10) by rendering one chat line to every player whose active
     * channel matches the packet channel.
     *
     * <p>Receive-side semantics are "receive = render", matching the Bedrock
     * clients (pmmp/endstone/levilamina); the backend currently registers no
     * route for this packet. Hops to the Nukkit main thread before touching
     * the player API, mirroring {@link #handleTitle}. Bedrock clients have no
     * hover component, so the line is plain (color-coded) text.
     */
    private void handleItemDisplay(ItemDisplayPacket packet) {
        String channelId = packet.getChannelId();
        String senderName = packet.getSenderName();
        String itemJson = packet.getItemJson();
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers().values()) {
                // Skip senders the viewer has ignored (/nc ignore)
                com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
                if (ignoreService != null && ignoreService.isIgnored(player.getUniqueId(), senderName)) {
                    continue;
                }
                PlayerChannelState state = getState(player.getUniqueId());
                if (state != null && channelId != null && channelId.equals(state.getActiveChannel())) {
                    player.sendMessage(messageFormatter.translateColorCodes(
                            ItemDisplayMessages.formatLine(player.getUniqueId(), senderName, itemJson)));
                }
            }
        });
    }

    /**
     * Handles title packets by displaying them to players whose active channel
     * matches the packet channel (Requirements 15.1, 15.5). Hops to the Nukkit
     * main thread before touching the player API, mirroring
     * {@link #handleIncomingMessage}.
     */
    private void handleTitle(TitlePacket packet) {
        String channelId = packet.getChannelId();
        String title = packet.getTitle();
        String subtitle = packet.getSubtitle();
        int fadeIn = packet.getFadeIn();
        int stay = packet.getStay();
        int fadeOut = packet.getFadeOut();
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            String renderedTitle = messageFormatter.translateColorCodes(title != null ? title : "");
            String renderedSubtitle = messageFormatter.translateColorCodes(subtitle != null ? subtitle : "");
            for (Player player : plugin.getServer().getOnlinePlayers().values()) {
                PlayerChannelState state = getState(player.getUniqueId());
                if (state != null && channelId != null && channelId.equals(state.getActiveChannel())) {
                    player.sendTitle(renderedTitle, renderedSubtitle, fadeIn, stay, fadeOut);
                }
            }
        });
    }

    /** Legacy color prefix applied to @name mentions when rendering chat (UX-DESIGN §4.2). */
    static final String MENTION_HIGHLIGHT_COLOR = MentionNotifier.DEFAULT_HIGHLIGHT_COLOR;

    /**
     * Handles channel action responses from the backend by delegating the
     * shared "consume pending → route success/failure" skeleton to the shared
     * {@link ChannelResponseDispatcher} (DUP-3). The platform adapter owns the
     * Nukkit main-thread hops, rendering and the §7 action-bar flash.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        plugin.debug("Received channel action response: " + packet);
        // Private-message rejections are unsolicited (no pending context in the
        // shared tracker); the dispatcher would drop them, so route them to the
        // PrivateMessageService for player-locale rendering instead.
        if (com.nova.chat.client.privatemsg.PrivateMessageService.isPrivateMessageError(packet)) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () ->
                    plugin.getPrivateMessageService()
                            .renderError(packet, id -> findOnlinePlayer(id) != null)
                            .ifPresent(delivery -> {
                                Player player = findOnlinePlayer(delivery.getPlayerId());
                                if (player != null) {
                                    player.sendMessage(messageFormatter.translateColorCodes(delivery.getLine()));
                                }
                            }));
            return;
        }
        dispatcher.handle(packet);
    }

    /**
     * Nukkit-specific {@link ChannelResponseDispatcher.ChannelResponseAdapter}.
     * Player lookup / message / title sending hop to the main thread; the
     * KICK/MUTE notice uses a title plus an action-bar reinforcement.
     */
    private final class NukkitChannelResponseAdapter implements ChannelResponseDispatcher.ChannelResponseAdapter {

        @Override
        public void setActiveChannel(UUID playerId, String channelId) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                PlayerChannelState state = getState(playerId);
                if (state != null) {
                    state.setActiveChannel(channelId);
                }
            });
        }

        @Override
        public void rollbackJoin(UUID playerId, String attemptedChannel, String previousChannel) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
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
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                plugin.getMessageHelper().sendSuccess(player,
                        PlayerMessages.joined(playerId, channelId));
            });
        }

        @Override
        public void sendLeaveSuccess(UUID playerId, String channelId) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                plugin.getMessageHelper().sendSuccess(player,
                        PlayerMessages.left(playerId, channelId, config.getDefaultChannel()));
            });
        }

        @Override
        public void sendJoinChannelStatusBar(UUID playerId, String channelId) {
            if (channelId == null || channelId.isEmpty()) {
                return;
            }
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                sendChannelStatusBar(player, channelId);
            });
        }

        @Override
        public void sendLeaveChannelStatusBar(UUID playerId) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                PlayerChannelState state = getState(playerId);
                sendChannelStatusBar(player, state != null ? state.getActiveChannel() : null);
            });
        }

        @Override
        public void sendErrorMessage(UUID playerId, String text) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                plugin.getMessageHelper().sendError(player, text);
            });
        }

        @Override
        public void sendWhoResult(UUID playerId, String channelId, String displayName,
                                  String membersCsv, String memberCount) {
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(playerId).orElse(null);
                if (player == null) {
                    return;
                }
                String text = WhoCommandService.formatMemberList(
                        playerId, channelId, displayName, membersCsv, memberCount);
                for (String line : text.split("\n")) {
                    if (!line.isEmpty()) {
                        player.sendMessage(messageFormatter.translateColorCodes(line));
                    }
                }
            });
        }

        @Override
        public void notifyKickMuteTarget(ChannelResponseDispatcher.KickMuteNotice notice) {
            final String operator = notice.getOperator();
            final String durationText = notice.getDurationText();
            plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
                Player target = plugin.getServer().getPlayer(notice.getTargetId()).orElse(null);
                if (target == null) {
                    return; // not on this server
                }
                UUID targetId = target.getUniqueId();
                String channelId = notice.getChannelId();
                if (notice.getAction() == ChannelAction.KICK) {
                    String title = messageFormatter.translateColorCodes(
                            I18n.tr(targetId, "chat.notice.kick_title"));
                    String subtitle = messageFormatter.translateColorCodes(
                            I18n.tr(targetId, "chat.notice.kick_subtitle", operator, channelId));
                    target.sendTitle(title, subtitle,
                            MentionNotifier.DEFAULT_FADE_IN, MentionNotifier.DEFAULT_STAY, MentionNotifier.DEFAULT_FADE_OUT);
                    target.sendActionBar(messageFormatter.translateColorCodes(
                            I18n.tr(targetId, "chat.notice.kick_actionbar", operator, channelId)));
                    return;
                }
                // MUTE
                String title = messageFormatter.translateColorCodes(
                        I18n.tr(targetId, "chat.notice.mute_title"));
                String subtitle = messageFormatter.translateColorCodes(
                        I18n.tr(targetId, "chat.notice.mute_subtitle", channelId, durationText));
                target.sendTitle(title, subtitle,
                        MentionNotifier.DEFAULT_FADE_IN, MentionNotifier.DEFAULT_STAY, MentionNotifier.DEFAULT_FADE_OUT);
                target.sendActionBar(messageFormatter.translateColorCodes(
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
    private void sendChannelStatusBar(Player player, String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return;
        }
        PlayerChannelState state = getState(player.getUniqueId());
        ChatMode mode = (state != null) ? state.getChatMode() : null;
        if (mode == null) {
            mode = globalMode;
        }
        String text = PlayerMessages.currentChannelBar(player.getUniqueId(), channelId, mode);
        player.sendActionBar(messageFormatter.translateColorCodes(text));
    }

    /**
     * Handles incoming chat messages from the backend.
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
        
        // Format and send message to all players in the channel
        // Run on main thread for Nukkit API calls
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers().values()) {
                // Skip senders the recipient has ignored (/nc ignore)
                com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
                if (ignoreService != null && ignoreService.isIgnored(player.getUniqueId(), senderName)) {
                    continue;
                }
                // Check if player is in this channel
                PlayerChannelState state = getState(player.getUniqueId());
                if (state != null && channelId.equals(state.getActiveChannel())) {
                    String formattedMessage = messageFormatter.formatChatMessage(
                        player, channelId, channelName, senderName,
                        MentionNotifier.highlightMentions(content, MENTION_HIGHLIGHT_COLOR),
                        placeholders
                    );
                    player.sendMessage(formattedMessage);
                }
            }
        });
    }

    /**
     * Handles a mention notification packet by showing a title (and action-bar
     * fallback) to the mentioned player on the Nukkit main thread
     * (UX-DESIGN §4.1, Requirements 11.2).
     *
     * <p>Sound is intentionally omitted here: the Nukkit compile API surface used
     * by this module has no stable {@code playSound} entry point and there is no
     * existing in-repo precedent. The title + action-bar pair provides a clear,
     * non-spammy notification.
     */
    private void handleMention(MentionPacket packet) {
        UUID mentionedId = packet.getMentionedId();
        UUID mentionerId = packet.getMentionerId();
        if (mentionedId == null || mentionerId == null) {
            return;
        }
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(mentionedId).orElse(null);
            if (player == null) {
                return;
            }
            // Ignored mentioner: no title, no action bar (/nc ignore)
            com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
            if (ignoreService != null && ignoreService.isIgnored(mentionedId, packet.getMentionerName())) {
                return;
            }
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
                player.sendActionBar(messageFormatter.translateColorCodes(
                        "&e" + mentioner + " " + I18n.tr(mentionedId, "chat.mention.subtitle", channelId)));
            });
        });
    }

    /**
     * Handles player chat events.
     * In REPLACE mode, cancels the event and forwards to the channel.
     * In HYBRID mode, allows vanilla chat to proceed normally.
     *
     * @param event the chat event
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
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
        
        // In REPLACE mode, cancel vanilla chat and forward to channel
        event.setCancelled(true);
        
        // Check if connected to backend
        if (!plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(formatError(I18n.tr(player.getUniqueId(), "chat.network.not_connected_retry")));
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
     * Handles player join events to initialize chat state.
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        getOrCreateState(player);
        plugin.debug("Initialized chat state for " + player.getName());

        // UX-DESIGN §8.1: push the shared welcome line once per session to
        // first-time players. Nukkit has no hasPlayedBefore, so a session
        // memory set gates the push; it is cleared on quit below.
        if (welcomedPlayers.add(player.getUniqueId())) {
            plugin.getMessageHelper().sendRawMessage(player,
                    com.nova.chat.client.command.WelcomeMessageService.getWelcomeLine());
            plugin.debug("Sent first-join welcome to " + player.getName());
        }
    }

    /**
     * Handles player quit events to clean up chat state.
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        playerStates.remove(playerId);
        welcomedPlayers.remove(playerId);
        // Reply-target cleanup for /nc r (private messages); thread-safe map.
        if (plugin.getPrivateMessageService() != null) {
            plugin.getPrivateMessageService().onPlayerQuit(playerId);
        }
        plugin.debug("Removed chat state for " + event.getPlayer().getName());
    }
    
    /**
     * Sends a message to a specific channel.
     *
     * @param player the sending player
     * @param channelId the target channel ID
     * @param message the message content
     */
    public void sendToChannel(Player player, String channelId, String message) {
        if (!plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(formatError(I18n.tr(player.getUniqueId(), "chat.network.not_connected")));
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
        packet.addPlaceholder("world", player.getLevel().getName());
        
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
     * sends the air payload (renders the "Empty" placeholder). The per-player
     * cooldown lives in the shared {@link ItemDisplayTokens}. Only display
     * fields (id/count/custom name) are serialized — never full NBT.
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
     * held item. Nukkit's {@code cn.nukkit.item.Item} exposes no namespaced id,
     * so a {@code minecraft:*} id is derived from the vanilla display name
     * (e.g. "Netherite Sword" → {@code minecraft:netherite_sword}), keeping the
     * payload shape aligned with the protocol golden samples. Empty hand → air
     * payload, matching the Bedrock renderers' "Empty" placeholder behavior.
     */
    private String buildMainHandItemJson(Player player) {
        cn.nukkit.item.Item hand = player.getInventory() != null
                ? player.getInventory().getItemInHand() : null;
        if (hand == null || hand.isNull() || hand.getId() == 0 || hand.getCount() <= 0) {
            return ItemDisplayTokens.emptyHandJson();
        }
        String baseName = hand.getName() != null ? hand.getName() : "";
        String id = baseName.isBlank()
                ? ""
                : "minecraft:" + baseName.toLowerCase(Locale.ROOT).replace(' ', '_');
        String customName = hand.hasCustomName() ? hand.getCustomName() : null;
        return ItemDisplayTokens.buildItemJson(id, hand.getCount(), customName);
    }
    
    /**
     * Gets or creates a player's chat state.
     *
     * @param player the player
     * @return the player's chat state
     */
    public PlayerChannelState getOrCreateState(Player player) {
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
    public ChatMode togglePlayerMode(Player player) {
        PlayerChannelState state = getOrCreateState(player);
        return state.toggleMode();
    }
    
    /**
     * Sets a player's active channel.
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(Player player, String channelId) {
        PlayerChannelState state = getOrCreateState(player);
        state.setActiveChannel(channelId);
    }
    
    /**
     * Gets a player's active channel.
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
     */
    public void reload() {
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        this.messageFormatter.reload();
        plugin.debug("ChatInterceptor reloaded, global mode: " + globalMode);
    }
    
    /**
     * Gets the message formatter.
     *
     * @return the message formatter
     */
    public MessageFormatter getMessageFormatter() {
        return messageFormatter;
    }
    
    /**
     * Formats an error message with the plugin prefix.
     *
     * @param message the error message
     * @return the formatted message
     */
    private String formatError(String message) {
        return config.getPrefix() + config.getErrorFormat().replace("{message}", message);
    }
}
