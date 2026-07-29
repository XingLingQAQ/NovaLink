package com.nova.chat.sponge.chat;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.sponge.NovaChatSponge;
import com.nova.chat.sponge.config.NovaChatConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
    }

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
                if (packet.getAction() == ChannelAction.JOIN
                        && packet.getChannelId() != null && !packet.getChannelId().isEmpty()) {
                    PlayerChannelState state = getState(pending.getPlayerId());
                    if (state != null) {
                        state.setActiveChannel(packet.getChannelId());
                    }
                }
                return;
            }

            String code = packet.getErrorCode();
            if (code == null || code.isEmpty() || ErrorCode.SERVICE_UNAVAILABLE.getCode().equals(code)) {
                return;
            }
            player.sendMessage(plugin.getMessageFormatter().formatError(ErrorMessageFormatter.format(code)));
        });
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
                        player, channelId, channelName, senderName, content, placeholders
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
