package com.nova.chat.proxy.chat;

import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

final class ProxyChannelRecovery {
    static final int MAX_SELECTIONS = 1024;
    static final long RETENTION_NANOS = TimeUnit.MINUTES.toNanos(2);

    private final ProxyPlatform platform;
    private final ProxyPlayerStateStore states;
    private final Function<String, AbstractPlatformNetworkClient> clients;
    private final LongSupplier clock;
    private final Map<UUID, Selection> selections = new LinkedHashMap<>();
    private final Map<UUID, Attempt> pendingPlayers = new HashMap<>();
    private final Map<UUID, Attempt> pendingRequests = new HashMap<>();

    private record Selection(String channel, long savedAt) { }

    private static final class Attempt {
        final UUID playerId;
        final PlayerChannelState session;
        final String server;
        final ChannelActionPacket request;
        AbstractPlatformNetworkClient client;

        Attempt(UUID playerId, PlayerChannelState session, String server, ChannelActionPacket request) {
            this.playerId = playerId;
            this.session = session;
            this.server = server;
            this.request = request;
        }
    }

    ProxyChannelRecovery(ProxyPlatform platform, ProxyPlayerStateStore states,
                         Function<String, AbstractPlatformNetworkClient> clients) {
        this(platform, states, clients, System::nanoTime);
    }

    ProxyChannelRecovery(ProxyPlatform platform, ProxyPlayerStateStore states,
                         Function<String, AbstractPlatformNetworkClient> clients, LongSupplier clock) {
        this.platform = platform;
        this.states = states;
        this.clients = clients;
        this.clock = clock;
    }

    synchronized void disconnected(UUID playerId, PlayerChannelState state) {
        Attempt pending = pendingPlayers.get(playerId);
        if (state == null && pending == null) { return; }
        String channel = state != null ? state.getActiveChannel() : null;
        if (pending != null) {
            if (channel == null) { channel = pending.request.getChannelId(); }
            forget(pending);
            withdraw(pending, true);
        }
        long now = clock.getAsLong();
        selections.entrySet().removeIf(entry -> now - entry.getValue().savedAt() >= RETENTION_NANOS);
        selections.remove(playerId);
        if (channel == null || channel.equals(states.getConfig().getDefaultChannel())) { return; }
        if (selections.size() >= MAX_SELECTIONS) {
            selections.remove(selections.keySet().iterator().next());
        }
        selections.put(playerId, new Selection(channel, now));
    }

    synchronized boolean connected(ProxyPlayer player, PlayerChannelState state, String server) {
        Attempt pending = pendingPlayers.get(player.getUniqueId());
        if (pending != null && pending.session == state) {
            if (Objects.equals(pending.server, server)) { return true; }
            String channel = pending.request.getChannelId();
            forget(pending);
            withdraw(pending, true);
            start(player, state, server, channel);
            return true;
        }
        Selection selection = selections.remove(player.getUniqueId());
        if (selection == null || clock.getAsLong() - selection.savedAt() >= RETENTION_NANOS) { return false; }
        if (!player.hasPermission("novachat.use")) { return false; }
        String defaultChannel = states.getConfig().getDefaultChannel();
        if (selection.channel().equals(defaultChannel)) { return false; }
        if (state.getJoinedChannels().size() == 1 && defaultChannel.equals(state.getActiveChannel())) {
            state.leaveChannel(defaultChannel);
        }
        start(player, state, server, selection.channel());
        return true;
    }

    synchronized boolean authenticated(ProxyPlayer player, AbstractPlatformNetworkClient client) {
        Attempt pending = pendingPlayers.get(player.getUniqueId());
        if (pending == null) { return false; }
        if (clients.apply(pending.server) == client) {
            if (pending.client != null && pending.client != client) {
                forget(pending);
                start(player, pending.session, pending.server, pending.request.getChannelId());
            } else {
                send(pending);
            }
        }
        return true;
    }

    synchronized boolean handles(ChannelActionResponsePacket packet) {
        if (packet == null) { return false; }
        Attempt pending = pendingRequests.get(packet.getRequestId());
        if (pending == null) { return false; }
        platform.execute(() -> received(pending, packet));
        return true;
    }

    private void start(ProxyPlayer player, PlayerChannelState state, String server, String channel) {
        ChannelActionPacket request = new ChannelActionPacket(ChannelAction.JOIN, channel);
        request.addExtra("playerId", player.getUniqueId().toString());
        request.addExtra("playerName", player.getName());
        request.addExtra("platform", platform.getPlatformType().name());
        request.addExtra("server", server);
        Attempt pending = new Attempt(player.getUniqueId(), state, server, request);
        pendingPlayers.put(pending.playerId, pending);
        pendingRequests.put(request.getRequestId(), pending);
        platform.runLater(() -> platform.execute(() -> expired(pending)),
                TimeUnit.MILLISECONDS.toSeconds(NovaConstants.PENDING_REQUEST_TIMEOUT_MS));
        send(pending);
    }

    private void send(Attempt pending) {
        if (pending.client != null || !current(pending)) { return; }
        AbstractPlatformNetworkClient client = clients.apply(pending.server);
        if (client == null || !client.isAuthenticated()) { return; }
        pending.client = client;
        client.sendPacket(pending.request);
    }

    private boolean current(Attempt pending) {
        ProxyPlayer player = platform.getPlayer(pending.playerId);
        return pendingPlayers.get(pending.playerId) == pending && player != null
                && states.getState(pending.playerId) == pending.session
                && Objects.equals(pending.server, pending.session.getCurrentServer());
    }

    private synchronized void received(Attempt pending, ChannelActionResponsePacket response) {
        if (pendingRequests.get(response.getRequestId()) != pending) { return; }
        boolean active = current(pending);
        forget(pending);
        if (!active) { return; }
        ProxyPlayer player = platform.getPlayer(pending.playerId);
        if (player == null) { return; }
        String channel = pending.request.getChannelId();
        if (pending.client != null && response.isSuccess() && response.getAction() == ChannelAction.JOIN
                && channel.equals(response.getChannelId()) && player.hasPermission("novachat.use")) {
            pending.session.joinChannel(channel);
            if (pending.session.getActiveChannel() == null) {
                pending.session.setActiveChannel(channel);
                platform.showChannelStatusBar(player, channel, pending.session.getChatMode());
            }
        } else {
            if (response.isSuccess()) { withdraw(pending, false); }
            fallback(pending);
        }
    }

    private synchronized void expired(Attempt pending) {
        boolean active = current(pending);
        forget(pending);
        if (!active) { return; }
        withdraw(pending, false);
        fallback(pending);
    }

    private void fallback(Attempt pending) {
        String defaultChannel = states.getConfig().getDefaultChannel();
        ProxyPlayer player = platform.getPlayer(pending.playerId);
        if (!pending.request.getChannelId().equals(defaultChannel) && player != null
                && states.getState(pending.playerId) == pending.session && pending.session.getActiveChannel() == null) {
            start(player, pending.session, pending.server, defaultChannel);
        }
    }

    private void forget(Attempt pending) {
        pendingPlayers.remove(pending.playerId, pending);
        pendingRequests.remove(pending.request.getRequestId(), pending);
        if (pending.client != null) {
            pending.client.getChannelResponseTracker().consume(pending.request.getRequestId());
        }
    }

    private void withdraw(Attempt pending, boolean logout) {
        if (pending.client == null || !pending.client.isAuthenticated()) { return; }
        ChannelActionPacket leave = new ChannelActionPacket(ChannelAction.LEAVE, pending.request.getChannelId());
        leave.addExtra("playerId", pending.playerId.toString());
        if (logout) { leave.addExtra("logout", "true"); }
        pending.client.sendPacket(leave);
    }
}
