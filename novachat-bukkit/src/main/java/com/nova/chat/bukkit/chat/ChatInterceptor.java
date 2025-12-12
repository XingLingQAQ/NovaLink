package com.nova.chat.bukkit.chat;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.api.event.ChannelMessageEvent;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts player chat events and forwards messages to the NovaLink backend.
 * Supports HYBRID and REPLACE modes for vanilla chat compatibility.
 * 
 * Requirements: 11.1, 11.2
 */
public class ChatInterceptor implements Listener {
    
    private final NovaChatBukkit plugin;
    private final NovaChatConfig config;
    
    /** Message formatter for color codes and placeholders */
    private final MessageFormatter messageFormatter;
    
    /** Player chat states indexed by UUID */
    private final Map<UUID, PlayerChatState> playerStates = new ConcurrentHashMap<>();
    
    /** Global chat mode from configuration */
    private ChatMode globalMode;
    
    /**
     * Creates a new ChatInterceptor.
     *
     * @param plugin the plugin instance
     */
    public ChatInterceptor(NovaChatBukkit plugin) {
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
    }
    
    /**
     * Handles incoming chat messages from the backend.
     * Fires ChannelMessageEvent for other plugins to listen (Requirements: 25.1)
     *
     * @param packet the chat message packet
     */
    private void handleIncomingMessage(ChatMessagePacket packet) {
        plugin.debug("Received chat message from backend: " + packet);
        
        String channelId = packet.getChannelId();
        String senderName = packet.getSenderName();
        String content = packet.getContent();
        UUID senderId = packet.getSenderId();
        Map<String, String> placeholders = packet.getPlaceholders();
        
        // Fire ChannelMessageEvent for other plugins (Requirements: 25.1)
        ChannelMessageEvent event = new ChannelMessageEvent(
            senderId, senderName, channelId, content, placeholders
        );
        Bukkit.getPluginManager().callEvent(event);
        
        // Check if event was cancelled
        if (event.isCancelled()) {
            plugin.debug("ChannelMessageEvent was cancelled for message from " + senderName);
            return;
        }
        
        // Use potentially modified message from event
        String finalContent = event.getMessage();
        
        // Get channel display name from placeholders or use channel ID
        String channelName = placeholders.getOrDefault("channel_name", channelId);
        
        // Format the message for display
        // Run on main thread for Bukkit API calls
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                // Check if player is in this channel
                PlayerChatState state = getState(player.getUniqueId());
                if (state != null && channelId.equals(state.getActiveChannel())) {
                    String formattedMessage = messageFormatter.formatChatMessage(
                        player, channelId, channelName, senderName, finalContent, placeholders
                    );
                    player.sendMessage(formattedMessage);
                }
            }
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
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Get or create player state
        PlayerChatState state = getOrCreateState(player);
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
        
        // Add basic placeholders that will be resolved on the plugin side
        packet.addPlaceholder("player", player.getName());
        packet.addPlaceholder("display_name", player.getDisplayName());
        packet.addPlaceholder("world", player.getWorld().getName());
        
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
