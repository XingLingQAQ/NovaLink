package com.nova.chat.bukkit.chat;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.api.event.ChannelMessageEvent;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.channel.ChannelPrefixResolver;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.itemdisplay.ItemDisplayTokens;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Intercepts player chat events and forwards messages to the NovaLink backend.
 * Supports HYBRID and REPLACE modes for vanilla chat compatibility.
 * 
 * Requirements: 11.1, 11.2
 */
public class ChatInterceptor implements Listener {
    
    private final NovaChatBukkit plugin;

    /**
     * Latest immutable config snapshot. Marked {@code volatile} for cross-thread
     * visibility: Bukkit chat runs async while {@link #setConfig} / {@link #reload}
     * are invoked from the command/main thread with the new snapshot.
     */
    private volatile NovaChatConfig config;

    /** Message formatter for color codes and placeholders */
    private final MessageFormatter messageFormatter;

    /** Player chat states indexed by UUID (shared store). */
    private final com.nova.chat.client.state.PlayerStateStore playerStates =
            new com.nova.chat.client.state.PlayerStateStore();

    /** Shared [item]/[i] token detection + per-player cooldown (client-core). */
    private final ItemDisplayTokens itemDisplayTokens = new ItemDisplayTokens();

    /**
     * Thread hop for the inbound render path.
     *
     * <p>{@link ChannelMessageEvent} is declared async, so it may only be fired
     * off the main thread. The backend link already dispatches on Netty, but
     * the plugin-message callback runs on the server main thread — the exact
     * combination Paper rejects with
     * {@code IllegalStateException: ChannelMessageEvent may only be triggered
     * asynchronously}. Both callers therefore go through this hook, which the
     * plugin wires to {@code runTaskAsynchronously}.
     */
    private final Consumer<Runnable> asyncDispatcher;

    /**
     * @param plugin the plugin instance
     */
    public ChatInterceptor(NovaChatBukkit plugin) {
        this(plugin, task -> plugin.getServer().getScheduler()
                .runTaskAsynchronously(plugin, task));
    }

    /**
     * @param plugin the plugin instance
     * @param asyncDispatcher runs one task off the main thread; must not run it
     *                        synchronously when called from the main thread
     */
    ChatInterceptor(NovaChatBukkit plugin, Consumer<Runnable> asyncDispatcher) {
        this.plugin = plugin;
        this.asyncDispatcher = asyncDispatcher;
        this.config = plugin.getNovaChatConfig();
        this.messageFormatter = new MessageFormatter(plugin);
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;

        // Register handler for incoming chat messages from backend
        rebindIncomingHandlers();
    }
    /**
     * Global chat mode from configuration.
     *
     * <p>Marked {@code volatile} for cross-thread visibility: it is read in
     * {@link #onPlayerChat} (Bukkit chat runs async) and written from
     * {@link #setGlobalMode} / {@link #reload} (command/main thread), matching
     * the Folia adapter's {@code volatile} declaration.
     */
    private volatile ChatMode globalMode;

    /**
     * Registers the handler for incoming chat messages from the backend.
     *
     * <p>Public so that {@code reload} can re-bind to the freshly created
     * NetworkClient after a network reconnect without reconstructing this
     * interceptor (H6 fix).
     */
    public void rebindIncomingHandlers() {
        if (plugin.getNetworkClient() != null) {
            plugin.getNetworkClient().registerHandler(ChatMessagePacket.class, this::handleIncomingMessage);
        }
    }
    
    /**
     * Handles incoming chat messages from the backend.
     * Fires ChannelMessageEvent for other plugins to listen (Requirements: 25.1)
     *
     * @param packet the chat message packet
     */
    public void handleIncomingMessage(ChatMessagePacket packet) {
        handleIncomingMessage(packet, java.util.Set.of());
    }

    /**
     * Handles an inbound fan-out line and renders it to local players.
     *
     * <p>{@code excludedRecipients} is the set the proxy has already rendered
     * the line to (players connected through the proxy). The sub-server renders
     * only the remainder: players connected straight to this server, whom the
     * proxy cannot see. Without this every proxied player would see the line
     * twice.
     *
     * @param packet the chat message packet
     * @param excludedRecipients player UUIDs already served by the proxy
     */
    public void handleIncomingMessage(ChatMessagePacket packet,
                                      java.util.Set<UUID> excludedRecipients) {
        plugin.debug("Received chat message from backend: " + packet);

        // The event is async-only. The backend link already calls us on Netty,
        // but the plugin-message callback lands on the main thread, so hop off
        // it before firing. Messages that already arrive off-thread are handled
        // in place: an extra hop would reorder them relative to each other.
        if (plugin.getServer().isPrimaryThread()) {
            Set<UUID> excluded = excludedRecipients != null ? excludedRecipients : Set.of();
            asyncDispatcher.accept(() -> dispatchIncoming(packet, excluded));
            return;
        }
        dispatchIncoming(packet, excludedRecipients != null ? excludedRecipients : Set.of());
    }

    /**
     * Fires the async {@link ChannelMessageEvent} and renders the surviving
     * line to the local players the proxy did not already serve.
     *
     * <p>Must run off the main thread (the event is async-only); the render
     * loop hops back to the main thread for Bukkit API calls.
     */
    private void dispatchIncoming(ChatMessagePacket packet, Set<UUID> excludedRecipients) {
        String channelId = packet.getChannelId();
        String senderName = packet.getSenderName();
        String content = packet.getContent();
        UUID senderId = packet.getSenderId();
        Map<String, String> placeholders = packet.getPlaceholders();

        // Fire ChannelMessageEvent for other plugins (Requirements: 25.1)
        ChannelMessageEvent event = new ChannelMessageEvent(
            senderId, senderName, channelId, content, placeholders
        );
        plugin.getServer().getPluginManager().callEvent(event);

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
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                // The proxy already rendered this line to its own players.
                if (excludedRecipients.contains(player.getUniqueId())) {
                    continue;
                }
                // Skip senders the recipient has ignored (/nc ignore)
                com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
                if (ignoreService != null && ignoreService.isIgnored(player.getUniqueId(), senderName)) {
                    continue;
                }
                // Check if player is in this channel
                PlayerChannelState state = getState(player.getUniqueId());
                if (state != null && channelId.equals(state.getActiveChannel())) {
                    // Highlight @name mentions for this recipient (UX-DESIGN §4.2).
                    String displayContent = MentionNotifier.highlightMentions(finalContent, MENTION_HIGHLIGHT_COLOR);
                    String formattedMessage = messageFormatter.formatChatMessage(
                        player, channelId, channelName, senderName, displayContent, placeholders
                    );
                    player.sendMessage(formattedMessage);
                }
            }
        });
    }

    /** Legacy color prefix applied to @name mentions when rendering chat (UX-DESIGN §4.2). */
    static final String MENTION_HIGHLIGHT_COLOR = MentionNotifier.DEFAULT_HIGHLIGHT_COLOR;
    
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
        PlayerChannelState state = getOrCreateState(player);
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

        // Channel-prefix routing (e.g. "!hi" -> global) before the
        // active-channel send; escape/unknown-prefix cases fall through
        // with the resolver-produced message (UX: prefix = /nc <channel> shorthand).
        ChannelPrefixResolver.Resolution resolution = ChannelPrefixResolver.resolve(
                config.getChannelPrefixes(), event.getMessage(),
                plugin.getKnownChannelRegistry() != null ? plugin.getKnownChannelRegistry().getAll() : null);
        String targetChannel = resolution.isRedirect()
                ? resolution.getChannelId() : state.getActiveChannel();

        // Preferred transport: the backend link, exactly as before.
        com.nova.chat.bukkit.network.NetworkClient client = plugin.getNetworkClient();
        if (client != null && client.isAuthenticated()) {
            sendToChannel(player, targetChannel, resolution.getMessage());
            return;
        }

        // Backend unavailable (lite mode has none at all): hand the message to
        // the proxy over plugin messaging. Without this the line is cancelled
        // locally and the proxy never sees it (the NC-503 gap).
        com.nova.chat.bukkit.channel.ProxyChannelClient channel =
                plugin.getProxyChannelClient();
        if (channel != null && channel.isEnabled()
                && channel.send(buildChatPacket(player, targetChannel, resolution.getMessage()))) {
            plugin.debug("Forwarded chat to the proxy over novachat:main: "
                    + resolution.getMessage());
            return;
        }

        player.sendMessage(formatError(I18n.tr(playerId, "chat.network.not_connected_retry")));
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
        // Reply-target cleanup for /nc r (private messages).
        if (plugin.getPrivateMessageService() != null) {
            plugin.getPrivateMessageService().onPlayerQuit(playerId);
        }
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
            player.sendMessage(formatError(I18n.tr(player.getUniqueId(), "chat.network.not_connected")));
            return;
        }

        ChatMessagePacket packet = buildChatPacket(player, channelId, message);

        plugin.getNetworkClient().sendPacket(packet);
        plugin.debug("Sent message to channel " + channelId + ": " + message);

        // [item]/[i] display play: piggybacks on the outbound path (UX spec §4).
        maybeSendItemDisplay(player, channelId, message);
    }

    /**
     * Builds the outbound chat packet shared by the backend path and the
     * plugin-messaging path, including the placeholders the proxy resolves.
     *
     * @param player the sending player
     * @param channelId the target channel
     * @param message the message content
     * @return a packet ready for either transport
     */
    private ChatMessagePacket buildChatPacket(Player player, String channelId, String message) {
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
        return packet;
    }

    /**
     * Sends an {@link com.nova.chat.common.protocol.packets.ItemDisplayPacket}
     * when the outbound message carries an {@code [item]}/{@code [i]} token.
     *
     * <p>Semantics aligned with the Bedrock reference (pmmp/endstone):
     * case-insensitive {@code \[(item|i)\]} token, the shared
     * {@code novachat.feature.item} permission gate (no permission → tokens
     * stay plain text, Requirements 12.5), and an empty hand still sends the
     * air payload (Bedrock renders the "Empty" placeholder). The per-player
     * cooldown lives in the shared {@link ItemDisplayTokens}. Only display
     * fields (id/count/custom name) are serialized — never full NBT.
     *
     * @param player    the sending player
     * @param channelId the target channel
     * @param message   the outbound chat message
     */
    private void maybeSendItemDisplay(Player player, String channelId, String message) {
        try {
            if (!ItemDisplayTokens.hasItemToken(message)) {
                return;
            }
            if (!player.hasPermission(ItemDisplayTokens.PERMISSION_ITEM)) {
                return; // 12.5: without permission the token stays plain text
            }
            if (!itemDisplayTokens.tryAcquire(player.getUniqueId())) {
                return; // rate-limited: token stays plain text
            }
            String itemJson = buildMainHandItemJson(player);
            plugin.getNetworkClient().sendPacket(ItemDisplayTokens.buildPacket(
                    player.getUniqueId(), player.getName(), channelId, itemJson));
            plugin.debug("Sent item display to channel " + channelId + ": " + itemJson);
        } catch (Exception e) {
            plugin.debug("Failed to send item display: " + e.getMessage());
        }
    }

    /**
     * Extracts the display fields (id / count / custom name) of the player's
     * main-hand item. Empty hand → air payload, matching the Bedrock
     * renderers' "Empty" placeholder behavior.
     */
    private String buildMainHandItemJson(Player player) {
        org.bukkit.inventory.ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == org.bukkit.Material.AIR || hand.getAmount() <= 0) {
            return ItemDisplayTokens.emptyHandJson();
        }
        String customName = null;
        org.bukkit.inventory.meta.ItemMeta meta = hand.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            customName = meta.getDisplayName();
        }
        return ItemDisplayTokens.buildItemJson(
                hand.getType().getKey().toString(), hand.getAmount(), customName);
    }

    /**
     * Gets or creates a player's chat state.
     *
     * @param player the player
     * @return the player's chat state
     */
    public PlayerChannelState getOrCreateState(Player player) {
        return playerStates.getOrCreate(player.getUniqueId(),
            config.getDefaultChannel(), globalMode);
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
        return playerStates.getPlayer(playerId);
    }

    /**
     * Sets a player's chat state.
     *
     * @param playerId the player's UUID
     * @param state the chat state to set
     */
    public void setPlayerState(UUID playerId, PlayerChannelState state) {
        playerStates.set(playerId, state);
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
        PlayerChannelState state = getOrCreateState(player);
        return state.toggleMode();
    }
    
    /**
     * Sets a player's active channel.
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(Player player, String channelId) {
        PlayerChannelState state = getOrCreateState(player);
        state.setActiveChannel(channelId);
    }
    
    /**
     * Gets a player's active channel.
     *
     * @param player the player
     * @return the active channel ID
     */
    public String getPlayerChannel(Player player) {
        PlayerChannelState state = getOrCreateState(player);
        return state.getActiveChannel();
    }
    
    /**
     * Reloads configuration settings using the plugin's latest config snapshot.
     */
    public void reload() {
        setConfig(plugin.getNovaChatConfig());
    }

    /**
     * Installs a new {@link NovaChatConfig} snapshot into this interceptor and
     * cascades it to the message formatter. Called by the plugin main class
     * after {@code loadConfiguration()} succeeds so subsequent hot-path lookups
     * resolve against the freshly loaded values instead of a stale reference.
     *
     * @param newConfig the freshly loaded config snapshot
     */
    public void setConfig(NovaChatConfig newConfig) {
        if (newConfig == null) {
            return;
        }
        this.config = newConfig;
        this.globalMode = newConfig.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        this.messageFormatter.setConfig(newConfig);
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
