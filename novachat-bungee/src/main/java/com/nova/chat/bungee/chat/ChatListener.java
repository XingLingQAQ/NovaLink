package com.nova.chat.bungee.chat;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
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
        }
    }
    
    /**
     * Handles channel action responses from the backend.
     * Updates player state based on the response.
     *
     * @param packet the channel action response packet
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        plugin.debug("Received channel action response: " + packet);
        
        // Get player UUID from extra data if available
        String playerUuidStr = packet.getExtra("player_uuid");
        if (playerUuidStr == null) {
            return;
        }
        
        try {
            UUID playerId = UUID.fromString(playerUuidStr);
            PlayerChannelState state = getState(playerId);
            
            if (state == null) {
                return;
            }
            
            // Find the player
            ProxiedPlayer player = plugin.getProxy().getPlayer(playerId);
            if (player != null) {
                if (packet.isSuccess()) {
                    // Update player's active channel on successful join
                    if (packet.getAction() == ChannelAction.JOIN) {
                        state.setActiveChannel(packet.getChannelId());
                        player.sendMessage(messageFormatter.formatSuccess("已加入频道: " + packet.getChannelId()));
                    } else if (packet.getAction() == ChannelAction.LEAVE) {
                        // Reset to default channel on leave
                        state.setActiveChannel(config.getDefaultChannel());
                        player.sendMessage(messageFormatter.formatSuccess("已离开频道: " + packet.getChannelId()));
                    }
                } else {
                    // Show error message
                    String errorMsg = packet.getMessage();
                    if (errorMsg == null || errorMsg.isEmpty()) {
                        errorMsg = "操作失败: " + packet.getErrorCode();
                    }
                    player.sendMessage(messageFormatter.formatError(errorMsg));
                }
            }
        } catch (IllegalArgumentException e) {
            plugin.debug("Invalid player UUID in channel action response: " + playerUuidStr);
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
                    player, channelId, channelName, senderName, content, placeholders
                );
                player.sendMessage(formattedMessage);
            }
        }
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
        plugin.debug("Removed chat state for " + event.getPlayer().getName());
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
