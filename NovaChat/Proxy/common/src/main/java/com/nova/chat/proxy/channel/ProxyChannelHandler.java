package com.nova.chat.proxy.channel;

import com.nova.chat.channel.ProxyChannelException;
import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Proxy half of the {@code novachat:main} plugin-messaging channel.
 *
 * <p>Platform shells own only the event subscription and the source check
 * ({@code PluginMessageEvent#getSource()} is a {@code ServerConnection} on
 * Velocity, {@code getSender()} is a {@code Server} on BungeeCord). Everything
 * security- and routing-related lives here so both proxies behave identically:
 *
 * <ul>
 *   <li><b>Server-only sources.</b> A payload whose source is a player is
 *       rejected, never parsed and never forwarded — a client must not be able
 *       to inject chat that looks like it came from a downstream server.</li>
 *   <li><b>Source identity.</b> The envelope declares the sender's server id.
 *       It must match the server the message actually arrived from
 *       (case-insensitive). A downstream therefore cannot impersonate another
 *       downstream, which matters because every downstream is trusted to speak
 *       for its own players only.</li>
 *   <li><b>Oversize payloads</b> are dropped with a warning before any parsing
 *       (see {@link ProxyChannelProtocol#MAX_PAYLOAD_BYTES}).</li>
 *   <li><b>Lite vs relay.</b> In lite the packet goes through the
 *       {@link com.nova.chat.proxy.lite.LocalAuthority} (passed as a function so
 *       this class has no compile-time dependency on a concrete authority) and
 *       the resulting downstream packets are (a) rendered to players connected
 *       to the proxy and (b) sent down to every sub-server that has players, so
 *       players connected straight to a sub-server (whom the proxy cannot see)
 *       also get the line. In relay the packet is handed to the proxy's backend
 *       link; the backend owns fan-out.</li>
 * </ul>
 *
 * <p><b>Chat is network-wide.</b> A chat line must reach every sub-server, not
 * just the one it came from: a player may be connected straight to any of them.
 * {@link #broadcastProxyChat} covers lines typed by proxy players (the proxy's
 * own fan-out only reaches its own roster); {@code handleLite} covers lines that
 * came up from a sub-server.
 *
 * <p><b>Duplicate suppression.</b> When the proxy sends a fan-out packet back
 * down, the envelope carries the UUIDs of the proxy's own players on that
 * server. The sub-server skips exactly those players: proxied players already
 * received the line from the proxy. See
 * {@link ProxyChannelProtocol.Decoded#getExcludedRecipients()}.
 *
 * <p><b>Threading.</b> The shells invoke {@link #handleFromServer} from their
 * event thread (Netty). The authority is synchronized, the chat core's
 * downstream handlers are safe to call from there (they already run on the
 * Netty event loop in relay), and the platform send primitives are safe to call
 * from any thread. No additional hop is introduced.
 */
public class ProxyChannelHandler {

    /** Result of one inbound plugin message; exposed for tests and diagnostics. */
    public enum Outcome {
        /** Parsed, identity verified, and routed (lite fan-out or relay send). */
        ACCEPTED,
        /** Source was a player (or absent), not a downstream server. */
        REJECTED_NOT_SERVER,
        /** Declared server id does not match the actual source server. */
        REJECTED_SERVER_ID,
        /** Raw payload exceeded {@link ProxyChannelProtocol#MAX_PAYLOAD_BYTES}. */
        DROPPED_OVERSIZE,
        /** Not a valid channel envelope (magic/version/length/packet body). */
        DROPPED_MALFORMED,
        /** Parsed and verified, but neither a lite authority nor a relay link exists. */
        DROPPED_NO_ROUTE
    }

    private final ProxyPlatform platform;
    private final ProxyChatCore chatCore;

    /** Lite-mode authority adapter, or null in relay mode. */
    private final Function<Packet, List<Packet>> localAuthority;

    /** Relay-mode backend sink, or null in lite mode. */
    private final Consumer<Packet> relaySink;

    /**
     * @param platform the platform adapter (player roster, downstream send, logs)
     * @param chatCore the shared chat core whose handlers render downstream
     *                 packets to players connected to the proxy
     * @param localAuthority lite-mode authority ({@code LocalAuthority#handle}),
     *                       or null in relay mode
     * @param relaySink relay-mode backend send, or null in lite mode
     */
    public ProxyChannelHandler(ProxyPlatform platform,
                               ProxyChatCore chatCore,
                               Function<Packet, List<Packet>> localAuthority,
                               Consumer<Packet> relaySink) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.chatCore = Objects.requireNonNull(chatCore, "chatCore");
        this.localAuthority = localAuthority;
        this.relaySink = relaySink;
    }

    // -------------------------------------------------- proxy-side fan-out

    /**
     * Called when a player connected to the proxy sends chat.
     *
     * <p>The proxy's own chat path renders the line only to its own players.
     * Players connected straight to a downstream server never see it, because
     * nothing carries the line across. This pushes the same line down to every
     * server that has players, so the sub-server can render it to exactly the
     * players the proxy did not serve.
     *
     * <p>Only lite mode does this. In relay the backend owns fan-out, and it
     * already reaches the sub-servers through its own connections.
     *
     * @param packet the accepted chat line (already validated by the authority)
     */
    public void broadcastProxyChat(ChatMessagePacket packet) {
        if (packet == null || localAuthority == null) {
            return;
        }
        broadcastChat(packet);
    }

    /**
     * Sends one chat line to every server that has players.
     *
     * @param packet the chat line
     */
    private void broadcastChat(ChatMessagePacket packet) {
        for (String serverName : downstreamServers()) {
            // The exclusion list is that server's own proxy players: they have
            // already been rendered to by {@code deliverLocally} (or by the
            // proxy's own outbound path). Using the whole proxy roster here
            // would be harmless for rendering but would tell the sub-server
            // that players on other servers are proxied through it.
            sendDownstream(serverName, packet, proxyPlayersOn(serverName));
        }
    }

    /**
     * @return every downstream server that currently has a player on the proxy
     */
    private Set<String> downstreamServers() {
        Set<String> servers = new java.util.LinkedHashSet<>();
        for (ProxyPlayer player : platform.getOnlinePlayers()) {
            String server = player.getCurrentServer();
            if (server != null && !server.isBlank()) {
                servers.add(server);
            }
        }
        return servers;
    }

    /**
     * Handles one raw payload from a downstream server.
     *
     * @param sourceServerName the server the message actually arrived from, or
     *                         {@code null} when the source is not a server
     *                         (the shells pass null for player sources)
     * @param payload the raw plugin-message data
     * @return what happened; never null
     */
    public Outcome handleFromServer(String sourceServerName, byte[] payload) {
        if (sourceServerName == null || sourceServerName.isBlank()) {
            // Untrusted client input: debug only, otherwise a player can spam
            // the proxy log by writing to novachat:main.
            platform.logDebug("Dropped novachat:main payload from a non-server source");
            return Outcome.REJECTED_NOT_SERVER;
        }
        if (payload == null || payload.length == 0) {
            platform.logDebug("Dropped empty novachat:main payload from " + sourceServerName);
            return Outcome.DROPPED_MALFORMED;
        }
        if (payload.length > ProxyChannelProtocol.MAX_PAYLOAD_BYTES) {
            platform.logWarn("Dropped novachat:main payload from " + sourceServerName
                    + ": " + payload.length + " bytes exceeds the channel limit of "
                    + ProxyChannelProtocol.MAX_PAYLOAD_BYTES);
            return Outcome.DROPPED_OVERSIZE;
        }

        ProxyChannelProtocol.Decoded decoded;
        try {
            decoded = ProxyChannelProtocol.decode(payload);
        } catch (ProxyChannelException e) {
            platform.logDebug("Dropped malformed novachat:main payload from "
                    + sourceServerName + ": " + e.getMessage());
            return Outcome.DROPPED_MALFORMED;
        }

        String claimed = decoded.getSenderId();
        if (claimed == null || !sourceServerName.equalsIgnoreCase(claimed)) {
            platform.logWarn("Rejected novachat:main payload: claimed server id '"
                    + claimed + "' but arrived from '" + sourceServerName + "'");
            return Outcome.REJECTED_SERVER_ID;
        }

        Packet packet = decoded.getPacket();
        if (localAuthority != null) {
            return handleLite(sourceServerName, packet);
        }
        if (relaySink != null) {
            relaySink.accept(packet);
            platform.logDebug("Relayed " + packet.getClass().getSimpleName()
                    + " from " + sourceServerName + " to the backend");
            return Outcome.ACCEPTED;
        }
        platform.logDebug("No route for novachat:main packet from " + sourceServerName);
        return Outcome.DROPPED_NO_ROUTE;
    }

    // ------------------------------------------------------------------- lite

    /**
     * Runs the packet through the local authority, renders the responses to
     * proxy-connected players, and echoes them to the originating sub-server.
     */
    private Outcome handleLite(String sourceServerName, Packet packet) {
        List<Packet> downstream;
        try {
            downstream = localAuthority.apply(packet);
        } catch (RuntimeException e) {
            // A faulty authority must not take down the proxy event thread.
            platform.logError("Lite authority failed on a novachat:main packet from "
                    + sourceServerName, e);
            return Outcome.DROPPED_MALFORMED;
        }
        if (downstream == null || downstream.isEmpty()) {
            platform.logDebug("Lite authority produced no response for "
                    + packet.getClass().getSimpleName() + " from " + sourceServerName);
            return Outcome.ACCEPTED;
        }

        for (Packet response : downstream) {
            deliverLocally(response);
            if (response instanceof ChatMessagePacket chat) {
                // Chat is a network-wide line: any sub-server may have players
                // connected straight to it. Broadcasting (rather than echoing
                // only to the source) keeps this rule identical to the
                // proxy-player path; each server excludes its own proxy players.
                broadcastChat(chat);
            } else if (response instanceof ChannelActionResponsePacket actionResponse
                    && isTargetedModeration(actionResponse)) {
                // A moderation response names a target that may be connected
                // straight to any sub-server, not necessarily the one that
                // issued the command. Echoing only to the source leaves that
                // player unnotified, so broadcast instead; servers with no
                // pending context drop the response, which makes the extra
                // copies harmless.
                for (String serverName : downstreamServers()) {
                    sendDownstream(serverName, actionResponse,
                            proxyPlayersOn(serverName));
                }
            } else {
                sendDownstream(sourceServerName, response,
                        proxyPlayersOn(sourceServerName));
            }
        }
        return Outcome.ACCEPTED;
    }

    /**
     * Whether this action response concerns a specific player who may be on
     * another server.
     *
     * <p>Only these need broadcasting: JOIN/LEAVE/WHO answer the caller, whose
     * connection is by definition the source server, while MUTE/KICK/BAN notify
     * a target that can be anywhere.
     *
     * @param response the action response
     * @return {@code true} when the response names a target player
     */
    private static boolean isTargetedModeration(ChannelActionResponsePacket response) {
        ChannelAction action = response.getAction();
        if (action != ChannelAction.MUTE && action != ChannelAction.KICK
                && action != ChannelAction.BAN && action != ChannelAction.UNBAN
                && action != ChannelAction.UNMUTE) {
            return response.isSuccess() && response.getExtra("targetId") != null;
        }
        // A rejected command has nobody to notify, and the operator who issued
        // it is on the source server anyway, so broadcasting it would only
        // multiply the same error across servers.
        return response.isSuccess() && response.getExtra("targetId") != null;
    }

    /**
     * Dispatches one authority response to the shared chat core handlers,
     * mirroring {@code LocalAuthorityClient}'s delivery switch.
     */
    private void deliverLocally(Packet packet) {
        if (packet instanceof ChatMessagePacket chat) {
            chatCore.handleIncomingMessage(chat);
        } else if (packet instanceof MentionPacket mention) {
            chatCore.handleMention(mention);
        } else if (packet instanceof TitlePacket title) {
            chatCore.handleTitle(title);
        } else if (packet instanceof ItemDisplayPacket itemDisplay) {
            chatCore.handleItemDisplay(itemDisplay);
        } else if (packet instanceof PrivateMessagePacket privateMessage) {
            chatCore.handlePrivateMessage(privateMessage);
        } else if (packet instanceof ChannelActionResponsePacket response) {
            chatCore.handleChannelActionResponse(response);
        } else {
            platform.logDebug("Lite has no local handler for "
                    + packet.getClass().getSimpleName());
        }
    }

    /**
     * Sends one packet back to the sub-server so it can render players that are
     * connected to it directly (the proxy has no handle on them).
     *
     * <p>A failure here is expected when the server has no players online: the
     * Minecraft connection to it does not exist, so plugin messaging cannot
     * deliver. It is logged as debug and the packet is dropped — never reported
     * as sent.
     */
    private void sendDownstream(String serverName, Packet packet, Set<UUID> excluded) {
        byte[] payload;
        try {
            payload = ProxyChannelProtocol.encode(proxyIdentity(), packet, excluded);
        } catch (ProxyChannelException e) {
            platform.logWarn("Dropped downstream " + packet.getClass().getSimpleName()
                    + " for " + serverName + ": " + e.getMessage());
            return;
        }
        boolean sent = platform.sendPluginMessage(
                serverName, ProxyChannelProtocol.CHANNEL_ID, payload);
        if (!sent) {
            platform.logDebug("Dropped downstream " + packet.getClass().getSimpleName()
                    + " for " + serverName + ": no players online on that server");
            return;
        }
        platform.logDebug("Sent downstream " + packet.getClass().getSimpleName()
                + " to " + serverName + " (excluding " + excluded.size()
                + " proxy-rendered player(s))");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * @param serverName the downstream server
     * @return UUIDs of the proxy's players currently on that server, i.e. the
     *         players the proxy has already rendered the fan-out to
     */
    private Set<UUID> proxyPlayersOn(String serverName) {
        Set<UUID> ids = new HashSet<>();
        for (ProxyPlayer player : platform.getOnlinePlayers()) {
            if (serverName.equalsIgnoreCase(player.getCurrentServer())) {
                ids.add(player.getUniqueId());
            }
        }
        return ids;
    }

    /** @return the proxy's own identity for the downstream envelope */
    private String proxyIdentity() {
        String id = chatCore.getStates().getConfig().getClientId();
        return id != null && !id.isBlank() ? id : "proxy";
    }
}
