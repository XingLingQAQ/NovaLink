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
 * <p>Absorbs the body of the two near-identical {@code ServerSwitchHandler}
 * implementations:
 * <ol>
 *   <li>notify the backend that a player is moving between downstream servers
 *       (a {@code LEAVE}-actioned packet carrying {@code action_type =
 *       server_switch} and the from/to names), and</li>
 *   <li>re-request the player's active channel on the new server so the backend
 *       can re-evaluate scope membership.</li>
 * </ol>
 *
 * <p>The platform shells keep only the event subscription glue: Velocity's
 * {@code ServerPreConnectEvent} / {@code ServerConnectedEvent} and BungeeCord's
 * {@code ServerSwitchEvent} / {@code ServerConnectedEvent}. The core never
 * branches on platform identity.
 *
 * <p>Requirements: 4.3, 5.3 — cross-server message routing.
 */
public class ProxyServerSwitchCore {

    private final ProxyPlatform platform;
    private final ProxyChatCore chatCore;

    /**
     * @param platform the platform adapter
     * @param chatCore the shared chat core (state + config)
     */
    public ProxyServerSwitchCore(ProxyPlatform platform, ProxyChatCore chatCore) {
        this.platform = platform;
        this.chatCore = chatCore;
    }

    /**
     * Notifies the backend about a server switch.
     *
     * @param player the player switching servers
     * @param fromServer the server the player is leaving
     * @param toServer the server the player is joining
     */
    public void notifyServerSwitch(ProxyPlayer player, String fromServer, String toServer) {
        AbstractPlatformNetworkClient client = chatCore.getNetworkClient();
        if (client == null || !client.isAuthenticated()) {
            return;
        }

        // Send a channel action packet to notify backend about server switch.
        // The backend can use this to update player state and handle routing.
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.LEAVE, "");
        packet.addExtra("action_type", "server_switch");
        packet.addExtra("player_uuid", player.getUniqueId().toString());
        packet.addExtra("player_name", player.getName());
        packet.addExtra("from_server", fromServer);
        packet.addExtra("to_server", toServer);

        client.sendPacket(packet);
        platform.logDebug("Notified backend about server switch: "
                + fromServer + " -> " + toServer);
    }

    /**
     * Handles a completed server switch: notifies the backend and re-requests the
     * player's active channel on the new server.
     *
     * @param player the player switching servers
     * @param fromServer the server the player is leaving
     * @param toServer the server the player is joining
     */
    public void onServerSwitch(ProxyPlayer player, String fromServer, String toServer) {
        notifyServerSwitch(player, fromServer, toServer);
        handleChannelTransition(player, fromServer, toServer);
    }

    /**
     * Re-requests the player's current channel on the new server.
     *
     * <p>For SERVER-scoped channels the backend may drop or move the membership;
     * for GLOBAL-scoped channels the player remains in the channel across
     * servers. Either way the backend decides — the proxy only re-asks.
     *
     * @param player the player
     * @param previousServer the previous server (may be null for initial join)
     * @param newServer the new server
     */
    public void handleChannelTransition(ProxyPlayer player, String previousServer, String newServer) {
        AbstractPlatformNetworkClient client = chatCore.getNetworkClient();
        if (client == null || !client.isAuthenticated()) {
            return;
        }

        PlayerChannelState state = chatCore.getOrCreateState(player);
        String currentChannel = state.getActiveChannel();

        // If this is the initial connection (no previous server), just join the
        // default channel.
        if (previousServer == null) {
            joinDefaultChannel(player, state, newServer);
            return;
        }

        // Send a join request for the current channel on the new server; the
        // backend determines whether the player can stay in the channel.
        ChannelActionPacket joinPacket = new ChannelActionPacket(ChannelAction.JOIN, currentChannel);
        joinPacket.addExtra("player_uuid", player.getUniqueId().toString());
        joinPacket.addExtra("player_name", player.getName());
        joinPacket.addExtra("server", newServer);
        joinPacket.addExtra("previous_server", previousServer);

        client.sendPacket(joinPacket);
        platform.logDebug("Requested channel join after server switch: "
                + currentChannel + " on " + newServer);
    }

    /**
     * Joins the default channel for a player on initial connection.
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
