package com.nova.chat.velocity.chat;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens for player chat events on Velocity proxy.
 * Implements cancel-and-resend strategy to handle chat signing issues.
 * 
 * In Velocity, chat messages are signed by the client for security.
 * When we intercept and modify messages, we need to cancel the original
 * and resend as a system message to avoid signature verification failures.
 * 
 * Requirements: 23.2 - Handle chat signing issues
 */
public class ChatListener {
    
    private final NovaChatVelocity plugin;
    private final NovaChatConfig config;
    private final MessageFormatter messageFormatter;
    
    /** Player chat states indexed by UUID */
    private final Map<UUID, PlayerChannelState> playerStates = new ConcurrentHashMap<>();

    /**
     * UUIDs of players already shown the first-join welcome line this proxy
     * session (UX-DESIGN §8.1). Velocity has no hasPlayedBefore, so the welcome
     * is gated by this memory set and cleared on disconnect. Fires on the first
     * server a player connects to; server switches do not re-trigger it.
     */
    private final java.util.Set<UUID> welcomedPlayers = ConcurrentHashMap.newKeySet();
    
    /** Global chat mode from configuration */
    private ChatMode globalMode;
    
    /**
     * Creates a new ChatListener.
     *
     * @param plugin the plugin instance
     */
    public ChatListener(NovaChatVelocity plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        this.messageFormatter = new MessageFormatter(plugin);
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        
        // Register handler for incoming chat messages from backend
        registerIncomingMessageHandler();
    }
    
    /**
     * Registers the handler for incoming chat messages from the backend.
     */
    private void registerIncomingMessageHandler() {
        if (plugin.getNetworkClient() != null) {
            plugin.getNetworkClient().registerHandler(ChatMessagePacket.class, this::handleIncomingMessage);
            plugin.getNetworkClient().registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
            plugin.getNetworkClient().registerHandler(MentionPacket.class, this::handleMention);
        }
    }

    /** Legacy color prefix applied to @name mentions when rendering chat (UX-DESIGN §4.2). */
    static final String MENTION_HIGHLIGHT_COLOR = "&e";
    
    /**
     * Handles channel action responses from the backend.
     *
     * <p>Correlates the response back to the originating player via the shared
     * {@link ChannelResponseTracker} (the backend echoes the request id). On
     * failure, surfaces an actionable, formatted error via the shared
     * {@link ErrorCode} system. Network failures (NC-503) are already reported
     * at send time, so they are suppressed here to avoid a double message.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        plugin.debug("Received channel action response: " + packet);

        // UX-DESIGN §5: KICK/MUTE target-side notification. These responses may
        // arrive as backend pushes with no local pending request, so resolve the
        // target from the response's extras rather than the tracker.
        if (packet.isSuccess() && (packet.getAction() == com.nova.chat.common.protocol.ChannelAction.KICK
                || packet.getAction() == com.nova.chat.common.protocol.ChannelAction.MUTE)) {
            notifyKickMuteTarget(packet);
        }

        ChannelResponseTracker tracker = plugin.getNetworkClient().getChannelResponseTracker();
        ChannelResponseTracker.PendingChannelAction pending = tracker.consume(packet.getRequestId());
        if (pending == null || pending.getPlayerId() == null) {
            // No correlation context (e.g. console-issued or already expired) — nothing to render.
            return;
        }

        plugin.getServer().getPlayer(pending.getPlayerId()).ifPresent(player -> {
            if (packet.isSuccess()) {
                // §7: the immediate join receipt is the optimistic "正在加入频道 X…";
                // confirm here once the backend accepts so the player sees the join land.
                if (packet.getAction() == com.nova.chat.common.protocol.ChannelAction.JOIN) {
                    String confirmedChannel = (packet.getChannelId() != null && !packet.getChannelId().isEmpty())
                            ? packet.getChannelId()
                            : pending.getChannelId();
                    if (confirmedChannel != null && !confirmedChannel.isEmpty()) {
                        player.sendMessage(messageFormatter.formatSuccess("已加入频道 " + confirmedChannel));
                    }
                    if (packet.getChannelId() != null && !packet.getChannelId().isEmpty()) {
                        PlayerChannelState state = getState(pending.getPlayerId());
                        if (state != null) {
                            state.setActiveChannel(packet.getChannelId());
                        }
                    }
                }
                // §7 action bar ("当前频道：X（模式）") is intentionally not sent here:
                // Velocity is a proxy and has no stable action-bar API that reliably
                // reaches the downstream client. TODO: revisit if a reliable proxy
                // action-bar path becomes available.
                return;
            }

            String code = packet.getErrorCode();
            if (code == null || code.isEmpty() || ErrorCode.SERVICE_UNAVAILABLE.getCode().equals(code)) {
                // Network-down is already reported at command send time; skip double prompt.
                return;
            }
            player.sendMessage(messageFormatter.formatError(ErrorMessageFormatter.format(code)));
        });
    }

    /**
     * Sends a personalized kick/mute notice to the affected player
     * (UX-DESIGN §5). Proxy has no reliable action bar, so the notice is a
     * title plus a chat-message reinforcement. Falls back silently when the
     * target is offline or the response lacks the {@code targetId} extra.
     */
    private void notifyKickMuteTarget(ChannelActionResponsePacket packet) {
        String targetIdRaw = packet.getExtra("targetId");
        if (targetIdRaw == null || targetIdRaw.isEmpty()) {
            plugin.debug("KICK/MUTE response without targetId extra — cannot notify target: " + packet);
            return;
        }
        UUID targetId;
        try {
            targetId = UUID.fromString(targetIdRaw);
        } catch (IllegalArgumentException e) {
            plugin.debug("KICK/MUTE response has invalid targetId: " + targetIdRaw);
            return;
        }
        plugin.getServer().getPlayer(targetId).ifPresent(target -> {
            String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
            String operator = packet.getExtra("operatorName");
            if (operator == null || operator.isEmpty()) {
                operator = "管理员";
            }
            com.nova.chat.common.protocol.ChannelAction action = packet.getAction();
            if (action == com.nova.chat.common.protocol.ChannelAction.KICK) {
                Component title = Component.text("你已被踢出频道", NamedTextColor.RED);
                Component subtitle = Component.text()
                        .append(Component.text("被 ", NamedTextColor.GRAY))
                        .append(Component.text(operator, NamedTextColor.YELLOW))
                        .append(Component.text(" 踢出频道 ", NamedTextColor.GRAY))
                        .append(Component.text(channelId, NamedTextColor.AQUA))
                        .build();
                Title.Times times = Title.Times.times(
                        Duration.ofMillis(MentionNotifier.DEFAULT_FADE_IN * 50L),
                        Duration.ofMillis(MentionNotifier.DEFAULT_STAY * 50L),
                        Duration.ofMillis(MentionNotifier.DEFAULT_FADE_OUT * 50L));
                target.showTitle(Title.title(title, subtitle, times));
                target.sendMessage(messageFormatter.formatError(
                        "你已被 " + operator + " 踢出频道 " + channelId));
                return;
            }
            // MUTE
            String durationText = formatDurationExtra(packet.getExtra("duration"));
            Component title = Component.text("你已被禁言", NamedTextColor.RED);
            Component subtitle = Component.text()
                    .append(Component.text("在频道 ", NamedTextColor.GRAY))
                    .append(Component.text(channelId, NamedTextColor.AQUA))
                    .append(Component.text(" 持续 ", NamedTextColor.GRAY))
                    .append(Component.text(durationText, NamedTextColor.YELLOW))
                    .build();
            Title.Times times = Title.Times.times(
                    Duration.ofMillis(MentionNotifier.DEFAULT_FADE_IN * 50L),
                    Duration.ofMillis(MentionNotifier.DEFAULT_STAY * 50L),
                    Duration.ofMillis(MentionNotifier.DEFAULT_FADE_OUT * 50L));
            target.showTitle(Title.title(title, subtitle, times));
            target.sendMessage(messageFormatter.formatError(
                    "你已被禁言 " + durationText + "（频道 " + channelId + "）"));
        });
    }

    /** Formats a duration given as a seconds string, or "一段时间" if unknown. */
    private String formatDurationExtra(String durationSeconds) {
        if (durationSeconds == null || durationSeconds.isEmpty()) {
            return "一段时间";
        }
        try {
            long seconds = Long.parseLong(durationSeconds);
            if (seconds < 60) {
                return seconds + "秒";
            } else if (seconds < 3600) {
                return (seconds / 60) + "分钟";
            } else if (seconds < 86400) {
                return (seconds / 3600) + "小时";
            } else {
                return (seconds / 86400) + "天";
            }
        } catch (NumberFormatException e) {
            return "一段时间";
        }
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
        for (Player player : plugin.getServer().getAllPlayers()) {
            PlayerChannelState state = getState(player.getUniqueId());
            if (state != null && channelId.equals(state.getActiveChannel())) {
                Component formattedMessage = messageFormatter.formatChatMessage(
                    player, channelId, channelName, senderName,
                    MentionNotifier.highlightMentions(content, MENTION_HIGHLIGHT_COLOR),
                    placeholders
                );
                player.sendMessage(formattedMessage);
            }
        }
    }

    /**
     * Handles a mention notification packet by playing a sound and showing a
     * title to the mentioned player (UX-DESIGN §4.1, Requirements 11.2).
     *
     * <p>Velocity's {@link Player} is an Adventure {@code Audience}, so title and
     * sound play directly to the proxied client without a backend round-trip.
     */
    private void handleMention(MentionPacket packet) {
        UUID mentionedId = packet.getMentionedId();
        if (mentionedId == null) {
            return;
        }
        plugin.getServer().getPlayer(mentionedId).ifPresent(player -> {
            String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
            String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
            Component title = Component.text(mentioner, NamedTextColor.YELLOW);
            Component subtitle = Component.text()
                    .append(Component.text("在频道 ", NamedTextColor.GRAY))
                    .append(Component.text(channelId, NamedTextColor.AQUA))
                    .append(Component.text(" 提到了你", NamedTextColor.GRAY))
                    .build();
            Title.Times times = Title.Times.times(
                    Duration.ofMillis(MentionNotifier.DEFAULT_FADE_IN * 50L),
                    Duration.ofMillis(MentionNotifier.DEFAULT_STAY * 50L),
                    Duration.ofMillis(MentionNotifier.DEFAULT_FADE_OUT * 50L));
            player.showTitle(Title.title(title, subtitle, times));
            try {
                net.kyori.adventure.key.Key key = net.kyori.adventure.key.Key.key("minecraft",
                        MentionNotifier.DEFAULT_SOUND.toLowerCase(java.util.Locale.ROOT));
                player.playSound(Sound.sound(key, Sound.Source.PLAYER, 1.0f, 1.0f));
            } catch (Exception e) {
                plugin.debug("Failed to play mention sound: " + e.getMessage());
            }
        });
    }
    
    /**
     * Handles player chat events.
     * Uses cancel-and-resend strategy to handle chat signing issues.
     * 
     * In REPLACE mode:
     * 1. Cancel the original chat event (prevents signature verification issues)
     * 2. Forward the message to the NovaLink backend
     * 3. Backend broadcasts to all channel members
     * 
     * In HYBRID mode:
     * - Let vanilla chat proceed normally
     *
     * @param event the chat event
     */
    @Subscribe(order = PostOrder.EARLY)
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Get or create player state
        PlayerChannelState state = getOrCreateState(player);
        ChatMode effectiveMode = state.isModeOverridden() ? state.getChatMode() : globalMode;
        
        plugin.debug("Player " + player.getUsername() + " chat event, mode: " + effectiveMode + 
                    ", channel: " + state.getActiveChannel());
        
        // In HYBRID mode, let vanilla chat proceed
        if (effectiveMode == ChatMode.HYBRID) {
            plugin.debug("HYBRID mode: allowing vanilla chat for " + player.getUsername());
            return;
        }
        
        // In REPLACE mode, cancel the event and forward to channel
        // This is the cancel-and-resend strategy to handle chat signing
        event.setResult(PlayerChatEvent.ChatResult.denied());
        
        // Check if connected to backend
        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(messageFormatter.formatError("未连接到聊天服务器，请稍后再试"));
            return;
        }
        
        // Forward message to backend
        sendToChannel(player, state.getActiveChannel(), event.getMessage());
    }
    
    /**
     * Handles player server switch events.
     * Updates player state with new server information.
     *
     * @param event the server connected event
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        PlayerChannelState state = getOrCreateState(player);

        String serverName = event.getServer().getServerInfo().getName();
        state.setCurrentServer(serverName);

        plugin.debug("Player " + player.getUsername() + " connected to server: " + serverName);

        // UX-DESIGN §8.1: push the shared welcome line once per proxy session
        // to first-time players (no hasPlayedBefore on a proxy). Fires on the
        // first server the player joins; subsequent server switches are
        // ignored because the UUID is already in the set.
        if (welcomedPlayers.add(player.getUniqueId())) {
            player.sendMessage(messageFormatter.parseColors(
                    com.nova.chat.client.command.WelcomeMessageService.getWelcomeLine()));
            plugin.debug("Sent first-join welcome to " + player.getUsername());
        }

        // Notify backend about server switch for cross-server routing
        // The backend can use this to update player state
    }
    
    /**
     * Handles player disconnect events.
     * Cleans up player state.
     *
     * @param event the disconnect event
     */
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        playerStates.remove(playerId);
        welcomedPlayers.remove(playerId);
        plugin.debug("Removed chat state for " + event.getPlayer().getUsername());
    }
    
    /**
     * Sends a message to a specific channel.
     *
     * @param player the sending player
     * @param channelId the target channel ID
     * @param message the message content
     */
    public void sendToChannel(Player player, String channelId, String message) {
        if (plugin.getNetworkClient() == null || !plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(messageFormatter.formatError("未连接到聊天服务器"));
            return;
        }
        
        ChatMessagePacket packet = new ChatMessagePacket(
            player.getUniqueId(),
            player.getUsername(),
            config.getUsername(), // Client ID
            channelId,
            message
        );
        
        // Add basic placeholders
        packet.addPlaceholder("player", player.getUsername());
        packet.addPlaceholder("display_name", player.getUsername());
        
        // Add server info if available
        player.getCurrentServer().ifPresent(server -> 
            packet.addPlaceholder("server", server.getServerInfo().getName())
        );
        
        plugin.getNetworkClient().sendPacket(packet);
        plugin.debug("Sent message to channel " + channelId + ": " + message);
    }
    
    /**
     * Gets or creates a player's chat state.
     *
     * @param player the player
     * @return the player's chat state
     */
    public PlayerChannelState getOrCreateState(Player player) {
        return playerStates.computeIfAbsent(player.getUniqueId(), 
            uuid -> new PlayerChannelState(uuid, config.getDefaultChannel(), globalMode));
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
        return playerStates.get(playerId);
    }
    
    /**
     * Sets a player's chat state.
     *
     * @param playerId the player's UUID
     * @param state the chat state to set
     */
    public void setPlayerState(UUID playerId, PlayerChannelState state) {
        playerStates.put(playerId, state);
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
