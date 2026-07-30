package com.nova.chat.bungee.chat;

import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
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

    /** Shared response dispatcher (DUP-3); created in {@link #registerIncomingMessageHandler()}. */
    private ChannelResponseDispatcher dispatcher;

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
            this.dispatcher = new ChannelResponseDispatcher(
                    plugin.getNetworkClient().getChannelResponseTracker(),
                    new BungeeChannelResponseAdapter());
            plugin.getNetworkClient().registerHandler(ChatMessagePacket.class, this::handleIncomingMessage);
            plugin.getNetworkClient().registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
            plugin.getNetworkClient().registerHandler(MentionPacket.class, this::handleMention);
        }
    }

    /** Legacy color prefix applied to @name mentions when rendering chat (UX-DESIGN §4.2). */
    static final String MENTION_HIGHLIGHT_COLOR = MentionNotifier.DEFAULT_HIGHLIGHT_COLOR;
    
    /**
     * Handles channel action responses from the backend by delegating the
     * shared "consume pending → route success/failure" skeleton to the shared
     * {@link ChannelResponseDispatcher} (DUP-3). The platform adapter owns player
     * lookup, rendering and the proxy action-bar no-op.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        plugin.debug("Received channel action response: " + packet);
        dispatcher.handle(packet);
    }

    /**
     * Bungee-specific {@link ChannelResponseDispatcher.ChannelResponseAdapter}.
     * Bungee has no reliable proxy title/action-bar API, so the status-bar
     * callbacks are no-ops and the KICK/MUTE notice is a chat message only.
     */
    private final class BungeeChannelResponseAdapter implements ChannelResponseDispatcher.ChannelResponseAdapter {

        @Override
        public void setActiveChannel(UUID playerId, String channelId) {
            PlayerChannelState state = getState(playerId);
            if (state != null) {
                state.setActiveChannel(channelId);
            }
        }

        @Override
        public void rollbackJoin(UUID playerId, String attemptedChannel, String previousChannel) {
            PlayerChannelState state = getState(playerId);
            if (state == null) {
                return;
            }
            String current = state.getActiveChannel();
            if (current != null && current.equals(attemptedChannel)) {
                state.setActiveChannel(previousChannel);
            }
        }

        @Override
        public void sendJoinSuccess(UUID playerId, String channelId) {
            ProxiedPlayer player = plugin.getProxy().getPlayer(playerId);
            if (player == null) {
                return;
            }
            player.sendMessage(messageFormatter.formatSuccess("已加入频道 " + channelId));
        }

        @Override
        public void sendJoinChannelStatusBar(UUID playerId, String channelId) {
            // §7 action bar is intentionally not sent: Bungee is a proxy and has no
            // stable action-bar API that reliably reaches the downstream client.
        }

        @Override
        public void sendLeaveChannelStatusBar(UUID playerId) {
            // No-op — see sendJoinChannelStatusBar.
        }

        @Override
        public void sendErrorMessage(UUID playerId, String text) {
            ProxiedPlayer player = plugin.getProxy().getPlayer(playerId);
            if (player == null) {
                return;
            }
            player.sendMessage(messageFormatter.formatError(text));
        }

        @Override
        public void notifyKickMuteTarget(ChannelResponseDispatcher.KickMuteNotice notice) {
            ProxiedPlayer target = plugin.getProxy().getPlayer(notice.getTargetId());
            if (target == null) {
                return; // not on this proxy
            }
            String channelId = notice.getChannelId();
            String operator = notice.getOperator();
            if (notice.getAction() == ChannelAction.KICK) {
                target.sendMessage(messageFormatter.parseColors(
                        "&c你已被 " + operator + " 踢出频道 " + channelId));
                return;
            }
            String durationText = notice.getDurationText();
            target.sendMessage(messageFormatter.parseColors(
                    "&c你已被禁言 " + durationText + "（频道 " + channelId + "）"));
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
