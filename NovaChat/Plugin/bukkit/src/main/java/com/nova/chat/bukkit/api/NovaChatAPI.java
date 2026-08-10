package com.nova.chat.bukkit.api;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.api.event.ChannelMessageEvent;
import com.nova.chat.bukkit.api.event.PlayerChannelSwitchEvent;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * Public API for NovaChat plugin.
 * Provides methods for other plugins to interact with the NovaChat system.
 * 
 * Requirements: 25.1-25.3
 * - 25.1: ChannelMessageEvent for other plugins to listen
 * - 25.2: PlayerChannelSwitchEvent for other plugins to listen
 * - 25.3: sendToChannel() method for cross-channel messaging
 */
public class NovaChatAPI {

    private static NovaChatAPI instance;
    private final NovaChatBukkit plugin;

    /**
     * Creates a new NovaChatAPI instance.
     * This should only be called by the plugin itself.
     *
     * @param plugin the plugin instance
     */
    public NovaChatAPI(NovaChatBukkit plugin) {
        this.plugin = plugin;
        instance = this;
    }

    /**
     * Gets the NovaChatAPI instance.
     *
     * @return the API instance, or null if the plugin is not loaded
     */
    public static NovaChatAPI getInstance() {
        return instance;
    }

    /**
     * Sends a message to a specific channel.
     * This method allows other plugins to send messages to NovaChat channels.
     * 
     * Requirements: 25.3
     *
     * @param channelId the target channel ID
     * @param senderName the display name of the sender
     * @param message the message content
     * @return true if the message was sent successfully
     */
    public boolean sendToChannel(String channelId, String senderName, String message) {
        return sendToChannel(channelId, null, senderName, message, null);
    }

    /**
     * Sends a message to a specific channel with a specific sender UUID.
     * 
     * Requirements: 25.3
     *
     * @param channelId the target channel ID
     * @param senderId the UUID of the sender (can be null for system messages)
     * @param senderName the display name of the sender
     * @param message the message content
     * @return true if the message was sent successfully
     */
    public boolean sendToChannel(String channelId, UUID senderId, String senderName, String message) {
        return sendToChannel(channelId, senderId, senderName, message, null);
    }

    /**
     * Sends a message to a specific channel with custom placeholders.
     * 
     * Requirements: 25.3
     *
     * @param channelId the target channel ID
     * @param senderId the UUID of the sender (can be null for system messages)
     * @param senderName the display name of the sender
     * @param message the message content
     * @param placeholders custom placeholders to include in the message
     * @return true if the message was sent successfully
     */
    public boolean sendToChannel(String channelId, UUID senderId, String senderName, 
                                  String message, Map<String, String> placeholders) {
        if (!isConnected()) {
            plugin.debug("Cannot send message: not connected to backend");
            return false;
        }

        if (channelId == null || channelId.isEmpty()) {
            plugin.debug("Cannot send message: channel ID is null or empty");
            return false;
        }

        if (message == null || message.isEmpty()) {
            plugin.debug("Cannot send message: message is null or empty");
            return false;
        }

        // Use a system UUID if sender is null
        UUID effectiveSenderId = senderId != null ? senderId : new UUID(0, 0);
        String effectiveSenderName = senderName != null ? senderName : "System";

        ChatMessagePacket packet = new ChatMessagePacket(
            effectiveSenderId,
            effectiveSenderName,
            plugin.getNovaChatConfig().getUsername(),
            channelId,
            message
        );

        // Add placeholders
        packet.addPlaceholder("player", effectiveSenderName);
        packet.addPlaceholder("display_name", effectiveSenderName);
        
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                packet.addPlaceholder(entry.getKey(), entry.getValue());
            }
        }

        plugin.getNetworkClient().sendPacket(packet);
        plugin.debug("API: Sent message to channel " + channelId + ": " + message);
        return true;
    }

    /**
     * Sends a message to a channel as a specific player.
     * 
     * Requirements: 25.3
     *
     * @param player the player sending the message
     * @param channelId the target channel ID
     * @param message the message content
     * @return true if the message was sent successfully
     */
    public boolean sendToChannel(Player player, String channelId, String message) {
        if (player == null) {
            return sendToChannel(channelId, "System", message);
        }

        Map<String, String> placeholders = new java.util.HashMap<>();
        placeholders.put("world", player.getWorld().getName());
        
        return sendToChannel(channelId, player.getUniqueId(), player.getName(), message, placeholders);
    }

    /**
     * Gets a player's current active channel.
     *
     * @param player the player
     * @return the channel ID, or null if the player has no active channel
     */
    public String getPlayerChannel(Player player) {
        if (player == null) {
            return null;
        }
        return plugin.getChatInterceptor().getPlayerChannel(player);
    }

    /**
     * Gets a player's current active channel by UUID.
     *
     * @param playerId the player's UUID
     * @return the channel ID, or null if the player has no active channel
     */
    public String getPlayerChannel(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        PlayerChannelState state = plugin.getChatInterceptor().getState(playerId);
        return state != null ? state.getActiveChannel() : null;
    }

    /**
     * Sets a player's active channel.
     * This will fire a PlayerChannelSwitchEvent.
     *
     * @param player the player
     * @param channelId the new channel ID
     * @return true if the channel was changed successfully
     */
    public boolean setPlayerChannel(Player player, String channelId) {
        if (player == null || channelId == null) {
            return false;
        }

        String oldChannel = getPlayerChannel(player);
        
        // Fire the event
        PlayerChannelSwitchEvent event = new PlayerChannelSwitchEvent(player, oldChannel, channelId);
        Bukkit.getPluginManager().callEvent(event);
        
        if (event.isCancelled()) {
            plugin.debug("PlayerChannelSwitchEvent was cancelled for " + player.getName());
            return false;
        }

        // Apply the channel change
        plugin.getChatInterceptor().setPlayerChannel(player, event.getNewChannel());
        plugin.debug("API: Set player " + player.getName() + " channel to " + event.getNewChannel());
        return true;
    }

    /**
     * Checks if the plugin is connected to the NovaLink backend.
     *
     * @return true if connected and authenticated
     */
    public boolean isConnected() {
        return plugin.getNetworkClient() != null && 
               plugin.getNetworkClient().isAuthenticated();
    }

    /**
     * Gets the plugin instance.
     * For advanced usage only.
     *
     * @return the plugin instance
     */
    public NovaChatBukkit getPlugin() {
        return plugin;
    }

    /**
     * Fires a ChannelMessageEvent for incoming messages.
     * This is called internally by the plugin.
     *
     * @param senderId the sender's UUID
     * @param senderName the sender's name
     * @param channelId the channel ID
     * @param message the message content
     * @param placeholders the message placeholders
     * @return the event (may be cancelled)
     */
    public ChannelMessageEvent fireChannelMessageEvent(UUID senderId, String senderName,
                                                        String channelId, String message,
                                                        Map<String, String> placeholders) {
        ChannelMessageEvent event = new ChannelMessageEvent(
            senderId, senderName, channelId, message, placeholders
        );
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    /**
     * Clears the API instance.
     * Called when the plugin is disabled.
     */
    public static void clearInstance() {
        instance = null;
    }
}
