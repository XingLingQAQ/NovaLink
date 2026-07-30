package com.nova.chat.multipaper.chat;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.multipaper.NovaChatMultiPaper;
import com.nova.chat.multipaper.config.NovaChatConfig;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
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
 * Uses AsyncPlayerChatEvent for thread-safe chat handling.
 * Supports MultiPaper cross-instance player synchronization.
 * 
 * Requirements: 1.3, 1.4
 */
public class ChatInterceptor implements Listener {
    
    private final NovaChatMultiPaper plugin;
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
    public ChatInterceptor(NovaChatMultiPaper plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();
        this.messageFormatter = new MessageFormatter(plugin);
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        
        // Register handler for incoming chat messages from backend
        registerIncomingMessageHandler();
        // Register handler for mention notifications from backend (UX-DESIGN §4.1, Requirements 11.2)
        plugin.getNetworkClient().registerHandler(MentionPacket.class, this::handleMention);
    }
    
    /**
     * Registers the handler for incoming chat messages from the backend.
     */
    private void registerIncomingMessageHandler() {
        plugin.getNetworkClient().registerHandler(ChatMessagePacket.class, this::handleIncomingMessage);
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
        // Run on main thread for Bukkit API calls
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                // Check if player is in this channel
                PlayerChatState state = getState(player.getUniqueId());
                if (state != null && channelId.equals(state.getActiveChannel())) {
                    String formattedMessage = messageFormatter.formatChatMessage(
                        player, channelId, channelName, senderName, content, placeholders
                    );
                    player.sendMessage(formattedMessage);
                }
            }
        });
    }

    /**
     * Handles a mention notification packet by playing a sound and showing a
     * title to the mentioned player (UX-DESIGN §4.1, Requirements 11.2).
     *
     * <p>The mentioned player must be online on this server; packets for
     * players on other MultiPaper instances are expected to be routed there
     * by the backend.
     */
    private void handleMention(MentionPacket packet) {
        UUID mentionedId = packet.getMentionedId();
        if (mentionedId == null) {
            return;
        }
        Player player = Bukkit.getPlayer(mentionedId);
        if (player == null) {
            return; // not on this instance
        }
        // Must run on main thread for Bukkit API
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            try {
                String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
                String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
                String title = messageFormatter.translateColorCodes("&e" + mentioner);
                String subtitle = messageFormatter.translateColorCodes(
                        "&7在频道 &b" + channelId + " &7提到了你");
                player.sendTitle(title, subtitle,
                        MentionNotifier.DEFAULT_FADE_IN,
                        MentionNotifier.DEFAULT_STAY,
                        MentionNotifier.DEFAULT_FADE_OUT);
                playMentionSound(player);
            } catch (Exception e) {
                plugin.debug("Failed to handle MentionPacket: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Plays the default mention notification sound to a player.
     */
    private void playMentionSound(Player player) {
        // The common DEFAULT_SOUND constant names the ENTITY_EXPERIENCE_ORB_PICKUP
        // enum, which we resolve directly here to avoid the deprecated
        // Sound.valueOf(String) removal API.
        player.playSound(player.getLocation(),
                org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
    }
    
    /**
     * Handles player chat events using AsyncPlayerChatEvent.
     * In REPLACE mode, cancels the event and forwards to the channel.
     * In HYBRID mode, allows vanilla chat to proceed normally.
     *
     * Requirements: 1.4
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
     * Attempts to restore state from MultiPaper shared data if available.
     *
     * Requirements: 1.3
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Try to get state from MultiPaper shared data first
        PlayerChatState sharedState = plugin.getMultiPaperAdapter().getSharedPlayerState(playerId);
        if (sharedState != null) {
            playerStates.put(playerId, sharedState);
            plugin.debug("Restored chat state for " + player.getName() + " from MultiPaper shared data");
        } else {
            getOrCreateState(player);
            plugin.debug("Initialized new chat state for " + player.getName());
        }
    }
    
    /**
     * Handles player quit events to clean up chat state.
     * Syncs state to MultiPaper before removal.
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        
        // Sync state to MultiPaper before removing
        PlayerChatState state = playerStates.get(playerId);
        if (state != null) {
            plugin.getMultiPaperAdapter().syncPlayerState(playerId, state);
        }
        
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
            config.getUsername(),
            channelId,
            message
        );
        
        // Add basic placeholders
        packet.addPlaceholder("player", player.getName());
        packet.addPlaceholder("display_name", player.getDisplayName());
        packet.addPlaceholder("world", player.getWorld().getName());
        
        // Add MultiPaper instance ID
        if (plugin.getMultiPaperAdapter().isMultiPaper()) {
            packet.addPlaceholder("instance", plugin.getMultiPaperAdapter().getInstanceId());
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
     *
     * @param playerId the player's UUID
     * @return the player's chat state, or null if not found
     */
    public PlayerChatState getPlayerState(UUID playerId) {
        return playerStates.get(playerId);
    }
    
    /**
     * Sets a player's chat state and syncs to MultiPaper.
     *
     * @param playerId the player's UUID
     * @param state the chat state to set
     */
    public void setPlayerState(UUID playerId, PlayerChatState state) {
        playerStates.put(playerId, state);
        // Sync to MultiPaper
        plugin.getMultiPaperAdapter().syncPlayerState(playerId, state);
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
     * Toggles a player's chat mode and syncs to MultiPaper.
     *
     * @param player the player
     * @return the new chat mode
     */
    public ChatMode togglePlayerMode(Player player) {
        PlayerChatState state = getOrCreateState(player);
        ChatMode newMode = state.toggleMode();
        // Sync to MultiPaper
        plugin.getMultiPaperAdapter().syncPlayerState(player.getUniqueId(), state);
        return newMode;
    }
    
    /**
     * Sets a player's active channel and syncs to MultiPaper.
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(Player player, String channelId) {
        PlayerChatState state = getOrCreateState(player);
        state.setActiveChannel(channelId);
        // Sync to MultiPaper
        plugin.getMultiPaperAdapter().syncPlayerState(player.getUniqueId(), state);
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
