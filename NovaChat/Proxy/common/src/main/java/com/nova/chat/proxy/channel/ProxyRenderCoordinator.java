package com.nova.chat.proxy.channel;

import com.google.gson.Gson;
import com.nova.chat.channel.ProxyChannelException;
import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.channel.RenderPacketCodec;
import com.nova.chat.channel.RenderCoordinationProtocol;
import com.nova.chat.channel.RenderCoordinationProtocol.Frame;
import com.nova.chat.channel.RenderCoordinationProtocol.Kind;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class ProxyRenderCoordinator {
    private static final UUID ZERO = new UUID(0, 0);
    private static final int LIMIT = 512;
    private static final int MAX_ASSEMBLY_BYTES = 16 * 1024 * 1024;
    private static final Gson JSON = new Gson();
    private final ProxyPlatform platform;
    private final Supplier<ProxyConfigView> config;
    private final BiConsumer<Packet, UUID> offerRenderer;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();

    private record Key(int packetId, UUID requestId) { }
    private record Pending(Key key, String channel, Runnable render) { }

    private static final class Assembly {
        final byte[] bytes;
        final java.util.BitSet received = new java.util.BitSet();
        int count;
        long touched = System.nanoTime();

        Assembly(int length) { bytes = new byte[length]; }
    }

    private static final class Session {
        final UUID player;
        final String server;
        final Object connection;
        final String secret;
        UUID proxyNonce = UUID.randomUUID();
        final Map<Key, Pending> pending = new LinkedHashMap<>();
        final Map<Key, Kind> completed = new LinkedHashMap<>();
        final Map<Key, Assembly> assemblies = new LinkedHashMap<>();
        int assemblyBytes;
        UUID bukkitNonce;
        boolean ready;
        boolean retryScheduled;
        boolean overflowWarned;
        int retries;

        Session(UUID player, String server, Object connection, String secret) {
            this.player = player;
            this.server = server;
            this.connection = connection;
            this.secret = secret;
        }
    }

    public ProxyRenderCoordinator(ProxyPlatform platform, Supplier<ProxyConfigView> config,
                                  BiConsumer<Packet, UUID> offerRenderer) {
        this.platform = platform;
        this.config = config;
        this.offerRenderer = offerRenderer;
    }

    public synchronized void deliver(Packet packet, ProxyPlayer player, String channel, Runnable render) {
        if (secret(player.getCurrentServer()) == null) {
            render.run();
            return;
        }
        Session session = sessionFor(player);
        if (session == null) {
            platform.logWarn("Render coordination has no live connection for " + player.getUniqueId());
            return;
        }
        Key key = new Key(packet.getPacketId(), packet.getRequestId());
        if (session.completed.containsKey(key) || session.pending.containsKey(key)) return;
        discardAssembly(session, key);
        if (session.pending.size() >= LIMIT) {
            if (!session.overflowWarned) {
                session.overflowWarned = true;
                platform.logWarn("Render coordination queue full for " + session.player + "; no legacy fallback");
            }
            return;
        }
        Pending pending = new Pending(key, channel == null ? "" : channel, render);
        session.pending.put(key, pending);
        if (!session.ready) probe(session);
        else claim(session, pending.key, Kind.CLAIM);
        scheduleRetry(session);
    }

    public synchronized void connected(ProxyPlayer player) {
        Session session = sessionFor(player);
        if (session != null && !session.ready) {
            probe(session);
            scheduleRetry(session);
        }
    }

    private Session sessionFor(ProxyPlayer player) {
        String server = player.getCurrentServer();
        String secret = secret(server);
        Object connection = platform.getPlayerConnectionIdentity(player.getUniqueId());
        if (secret == null || connection == null) return null;
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.connection != connection || !session.secret.equals(secret)
                || !session.server.equalsIgnoreCase(server)) {
            session = new Session(player.getUniqueId(), server, connection, secret);
            sessions.put(player.getUniqueId(), session);
        }
        return session;
    }

    public synchronized void receive(String server, UUID carrier, Object source, byte[] payload) {
        Session session = sessions.get(carrier);
        if (session == null || source != session.connection || !current(session)
                || !session.server.equalsIgnoreCase(server)) return;
        Frame frame;
        Packet offered = null;
        RenderCoordinationProtocol.OfferPart part = null;
        try {
            if (payload != null && payload.length > 105 && Byte.toUnsignedInt(payload[3]) == Kind.OFFER_PART.ordinal()) {
                part = RenderCoordinationProtocol.decodeOfferPart(payload, session.secret);
                frame = part.frame();
            } else if (payload != null && payload.length > 105) {
                var offer = RenderCoordinationProtocol.decodeOffer(payload, session.secret);
                frame = offer.frame();
                var envelope = ProxyChannelProtocol.decode(offer.envelope());
                offered = envelope.getPacket();
                if (!envelope.getSenderId().equalsIgnoreCase(server)
                        || offered.getPacketId() != frame.packetId()
                        || !offered.getRequestId().equals(frame.requestId())
                        || !envelope.getExcludedRecipients().isEmpty()) return;
            } else {
                frame = RenderCoordinationProtocol.decode(payload, session.secret);
            }
        } catch (ProxyChannelException error) {
            platform.logDebug("Rejected render control: " + error.getMessage());
            return;
        }
        if (!session.player.equals(frame.playerId()) || !session.proxyNonce.equals(frame.proxySession())) return;
        if (frame.kind() == Kind.HELLO) {
            if (session.ready) return;
            if (session.bukkitNonce != null && !session.bukkitNonce.equals(frame.bukkitSession())) {
                restartChallenge(session);
                return;
            }
            session.bukkitNonce = frame.bukkitSession();
            send(session, new Frame(Kind.CONFIRM, session.player, session.proxyNonce, session.bukkitNonce, ZERO, 0, false));
            return;
        }
        if (frame.kind() == Kind.CONFIRMED && frame.bukkitSession().equals(session.bukkitNonce)) {
            if (!session.ready) event("negotiated", session, null, null);
            session.ready = true;
            for (Pending pending : List.copyOf(session.pending.values())) claim(session, pending.key, Kind.CLAIM);
            return;
        }
        if (!session.ready || !frame.bukkitSession().equals(session.bukkitNonce)) return;
        Key key = new Key(frame.packetId(), frame.requestId());
        if (part != null) {
            Kind disposition = session.completed.get(key);
            if (disposition != null || session.pending.containsKey(key)) {
                discardAssembly(session, key);
                claim(session, key, disposition == null ? Kind.CLAIM : disposition);
                return;
            }
            byte[] bytes = assemble(session, key, part);
            if (bytes == null) return;
            try {
                offered = RenderPacketCodec.decode(bytes);
                if (offered.getPacketId() != frame.packetId() || !offered.getRequestId().equals(frame.requestId())) return;
            } catch (ProxyChannelException error) {
                platform.logDebug("Rejected assembled render packet: " + error.getMessage());
                return;
            }
        }
        if ((frame.kind() == Kind.OFFER || frame.kind() == Kind.OFFER_PART) && offered != null) {
            Kind completed = session.completed.get(key);
            if (completed != null) {
                claim(session, key, completed);
                return;
            }
            offerRenderer.accept(offered, carrier);
            if (!session.pending.containsKey(key) && !session.completed.containsKey(key)) {
                completed(session, key, Kind.SKIP);
                claim(session, key, Kind.SKIP);
            }
            return;
        }
        if (frame.kind() != Kind.DECISION) return;
        Pending pending = session.pending.remove(key);
        if (pending == null) return;
        completed(session, key, Kind.CLAIM);
        event("decision", session, pending, frame.proxyOwner() ? "proxy" : "backend");
        if (frame.proxyOwner()) {
            platform.execute(() -> {
                synchronized (ProxyRenderCoordinator.this) {
                    // The physical connection, not a handshake epoch, owns this accepted render.
                    if (current(session)) pending.render.run();
                }
            });
        }
    }

    private byte[] assemble(Session session, Key key, RenderCoordinationProtocol.OfferPart part) {
        byte[] data = part.data();
        int stride = RenderCoordinationProtocol.MAX_PART_DATA_BYTES;
        if (part.offset() % stride != 0 || data.length != Math.min(stride, part.totalLength() - part.offset())) return null;
        long now = System.nanoTime();
        for (Key old : List.copyOf(session.assemblies.keySet())) {
            if (now - session.assemblies.get(old).touched > java.util.concurrent.TimeUnit.SECONDS.toNanos(30)) {
                discardAssembly(session, old);
            }
        }
        Assembly assembly = session.assemblies.get(key);
        if (assembly == null) {
            if (session.assemblies.size() >= LIMIT || session.assemblyBytes + part.totalLength() > MAX_ASSEMBLY_BYTES) {
                platform.logWarn("Render coordination assembly capacity reached for " + session.player);
                return null;
            }
            assembly = new Assembly(part.totalLength());
            session.assemblies.put(key, assembly);
            session.assemblyBytes += assembly.bytes.length;
        }
        if (assembly.bytes.length != part.totalLength()) { discardAssembly(session, key); return null; }
        int index = part.offset() / stride;
        if (assembly.received.get(index)) {
            for (int i = 0; i < data.length; i++) {
                if (assembly.bytes[part.offset() + i] != data[i]) { discardAssembly(session, key); return null; }
            }
            return null;
        }
        System.arraycopy(data, 0, assembly.bytes, part.offset(), data.length);
        assembly.received.set(index);
        assembly.count += data.length;
        assembly.touched = now;
        if (assembly.count != assembly.bytes.length) return null;
        discardAssembly(session, key);
        return assembly.bytes;
    }

    private void discardAssembly(Session session, Key key) {
        Assembly removed = session.assemblies.remove(key);
        if (removed != null) session.assemblyBytes -= removed.bytes.length;
    }

    private void completed(Session session, Key key, Kind disposition) {
        session.completed.put(key, disposition);
        if (session.completed.size() > LIMIT) session.completed.remove(session.completed.keySet().iterator().next());
    }

    public synchronized void forget(UUID playerId) {
        sessions.remove(playerId);
    }

    public synchronized void refresh() {
        sessions.values().removeIf(session -> !current(session));
    }

    private String secret(String server) {
        Map<String, String> secrets = config.get().getRenderCoordinationSecrets();
        return server == null || secrets == null ? null : secrets.get(server.toLowerCase(Locale.ROOT));
    }

    private boolean current(Session session) {
        ProxyPlayer player = platform.getPlayer(session.player);
        return sessions.get(session.player) == session && player != null
                && platform.getPlayerConnectionIdentity(session.player) == session.connection
                && session.server.equalsIgnoreCase(player.getCurrentServer())
                && session.secret.equals(secret(session.server));
    }

    private void restartChallenge(Session session) {
        session.ready = false;
        session.assemblies.clear();
        session.assemblyBytes = 0;
        session.bukkitNonce = null;
        session.proxyNonce = UUID.randomUUID();
        probe(session);
        scheduleRetry(session);
    }

    private void probe(Session session) {
        send(session, new Frame(Kind.PROBE, session.player, session.proxyNonce, ZERO, ZERO, 0, false));
    }

    private void claim(Session session, Key key, Kind kind) {
        send(session, new Frame(kind, session.player, session.proxyNonce, session.bukkitNonce,
                key.requestId, key.packetId, false));
    }

    private void send(Session session, Frame frame) {
        if (!current(session)) return;
        try {
            if (!platform.sendPlayerPluginMessage(session.player, session.connection, RenderCoordinationProtocol.CHANNEL_ID,
                    RenderCoordinationProtocol.encode(frame, session.secret))) {
                platform.logDebug("Render control awaits connection for " + session.player);
            }
        } catch (ProxyChannelException | RuntimeException error) {
            platform.logDebug("Render control unavailable: " + error.getMessage());
        }
    }

    private void scheduleRetry(Session session) {
        if (session.retryScheduled || (session.pending.isEmpty() && session.ready)) return;
        session.retryScheduled = true;
        platform.runLater(() -> retry(session), 2);
    }

    private synchronized void retry(Session session) {
        session.retryScheduled = false;
        if (!current(session) || (session.pending.isEmpty() && session.ready)) return;
        if (++session.retries == 5) {
            platform.logWarn("Render coordination unconfirmed for " + session.player
                    + "; check both peer versions and shared secret; no legacy fallback");
        }
        if (session.ready) restartChallenge(session);
        else probe(session);
        scheduleRetry(session);
    }

    private void event(String kind, Session session, Pending pending, String owner) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", kind);
        data.put("atMs", System.currentTimeMillis());
        data.put("version", 1);
        data.put("server", session.server);
        data.put("playerId", session.player);
        data.put("session", session.proxyNonce);
        if (pending != null) {
            data.put("packetId", pending.key.packetId);
            data.put("requestId", pending.key.requestId);
            data.put("channelId", pending.channel);
            data.put("owner", owner);
        }
        platform.logDebug("NOVA_RENDER_EVENT " + JSON.toJson(data));
    }
}
