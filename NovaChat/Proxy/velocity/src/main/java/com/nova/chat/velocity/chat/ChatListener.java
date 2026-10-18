package com.nova.chat.velocity.chat;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.platform.ProxyPlayer;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.nova.chat.velocity.platform.VelocityProxyPlatform;
import com.nova.chat.velocity.platform.VelocityProxyPlayer;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;

import java.util.UUID;

/**
 * Velocity chat listener shell.
 *
 * <p>Implements cancel-and-resend strategy to handle chat signing issues: in
 * REPLACE mode the original chat event is denied and the message is forwarded to
 * the channel; in HYBRID mode vanilla chat proceeds untouched.
 *
 * <p>All handler bodies, state accessors and rendering live in the shared
 * {@link ProxyChatCore}; this class keeps only the Velocity event subscription
 * and the {@link Player} → {@link ProxyPlayer} adaptation.
 *
 * <p>Requirements: 23.2 - Handle chat signing issues
 */
public class ChatListener {

    private final NovaChatVelocity plugin;
    private final VelocityProxyPlatform platform;
    private final ProxyChatCore core;
    private final MessageFormatter messageFormatter;

    /**
     * Creates a new ChatListener and binds the inbound packet handlers to the
     * plugin's current network client.
     *
     * @param plugin the plugin instance
     */
    public ChatListener(NovaChatVelocity plugin) {
        this.plugin = plugin;
        this.platform = new VelocityProxyPlatform(plugin);
        this.messageFormatter = new MessageFormatter(plugin);
        // Production: the plugin owns the single shared chat core so the command
        // layer and this listener share one player-state store. Test doubles
        // (mocked plugin) return null, so build a private core instead.
        ProxyChatCore shared = plugin.getChatCore();
        this.core = shared != null ? shared : new ProxyChatCore(
                platform,
                plugin.getConfig(),
                plugin.getIgnoreListService(),
                plugin.getPrivateMessageService(),
                plugin.getKnownChannelRegistry(),
                plugin::getNetworkClient);
        // Register handler for incoming chat messages from backend
        rebindIncomingHandlers();
    }

    /**
     * Registers the handler for incoming chat messages from the backend.
     *
     * <p>Public so that {@code reload} can re-bind to the freshly created
     * NetworkClient after a network reconnect without reconstructing this
     * listener (H6 fix).
     */
    public void rebindIncomingHandlers() {
        core.rebindIncomingHandlers(plugin.getNetworkClient());
    }

    /**
     * Handles player chat events.
     *
     * <p>In REPLACE mode the original chat event is denied (preventing signature
     * verification issues) and the message is forwarded to the channel; the
     * backend broadcasts to all channel members. In HYBRID mode vanilla chat
     * proceeds normally.
     *
     * @param event the chat event
     */
    @Subscribe(order = PostOrder.EARLY)
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        ProxyPlayer handle = new VelocityProxyPlayer(player);

        ProxyChatCore.ChatDecision decision = core.decideOutbound(handle, event.getMessage());
        if (decision.isPassthrough()) {
            return;
        }

        // In REPLACE mode, cancel the event and forward to channel.
        // This is the cancel-and-resend strategy to handle chat signing.
        event.setResult(PlayerChatEvent.ChatResult.denied());

        if (decision.isReply()) {
            core.sendFormatted(handle, decision.getReplyMessage());
            return;
        }
        core.sendToChannel(handle, decision.getChannelId(), decision.getContent());
    }

    /**
     * Handles player server switch events: updates the player state with the new
     * server and shows the first-join welcome once per session.
     *
     * @param event the server connected event
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        core.onServerConnected(
                new VelocityProxyPlayer(event.getPlayer()),
                event.getServer().getServerInfo().getName());
    }

    /**
     * Handles player disconnect events: cleans up player state.
     *
     * @param event the disconnect event
     */
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        core.onPlayerDisconnect(playerId);
        plugin.debug("Removed chat state for " + event.getPlayer().getUsername());
    }

    // ------------------------------------------------------------- state access

    /**
     * Sends a message to a specific channel.
     *
     * @param player the sending player
     * @param channelId the target channel ID
     * @param message the message content
     */
    public void sendToChannel(Player player, String channelId, String message) {
        core.sendToChannel(new VelocityProxyPlayer(player), channelId, message);
    }

    /**
     * Gets or creates a player's chat state.
     *
     * @param player the player
     * @return the player's chat state
     */
    public PlayerChannelState getOrCreateState(Player player) {
        return core.getOrCreateState(new VelocityProxyPlayer(player));
    }

    /**
     * Gets a player's chat state if it exists.
     *
     * @param playerId the player's UUID
     * @return the player's chat state, or null if not found
     */
    public PlayerChannelState getState(UUID playerId) {
        return core.getState(playerId);
    }

    /**
     * Gets a player's chat state if it exists. Alias for getState() for command
     * compatibility.
     *
     * @param playerId the player's UUID
     * @return the player's chat state, or null if not found
     */
    public PlayerChannelState getPlayerState(UUID playerId) {
        return core.getPlayerState(playerId);
    }

    /**
     * Sets a player's chat state.
     *
     * @param playerId the player's UUID
     * @param state the chat state to set
     */
    public void setPlayerState(UUID playerId, PlayerChannelState state) {
        core.setPlayerState(playerId, state);
    }

    /**
     * Sets the global chat mode.
     *
     * @param mode the new global mode
     */
    public void setGlobalMode(ChatMode mode) {
        core.setGlobalMode(mode);
    }

    /**
     * Gets the global chat mode.
     *
     * @return the global chat mode
     */
    public ChatMode getGlobalMode() {
        return core.getGlobalMode();
    }

    /**
     * Toggles a player's chat mode.
     *
     * @param player the player
     * @return the new chat mode
     */
    public ChatMode togglePlayerMode(Player player) {
        return core.togglePlayerMode(new VelocityProxyPlayer(player));
    }

    /**
     * Sets a player's active channel.
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(Player player, String channelId) {
        core.setPlayerChannel(new VelocityProxyPlayer(player), channelId);
    }

    /**
     * Gets a player's active channel.
     *
     * @param player the player
     * @return the active channel ID
     */
    public String getPlayerChannel(Player player) {
        return core.getPlayerChannel(new VelocityProxyPlayer(player));
    }

    /**
     * Reloads configuration settings using the plugin's latest config snapshot.
     */
    public void reload() {
        setConfig(plugin.getConfig());
    }

    /**
     * Installs a new {@link NovaChatConfig} snapshot into the shared core so
     * subsequent hot-path lookups resolve against the freshly loaded values
     * instead of a stale reference.
     *
     * @param newConfig the freshly loaded config snapshot
     */
    public void setConfig(NovaChatConfig newConfig) {
        if (newConfig == null) {
            return;
        }
        core.setConfig(newConfig);
        plugin.debug("ChatListener reloaded, global mode: " + core.getGlobalMode());
    }

    /**
     * Atomically claims the player's first server connection of this proxy
     * session (P3b fix 4).
     *
     * <p>Used by the server-switch handler so the "first join" decision never
     * depends on listener execution order or on
     * {@code PlayerChannelState#getCurrentServer()}, which both listeners write.
     *
     * @param playerId the connecting player
     * @return {@code true} the first time this player connects in this session
     */
    public boolean claimFirstConnection(UUID playerId) {
        return core.claimFirstConnection(playerId);
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
     * @return the shared chat core (the command layer shares its player state)
     */
    public ProxyChatCore getChatCore() {
        return core;
    }

    /**
     * @return the Velocity platform adapter backing this listener
     */
    public VelocityProxyPlatform getPlatform() {
        return platform;
    }
}
