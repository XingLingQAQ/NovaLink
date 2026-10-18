package com.nova.chat.bungee.chat;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.bungee.platform.BungeeProxyPlatform;
import com.nova.chat.bungee.platform.BungeeProxyPlayer;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.platform.ProxyPlayer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.util.UUID;

/**
 * Listens for player chat events on BungeeCord proxy.
 *
 * <p>In BungeeCord, chat messages can be intercepted at the proxy level. In
 * REPLACE mode the original message is cancelled and routed through the
 * NovaLink backend; in HYBRID mode vanilla chat proceeds.
 *
 * <p>All handler bodies, state accessors and rendering live in the shared
 * {@link ProxyChatCore}; this class keeps only the BungeeCord event subscription
 * and the {@link ProxiedPlayer} → {@link ProxyPlayer} adaptation.
 *
 * <p>Requirements: 23.3 - BungeeCord proxy support
 */
public class ChatListener implements Listener {

    private final NovaChatBungee plugin;
    private final BungeeProxyPlatform platform;
    private final ProxyChatCore core;
    private final MessageFormatter messageFormatter;

    /**
     * Creates a new ChatListener and binds the inbound packet handlers to the
     * plugin's current network client.
     *
     * @param plugin the plugin instance
     */
    public ChatListener(NovaChatBungee plugin) {
        this.plugin = plugin;
        this.platform = new BungeeProxyPlatform(plugin);
        this.messageFormatter = new MessageFormatter(plugin);
        // Production: the plugin owns the single shared chat core so the command
        // layer and this listener share one player-state store. Test doubles
        // (mocked plugin) return null, so build a private core instead.
        ProxyChatCore shared = plugin.getChatCore();
        this.core = shared != null ? shared : new ProxyChatCore(
                platform,
                plugin.getPluginConfig(),
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
     * <p>In REPLACE mode the original event is cancelled and the message is
     * forwarded through the backend; in HYBRID mode vanilla chat proceeds.
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

        ProxyPlayer handle = new BungeeProxyPlayer(player);
        ProxyChatCore.ChatDecision decision = core.decideOutbound(handle, event.getMessage());
        if (decision.isPassthrough()) {
            return;
        }

        // In REPLACE mode, cancel the event and forward to channel.
        event.setCancelled(true);

        if (decision.isReply()) {
            core.sendFormatted(handle, decision.getReplyMessage());
            return;
        }
        core.sendToChannel(handle, decision.getChannelId(), decision.getContent());
    }

    /**
     * Handles player server switch events: updates the player state with the new
     * server information.
     *
     * @param event the server connected event
     */
    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        core.recordServer(new BungeeProxyPlayer(event.getPlayer()),
                event.getServer().getInfo().getName());
    }

    /**
     * Handles player disconnect events: cleans up player state.
     *
     * @param event the disconnect event
     */
    @EventHandler
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        core.onPlayerDisconnect(playerId);
        // Lite has no backend to receive the logout LEAVE, so the local
        // authority drops the membership itself. No-op in relay mode.
        plugin.onLitePlayerDisconnect(playerId);
        plugin.debug("Removed chat state for " + event.getPlayer().getName());
    }

    /**
     * Pushes the shared first-join welcome line once per proxy session to the
     * given player (UX-DESIGN §8.1).
     *
     * @param player the freshly connected player
     */
    public void pushWelcomeIfFirst(ProxiedPlayer player) {
        core.pushWelcomeIfFirst(new BungeeProxyPlayer(player));
    }

    // ------------------------------------------------------------- state access

    /**
     * Sends a message to a specific channel.
     *
     * @param player the sending player
     * @param channelId the target channel ID
     * @param message the message content
     */
    public void sendToChannel(ProxiedPlayer player, String channelId, String message) {
        core.sendToChannel(new BungeeProxyPlayer(player), channelId, message);
    }

    /**
     * Gets or creates a player's chat state.
     *
     * @param player the player
     * @return the player's chat state
     */
    public PlayerChannelState getOrCreateState(ProxiedPlayer player) {
        return core.getOrCreateState(new BungeeProxyPlayer(player));
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
    public ChatMode togglePlayerMode(ProxiedPlayer player) {
        return core.togglePlayerMode(new BungeeProxyPlayer(player));
    }

    /**
     * Sets a player's active channel.
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(ProxiedPlayer player, String channelId) {
        core.setPlayerChannel(new BungeeProxyPlayer(player), channelId);
    }

    /**
     * Gets a player's active channel.
     *
     * @param player the player
     * @return the active channel ID
     */
    public String getPlayerChannel(ProxiedPlayer player) {
        return core.getPlayerChannel(new BungeeProxyPlayer(player));
    }

    /**
     * Reloads configuration settings using the plugin's latest config snapshot.
     */
    public void reload() {
        setConfig(plugin.getPluginConfig());
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
     * @return the BungeeCord platform adapter backing this listener
     */
    public BungeeProxyPlatform getPlatform() {
        return platform;
    }
}
