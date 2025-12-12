package com.nova.chat.velocity.chat;

import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;

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
    private final Map<UUID, PlayerChatState> playerStates = new ConcurrentHashMap<>();
    
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
            java.util.UUID playerId = java.util.UUID.fromString(playerUuidStr);
            PlayerChatState state = getState(playerId);
            
            if (state == null) {
                return;
            }
            
            // Find the player
            plugin.getServer().getPlayer(playerId).ifPresent(player -> {
                if (packet.isSuccess()) {
                    // Update player's active channel on successful join
                    if (packet.getAction() == com.nova.chat.common.protocol.ChannelAction.JOIN) {
                        state.setActiveChannel(packet.getChannelId());
                        player.sendMessage(messageFormatter.formatSuccess("已加入频道: " + packet.getChannelId()));
                    } else if (packet.getAction() == com.nova.chat.common.protocol.ChannelAction.LEAVE) {
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
            });
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
        for (Player player : plugin.getServer().getAllPlayers()) {
            PlayerChatState state = getState(player.getUniqueId());
            if (state != null && channelId.equals(state.getActiveChannel())) {
                Component formattedMessage = messageFormatter.formatChatMessage(
                    player, channelId, channelName, senderName, content, placeholders
                );
                player.sendMessage(formattedMessage);
            }
        }
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
        PlayerChatState state = getOrCreateState(player);
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
        PlayerChatState state = getOrCreateState(player);
        
        String serverName = event.getServer().getServerInfo().getName();
        state.setCurrentServer(serverName);
        
        plugin.debug("Player " + player.getUsername() + " connected to server: " + serverName);
        
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
    public PlayerChatState getOrCreateState(Player player) {
        return playerStates.computeIfAbsent(player.getUniqueId(), 
            uuid -> new PlayerChatState(uuid, config.getDefaultChannel(), globalMode));
    }
    
    /**
     * Gets a player's chat state if it exists.
     *
     * @param playerId the player's UUID
     * @return the player's chat state, or null if not found
     */
    public PlayerChatState getState(UUID playerId) {
        return playerStates.get(playerId);
    }
    
    /**
     * Gets a player's chat state if it exists.
     * Alias for getState() for command compatibility.
     *
     * @param playerId the player's UUID
     * @return the player's chat state, or null if not found
     */
    public PlayerChatState getPlayerState(UUID playerId) {
        return playerStates.get(playerId);
    }
    
    /**
     * Sets a player's chat state.
     *
     * @param playerId the player's UUID
     * @param state the chat state to set
     */
    public void setPlayerState(UUID playerId, PlayerChatState state) {
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
        PlayerChatState state = getOrCreateState(player);
        return state.toggleMode();
    }
    
    /**
     * Sets a player's active channel.
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(Player player, String channelId) {
        PlayerChatState state = getOrCreateState(player);
        state.setActiveChannel(channelId);
    }
    
    /**
     * Gets a player's active channel.
     *
     * @param player the player
     * @return the active channel ID
     */
    public String getPlayerChannel(Player player) {
        PlayerChatState state = getOrCreateState(player);
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
