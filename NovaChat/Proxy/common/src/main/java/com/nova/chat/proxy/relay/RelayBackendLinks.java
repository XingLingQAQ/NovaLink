package com.nova.chat.proxy.relay;

import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Relay-mode backend link set: one connection per downstream server.
 *
 * <p><b>Why a link per server.</b> The backend decides where a player's
 * mention, title and private message go by looking up the connection for
 * {@code PlayerState#clientId}, and it enforces SERVER/PRIVATE channel
 * ownership by comparing the sending connection's client id with the channel
 * owner. With a single proxy-wide connection every sub-server looked like one
 * client: players were attributed to the proxy, and channel isolation collapsed.
 * Giving each downstream server its own authenticated connection makes
 * {@code connection.getClientId()} — which is what the backend stamps on the
 * player state on JOIN — the server the player is actually on.
 *
 * <p><b>Fallback identity.</b> {@code backend.username} remains the identity
 * used for servers that have no entry in {@code backend.identities}, so an
 * existing config with one downstream server keeps exactly one connection and
 * needs no change. When every server is mapped, the operator can leave the
 * username empty and only the mapped links exist.
 *
 * <p><b>Routing.</b>
 * <ul>
 *   <li>Upstream: {@link #sendUpstream(String, Packet)} sends on the source
 *       server's link, or on the fallback link when it has none.</li>
 *   <li>Downstream: each link registers the inbound handlers, so a packet that
 *       arrived on server X's link is delivered to X only. The fallback link
 *       covers every server that has no dedicated link, which preserves the
 *       legacy broadcast when {@code backend.identities} is empty.</li>
 * </ul>
 *
 * <p><b>Lifecycle.</b> Links are created once by {@link #start(String, int)} and
 * live until {@link #close()}; each {@link com.nova.chat.client.network.CoreNetworkClient}
 * owns its own reconnect policy, so one server's outage never touches the
 * others. Nothing here tears a link down when a server's population drops to
 * zero: a sub-server with no players still sends traffic (a console command
 * forwarded over plugin messaging) and must keep its connection.
 */
public final class RelayBackendLinks implements BackendRouter {

    /** Inbound packet types the shared chat core renders. */
    private static final List<Class<? extends Packet>> INBOUND_TYPES = List.of(
            ChatMessagePacket.class,
            ChannelActionResponsePacket.class,
            MentionPacket.class,
            TitlePacket.class,
            ItemDisplayPacket.class,
            PrivateMessagePacket.class);

    /** Creates the platform network facade for one identity. */
    public interface ClientFactory {

        /**
         * @param identity the backend client username the facade must
         *                 authenticate as
         * @return an unconnected facade
         */
        AbstractPlatformNetworkClient create(String identity);
    }

    /**
     * Receives one packet the backend pushed, after it has been routed to the
     * downstream servers.
     */
    public interface PacketListener {

        /**
         * @param link the link the packet arrived on (the default link for the
         *             fallback connection)
         * @param scopeServers the servers whose proxy players should render it;
         *                     {@code null} means "every proxy player" (legacy
         *                     single-connection behaviour), empty means none
         * @param packet the packet
         */
        void accept(AbstractPlatformNetworkClient link, Set<String> scopeServers, Packet packet);
    }

    /** Sends one packet to exactly one downstream server. */
    public interface TargetedSender {

        /**
         * @param serverName the downstream server
         * @param packet the packet
         * @return whether a player connection on that server carried it
         */
        boolean send(String serverName, Packet packet);
    }

    /** Broadcasts one packet to every server that has a proxy player. */
    public interface BroadcastSender {

        /**
         * @param packet the packet
         */
        void send(Packet packet);
    }

    /** One established route: a server (or the fallback) and its identity. */
    public static final class Link {

        private final String serverName;
        private final String identity;
        private final AbstractPlatformNetworkClient client;

        private Link(String serverName, String identity, AbstractPlatformNetworkClient client) {
            this.serverName = serverName;
            this.identity = identity;
            this.client = client;
        }

        /** @return the server, or {@code null} for the fallback link */
        public String getServerName() {
            return serverName;
        }

        /** @return the backend client username this link authenticates as */
        public String getIdentity() {
            return identity;
        }

        /** @return the transport facade */
        public AbstractPlatformNetworkClient getClient() {
            return client;
        }
    }

    private final ProxyPlatform platform;
    private final ClientFactory factory;
    private final String defaultIdentity;
    /** Normalised server name → configured identity, in declaration order. */
    private final Map<String, String> identitiesByServer;

    private final Map<String, Link> linksByServer = new LinkedHashMap<>();
    private final List<Link> links = new CopyOnWriteArrayList<>();
    private volatile Link fallbackLink;

    private volatile PacketListener listener;
    private volatile TargetedSender targetedSender;
    private volatile BroadcastSender broadcastSender;
    private volatile Consumer<Link> linkListener;
    private volatile boolean started;
    private volatile boolean closed;

    /**
     * @param platform the platform adapter (roster lookup + async dispatch)
     * @param factory creates the facade for an identity
     * @param defaultIdentity the fallback identity ({@code backend.username}),
     *                        or null/blank to disable the fallback link
     * @param identitiesByServer {@code backend.identities}: server name →
     *                           backend username
     */
    public RelayBackendLinks(ProxyPlatform platform,
                             ClientFactory factory,
                             String defaultIdentity,
                             Map<String, String> identitiesByServer) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.defaultIdentity = defaultIdentity != null && !defaultIdentity.isBlank()
                ? defaultIdentity : null;
        this.identitiesByServer = new LinkedHashMap<>();
        if (identitiesByServer != null) {
            for (Map.Entry<String, String> entry : identitiesByServer.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().isBlank()) {
                    continue;
                }
                this.identitiesByServer.put(normalise(entry.getKey()), entry.getValue());
            }
        }
    }

    // ------------------------------------------------------------------ wiring

    /** @param sink receives backend-originated packets after downstream routing */
    public void bindInbound(PacketListener sink) {
        this.listener = sink;
    }

    /**
     * @param targeted sends one packet to one downstream server
     * @param broadcast sends one packet to every server with a proxy player
     */
    public void bindDownstream(TargetedSender targeted, BroadcastSender broadcast) {
        this.targetedSender = targeted;
        this.broadcastSender = broadcast;
    }

    /**
     * Observes every link as it is created, before it connects. The shells use
     * this to register the per-link ConfigSync handler with that link's own
     * identity (so a sub-server sees its own per-client channel roster).
     *
     * @param observer the observer, or null
     */
    public void onLinkCreated(Consumer<Link> observer) {
        this.linkListener = observer;
    }

    // --------------------------------------------------------------- lifecycle

    /**
     * Creates and connects every configured link. Idempotent; a second call
     * returns the links already created.
     *
     * <p>Each connect is dispatched through {@link ProxyPlatform#runAsync} and
     * guarded individually: a failure for one identity is reported by that
     * link's own client and never aborts the others.
     *
     * @param host backend host
     * @param port backend port
     * @return the created links, fallback first
     */
    public synchronized List<Link> start(String host, int port) {
        if (started) {
            return snapshot();
        }
        started = true;
        closed = false;

        if (defaultIdentity != null) {
            fallbackLink = createLink(null, defaultIdentity, host, port);
        }
        for (Map.Entry<String, String> entry : identitiesByServer.entrySet()) {
            Link link = createLink(entry.getKey(), entry.getValue(), host, port);
            if (link != null) {
                linksByServer.put(entry.getKey(), link);
                links.add(link);
            }
        }
        return snapshot();
    }

    /**
     * Disconnects every link and forgets them. Safe to call more than once; a
     * later {@link #start(String, int)} on the same instance re-creates.
     */
    public synchronized void close() {
        closed = true;
        started = false;
        for (Link link : snapshot()) {
            try {
                link.getClient().disconnect();
            } catch (RuntimeException e) {
                platform.logWarn("Failed to close the backend link for "
                        + describe(link) + ": " + e.getMessage());
            }
        }
        links.clear();
        linksByServer.clear();
        fallbackLink = null;
    }

    private Link createLink(String serverName, String identity, String host, int port) {
        AbstractPlatformNetworkClient client;
        try {
            client = factory.create(identity);
        } catch (RuntimeException e) {
            platform.logError("Failed to create the backend link for "
                    + (serverName != null ? "server " + serverName : "the fallback identity")
                    + " (" + identity + ")", e);
            return null;
        }
        Link link = new Link(serverName, identity, client);
        registerInbound(link);
        Consumer<Link> observer = linkListener;
        if (observer != null) {
            try {
                observer.accept(link);
            } catch (RuntimeException e) {
                platform.logError("Backend link observer failed for " + describe(link), e);
            }
        }
        platform.runAsync(() -> connect(link, host, port));
        return link;
    }

    private void connect(Link link, String host, int port) {
        if (closed) {
            return;
        }
        try {
            // Completes asynchronously; the client schedules its own reconnect
            // on failure, independently of every other link.
            link.getClient().connect(host, port);
        } catch (RuntimeException e) {
            platform.logError("Failed to connect the backend link for " + describe(link), e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void registerInbound(Link link) {
        for (Class<? extends Packet> type : INBOUND_TYPES) {
            link.getClient().registerHandler((Class) type,
                    (Consumer<Packet>) packet -> handleInbound(link, packet));
        }
    }

    // ---------------------------------------------------------------- routing

    /**
     * Routes one packet the backend pushed on {@code origin}'s link.
     *
     * <p>A dedicated link delivers to its own server only. The fallback link
     * delivers to every server that has no dedicated link — when no server has
     * one, that is the legacy "every server with players" broadcast.
     *
     * @param origin the link the packet arrived on
     * @param packet the packet
     */
    public void handleInbound(Link origin, Packet packet) {
        if (origin == null || packet == null) {
            return;
        }
        String dedicated = origin.getServerName();
        Set<String> scope;
        if (dedicated != null) {
            scope = Set.of(dedicated);
            TargetedSender target = targetedSender;
            if (target != null) {
                target.send(dedicated, packet);
            }
        } else if (!hasDedicatedLinks()) {
            BroadcastSender broadcast = broadcastSender;
            if (broadcast != null) {
                broadcast.send(packet);
            }
            scope = null;
        } else {
            scope = unroutedServersWithPlayers();
            TargetedSender target = targetedSender;
            if (target != null) {
                for (String server : scope) {
                    target.send(server, packet);
                }
            }
        }
        PacketListener sink = listener;
        if (sink != null) {
            sink.accept(origin.getClient(), scope, packet);
        }
    }

    /**
     * Sends one upstream packet on the source server's link.
     *
     * @param sourceServer the server the packet arrived from
     * @param packet the packet
     * @return whether it was handed to an authenticated link
     */
    public boolean sendUpstream(String sourceServer, Packet packet) {
        if (packet == null) {
            return false;
        }
        AbstractPlatformNetworkClient client = clientFor(sourceServer);
        if (client == null) {
            platform.logDebug("Dropped " + packet.getClass().getSimpleName()
                    + " from " + sourceServer + ": no backend link is configured for it");
            return false;
        }
        if (!client.isAuthenticated()) {
            platform.logDebug("Dropped " + packet.getClass().getSimpleName()
                    + " from " + sourceServer + ": its backend link is not authenticated");
            return false;
        }
        client.sendPacket(packet);
        return true;
    }

    // ------------------------------------------------------------- BackendRouter

    @Override
    public AbstractPlatformNetworkClient clientFor(String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            Link link = linksByServer.get(normalise(serverName));
            if (link != null) {
                return link.getClient();
            }
        }
        Link fallback = fallbackLink;
        return fallback != null ? fallback.getClient() : null;
    }

    @Override
    public String identityFor(String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            Link link = linksByServer.get(normalise(serverName));
            if (link != null) {
                return link.getIdentity();
            }
        }
        return defaultIdentity;
    }

    // ------------------------------------------------------------------ access

    /** @return the fallback ({@code backend.username}) link, or null */
    public Link getFallbackLink() {
        return fallbackLink;
    }

    /** @return the fallback identity, or null when disabled */
    public String getDefaultIdentity() {
        return defaultIdentity;
    }

    /** @return every link, fallback first, in creation order */
    public List<Link> links() {
        return snapshot();
    }

    /** @return whether {@code backend.identities} declared at least one server */
    public boolean hasDedicatedLinks() {
        return !linksByServer.isEmpty();
    }

    /**
     * @param serverName the server name (case-insensitive)
     * @return whether that server has its own link
     */
    public boolean isDedicated(String serverName) {
        return serverName != null && linksByServer.containsKey(normalise(serverName));
    }

    /** @return the configured dedicated server names, in declaration order */
    public Set<String> dedicatedServers() {
        Set<String> names = new LinkedHashSet<>();
        for (Link link : snapshot()) {
            if (link.getServerName() != null) {
                names.add(link.getServerName());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    /**
     * @return the servers that currently have a proxy player but no dedicated
     *         link, i.e. the servers the fallback link is responsible for
     */
    private Set<String> unroutedServersWithPlayers() {
        Set<String> servers = new LinkedHashSet<>();
        for (ProxyPlayer player : platform.getOnlinePlayers()) {
            String server = player.getCurrentServer();
            if (server == null || server.isBlank()) {
                continue;
            }
            if (!isDedicated(server)) {
                servers.add(server);
            }
        }
        return servers;
    }

    private List<Link> snapshot() {
        List<Link> all = new ArrayList<>(links.size() + 1);
        Link fallback = fallbackLink;
        if (fallback != null) {
            all.add(fallback);
        }
        all.addAll(links);
        return List.copyOf(all);
    }

    private static String normalise(String serverName) {
        return serverName.toLowerCase(Locale.ROOT);
    }

    private static String describe(Link link) {
        return link.getServerName() != null
                ? "server " + link.getServerName()
                : "the fallback identity";
    }
}
