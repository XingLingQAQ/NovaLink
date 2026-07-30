package com.nova.chat.sponge.chat;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.sponge.NovaChatSponge;
import com.nova.chat.sponge.config.NovaChatConfig;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.message.PlayerChatEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

/**
 * Intercepts player chat events and forwards messages to the NovaLink backend.
 * Uses Sponge PlayerChatEvent for chat interception.
 * Handles Sponge permission system.
 * 
 * Requirements: 3.3
 */
public class ChatListener {
    
    private final NovaChatSponge plugin;
    private final NovaChatConfig config;
    
    /** Player chat states indexed by UUID */
    private final Map<UUID, PlayerChannelState> playerStates = new ConcurrentHashMap<>();

    /**
     * UUIDs of players already shown the first-join welcome line this session
     * (UX-DESIGN §8.1). Sponge 8 does not expose a reliable hasPlayedBefore
     * here, so the welcome is gated by this memory set and cleared on
     * disconnect.
     */
    private final java.util.Set<UUID> welcomedPlayers = ConcurrentHashMap.newKeySet();
    
    /** Global chat mode from configuration */
    private ChatMode globalMode;
    
    /**
     * Creates a new ChatListener.
     *
     * @param plugin the plugin instance
     */
    public ChatListener(NovaChatSponge plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        
        // Register handler for incoming chat messages from backend
        registerIncomingMessageHandler();
    }
    
    /**
     * Registers the handler for incoming chat messages from the backend.
     */
    private void registerIncomingMessageHandler() {
        plugin.getNetworkClient().registerHandler(ChatMessagePacket.class, this::handleIncomingMessage);
        plugin.getNetworkClient().registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
        plugin.getNetworkClient().registerHandler(MentionPacket.class, this::handleMention);
    }

    /** Legacy color prefix applied to @name mentions when rendering chat (UX-DESIGN §4.2). */
    static final String MENTION_HIGHLIGHT_COLOR = "&e";

    /**
     * Handles channel action responses from the backend.
     *
     * <p>Correlates the response back to the originating player via the shared
     * {@link ChannelResponseTracker}. On failure, surfaces an actionable, formatted
     * error via the shared {@link ErrorCode} system; NC-503 (network down) is already
     * reported at send time and is suppressed here to avoid a double message.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        plugin.debug("Received channel action response: " + packet);

        // UX-DESIGN §5: KICK/MUTE target-side notification. These responses may
        // arrive as backend pushes with no local pending request, so resolve the
        // target from the response's extras rather than the tracker.
        if (packet.isSuccess() && (packet.getAction() == ChannelAction.KICK
                || packet.getAction() == ChannelAction.MUTE)) {
            notifyKickMuteTarget(packet);
        }

        ChannelResponseTracker tracker = plugin.getNetworkClient().getChannelResponseTracker();
        ChannelResponseTracker.PendingChannelAction pending = tracker.consume(packet.getRequestId());
        if (pending == null || pending.getPlayerId() == null) {
            return;
        }

        // Hop to the plugin executor for safe player lookup / message sending.
        Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
            Optional<ServerPlayer> opt = Sponge.server().player(pending.getPlayerId());
            if (opt.isEmpty()) {
                return;
            }
            ServerPlayer player = opt.get();

            if (packet.isSuccess()) {
                // §7: the immediate join receipt is the optimistic "正在加入频道 X…";
                // confirm here once the backend accepts, then flash a short action
                // bar so the player sees the active channel + current mode.
                if (packet.getAction() == ChannelAction.JOIN) {
                    String confirmedChannel = (packet.getChannelId() != null && !packet.getChannelId().isEmpty())
                            ? packet.getChannelId()
                            : pending.getChannelId();
                    if (confirmedChannel != null && !confirmedChannel.isEmpty()) {
                        player.sendMessage(plugin.getMessageFormatter().formatSuccess("已加入频道 " + confirmedChannel));
                    }
                    if (packet.getChannelId() != null && !packet.getChannelId().isEmpty()) {
                        PlayerChannelState state = getState(pending.getPlayerId());
                        if (state != null) {
                            state.setActiveChannel(packet.getChannelId());
                        }
                    }
                    sendChannelStatusBar(player, confirmedChannel);
                } else if (packet.getAction() == ChannelAction.LEAVE) {
                    // §7: after a successful leave the active channel is the default;
                    // flash the action bar so the player sees where they landed.
                    PlayerChannelState state = getState(pending.getPlayerId());
                    String current = (state != null) ? state.getActiveChannel() : null;
                    sendChannelStatusBar(player, current);
                }
                return;
            }

            String code = packet.getErrorCode();
            // BUG-H2: backend rejected the JOIN — roll back the optimistic
            // active-channel switch ChannelCommandService.join made at send time.
            rollbackJoinIfNeeded(packet, pending);
            if (code == null || code.isEmpty() || ErrorCode.SERVICE_UNAVAILABLE.getCode().equals(code)) {
                return;
            }
            player.sendMessage(plugin.getMessageFormatter().formatError(ErrorMessageFormatter.format(code)));
        });
    }

    /**
     * Restores the player's pre-join active channel when the backend rejects a
     * JOIN (BUG-H2). Only rolls back for JOIN responses whose pending context
     * carries a non-blank {@code previousChannel} and whose optimistic channel
     * is still set on the state.
     */
    private void rollbackJoinIfNeeded(ChannelActionResponsePacket packet,
                                      ChannelResponseTracker.PendingChannelAction pending) {
        if (packet.getAction() != ChannelAction.JOIN) {
            return;
        }
        String previousChannel = pending.getPreviousChannel();
        if (previousChannel == null || previousChannel.isEmpty()) {
            return;
        }
        PlayerChannelState state = getState(pending.getPlayerId());
        if (state == null) {
            return;
        }
        String current = state.getActiveChannel();
        if (current != null && current.equals(pending.getChannelId())) {
            state.setActiveChannel(previousChannel);
        }
    }

    /**
     * Sends a personalized kick/mute notice to the affected player
     * (UX-DESIGN §5). Runs on the plugin executor for safe player lookup.
     * Falls back silently when the target is offline or the response lacks the
     * {@code targetId} extra (TODO logged).
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
        Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
            Optional<ServerPlayer> opt = Sponge.server().player(targetId);
            if (opt.isEmpty()) {
                return; // not on this server
            }
            ServerPlayer target = opt.get();
            String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
            String operator = packet.getExtra("operatorName");
            if (operator == null || operator.isEmpty()) {
                operator = "管理员";
            }
            Title.Times times = Title.Times.times(
                    Duration.ofMillis(MentionNotifier.DEFAULT_FADE_IN * 50L),
                    Duration.ofMillis(MentionNotifier.DEFAULT_STAY * 50L),
                    Duration.ofMillis(MentionNotifier.DEFAULT_FADE_OUT * 50L));
            if (packet.getAction() == ChannelAction.KICK) {
                Component title = Component.text("你已被踢出频道", NamedTextColor.RED);
                Component subtitle = Component.text()
                        .append(Component.text("被 ", NamedTextColor.GRAY))
                        .append(Component.text(operator, NamedTextColor.YELLOW))
                        .append(Component.text(" 踢出频道 ", NamedTextColor.GRAY))
                        .append(Component.text(channelId, NamedTextColor.AQUA))
                        .build();
                target.showTitle(Title.title(title, subtitle, times));
                target.sendActionBar(Component.text()
                        .append(Component.text("你已被 ", NamedTextColor.RED))
                        .append(Component.text(operator, NamedTextColor.YELLOW))
                        .append(Component.text(" 踢出频道 " + channelId, NamedTextColor.RED))
                        .build());
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
            target.showTitle(Title.title(title, subtitle, times));
            target.sendActionBar(Component.text()
                    .append(Component.text("你已被禁言 ", NamedTextColor.RED))
                    .append(Component.text(durationText, NamedTextColor.YELLOW))
                    .append(Component.text("（频道 " + channelId + "）", NamedTextColor.RED))
                    .build());
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
     * Flashes a one-shot action bar with the player's current channel and chat
     * mode after a successful join/leave (UX-DESIGN §7). Vanilla action-bar
     * fade handles the ~3s dismissal; no polling.
     *
     * @param player the recipient
     * @param channelId the channel to display; if null/blank, nothing is sent
     */
    private void sendChannelStatusBar(ServerPlayer player, String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return;
        }
        PlayerChannelState state = getState(player.uniqueId());
        ChatMode mode = (state != null) ? state.getChatMode() : null;
        String modeName = (mode == ChatMode.REPLACE) ? "频道模式" : "混合模式";
        Component bar = Component.text()
                .append(Component.text("当前频道：", NamedTextColor.GRAY))
                .append(Component.text(channelId, NamedTextColor.AQUA))
                .append(Component.text("（" + modeName + "）", NamedTextColor.GRAY))
                .build();
        player.sendActionBar(bar);
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
        
        // Format and send message to players in this channel
        Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
            for (ServerPlayer player : Sponge.server().onlinePlayers()) {
                // Check if player is in this channel
                PlayerChannelState state = getState(player.uniqueId());
                if (state != null && channelId.equals(state.getActiveChannel())) {
                    Component formattedMessage = plugin.getMessageFormatter().formatChatMessage(
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
     * Handles a mention notification packet by playing a sound and showing a
     * title to the mentioned player (UX-DESIGN §4.1, Requirements 11.2).
     */
    private void handleMention(MentionPacket packet) {
        UUID mentionedId = packet.getMentionedId();
        if (mentionedId == null) {
            return;
        }
        Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
            Optional<ServerPlayer> opt = Sponge.server().player(mentionedId);
            if (opt.isEmpty()) {
                return;
            }
            ServerPlayer player = opt.get();
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
            playMentionSound(player);
        });
    }

    /**
     * Plays the default mention notification sound to a player. Uses Adventure's
     * keyed-sound API so the exact {@code ENTITY_EXPERIENCE_ORB_PICKUP} sound is
     * resolved at play time.
     */
    private void playMentionSound(ServerPlayer player) {
        try {
            net.kyori.adventure.key.Key key =
                    net.kyori.adventure.key.Key.key("minecraft",
                            MentionNotifier.DEFAULT_SOUND.toLowerCase(java.util.Locale.ROOT));
            Sound sound = Sound.sound(key, Sound.Source.PLAYER, 1.0f, 1.0f);
            player.playSound(sound);
        } catch (Exception e) {
            plugin.debug("Failed to play mention sound: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handles player chat events.
     * In REPLACE mode, cancels the event and forwards to the channel.
     * In HYBRID mode, allows vanilla chat to proceed normally.
     *
     * @param event the chat event
     * @param player the player who sent the message
     */
    @Listener(order = Order.LAST)
    public void onPlayerChat(PlayerChatEvent event, @First ServerPlayer player) {
        UUID playerId = player.uniqueId();
        
        // Get or create player state
        PlayerChannelState state = getOrCreateState(player);
        ChatMode effectiveMode = state.isModeOverridden() ? state.getChatMode() : globalMode;
        
        plugin.debug("Player " + player.name() + " chat event, mode: " + effectiveMode + 
                    ", channel: " + state.getActiveChannel());
        
        // In HYBRID mode, let vanilla chat proceed
        if (effectiveMode == ChatMode.HYBRID) {
            plugin.debug("HYBRID mode: allowing vanilla chat for " + player.name());
            return;
        }
        
        // In REPLACE mode, cancel vanilla chat and forward to channel
        event.setCancelled(true);
        
        // Check if connected to backend
        if (!plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(formatError("未连接到聊天服务器，请稍后再试"));
            return;
        }
        
        // Get message content
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        
        // Forward message to backend
        sendToChannel(player, state.getActiveChannel(), message);
    }
    
    /**
     * Handles player join events to initialize chat state.
     *
     * @param event the join event
     */
    @Listener
    public void onPlayerJoin(ServerSideConnectionEvent.Join event) {
        ServerPlayer player = event.player();
        getOrCreateState(player);
        plugin.debug("Initialized chat state for " + player.name());

        // UX-DESIGN §8.1: push the shared welcome line once per session to
        // first-time players. Single non-intrusive chat line, no title.
        if (welcomedPlayers.add(player.uniqueId())) {
            player.sendMessage(plugin.getMessageFormatter().formatMessage(
                    com.nova.chat.client.command.WelcomeMessageService.getWelcomeLine()));
            plugin.debug("Sent first-join welcome to " + player.name());
        }
    }

    /**
     * Handles player disconnect events to clean up chat state.
     *
     * @param event the disconnect event
     */
    @Listener
    public void onPlayerDisconnect(ServerSideConnectionEvent.Disconnect event) {
        ServerPlayer player = event.player();
        playerStates.remove(player.uniqueId());
        welcomedPlayers.remove(player.uniqueId());
        plugin.debug("Removed chat state for " + player.name());
    }
    
    /**
     * Sends a message to a specific channel.
     *
     * @param player the sending player
     * @param channelId the target channel ID
     * @param message the message content
     */
    public void sendToChannel(ServerPlayer player, String channelId, String message) {
        if (!plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(formatError("未连接到聊天服务器"));
            return;
        }
        
        ChatMessagePacket packet = new ChatMessagePacket(
            player.uniqueId(),
            player.name(),
            config.getUsername(), // Client ID
            channelId,
            message
        );
        
        // Add basic placeholders
        packet.addPlaceholder("player", player.name());
        packet.addPlaceholder("display_name", PlainTextComponentSerializer.plainText().serialize(player.displayName().get()));
        packet.addPlaceholder("world", player.world().key().value());
        
        plugin.getNetworkClient().sendPacket(packet);
        plugin.debug("Sent message to channel " + channelId + ": " + message);
    }
    
    /**
     * Gets or creates a player's chat state.
     *
     * @param player the player
     * @return the player's chat state
     */
    public PlayerChannelState getOrCreateState(ServerPlayer player) {
        return playerStates.computeIfAbsent(player.uniqueId(),
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
    public ChatMode togglePlayerMode(ServerPlayer player) {
        PlayerChannelState state = getOrCreateState(player);
        return state.toggleMode();
    }
    
    /**
     * Sets a player's active channel.
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(ServerPlayer player, String channelId) {
        PlayerChannelState state = getOrCreateState(player);
        state.setActiveChannel(channelId);
    }

    /**
     * Gets a player's active channel.
     *
     * @param player the player
     * @return the active channel ID
     */
    public String getPlayerChannel(ServerPlayer player) {
        PlayerChannelState state = getOrCreateState(player);
        return state.getActiveChannel();
    }
    
    /**
     * Reloads configuration settings.
     */
    public void reload() {
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        plugin.debug("ChatListener reloaded, global mode: " + globalMode);
    }
    
    /**
     * Formats an error message with the plugin prefix.
     *
     * @param message the error message
     * @return the formatted message component
     */
    private Component formatError(String message) {
        return plugin.getMessageFormatter().formatError(message);
    }
}
