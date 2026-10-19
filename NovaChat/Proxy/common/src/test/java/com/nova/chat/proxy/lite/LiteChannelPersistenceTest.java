package com.nova.chat.proxy.lite;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.config.ConfigSource;
import com.nova.chat.proxy.config.LiteChannelConfig;
import com.nova.chat.proxy.config.LiteStorageConfig;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lite-mode channel membership persistence, end to end against real backends.
 *
 * <p>The SQLite tests use a genuine database file in a temporary directory and
 * a full authority restart in between, so they fail if the membership lives only
 * in the process. No provider is mocked: the point of these tests is to prove the
 * data actually survives.
 */
@DisplayName("Lite channel membership persistence")
class LiteChannelPersistenceTest {

    private static final String GLOBAL = "global";
    private static final String LOCAL = "local";
    private static final String VIP = "vip";

    @TempDir
    Path tempDir;

    private Locale previousLocale;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    // ------------------------------------------------------------------ SQLite

    @Test
    @DisplayName("a restarted authority restores joined channels and the active channel from SQLite")
    void sqliteRestoresMembershipAfterRestart() throws Exception {
        Path database = tempDir.resolve("nested").resolve("novachat.db");

        // --- first process: join two channels, VIP becomes active ---------------
        UUID playerId = UUID.randomUUID();
        Fixture first = new Fixture(sqliteStorage(database));
        try {
            Fixture.Player player = first.platform.add(playerId, "Alice");
            first.join(player, GLOBAL);
            first.join(player, VIP);

            PlayerChannelState beforeRestart = first.chatCore.getState(playerId);
            // "local" is the configured chat.default_channel, so the state starts
            // with it joined and the two explicit joins add to it.
            assertThat(beforeRestart.getJoinedChannels())
                    .contains(GLOBAL, VIP, LOCAL);
            assertThat(beforeRestart.getActiveChannel()).isEqualTo(VIP);
        } finally {
            // Closes the pool: the next authority opens the same file from disk.
            first.close();
        }

        assertThat(database).exists();

        // --- second process: a fresh authority on the same file ------------------
        Fixture second = new Fixture(sqliteStorage(database));
        try {
            Fixture.Player player = second.platform.add(playerId, "Alice");
            // The active channel is restored, and the joined set contains both.
            second.authority.onPlayerConnect(playerId);
            second.awaitStore();

            PlayerChannelState restored = second.chatCore.getOrCreateState(player);
            assertThat(restored.getJoinedChannels())
                    .as("joined channels restored from SQLite")
                    .contains(GLOBAL, VIP, LOCAL);
            assertThat(restored.getActiveChannel())
                    .as("active channel restored from SQLite")
                    .isEqualTo(VIP);
            // The membership table moved with it, so WHO/capacity see the player.
            assertThat(second.authority.memberCount(GLOBAL)).isEqualTo(1);
            assertThat(second.authority.memberCount(VIP)).isEqualTo(1);
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("a leave is persisted, so a restart does not resurrect the channel")
    void sqlitePersistsLeave() throws Exception {
        Path database = tempDir.resolve("novachat.db");
        UUID playerId = UUID.randomUUID();

        Fixture first = new Fixture(sqliteStorage(database));
        try {
            Fixture.Player player = first.platform.add(playerId, "Bob");
            first.join(player, GLOBAL);
            first.join(player, VIP);
            first.leave(player, VIP);
            assertThat(first.chatCore.getState(playerId).getJoinedChannels())
                    .contains(GLOBAL)
                    .doesNotContain(VIP);
        } finally {
            first.close();
        }

        Fixture second = new Fixture(sqliteStorage(database));
        try {
            Fixture.Player player = second.platform.add(playerId, "Bob");
            second.authority.onPlayerConnect(playerId);
            second.awaitStore();

            assertThat(second.chatCore.getOrCreateState(player).getJoinedChannels())
                    .contains(GLOBAL)
                    .doesNotContain(VIP);
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("the sqlite path resolves against the plugin data directory, not the JVM working directory")
    void sqliteRelativePathUsesDataDirectory() throws Exception {
        // A relative path must land in the plugin data dir. Resolving against the
        // working directory would drop it in the proxy server's root instead.
        LiteChannelStore store = LiteChannelStore.create(
                sqliteStorage(Path.of("data").resolve("relative.db")),
                tempDir,
                Map.of(GLOBAL, channel(GLOBAL)),
                "VELOCITY",
                warnings::add,
                message -> { });
        try {
            store.open();
            assertThat(store.flush(30_000L)).isTrue();
            assertThat(tempDir.resolve("data").resolve("relative.db"))
                    .as("sqlite file created under the plugin data directory")
                    .exists();
            assertThat(store.isConnected()).isTrue();
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("the declared lite.channels are mirrored into the backend")
    void sqliteMirrorsDeclaredChannels() throws Exception {
        Path database = tempDir.resolve("novachat.db");
        LiteChannelStore store = LiteChannelStore.create(
                sqliteStorage(database),
                tempDir,
                Map.of(GLOBAL, channel(GLOBAL), LOCAL, channel(LOCAL)),
                "VELOCITY",
                warnings::add,
                message -> { });
        try {
            store.open();
            assertThat(store.flush(30_000L)).isTrue();
            assertThat(store.isConnected()).isTrue();
            assertThat(store.getMirroredChannelIds()).containsExactlyInAnyOrder(GLOBAL, LOCAL);
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("close is idempotent and drops nothing that was already queued")
    void closeIsIdempotent() {
        LiteChannelStore store = LiteChannelStore.create(
                LiteStorageConfig.forTests(LiteStorageConfig.Type.MEMORY, null, 0, null),
                tempDir,
                Map.of(),
                "VELOCITY",
                warnings::add,
                message -> { });

        store.close();
        store.close();
        store.savePlayer(new LiteChannelStore.PlayerSnapshot(
                UUID.randomUUID(), "ignored", Set.of(GLOBAL), GLOBAL));
        store.evictPlayer(UUID.randomUUID());
        assertThat(store.flush(1_000L)).isTrue();

        LiteChannelStore sqlite = LiteChannelStore.create(
                sqliteStorage(tempDir.resolve("idempotent.db")),
                tempDir,
                Map.of(),
                "VELOCITY",
                warnings::add,
                message -> { });
        sqlite.open();
        sqlite.flush(30_000L);
        sqlite.close();
        sqlite.close();
    }

    // ------------------------------------------------------------------ memory

    @Test
    @DisplayName("memory mode performs no I/O and starts no storage thread")
    void memoryModeIsPureInMemory() {
        // A memory store must stay indistinguishable from the pre-persistence
        // behaviour: no file, no provider, no thread.
        Fixture fixture = new Fixture(memoryStorage());
        try {
            Fixture.Player player = fixture.platform.add(UUID.randomUUID(), "Carol");
            fixture.join(player, GLOBAL);

            assertThat(fixture.store.isPersistent()).isFalse();
            assertThat(fixture.store.isConnected()).isFalse();
            assertThat(fixture.threadExists()).isFalse();
            assertThat(fixture.warnings()).isEmpty();
            // The join still took effect locally.
            assertThat(fixture.chatCore.getState(player.id).getJoinedChannels()).contains(GLOBAL);
            assertThat(fixture.authority.memberCount(GLOBAL)).isEqualTo(1);
            // Nothing to persist anywhere.
            assertThat(tempDir.resolve("data").resolve("memory.db")).doesNotExist();
        } finally {
            fixture.close();
        }
    }

    // -------------------------------------------------------------- degradation

    @Test
    @DisplayName("an unreachable MySQL backend degrades to memory and chat keeps working")
    void unreachableMysqlDegradesToMemory() {
        // Port 1 on loopback: connection refused immediately, no network egress
        // and no long TCP timeout.
        LiteStorageConfig unreachable = LiteStorageConfig.forTests(
                LiteStorageConfig.Type.MYSQL, "127.0.0.1", 1, "novachat");

        Fixture fixture = new Fixture(unreachable);
        try {
            Fixture.Player player = fixture.platform.add(UUID.randomUUID(), "Dave");
            fixture.authority.onPlayerConnect(player.id);
            fixture.join(player, GLOBAL);
            assertThat(fixture.store.flush(30_000L)).isTrue();

            // Degraded, not broken: the pool never opened and the store says so.
            assertThat(fixture.store.isPersistent()).isTrue();
            assertThat(fixture.store.isConnected()).isFalse();
            assertThat(fixture.warnings())
                    .as("a warn line explains that persistence is off")
                    .anyMatch(line -> line.contains("unavailable")
                            && line.contains("memory only"));

            // Chat still works end to end.
            Fixture.Player listener = fixture.platform.add(UUID.randomUUID(), "Erin");
            fixture.join(listener, GLOBAL);
            fixture.send(new ChatMessagePacket(player.id, player.name, "proxy-1", GLOBAL, "still alive"));

            assertThat(listener.messages).anyMatch(line -> line.contains("still alive"));
        } finally {
            fixture.close();
        }
    }

    @Test
    @DisplayName("a write queued before open() still reaches the database")
    void writeBeforeOpenIsNotDropped() {
        // Regression: the backend used to be connected by a task that only ran
        // when open() was called first, so an early savePlayer saw a null
        // provider and silently dropped the row. Every I/O task now connects
        // lazily, so submission order cannot lose a write.
        Path database = tempDir.resolve("early.db");
        UUID playerId = UUID.randomUUID();

        LiteChannelStore store = LiteChannelStore.create(
                sqliteStorage(database), tempDir, Map.of(GLOBAL, channel(GLOBAL)),
                "VELOCITY", warnings::add, message -> { });
        try {
            // Deliberately before open().
            store.savePlayer(new LiteChannelStore.PlayerSnapshot(
                    playerId, "Gina", Set.of(GLOBAL), GLOBAL));
            store.open();
            assertThat(store.flush(30_000L)).isTrue();

            List<LiteChannelStore.PlayerSnapshot> loaded = new CopyOnWriteArrayList<>();
            store.loadPlayer(playerId, loaded::add);
            assertThat(store.flush(30_000L)).isTrue();
            assertThat(loaded).as("the early write was persisted").hasSize(1);
            assertThat(loaded.get(0).joinedChannels()).containsExactly(GLOBAL);
            assertThat(warnings).isEmpty();
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("an unsupported storage type cannot be constructed, memory is the fallback")
    void unsupportedBackendDoesNotBreakConstruction() {
        Fixture fixture = new Fixture(LiteStorageConfig.forTests(
                LiteStorageConfig.Type.POSTGRESQL, "127.0.0.1", 1, "novachat"));
        try {
            fixture.store.open();
            fixture.store.flush(30_000L);
            assertThat(fixture.store.isConnected()).isFalse();
            assertThat(fixture.warnings()).isNotEmpty();
        } finally {
            fixture.close();
        }
    }

    @Test
    @DisplayName("join, leave and disconnect never block on the database")
    void callerThreadNeverWaitsForTheDatabase() throws Exception {
        // A backend that accepts connections but never answers would stall every
        // caller if any database work ran inline. Pointing at a black-hole
        // listener and asserting the calls still return immediately proves the
        // event-thread rule holds, including on the disconnect path.
        try (java.net.ServerSocket blackHole = new java.net.ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                while (!blackHole.isClosed()) {
                    try {
                        java.net.Socket socket = blackHole.accept();
                        // Hold the connection open, never respond.
                        sockets.add(socket);
                    } catch (java.io.IOException e) {
                        return;
                    }
                }
            }, "blackhole-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            Fixture fixture = new Fixture(LiteStorageConfig.forTests(
                    LiteStorageConfig.Type.MYSQL, "127.0.0.1", blackHole.getLocalPort(), "novachat"));
            try {
                Fixture.Player player = fixture.platform.add(UUID.randomUUID(), "Frank");

                long start = System.nanoTime();
                fixture.authority.onPlayerConnect(player.id);
                fixture.join(player, GLOBAL);
                fixture.leave(player, GLOBAL);
                fixture.authority.onPlayerDisconnect(player.id);
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

                // The connect/Hikari attempt can take seconds; the caller must not.
                assertThat(elapsedMillis)
                        .as("join/leave/disconnect returned without waiting for the driver")
                        .isLessThan(2_000L);
            } finally {
                fixture.close();
                sockets.forEach(socket -> {
                    try {
                        socket.close();
                    } catch (java.io.IOException ignored) {
                        // Test cleanup only.
                    }
                });
            }
        }
    }

    /** Connections accepted by the black-hole listener, closed on teardown. */
    private final List<java.net.Socket> sockets = new CopyOnWriteArrayList<>();

    // ----------------------------------------------------------------- fixture

    private LiteStorageConfig sqliteStorage(Path file) {
        return LiteStorageConfig.forTests(
                LiteStorageConfig.Type.SQLITE, file.toString(), 0, null);
    }

    private LiteStorageConfig memoryStorage() {
        return LiteStorageConfig.forTests(
                LiteStorageConfig.Type.MEMORY, null, 0, null);
    }

    private static LiteChannelConfig channel(String id) {
        return new LiteChannelConfig(id, id, null, 0, 0);
    }

    /** One process lifetime: platform, chat core, store and authority. */
    private final class Fixture implements AutoCloseable {

        private final FakePlatform platform = new FakePlatform();
        private final ProxyChatCore chatCore;
        private final LiteChannelStore store;
        private final LocalAuthority authority;
        private final List<String> capturedWarnings = new CopyOnWriteArrayList<>();

        private Fixture(LiteStorageConfig storage) {
            ProxyConfig config;
            try {
                config = ProxyConfig.from(new MapSource(liteDefaults()));
            } catch (ConfigSource.ConfigException e) {
                throw new IllegalStateException("test config must parse", e);
            }
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.replaceAll(Set.of(GLOBAL, LOCAL, VIP));
            chatCore = new ProxyChatCore(platform, config, new IgnoreListService(),
                    new PrivateMessageService(), registry, () -> null);
            chatCore.rebindIncomingHandlers(new LocalAuthorityClient(
                    (task, delayMillis) -> task.run(), 0L, message -> { }));

            store = LiteChannelStore.create(storage, tempDir,
                    Map.of(GLOBAL, channel(GLOBAL), LOCAL, channel(LOCAL), VIP, channel(VIP)),
                    "VELOCITY", capturedWarnings::add, message -> { });
            store.open();
            // Route the platform's logWarn at the same list so a degradation
            // reported through either sink is assertable.
            platform.warningSink = capturedWarnings::add;
            authority = new LocalAuthority(platform, chatCore, null, store);
        }

        private List<String> warnings() {
            List<String> all = new ArrayList<>(warnings);
            all.addAll(capturedWarnings);
            return all;
        }

        private void join(Player player, String channelId) {
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, channelId);
            packet.addExtra("playerId", player.id.toString());
            packet.addExtra("playerName", player.name);
            List<Packet> response = authority.handle(packet);
            assertThat(response).hasSize(1);
            assertThat(((ChannelActionResponsePacket) response.get(0)).isSuccess())
                    .as("join %s for %s", channelId, player.name)
                    .isTrue();
        }

        private void leave(Player player, String channelId) {
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.LEAVE, channelId);
            packet.addExtra("playerId", player.id.toString());
            packet.addExtra("playerName", player.name);
            List<Packet> response = authority.handle(packet);
            assertThat(((ChannelActionResponsePacket) response.get(0)).isSuccess()).isTrue();
        }

        private void send(Packet packet) {
            // Mirror LocalAuthorityClient's delivery loop: the authority returns
            // downstream packets, the shared core renders them.
            for (Packet response : authority.handle(packet)) {
                if (response instanceof ChatMessagePacket chat) {
                    chatCore.handleIncomingMessage(chat);
                }
            }
        }

        /** Waits for every queued storage task, including the post-read dispatch. */
        private void awaitStore() {
            assertThat(store.flush(30_000L)).isTrue();
        }

        /**
         * @return whether a thread named after the storage I/O worker exists
         */
        private boolean threadExists() {
            return Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(thread -> LiteChannelStore.IO_THREAD_NAME.equals(thread.getName()));
        }

        @Override
        public void close() {
            store.close();
        }

        private final class Player implements ProxyPlayer {
            private final UUID id;
            private final String name;
            private final Set<String> permissions = ConcurrentHashMap.newKeySet();
            private final List<String> messages = new CopyOnWriteArrayList<>();

            private Player(UUID id, String name) {
                this.id = id;
                this.name = name;
            }

            @Override public UUID getUniqueId() { return id; }
            @Override public String getName() { return name; }
            @Override public String getDisplayName() { return name; }
            @Override public String getCurrentServer() { return "lobby"; }
            @Override public boolean hasPermission(String permission) { return true; }
        }

        private final class FakePlatform implements ProxyPlatform {
            private final Map<UUID, Player> players = new LinkedHashMap<>();
            /** Set by the fixture so logWarn lands in the same list as the store's. */
            private volatile java.util.function.Consumer<String> warningSink = message -> { };

            private Player add(UUID id, String name) {
                Player player = new Player(id, name);
                players.put(id, player);
                return player;
            }

            @Override
            public Collection<? extends ProxyPlayer> getOnlinePlayers() {
                return List.copyOf(players.values());
            }

            @Override
            public ProxyPlayer getPlayer(UUID playerId) {
                return players.get(playerId);
            }

            @Override
            public void sendMessage(ProxyPlayer player, String legacyText) {
                ((Player) player).messages.add(legacyText);
            }

            @Override public void sendHoverableMessage(ProxyPlayer p, String t, String h) { }
            @Override public void showTitle(ProxyPlayer p, String t, String s, int a, int b, int c) { }
            @Override public void playSound(ProxyPlayer p, String id, float v, float pitch) { }
            @Override public void notifyMention(ProxyPlayer p, String h, String s) { }
            @Override public void sendActionBar(ProxyPlayer p, String t) { }
            @Override public void showChannelStatusBar(ProxyPlayer p, String id, ChatMode m) { }
            @Override public void notifyKickMute(ProxyPlayer p, ChannelAction a, String c,
                                                 String o, String d) { }
            @Override public boolean consoleHasPermission(String permission) { return true; }

            /** Runs inline: the restore hop must complete before the assertion. */
            @Override public void execute(Runnable task) { task.run(); }
            @Override public void runAsync(Runnable task) { task.run(); }
            @Override public void runLater(Runnable task, long delaySeconds) { task.run(); }

            @Override public void logInfo(String message) { }
            @Override public void logWarn(String message) { warningSink.accept(message); }
            @Override public void logDebug(String message) { }
            @Override public void logError(String message) { }
            @Override public void logError(String message, Throwable cause) { }
            @Override public PlatformType getPlatformType() { return PlatformType.VELOCITY; }
            @Override public void reloadConfiguration() { }
        }
    }

    // ----------------------------------------------------------- config fixture

    private static final class MapSource implements ConfigSource {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private MapSource(Map<String, Object> values) {
            this.values.putAll(values);
        }

        private Object lookup(String path) {
            String[] segments = path.split("\\.");
            for (int length = segments.length; length > 0; length--) {
                String prefix = String.join(".", java.util.Arrays.copyOf(segments, length));
                if (!values.containsKey(prefix)) {
                    continue;
                }
                Object current = values.get(prefix);
                for (int i = length; i < segments.length; i++) {
                    if (!(current instanceof Map<?, ?> map)) {
                        return null;
                    }
                    current = map.get(segments[i]);
                    if (current == null) {
                        return null;
                    }
                }
                return current;
            }
            return null;
        }

        @Override
        public String requiredString(String path) throws ConfigException {
            Object value = lookup(path);
            if (value == null) {
                throw new ConfigException("missing " + path);
            }
            return value.toString();
        }

        @Override
        public int requiredInt(String path) throws ConfigException {
            Object value = lookup(path);
            if (!(value instanceof Number number)) {
                throw new ConfigException("missing int " + path);
            }
            return number.intValue();
        }

        @Override
        public boolean requiredBoolean(String path) throws ConfigException {
            Object value = lookup(path);
            if (!(value instanceof Boolean flag)) {
                throw new ConfigException("missing boolean " + path);
            }
            return flag;
        }

        @Override
        public Map<String, Object> requiredTable(String path) throws ConfigException {
            Object value = lookup(path);
            if (!(value instanceof Map<?, ?> map)) {
                throw new ConfigException("missing table " + path);
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
            return copy;
        }

        @Override
        public boolean contains(String path) {
            return lookup(path) != null;
        }

        @Override
        public String optionalString(String path) {
            Object value = lookup(path);
            return value != null ? value.toString() : null;
        }
    }

    private static Map<String, Object> liteDefaults() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mode", "lite");
        values.put("lite.server-id", "proxy-1");
        values.put("chat.replace_vanilla", true);
        values.put("chat.default_channel", LOCAL);
        values.put("chat.locale", "zh_CN");
        values.put("chat.channel-prefixes", Map.of());
        values.put("format.prefix", "");
        values.put("format.error", "{message}");
        values.put("format.success", "{message}");
        values.put("format.default", "[{channel_name}] {player}: {message}");
        values.put("format.channels", Map.of(
                GLOBAL, "[{channel_name}] {player}: {message}",
                LOCAL, "[{channel_name}] {player}: {message}",
                VIP, "[{channel_name}] {player}: {message}"));
        values.put("debug", false);

        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put(GLOBAL, channelTable("鍏ㄦ湇", null, 1000, 0));
        channels.put(LOCAL, channelTable("鏈湴", null, 0, 0));
        channels.put(VIP, channelTable("VIP", null, 1, 0));
        values.put("lite.channels", channels);
        values.put("lite.storage", Map.of("type", "memory"));
        return values;
    }

    private static Map<String, Object> channelTable(String displayName, String permission,
                                                    int maxCapacity, int slowMode) {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("display-name", displayName);
        if (permission != null) {
            channel.put("permission", permission);
        }
        channel.put("max-capacity", (long) maxCapacity);
        channel.put("slow-mode", (long) slowMode);
        return channel;
    }
}
