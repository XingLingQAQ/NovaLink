package com.nova.chat.folia.chat;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.ChatMode;

import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Async chat interceptor for Folia that properly handles region thread concurrency.
 * 
 * <p>This class is designed to work correctly with Folia's regionized multithreading model.
 * Key thread safety considerations:</p>
 * 
 * <ul>
 *   <li>Uses {@link AsyncPlayerChatEvent} which is already called from an async thread</li>
 *   <li>All player state is stored in a {@link ConcurrentHashMap} for thread-safe access</li>
 *   <li>All player operations (sending messages, etc.) are scheduled on the correct region thread
 *       using {@link FoliaSchedulerAdapter#runForPlayer(Player, Runnable)}</li>
 *   <li>The global chat mode is marked volatile for visibility across threads</li>
 * </ul>
 * 
 * <p>In Folia, different regions of the world run on different threads. This means that
 * when we need to interact with a player (send messages, check permissions, etc.), we must
 * ensure we're on the correct thread for that player's region. This class handles this
 * automatically by using the scheduler adapter.</p>
 * 
 * Requirements: 2.3, 2.4
 */
public class AsyncChatInterceptor implements Listener {
    
    private final NovaChatFolia plugin;
    private final NovaChatConfig config;
    private final FoliaSchedulerAdapter scheduler;
    
    /** Message formatter for color codes and placeholders - thread-safe */
    private final AsyncMessageFormatter messageFormatter;
    
    /** 
     * Player chat states indexed by UUID.
     * Uses ConcurrentHashMap for thread-safe access from multiple region threads.
     * All operations on this map are atomic and thread-safe.
     */
    private final Map<UUID, PlayerChatState> playerStates = new ConcurrentHashMap<>();
    
    /** 
     * Global chat mode from configuration.
     * Marked volatile to ensure visibility across all region threads.
     */
    private volatile ChatMode globalMode;
    
    /**
     * Creates a new AsyncChatInterceptor.
     *
     * @param plugin the plugin instance
     */
    public AsyncChatInterceptor(NovaChatFolia plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();
        this.scheduler = plugin.getScheduler();
        this.messageFormatter = new AsyncMessageFormatter(plugin);
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
     * Handles asynchronous channel-action responses from the backend.
     *
     * <p>Correlates the response back to the originating player via the shared
     * {@link ChannelResponseTracker}, then hops onto the player's region thread via
     * {@link FoliaSchedulerAdapter#runForPlayer(Player, Runnable)} to render an
     * actionable error (shared {@link ErrorCode} system). NC-503 is already reported
     * at send time and is suppressed here to avoid a double message. Success is owned
     * by the command's immediate feedback; we only mirror the authoritative channel
     * on JOIN.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        plugin.debug("Received channel action response: " + packet);

        ChannelResponseTracker tracker = plugin.getNetworkClient().getChannelResponseTracker();
        ChannelResponseTracker.PendingChannelAction pending = tracker.consume(packet.getRequestId());
        if (pending == null || pending.getPlayerId() == null) {
            return;
        }

        Player player = plugin.getServer().getPlayer(pending.getPlayerId());
        if (player == null) {
            return;
        }

        // Hop to the player's region thread before touching the player or sending.
        scheduler.runForPlayer(player, () -> {
            if (packet.isSuccess()) {
                if (packet.getAction() == ChannelAction.JOIN
                        && packet.getChannelId() != null && !packet.getChannelId().isEmpty()) {
                    plugin.getChatInterceptor().getOrCreateState(player).setActiveChannel(packet.getChannelId());
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
     * Handles incoming chat messages from the backend.
     * Dispatches messages to players on their correct region threads.
     * 
     * <p>Thread Safety: This method can be called from any thread (typically Netty's event loop
     * or an async scheduler thread). It safely iterates over online players and schedules
     * message delivery on each player's correct region thread.</p>
     * 
     * <p>In Folia, each player may be in a different region running on a different thread.
     * We must schedule the message sending operation on the correct thread for each player
     * to avoid concurrent modification issues.</p>
     *
     * Requirements: 2.3
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
        
        // Create a snapshot of online players to avoid concurrent modification
        // This is important because the player list can change while we're iterating
        Collection<? extends Player> onlinePlayers = plugin.getServer().getOnlinePlayers();
        List<Player> playersSnapshot = new ArrayList<>(onlinePlayers);
        
        // Dispatch messages to each player on their correct region thread
        // This is critical for Folia - we must send messages on the correct thread
        for (Player player : playersSnapshot) {
            // Skip if player is no longer online (may have disconnected during iteration)
            if (!player.isOnline()) {
                continue;
            }
            
            // Get player state (thread-safe read from ConcurrentHashMap)
            PlayerChatState state = getState(player.getUniqueId());
            if (state != null && channelId.equals(state.getActiveChannel())) {
                // Capture variables for lambda (effectively final)
                final String finalChannelName = channelName;
                
                // Schedule message sending on the player's region thread
                // This ensures we're on the correct thread for Bukkit API calls
                scheduler.runForPlayer(player, () -> {
                    // Double-check player is still online when task executes
                    if (!player.isOnline()) {
                        return;
                    }
                    
                    String formattedMessage = messageFormatter.formatChatMessage(
                        player, channelId, finalChannelName, senderName, content, placeholders
                    );
                    player.sendMessage(formattedMessage);
                });
            }
        }
    }
    
    /**
     * Handles player chat events using AsyncPlayerChatEvent.
     * 
     * <p>Thread Safety: AsyncPlayerChatEvent is already called from an async thread,
     * making it inherently safe for Folia's regionized model. The event is NOT called
     * from any region thread, so we don't need to worry about region thread conflicts
     * when handling this event.</p>
     * 
     * <p>However, when we need to send messages back to the player (e.g., error messages),
     * we must schedule those operations on the player's correct region thread.</p>
     * 
     * <p>In REPLACE mode, cancels the event and forwards to the channel.
     * In HYBRID mode, allows vanilla chat to proceed normally.</p>
     *
     * Requirements: 2.3, 2.4
     *
     * @param event the chat event
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Get or create player state (thread-safe operation via ConcurrentHashMap)
        PlayerChatState state = getOrCreateState(player);
        
        // Read volatile field once for consistency within this method
        ChatMode currentGlobalMode = globalMode;
        ChatMode effectiveMode = state.isModeOverridden() ? state.getChatMode() : currentGlobalMode;
        
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
            // Send error message on player's region thread
            // This is necessary because sendMessage() may interact with player state
            scheduler.runForPlayer(player, () -> {
                if (player.isOnline()) {
                    player.sendMessage(formatError("未连接到聊天服务器，请稍后再试"));
                }
            });
            return;
        }
        
        // Capture message before forwarding (event data should be accessed in event handler)
        String message = event.getMessage();
        String activeChannel = state.getActiveChannel();
        
        // Forward message to backend (async operation, safe from any thread)
        // The network client handles its own thread safety
        sendToChannel(player, activeChannel, message);
    }
    
    /**
     * Handles player join events to initialize chat state.
     * 
     * <p>Thread Safety: PlayerJoinEvent is called on the player's region thread in Folia.
     * This means we're already on the correct thread for this player, so we can safely
     * access player data and initialize state.</p>
     * 
     * <p>The state initialization itself is thread-safe because we use ConcurrentHashMap.</p>
     *
     * Requirements: 2.3
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        
        // Initialize state (thread-safe via ConcurrentHashMap.computeIfAbsent)
        // This is safe to call from the player's region thread
        getOrCreateState(player);
        plugin.debug("Initialized chat state for " + player.getName());
    }
    
    /**
     * Handles player quit events to clean up chat state.
     * 
     * <p>Thread Safety: PlayerQuitEvent is called on the player's region thread in Folia.
     * The removal from ConcurrentHashMap is atomic and thread-safe.</p>
     *
     * @param event the quit event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        // Atomic removal from ConcurrentHashMap
        PlayerChatState removedState = playerStates.remove(playerId);
        if (removedState != null) {
            plugin.debug("Removed chat state for " + event.getPlayer().getName());
        }
    }
    
    /**
     * Sends a message to a specific channel.
     * 
     * <p>Thread Safety: This method is thread-safe and can be called from any thread.
     * The network client handles its own thread safety for packet sending.
     * Player data access (getName, getDisplayName, getWorld) should ideally be done
     * on the player's region thread, but these are generally safe read operations.</p>
     * 
     * <p>For maximum safety in Folia, we capture player data that might change and
     * send the packet asynchronously.</p>
     *
     * @param player the sending player
     * @param channelId the target channel ID
     * @param message the message content
     */
    public void sendToChannel(Player player, String channelId, String message) {
        if (!plugin.getNetworkClient().isAuthenticated()) {
            scheduler.runForPlayer(player, () -> {
                if (player.isOnline()) {
                    player.sendMessage(formatError("未连接到聊天服务器"));
                }
            });
            return;
        }
        
        // Capture player data that we need for the packet
        // These reads are generally safe but we capture them to avoid issues
        // if player state changes during packet construction
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        String displayName = player.getDisplayName();
        String worldName = player.getWorld().getName();
        
        ChatMessagePacket packet = new ChatMessagePacket(
            playerId,
            playerName,
            config.getUsername(),
            channelId,
            message
        );
        
        // Add basic placeholders with captured data
        packet.addPlaceholder("player", playerName);
        packet.addPlaceholder("display_name", displayName);
        packet.addPlaceholder("world", worldName);
        
        // Send packet (async operation via Netty, thread-safe)
        plugin.getNetworkClient().sendPacket(packet);
        plugin.debug("Sent message to channel " + channelId + ": " + message);
    }
    
    /**
     * Gets or creates a player's chat state.
     * 
     * <p>Thread Safety: This method is thread-safe. It uses ConcurrentHashMap.computeIfAbsent
     * which is atomic - if multiple threads call this simultaneously for the same player,
     * only one state will be created and all threads will receive the same instance.</p>
     *
     * @param player the player
     * @return the player's chat state (never null)
     */
    public PlayerChatState getOrCreateState(Player player) {
        // Read volatile field once for consistency
        ChatMode currentGlobalMode = globalMode;
        return playerStates.computeIfAbsent(player.getUniqueId(), 
            uuid -> new PlayerChatState(uuid, config.getDefaultChannel(), currentGlobalMode));
    }
    
    /**
     * Gets a player's chat state if it exists.
     * 
     * <p>Thread Safety: This is a simple read from ConcurrentHashMap, which is thread-safe.</p>
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
     * <p>Thread Safety: This is a simple read from ConcurrentHashMap, which is thread-safe.</p>
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
     * <p>Thread Safety: This is a simple write to ConcurrentHashMap, which is thread-safe.</p>
     *
     * @param playerId the player's UUID
     * @param state the chat state to set
     */
    public void setPlayerState(UUID playerId, PlayerChatState state) {
        if (state != null) {
            playerStates.put(playerId, state);
        }
    }
    
    /**
     * Sets the global chat mode.
     * 
     * <p>Thread Safety: The globalMode field is volatile, so this write is immediately
     * visible to all threads. This is typically called from the main/global thread
     * during configuration reload.</p>
     *
     * @param mode the new global mode
     */
    public void setGlobalMode(ChatMode mode) {
        this.globalMode = mode;
        plugin.debug("Global chat mode set to: " + mode);
    }
    
    /**
     * Gets the global chat mode.
     * 
     * <p>Thread Safety: The globalMode field is volatile, so this read always
     * returns the most recent value.</p>
     *
     * @return the global chat mode
     */
    public ChatMode getGlobalMode() {
        return globalMode;
    }
    
    /**
     * Toggles a player's chat mode.
     * 
     * <p>Thread Safety: The toggle operation on PlayerChatState is synchronized,
     * and the state retrieval is thread-safe via ConcurrentHashMap.</p>
     *
     * @param player the player
     * @return the new chat mode
     */
    public ChatMode togglePlayerMode(Player player) {
        PlayerChatState state = getOrCreateState(player);
        ChatMode newMode = state.toggleMode();
        plugin.debug("Player " + player.getName() + " chat mode toggled to: " + newMode);
        return newMode;
    }
    
    /**
     * Sets a player's active channel.
     * 
     * <p>Thread Safety: The channel field in PlayerChatState is volatile,
     * and the state retrieval is thread-safe via ConcurrentHashMap.</p>
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(Player player, String channelId) {
        PlayerChatState state = getOrCreateState(player);
        state.setActiveChannel(channelId);
        plugin.debug("Player " + player.getName() + " channel set to: " + channelId);
    }
    
    /**
     * Gets a player's active channel.
     * 
     * <p>Thread Safety: The channel field in PlayerChatState is volatile,
     * and the state retrieval is thread-safe via ConcurrentHashMap.</p>
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
     * 
     * <p>Thread Safety: This should be called from the global region thread or
     * during plugin reload. The volatile write to globalMode ensures visibility.</p>
     */
    public void reload() {
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        this.messageFormatter.reload();
        plugin.debug("AsyncChatInterceptor reloaded, global mode: " + globalMode);
    }
    
    /**
     * Gets the message formatter.
     *
     * @return the message formatter
     */
    public AsyncMessageFormatter getMessageFormatter() {
        return messageFormatter;
    }
    
    /**
     * Gets the number of tracked player states.
     * Useful for debugging and monitoring.
     *
     * @return the number of player states
     */
    public int getPlayerStateCount() {
        return playerStates.size();
    }
    
    /**
     * Clears all player states.
     * Should only be called during plugin disable or reload.
     */
    public void clearAllStates() {
        playerStates.clear();
        plugin.debug("Cleared all player chat states");
    }
    
    /**
     * Formats an error message with the plugin prefix.
     * This method is thread-safe as it only uses immutable config values.
     *
     * @param message the error message
     * @return the formatted message
     */
    private String formatError(String message) {
        return config.getPrefix() + config.getErrorFormat().replace("{message}", message);
    }
    
    /**
     * Formats a success message with the plugin prefix.
     * This method is thread-safe as it only uses immutable config values.
     *
     * @param message the success message
     * @return the formatted message
     */
    private String formatSuccess(String message) {
        return config.getPrefix() + config.getSuccessFormat().replace("{message}", message);
    }
    
    /**
     * Sends a formatted error message to a player on their region thread.
     * 
     * <p>Thread Safety: This method schedules the message on the player's
     * correct region thread, making it safe to call from any thread.</p>
     *
     * @param player the player to send the message to
     * @param message the error message
     */
    public void sendError(Player player, String message) {
        scheduler.runForPlayer(player, () -> {
            if (player.isOnline()) {
                player.sendMessage(formatError(message));
            }
        });
    }
    
    /**
     * Sends a formatted success message to a player on their region thread.
     * 
     * <p>Thread Safety: This method schedules the message on the player's
     * correct region thread, making it safe to call from any thread.</p>
     *
     * @param player the player to send the message to
     * @param message the success message
     */
    public void sendSuccess(Player player, String message) {
        scheduler.runForPlayer(player, () -> {
            if (player.isOnline()) {
                player.sendMessage(formatSuccess(message));
            }
        });
    }
}
