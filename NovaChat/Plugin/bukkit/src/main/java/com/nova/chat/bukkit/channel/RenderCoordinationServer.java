package com.nova.chat.bukkit.channel;

import com.google.gson.Gson;
import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.channel.ProxyChannelException;
import com.nova.chat.channel.RenderPacketCodec;
import com.nova.chat.channel.RenderCoordinationProtocol;
import com.nova.chat.channel.RenderCoordinationProtocol.Frame;
import com.nova.chat.channel.RenderCoordinationProtocol.Kind;
import com.nova.chat.common.protocol.Packet;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RenderCoordinationServer {
    private static final UUID ZERO = new UUID(0, 0);
    private static final int LIMIT = 512;
    private static final int MAX_PENDING_BYTES = 16 * 1024 * 1024;
    private static final Gson JSON = new Gson();
    private final NovaChatBukkit plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private String activeSecret;

    private record Key(int packetId, UUID requestId) { }
    private static final class Session {
        final Player player;
        UUID proxyNonce;
        UUID bukkitNonce = UUID.randomUUID();
        final Set<UUID> retired = new LinkedHashSet<>();
        final Map<Key, byte[]> offers = new LinkedHashMap<>();
        int pendingBytes;
        boolean ownsRendering;
        boolean confirmed;
        boolean retryScheduled;
        boolean overflowWarned;

        Session(Player player, UUID proxyNonce) {
            this.player = player;
            this.proxyNonce = proxyNonce;
        }
    }

    public RenderCoordinationServer(NovaChatBukkit plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        var config = plugin.getNovaChatConfig();
        return config != null && config.isPluginMessagingEnabled()
                && config.getRenderCoordinationSecret() != null && !config.getRenderCoordinationSecret().isEmpty();
    }

    public synchronized boolean owns(UUID playerId) {
        if (!enabled()) return false;
        refreshSecret();
        Session session = sessions.get(playerId);
        return session != null && session.ownsRendering && current(session);
    }

    public synchronized boolean authenticated(Player player) {
        if (player == null || !owns(player.getUniqueId())) return false;
        return sessions.get(player.getUniqueId()).player == player;
    }

    public synchronized void forget(UUID playerId) {
        sessions.remove(playerId);
    }

    // Backend handlers only; ordinary v1 downstream traffic must never be signed here.
    public synchronized Set<UUID> offer(Packet packet) {
        if (!enabled()) return Set.of();
        refreshSecret();
        Set<UUID> deferred = new HashSet<>();
        byte[] packetBytes = null;
        for (Session session : java.util.List.copyOf(sessions.values())) {
            if (!session.ownsRendering || !current(session)) continue;
            UUID playerId = session.player.getUniqueId();
            deferred.add(playerId);
            Key key = new Key(packet.getPacketId(), packet.getRequestId());
            if (!session.offers.containsKey(key)) {
                if (session.offers.size() >= LIMIT || session.pendingBytes >= MAX_PENDING_BYTES) {
                    if (!session.overflowWarned) {
                        session.overflowWarned = true;
                        plugin.getLogger().warning("Render coordination offer queue full for " + playerId + "; no legacy fallback");
                    }
                    continue;
                }
                try {
                    if (packetBytes == null) packetBytes = RenderPacketCodec.encode(packet);
                    byte[] encoded = packetBytes;
                    if (session.pendingBytes + encoded.length > MAX_PENDING_BYTES) {
                        plugin.getLogger().warning("Render coordination pending-byte limit for " + playerId);
                        continue;
                    }
                    session.offers.put(key, encoded);
                    session.pendingBytes += encoded.length;
                } catch (ProxyChannelException error) {
                    plugin.getLogger().warning("Cannot coordinate backend render: " + error.getMessage());
                    continue;
                }
            }
            sendOffer(session, key);
            scheduleRetry(session);
        }
        return Set.copyOf(deferred);
    }

    public synchronized void receive(Player carrier, byte[] payload) {
        if (!enabled() || carrier == null || plugin.getServer().getPlayer(carrier.getUniqueId()) != carrier) return;
        String secret = refreshSecret();
        Frame frame;
        try {
            frame = RenderCoordinationProtocol.decode(payload, secret);
        } catch (ProxyChannelException error) {
            plugin.debug("Rejected render control: " + error.getMessage());
            return;
        }
        if (!carrier.getUniqueId().equals(frame.playerId())) return;
        Session session = sessions.get(frame.playerId());
        Frame response;
        if (frame.kind() == Kind.PROBE) {
            if (session == null || session.player != carrier) {
                session = new Session(carrier, frame.proxySession());
                sessions.put(frame.playerId(), session);
            } else if (!session.proxyNonce.equals(frame.proxySession())) {
                if (session.retired.contains(frame.proxySession())) return;
                session.retired.add(session.proxyNonce);
                if (session.retired.size() > LIMIT) session.retired.remove(session.retired.iterator().next());
                session.proxyNonce = frame.proxySession();
                session.bukkitNonce = UUID.randomUUID();
                session.confirmed = false;
            }
            response = new Frame(Kind.HELLO, frame.playerId(), session.proxyNonce, session.bukkitNonce, ZERO, 0, false);
        } else if (frame.kind() == Kind.CONFIRM && session != null && session.player == carrier
                && session.proxyNonce.equals(frame.proxySession()) && session.bukkitNonce.equals(frame.bukkitSession())) {
            if (!session.confirmed) {
                session.confirmed = true;
                session.ownsRendering = true;
                plugin.debug("NOVA_RENDER_EVENT " + JSON.toJson(Map.of("event", "negotiated", "version", 1,
                        "server", plugin.getNovaChatConfig().getPluginMessagingServerId(),
                        "playerId", frame.playerId(), "session", session.proxyNonce)));
            }
            response = new Frame(Kind.CONFIRMED, frame.playerId(), session.proxyNonce, session.bukkitNonce, ZERO, 0, false);
        } else if ((frame.kind() == Kind.CLAIM || frame.kind() == Kind.SKIP) && session != null
                && session.confirmed && session.player == carrier
                && session.proxyNonce.equals(frame.proxySession()) && session.bukkitNonce.equals(frame.bukkitSession())) {
            var interceptor = plugin.getChatInterceptor();
            if (interceptor == null) return;
            Key key = new Key(frame.packetId(), frame.requestId());
            boolean proxyOwner = interceptor.claimProxyRender(frame.packetId(), frame.requestId(), frame.playerId());
            byte[] removed = session.offers.remove(key);
            if (removed != null) session.pendingBytes -= removed.length;
            response = new Frame(Kind.DECISION, frame.playerId(), session.proxyNonce, session.bukkitNonce,
                    frame.requestId(), frame.packetId(), frame.kind() == Kind.CLAIM && proxyOwner);
        } else {
            return;
        }
        send(session, RenderCoordinationProtocol.CHANNEL_ID, response, secret);
        if (frame.kind() == Kind.CONFIRM) {
            for (Key key : java.util.List.copyOf(session.offers.keySet())) sendOffer(session, key);
            scheduleRetry(session);
        }
    }

    private boolean current(Session session) {
        return sessions.get(session.player.getUniqueId()) == session
                && plugin.getServer().getPlayer(session.player.getUniqueId()) == session.player;
    }

    private void send(Session session, String channel, Frame frame, String secret) {
        if (!current(session)) return;
        try {
            session.player.sendPluginMessage(plugin, channel, RenderCoordinationProtocol.encode(frame, secret));
        } catch (ProxyChannelException | RuntimeException error) {
            plugin.debug("Render control reply unavailable: " + error.getMessage());
        }
    }

    private void sendOffer(Session session, Key key) {
        if (!current(session) || !session.confirmed) return;
        byte[] envelope = session.offers.get(key);
        if (envelope == null) return;
        try {
            Frame frame = new Frame(Kind.OFFER_PART, session.player.getUniqueId(), session.proxyNonce,
                    session.bukkitNonce, key.requestId, key.packetId, false);
            for (int offset = 0; offset < envelope.length; offset += RenderCoordinationProtocol.MAX_PART_DATA_BYTES) {
                int end = Math.min(envelope.length, offset + RenderCoordinationProtocol.MAX_PART_DATA_BYTES);
                byte[] chunk = java.util.Arrays.copyOfRange(envelope, offset, end);
                session.player.sendPluginMessage(plugin, RenderCoordinationProtocol.CHANNEL_ID,
                        RenderCoordinationProtocol.encodeOfferPart(frame, envelope.length, offset, chunk, activeSecret));
            }
        } catch (ProxyChannelException | RuntimeException error) {
            plugin.debug("Render offer unavailable: " + error.getMessage());
        }
    }

    private void scheduleRetry(Session session) {
        if (session.retryScheduled || session.offers.isEmpty()) return;
        session.retryScheduled = true;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> retry(session), 40L);
    }

    private synchronized void retry(Session session) {
        session.retryScheduled = false;
        if (!enabled()) return;
        refreshSecret();
        if (!current(session) || session.offers.isEmpty()) return;
        for (Key key : java.util.List.copyOf(session.offers.keySet())) sendOffer(session, key);
        scheduleRetry(session);
    }

    private String refreshSecret() {
        String secret = plugin.getNovaChatConfig().getRenderCoordinationSecret();
        if (!secret.equals(activeSecret)) {
            sessions.clear();
            activeSecret = secret;
        }
        return secret;
    }
}
