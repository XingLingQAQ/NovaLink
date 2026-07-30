package com.nova.chat.bungee.chat;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.format.DurationFormatter;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.chat.MentionNotifier;
import net.md_5.bungee.api.chat.BaseComponent;
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
    private final NovaChatConfig config;
    private final MessageFormatter messageFormatter;
    
    /** Player chat states indexed by UUID */
    private final Map<UUID, PlayerChannelState> playerStates = new ConcurrentHashMap<>();

    /**
     * UUIDs of players already shown the first-join welcome line this proxy
     * session (UX-DESIGN §8.1). BungeeCord has no hasPlayedBefore, so the
     * welcome is gated by this memory set and cleared on disconnect.
     */
    private final java.util.Set<UUID> welcomedPlayers = ConcurrentHashMap.newKeySet();
    
    /** Global chat mode from configuration */
    private ChatMode globalMode;
    
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

        ChannelResponseTracker tracker = plugin.getNetworkClient().getChannelResponseTracker();
        ChannelResponseTracker.PendingChannelAction pending = tracker.consume(packet.getRequestId());

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

        ProxiedPlayer player = plugin.getProxy().getPlayer(pending.getPlayerId());
        if (player == null) {
            return;
        }

        if (packet.isSuccess()) {
            // §7: the immediate join receipt is the optimistic "正在加入频道 X…";
            // confirm here once the backend accepts so the player sees the join land.
            if (packet.getAction() == ChannelAction.JOIN) {
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
            // Bungee is a proxy and has no stable action-bar API that reliably
            // reaches the downstream client. TODO: revisit if a reliable proxy
            // action-bar path becomes available.
            return;
        }

        String code = packet.getErrorCode();
        // BUG-H2: backend rejected the JOIN — roll back the optimistic
        // active-channel switch ChannelCommandService.join made at send time.
        rollbackJoinIfNeeded(packet, pending);
        if (code == null || code.isEmpty() || ErrorCode.SERVICE_UNAVAILABLE.getCode().equals(code)) {
            // Network-down is already reported at command send time; skip double prompt.
            return;
        }
        player.sendMessage(messageFormatter.formatError(ErrorMessageFormatter.format(code)));
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
     * (UX-DESIGN §5). Bungee has no reliable proxy title/action-bar API, so the
     * notice is a chat message only. Falls back silently when the target is
     * offline or the response lacks the {@code targetId} extra.
     *
     * <p>BUG-H1: the operator name and mute duration are read from the pending
     * context captured at send time (the backend never echoes them); the
     * response extras are only consulted as a fallback for backend pushes with
     * no local pending (operator on another server), in which case the
     * "管理员"/"一段时间" fallbacks intentionally apply.
     */
    private void notifyKickMuteTarget(ChannelActionResponsePacket packet,
                                      ChannelResponseTracker.PendingChannelAction pending) {
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
        ProxiedPlayer target = plugin.getProxy().getPlayer(targetId);
        if (target == null) {
            return; // not on this proxy
        }
        String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
        String operator = pending != null ? pending.getOperatorName() : null;
        if (operator == null || operator.isEmpty()) {
            operator = packet.getExtra("operatorName");
        }
        if (operator == null || operator.isEmpty()) {
            operator = "管理员";
        }
        if (packet.getAction() == ChannelAction.KICK) {
            target.sendMessage(messageFormatter.parseColors(
                    "&c你已被 " + operator + " 踢出频道 " + channelId));
            return;
        }
        // MUTE
        String durationText = resolveDurationText(packet, pending);
        target.sendMessage(messageFormatter.parseColors(
                "&c你已被禁言 " + durationText + "（频道 " + channelId + "）"));
    }

    /**
     * Resolves the mute duration text for the target notice (BUG-H1). Prefers
     * the seconds value captured at send time on the pending context; falls back
     * to the response {@code duration} extra (seconds) for backend pushes with
     * no local pending; finally falls back to {@code "一段时间"}.
     */
    private String resolveDurationText(ChannelActionResponsePacket packet,
                                       ChannelResponseTracker.PendingChannelAction pending) {
        String seconds = pending != null ? pending.getDurationSeconds() : null;
        if (seconds == null || seconds.isEmpty()) {
            seconds = packet.getExtra("duration");
        }
        return DurationFormatter.formatSeconds(seconds);
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
     * <p><b>Proxy degradation:</b> BungeeCord's {@link ProxiedPlayer} has no native
     * title or sound API (no Adventure {@code Audience}). Sending a raw
     * {@code net.md_5.bungee.protocol.packet.Title} / sound packet would couple us
     * to unstable internal protocol classes, so we degrade to an in-chat
     * highlighted notification. The {@link MentionPacket} itself still transits
     * the proxy transparently (it is registered and handled here, not swallowed),
     * so backends that want to render the title themselves via a client plugin
     * can still do so.
     */
    private void handleMention(MentionPacket packet) {
        UUID mentionedId = packet.getMentionedId();
        if (mentionedId == null) {
            return;
        }
        ProxiedPlayer player = plugin.getProxy().getPlayer(mentionedId);
        if (player == null) {
            return;
        }
        String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
        String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
        String text = "&e" + mentioner + " &7在频道 &b" + channelId + " &7提到了你";
        player.sendMessage(messageFormatter.parseColors(text));
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
     * Cleans up player state.
     *
     * @param event the disconnect event
     */
    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        playerStates.remove(playerId);
        welcomedPlayers.remove(playerId);
        plugin.debug("Removed chat state for " + event.getPlayer().getName());
    }

    /**
     * Pushes the shared first-join welcome line once per proxy session to the
     * given player (UX-DESIGN §8.1). No-op if already welcomed this session.
     * Intended to be called from {@code ServerSwitchHandler} on the player's
     * first server connection (previous server == null).
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
            player.sendMessage(messageFormatter.formatError("未连接到聊天服务器"));
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
