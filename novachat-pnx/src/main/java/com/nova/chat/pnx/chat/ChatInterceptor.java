package com.nova.chat.pnx.chat;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerChatEvent;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.pnx.NovaChatPNX;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts player chat events and forwards them to the NovaLink backend.
 * Uses novachat-common protocol for packet creation.
 * 
 * Requirements: 28.4
 */
public class ChatInterceptor implements Listener {

    private final NovaChatPNX plugin;
    
    // Player chat states (current channel, etc.)
    private final Map<UUID, PlayerChatState> playerStates = new ConcurrentHashMap<>();

    public ChatInterceptor(NovaChatPNX plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(PlayerChatEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        String message = event.getMessage();

        // Get player's chat state
        PlayerChatState state = getOrCreateState(player);
        
        // Check if chat is enabled for this player
        if (!state.isChatEnabled()) {
            player.sendMessage(plugin.getMessageFormatter().formatError("你的聊天已关闭，使用 /nc toggle 开启"));
            event.setCancelled(true);
            return;
        }

        // Check if we should replace vanilla chat
        if (plugin.getNovaChatConfig().isReplaceVanilla()) {
            event.setCancelled(true);
        }

        // Get player's current channel
        String channelId = state.getCurrentChannel();

        // Send message to backend
        sendChatMessage(player, channelId, message);

        plugin.debug("Chat intercepted: " + player.getName() + " -> " + channelId + ": " + message);
    }

    /**
     * Send a chat message to the backend.
     *
     * @param player the player sending the message
     * @param channelId the target channel
     * @param message the message content
     */
    private void sendChatMessage(Player player, String channelId, String message) {
        if (!plugin.getNetworkClient().isConnected()) {
            player.sendMessage(plugin.getMessageFormatter().formatError("未连接到聊天服务器"));
            return;
        }

        if (!plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(plugin.getMessageFormatter().formatError("正在连接聊天服务器，请稍后再试"));
            return;
        }

        // Create chat message packet using novachat-common
        ChatMessagePacket packet = new ChatMessagePacket(
            player.getUniqueId(),
            player.getName(),
            plugin.getNovaChatConfig().getBackendUsername(), // clientId
            channelId,
            message
        );

        // Add placeholders for formatting
        packet.addPlaceholder("world", player.getLevel().getName());
        packet.addPlaceholder("display_name", player.getDisplayName());

        // Send packet to backend
        plugin.getNetworkClient().sendPacket(packet);
    }

    /**
     * Display an incoming message from the backend.
     *
     * @param senderName the sender's name
     * @param channelId the channel ID
     * @param content the message content
     */
    public void displayIncomingMessage(String senderName, String channelId, String content) {
        String formatted = plugin.getMessageFormatter().formatIncomingMessage(senderName, channelId, content);
        
        // Broadcast to all online players
        plugin.getServer().broadcastMessage(formatted);
    }

    /**
     * Display an incoming message from the backend with placeholders.
     *
     * @param senderName the sender's name
     * @param channelId the channel ID
     * @param content the message content
     * @param placeholders additional placeholders
     */
    public void displayIncomingMessage(String senderName, String channelId, String content, 
                                       Map<String, String> placeholders) {
        String format = plugin.getNovaChatConfig().getChannelFormat(channelId);
        
        String formatted = format
            .replace("{player}", senderName)
            .replace("{channel}", channelId)
            .replace("{channel_name}", channelId)
            .replace("{message}", content);
        
        // Apply additional placeholders
        if (placeholders != null) {
            formatted = plugin.getMessageFormatter().applyPlaceholders(formatted, placeholders);
        } else {
            formatted = plugin.getMessageFormatter().colorize(formatted);
        }
        
        // Broadcast to all online players
        plugin.getServer().broadcastMessage(formatted);
    }

    /**
     * Get or create a player's chat state.
     *
     * @param player the player
     * @return the player's chat state
     */
    public PlayerChatState getOrCreateState(Player player) {
        return playerStates.computeIfAbsent(player.getUniqueId(),
            uuid -> new PlayerChatState(
                    new PlayerChannelState(uuid, plugin.getNovaChatConfig().getDefaultChannel(), ChatMode.HYBRID)));
    }

    /**
     * Remove a player's chat state.
     *
     * @param player the player
     */
    public void removeState(Player player) {
        playerStates.remove(player.getUniqueId());
    }

    /**
     * Set a player's current channel.
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(Player player, String channelId) {
        getOrCreateState(player).setCurrentChannel(channelId);
    }

    /**
     * Get a player's current channel.
     *
     * @param player the player
     * @return the channel ID
     */
    public String getPlayerChannel(Player player) {
        return getOrCreateState(player).getCurrentChannel();
    }

    /**
     * Reload the chat interceptor settings.
     * Called when the plugin configuration is reloaded.
     */
    public void reload() {
        // Update default channel for players who haven't changed their channel
        String defaultChannel = plugin.getNovaChatConfig().getDefaultChannel();
        
        plugin.debug("ChatInterceptor reloaded, default channel: " + defaultChannel);
    }

    /**
     * Player chat state holder.
     *
     * <p>Hosts a shared {@link PlayerChannelState} (active channel + joined-channel
     * set) so {@link com.nova.chat.client.command.ChannelCommandService} join/leave
     * can mutate membership in place. The single PNX-local {@code chatEnabled}
     * on/off toggle is <strong>not</strong> represented by the shared
     * {@link ChatMode} HYBRID/REPLACE model — it controls whether chat is forwarded
     * at all, not vanilla-cancel semantics — so it stays a separate boolean here
     * and {@code /nc toggle} flips it locally rather than via the shared service.
     *
     * <p>{@code currentChannel} getters/setters delegate to the shared state's
     * active channel so legacy readers ({@link com.nova.chat.pnx.world.WorldMonitor},
     * {@link com.nova.chat.pnx.form.ChannelFormManager}) observe service mutations.
     */
    @Getter
    @Setter
    public static class PlayerChatState {
        private final PlayerChannelState channelState;
        private boolean chatEnabled = true;

        public PlayerChatState(PlayerChannelState channelState) {
            this.channelState = channelState;
        }

        public String getCurrentChannel() {
            return channelState.getActiveChannel();
        }

        public void setCurrentChannel(String channelId) {
            channelState.setActiveChannel(channelId);
        }
    }
}
