package com.nova.chat.proxy.relay;

import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Shared doubles for the relay link tests: a facade that records what it was
 * asked to send, and a platform with a mutable player roster.
 */
public final class RelayTestFixture {

    private RelayTestFixture() {
    }

    /** Facade double: no Netty, records sends, delivers to registered handlers. */
    public static final class FakeLinkClient extends AbstractPlatformNetworkClient {

        final String identity;
        final List<Packet> sent = new CopyOnWriteArrayList<>();
        final Map<Class<? extends Packet>, Consumer<Packet>> handlers =
                new ConcurrentHashMap<>();
        final ChannelResponseTracker tracker = new ChannelResponseTracker();
        final PacketRegistry registry = NovaProtocol.createRegistry();
        final AtomicBoolean authenticated = new AtomicBoolean(true);
        final AtomicBoolean connected = new AtomicBoolean(true);
        final AtomicBoolean disconnected = new AtomicBoolean();
        final List<String> connectHosts = new CopyOnWriteArrayList<>();

        public FakeLinkClient(String identity) {
            this.identity = identity;
        }

        @Override
        public java.util.concurrent.CompletableFuture<Boolean> connect(String host, int port) {
            connectHosts.add(host + ":" + port);
            return java.util.concurrent.CompletableFuture.completedFuture(authenticated.get());
        }

        @Override
        public void disconnect() {
            disconnected.set(true);
            authenticated.set(false);
            connected.set(false);
        }

        @Override
        public void sendPacket(Packet packet) {
            if (connected.get() && authenticated.get()) {
                sent.add(packet);
            }
        }

        @Override
        public ChannelResponseTracker getChannelResponseTracker() {
            return tracker;
        }

        @Override
        public <T extends Packet> void registerHandler(Class<T> packetClass,
                                                      Consumer<T> handler) {
            handlers.put(packetClass, (Consumer<Packet>) handler);
        }

        @Override
        public boolean isConnected() {
            return connected.get();
        }

        @Override
        public boolean isAuthenticated() {
            return authenticated.get();
        }

        @Override
        public PacketRegistry getPacketRegistry() {
            return registry;
        }

        /** Delivers an inbound packet to the registered handler, as Netty would. */
        void deliver(Packet packet) {
            Consumer<Packet> handler = handlers.get(packet.getClass());
            if (handler != null) {
                handler.accept(packet);
            }
        }
    }

    /** Mutable player handle. */
    public static final class FakePlayer implements ProxyPlayer {
        final UUID id = UUID.randomUUID();
        final String name;
        volatile String server;
        final List<String> messages = new CopyOnWriteArrayList<>();

        FakePlayer(String name, String server) {
            this.name = name;
            this.server = server;
        }

        @Override public UUID getUniqueId() { return id; }
        @Override public String getName() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public String getCurrentServer() { return server; }
        @Override public boolean hasPermission(String permission) { return false; }
    }

    /** Platform double: roster, async dispatch runs inline, records logs. */
    public static final class FakePlatform implements ProxyPlatform {
        final Map<UUID, FakePlayer> players = new LinkedHashMap<>();
        final List<String> warnings = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();

        FakePlayer add(String name, String server) {
            FakePlayer player = new FakePlayer(name, server);
            players.put(player.id, player);
            return player;
        }

        @Override public Collection<? extends ProxyPlayer> getOnlinePlayers() {
            return List.copyOf(players.values());
        }
        @Override public ProxyPlayer getPlayer(UUID playerId) { return players.get(playerId); }
        @Override public void sendMessage(ProxyPlayer player, String legacyText) {
            if (player instanceof FakePlayer fake) {
                fake.messages.add(legacyText);
            }
        }
        @Override public void sendHoverableMessage(ProxyPlayer player, String text, String hover) { }
        @Override public void showTitle(ProxyPlayer player, String title, String subtitle,
                                        int fadeIn, int stay, int fadeOut) { }
        @Override public void playSound(ProxyPlayer player, String soundId, float volume, float pitch) { }
        @Override public void notifyMention(ProxyPlayer player, String highlight, String subtitle) { }
        @Override public void sendActionBar(ProxyPlayer player, String legacyText) { }
        @Override public void showChannelStatusBar(ProxyPlayer player, String channelId, ChatMode mode) { }
        @Override public void notifyKickMute(ProxyPlayer player, ChannelAction action, String channelId,
                                             String operator, String durationText) { }
        @Override public boolean consoleHasPermission(String permission) { return true; }
        public void execute(Runnable task) { task.run(); }
        @Override public void runAsync(Runnable task) { task.run(); }
        @Override public void runLater(Runnable task, long delaySeconds) { task.run(); }
        @Override public void logInfo(String message) { }
        @Override public void logWarn(String message) { warnings.add(message); }
        @Override public void logDebug(String message) { }
        @Override public void logError(String message) { errors.add(message); }
        @Override public void logError(String message, Throwable cause) { errors.add(message); }
        @Override public PlatformType getPlatformType() { return PlatformType.VELOCITY; }
        @Override public void reloadConfiguration() { }
    }

    /** Factory double: records created identities and hands out fake links. */
    public static final class FakeFactory implements RelayBackendLinks.ClientFactory {
        final Map<String, FakeLinkClient> created = new LinkedHashMap<>();
        final List<FakeLinkClient> order = new ArrayList<>();

        @Override
        public AbstractPlatformNetworkClient create(String identity) {
            FakeLinkClient client = new FakeLinkClient(identity);
            created.put(identity, client);
            order.add(client);
            return client;
        }
    }
}
