package com.nova.chat.nukkit.chat;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
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
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.chat.MentionNotifier;

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
    
    /** Message formatter for color codes and placeholders */
    private final MessageFormatter messageFormatter;
    
    /** Player chat states indexed by UUID */
    private final Map<UUID, PlayerChannelState> playerStates = new ConcurrentHashMap<>();
    
    /** Global chat mode from configuration */
    private ChatMode globalMode;
    
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
     * reported at send time and is suppressed here to avoid a double message. Player
     * lookup / message sending run on the Nukkit main thread.
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

        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(pending.getPlayerId()).orElse(null);
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
                    if (confirmedChannel != null && !confirmedChannel.isEmpty()) {
                        plugin.getMessageHelper().sendSuccess(player, "已加入频道 " + confirmedChannel);
                    }
                    if (packet.getChannelId() != null && !packet.getChannelId().isEmpty()) {
                        PlayerChannelState state = getState(pending.getPlayerId());
                        if (state != null) {
                            state.setActiveChannel(packet.getChannelId());
                        }
                    }
                    sendChannelStatusBar(player, confirmedChannel);
                } else if (packet.getAction() == ChannelAction.LEAVE) {
                    // §7: after a successful leave the active channel is the default.
                    PlayerChannelState state = getState(pending.getPlayerId());
                    sendChannelStatusBar(player, state != null ? state.getActiveChannel() : null);
                }
                return;
            }
            String code = packet.getErrorCode();
            if (code == null || code.isEmpty() || ErrorCode.SERVICE_UNAVAILABLE.getCode().equals(code)) {
                return;
            }
            plugin.getMessageHelper().sendError(player, ErrorMessageFormatter.format(code));
        });
    }

    /**
     * Sends a personalized kick/mute notice to the affected player
     * (UX-DESIGN §5). Runs on the Nukkit main thread for safe player lookup.
     * Falls back to a chat message when the target cannot be resolved or the
     * response lacks the {@code targetId} extra (TODO logged).
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

        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            Player target = plugin.getServer().getPlayer(targetId).orElse(null);
            if (target == null) {
                return; // not on this server
            }
            String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
            String operator = packet.getExtra("operatorName");
            if (operator == null || operator.isEmpty()) {
                operator = "管理员";
            }
            if (packet.getAction() == ChannelAction.KICK) {
                String title = messageFormatter.translateColorCodes("&c你已被踢出频道");
                String subtitle = messageFormatter.translateColorCodes(
                        "&7被 &e" + operator + " &7踢出频道 &b" + channelId);
                target.sendTitle(title, subtitle,
                        MentionNotifier.DEFAULT_FADE_IN, MentionNotifier.DEFAULT_STAY, MentionNotifier.DEFAULT_FADE_OUT);
                target.sendActionBar(messageFormatter.translateColorCodes(
                        "&c你已被 " + operator + " 踢出频道 " + channelId));
                return;
            }
            // MUTE
            String durationText = formatDurationExtra(packet.getExtra("duration"));
            String title = messageFormatter.translateColorCodes("&c你已被禁言");
            String subtitle = messageFormatter.translateColorCodes(
                    "&7在频道 &b" + channelId + " &7持续 &e" + durationText);
            target.sendTitle(title, subtitle,
                    MentionNotifier.DEFAULT_FADE_IN, MentionNotifier.DEFAULT_STAY, MentionNotifier.DEFAULT_FADE_OUT);
            target.sendActionBar(messageFormatter.translateColorCodes(
                    "&c你已被禁言 " + durationText + "（频道 " + channelId + "）"));
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
    private void sendChannelStatusBar(Player player, String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return;
        }
        PlayerChannelState state = getState(player.getUniqueId());
        ChatMode mode = (state != null) ? state.getChatMode() : null;
        String modeName = (mode == ChatMode.REPLACE) ? "频道模式" : "混合模式";
        String text = "&7当前频道：&b" + channelId + " &7（" + modeName + "）";
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
        if (mentionedId == null) {
            return;
        }
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            Player player = plugin.getServer().getPlayer(mentionedId).orElse(null);
            if (player == null) {
                return;
            }
            String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
            String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
            String title = messageFormatter.translateColorCodes("&e" + mentioner);
            String subtitle = messageFormatter.translateColorCodes(
                    "&7在频道 &b" + channelId + " &7提到了你");
            player.sendTitle(title, subtitle,
                    MentionNotifier.DEFAULT_FADE_IN,
                    MentionNotifier.DEFAULT_STAY,
                    MentionNotifier.DEFAULT_FADE_OUT);
            player.sendActionBar(messageFormatter.translateColorCodes(
                    "&e" + mentioner + " &7在频道 &b" + channelId + " &7提到了你"));
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
            player.sendMessage(formatError("未连接到聊天服务器，请稍后再试"));
            return;
        }
        
        // Forward message to backend
        sendToChannel(player, state.getActiveChannel(), event.getMessage());
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
            player.sendMessage(formatError("未连接到聊天服务器"));
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
