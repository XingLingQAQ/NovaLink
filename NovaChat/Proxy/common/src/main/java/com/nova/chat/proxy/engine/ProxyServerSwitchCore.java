package com.nova.chat.proxy.engine;

import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

/**
 * Platform-agnostic server-switch core for the NovaChat proxy family.
 *
 * <p>On a server connection it records the new server on the player's state and
 * then either joins the default channel (first connection of the session) or
 * re-requests the player's active channel on the new server.
 *
 * <p><b>The JOIN is the notification.</b> The backend learns about the new
 * server from the JOIN packet's {@code server} extra, which is a real channel
 * action it understands. There is deliberately no separate "server switch"
 * packet: {@code ChannelAction} has no such value and StarLink's
 * {@code ChannelActionHandler} only dispatches the declared actions, so the
 * historical switch-notification LEAVE was dead code (the backend answered
 * NC-400 for its blank channel id and the response was dropped because it
 * carried {@code player_uuid} rather than the {@code playerId} the tracker keys
 * on).
 *
 * <p>The platform shells keep only the event subscription glue: Velocity's
 * {@code ServerConnectedEvent} and BungeeCord's {@code ServerSwitchEvent} /
 * {@code ServerConnectedEvent}.
 *
 * <p>Requirements: 4.3, 5.3 — cross-server message routing.
 */
public class ProxyServerSwitchCore {

    private final ProxyPlatform platform;
    private final ProxyChatCore chatCore;

    /**
     * @param platform the platform adapter
     * @param chatCore the shared chat core (state + config + first-connection claim)
     */
    public ProxyServerSwitchCore(ProxyPlatform platform, ProxyChatCore chatCore) {
        this.platform = platform;
        this.chatCore = chatCore;
    }

    /**
     * Handles a server connection: records the server, then joins the default
     * channel on the player's first connection of the session or re-requests the
     * active channel otherwise.
     *
     * <p>The first-connection decision comes from
     * {@link ProxyChatCore#claimFirstConnection(java.util.UUID)}, an atomic
     * per-session claim, deliberately <em>not</em> from
     * {@code PlayerChannelState#getCurrentServer() == null}: the chat listener
     * and this handler both write {@code currentServer}, and their execution
     * order is not guaranteed (BungeeCord keeps same-priority listeners in a
     * {@code HashMap}).
     *
     * @param player the connecting player
     * @param serverName the downstream server name
     * @return {@code true} when this was the player's first connection of the
     *         session (the caller may then push the welcome line)
     */
    public boolean onServerConnected(ProxyPlayer player, String serverName) {
        chatCore.recordServer(player, serverName);

        PlayerChannelState state = chatCore.getOrCreateState(player);
        if (chatCore.claimFirstConnection(player.getUniqueId())) {
            joinDefaultChannel(player, state, serverName);
            return true;
        }
        handleChannelTransition(player, state, serverName);
        return false;
    }

    /**
     * Re-requests the player's current channel on the new server.
     *
     * <p>For SERVER-scoped channels the backend may drop or move the membership;
     * for GLOBAL-scoped channels the player remains in the channel across
     * servers. Either way the backend decides — the proxy only re-asks.
     *
     * @param player the player
     * @param state the player's chat state
     * @param newServer the server the player is joining
     */
    public void handleChannelTransition(ProxyPlayer player, PlayerChannelState state,
                                        String newServer) {
        AbstractPlatformNetworkClient client = chatCore.getNetworkClient();
        if (client == null || !client.isAuthenticated()) {
            return;
        }

        String currentChannel = state.getActiveChannel();

        // Send a join request for the current channel on the new server.
        // The backend will determine if the player can stay in the channel.

        ChannelActionPacket joinPacket = new ChannelActionPacket(ChannelAction.JOIN, currentChannel);
        joinPacket.addExtra("player_uuid", player.getUniqueId().toString());
        joinPacket.addExtra("player_name", player.getName());
        joinPacket.addExtra("server", newServer);

        client.sendPacket(joinPacket);
        platform.logDebug("Requested channel join after server switch: "
                + currentChannel + " on " + newServer);
    }

    /**
     * Joins the default channel for a player on their first connection.
     *
     * @param player the player
     * @param state the player's chat state
     * @param server the server name
     */
    public void joinDefaultChannel(ProxyPlayer player, PlayerChannelState state, String server) {
        String defaultChannel = chatCore.getStates().getConfig().getDefaultChannel();
        state.setActiveChannel(defaultChannel);

        AbstractPlatformNetworkClient client = chatCore.getNetworkClient();
        if (client != null && client.isAuthenticated()) {
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, defaultChannel);
            packet.addExtra("player_uuid", player.getUniqueId().toString());
            packet.addExtra("player_name", player.getName());
            packet.addExtra("server", server);

            client.sendPacket(packet);
            platform.logDebug("Player " + player.getName()
                    + " joined default channel: " + defaultChannel);
        }
    }
}
