package com.nova.chat.proxy.lite;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.ChannelResponseTracker;
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
import com.nova.chat.proxy.command.ProxyCommandCore;
import com.nova.chat.proxy.command.ProxyCommandSender;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Tests for the lite transport facade: the async response contract and the
 * handler-delivery loop.
 *
 * <p>The ordering assertion is the important one: {@code ChannelCommandService.join}
 * sends the JOIN and <em>then</em> performs the optimistic active-channel
 * switch. If the authority answered synchronously from {@code sendPacket}, a
 * rejection would roll back before that switch and leave the rejected channel
 * active. These tests pin that the authority runs strictly after
 * {@code sendPacket} returned, and that the request was tracked first so the
 * response carries the original request id.
 */
@DisplayName("Lite LocalAuthorityClient")
class LocalAuthorityClientTest {

    private static final String GLOBAL = "global";
    private static final String LOCAL = "local";

    private static final String VIP = "vip";

    private FakePlatform platform;
    private FakePlayer alice;
    private ProxyChatCore chatCore;
    private PrivateMessageService privateMessageService;
    private LocalAuthority boundAuthority;
    private Locale previousLocale;

    @BeforeEach
    void setUp() throws com.nova.chat.proxy.config.ConfigSource.ConfigException {
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        platform = new FakePlatform();
        alice = platform.add("Alice");
        alice.permissions.add("novachat.channel.global");

        ProxyConfig config = ProxyConfig.from(new LocalAuthorityTestSource(liteDefaults()));
        KnownChannelRegistry registry = new KnownChannelRegistry();
        registry.replaceAll(Set.of(GLOBAL, LOCAL, VIP));
        privateMessageService = new PrivateMessageService();
        chatCore = new ProxyChatCore(platform, config, new IgnoreListService(),
                privateMessageService, registry, () -> null);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    @Test
    @DisplayName("connect succeeds without a backend and the client reports itself ready")
    void connectAndStatusWithoutBackend() {
        try (LocalAuthorityClient client = client(task -> { /* never run */ })) {
            assertThat(client.connect("127.0.0.1", 1).join()).isTrue();
            assertThat(client.isConnected()).isTrue();
            assertThat(client.isAuthenticated()).isTrue();
            assertThat(client.getPacketRegistry()).isNotNull();
            assertThat(client.getChannelResponseTracker()).isNotNull();

            // Relay behaviour is untouched: lite deliberately has no connection.
            client.disconnect();
            assertThat(client.isConnected()).isTrue();
        }
    }

    @Test
    @DisplayName("sendPacket returns before the authority runs (responses are asynchronous)")
    void responsesAreDeferred() {
        AtomicBoolean authorityRan = new AtomicBoolean(false);
        AtomicBoolean sendReturned = new AtomicBoolean(false);
        AtomicReference<Packet> handled = new AtomicReference<>();
        List<Runnable> pending = new ArrayList<>();

        try (LocalAuthorityClient client = new LocalAuthorityClient((task, delay) ->
                pending.add(() -> {
                    // When the deferred task finally runs, sendPacket must
                    // already have returned — otherwise a rejection could roll
                    // back the optimistic switch ChannelCommandService.join
                    // makes right after the send.
                    assertThat(sendReturned).isTrue();
                    task.run();
                    authorityRan.set(true);
                }), 0L, message -> { })) {
            client.bind(authority());
            client.registerHandler(ChannelActionResponsePacket.class, handled::set);
            client.sendPacket(action(alice, ChannelAction.JOIN, GLOBAL));
            sendReturned.set(true);

            assertThat(authorityRan).isFalse();
            assertThat(pending).hasSize(1);
            assertThat(handled.get()).isNull();

            pending.get(0).run();
            assertThat(authorityRan).isTrue();
            assertThat(handled.get()).isNotNull();
        }
    }

    @Test
    @DisplayName("a channel action is tracked before its response is produced (original request id)")
    void channelActionIsTrackedBeforeDispatch() {
        List<Runnable> pending = new ArrayList<>();
        AtomicReference<ChannelActionResponsePacket> handled = new AtomicReference<>();

        try (LocalAuthorityClient client = client(pending::add)) {
            client.bind(authority());
            client.registerHandler(ChannelActionResponsePacket.class, handled::set);

            ChannelActionPacket request = action(alice, ChannelAction.JOIN, GLOBAL);
            client.sendPacket(request);

            ChannelResponseTracker tracker = client.getChannelResponseTracker();
            assertThat(tracker.consume(request.getRequestId()))
                    .as("JOIN must be tracked before the deferred authority runs")
                    .isNotNull();

            // The dispatcher consumed the entry; re-track so the response can be
            // correlated exactly as in production.
            tracker.track(request);
            pending.get(0).run();

            ChannelActionResponsePacket response = handled.get();
            assertThat(response).isNotNull();
            assertThat(response.getRequestId()).isEqualTo(request.getRequestId());
            assertThat(response.isSuccess()).isTrue();
        }
    }

    @Test
    @DisplayName("downstream chat packets reach the registered handler")
    void downstreamDeliveryReachesHandler() {
        List<Runnable> pending = new ArrayList<>();
        AtomicReference<ChatMessagePacket> received = new AtomicReference<>();

        try (LocalAuthorityClient client = client(pending::add)) {
            client.bind(authority());
            client.registerHandler(ChatMessagePacket.class, received::set);

            client.sendPacket(chat(alice, GLOBAL, "hello"));
            pending.get(0).run();

            assertThat(received.get()).isNotNull();
            assertThat(received.get().getContent()).isEqualTo("hello");
            assertThat(received.get().getPlaceholders().get("channel_name")).isEqualTo("全服");
        }
    }

    @Test
    @DisplayName("an accepted chat is handed to the downstream broadcast hook")
    void acceptedChatIsBroadcastDownstream() {
        List<Runnable> pending = new ArrayList<>();
        List<ChatMessagePacket> broadcast = new ArrayList<>();

        try (LocalAuthorityClient client = client(pending::add)) {
            client.bind(authority());
            client.bindChatBroadcast(broadcast::add);

            client.sendPacket(chat(alice, GLOBAL, "reach the downstream"));
            pending.get(0).run();

            assertThat(broadcast).hasSize(1);
            assertThat(broadcast.get(0).getContent()).isEqualTo("reach the downstream");
            // The authority enriched the packet before fan-out, so the
            // downstream envelope carries the resolved channel name.
            assertThat(broadcast.get(0).getPlaceholders().get("channel_name")).isEqualTo("全服");
        }
    }

    @Test
    @DisplayName("a rejected chat is not broadcast downstream")
    void rejectedChatIsNotBroadcast() {
        List<Runnable> pending = new ArrayList<>();
        List<ChatMessagePacket> broadcast = new ArrayList<>();

        try (LocalAuthorityClient client = client(pending::add)) {
            client.bind(authority());
            client.bindChatBroadcast(broadcast::add);

            // "missing" is not declared in lite.channels: the authority rejects it.
            client.sendPacket(chat(alice, "missing", "nope"));
            pending.get(0).run();

            assertThat(broadcast).isEmpty();
        }
    }

    @Test
    @DisplayName("channel actions do not trigger the downstream chat broadcast")
    void channelActionsAreNotBroadcast() {
        List<Runnable> pending = new ArrayList<>();
        List<ChatMessagePacket> broadcast = new ArrayList<>();

        try (LocalAuthorityClient client = client(pending::add)) {
            client.bind(authority());
            client.bindChatBroadcast(broadcast::add);

            client.sendPacket(action(alice, ChannelAction.JOIN, GLOBAL));
            pending.get(0).run();

            assertThat(broadcast).isEmpty();
        }
    }

    @Test
    @DisplayName("a failing broadcast hook does not stop handler delivery")
    void failingBroadcastDoesNotBreakDelivery() {
        List<Runnable> pending = new ArrayList<>();
        AtomicReference<ChatMessagePacket> received = new AtomicReference<>();

        try (LocalAuthorityClient client = client(pending::add)) {
            client.bind(authority());
            client.bindChatBroadcast(packet -> {
                throw new IllegalStateException("downstream link down");
            });
            client.registerHandler(ChatMessagePacket.class, received::set);

            client.sendPacket(chat(alice, GLOBAL, "still delivered"));
            pending.get(0).run();

            assertThat(received.get()).isNotNull();
            assertThat(received.get().getContent()).isEqualTo("still delivered");
        }
    }

    @Test
    @DisplayName("the default dispatcher runs the authority off the calling thread")
    void defaultDispatcherIsOffThread() throws Exception {
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<String> dispatcherThread = new AtomicReference<>();

        try (LocalAuthorityClient client = LocalAuthorityClient.create(platform)) {
            client.bind(authority());
            client.registerHandler(ChatMessagePacket.class, packet -> {
                dispatcherThread.set(Thread.currentThread().getName());
                delivered.countDown();
            });

            client.sendPacket(chat(alice, GLOBAL, "thread check"));

            assertThat(delivered.await(5, TimeUnit.SECONDS))
                    .as("the deferred authority task must execute")
                    .isTrue();
            assertThat(dispatcherThread.get())
                    .isNotEqualTo(Thread.currentThread().getName())
                    .contains("novachat-lite-authority");
        }
    }

    @Test
    @DisplayName("a failing handler does not stop later deliveries")
    void handlerFailureIsIsolated() {
        List<Runnable> pending = new ArrayList<>();
        List<String> delivered = new ArrayList<>();

        try (LocalAuthorityClient client = client(pending::add)) {
            client.bind(authority());
            client.registerHandler(ChatMessagePacket.class, packet -> {
                throw new IllegalStateException("boom");
            });
            client.registerHandler(ChannelActionResponsePacket.class, packet -> delivered.add("response"));

            client.sendPacket(chat(alice, GLOBAL, "first"));
            pending.get(0).run();
            client.sendPacket(action(alice, ChannelAction.JOIN, GLOBAL));
            pending.get(1).run();

            assertThat(delivered).containsExactly("response");
        }
    }

    @Test
    @DisplayName("sendPacket before bind is dropped without throwing")
    void unboundClientDropsSends() {
        List<Runnable> pending = new ArrayList<>();
        try (LocalAuthorityClient client = client(pending::add)) {
            client.sendPacket(action(alice, ChannelAction.JOIN, GLOBAL));
            pending.get(0).run(); // must not throw
            assertThat(chatCore.getState(alice.id)).isNull();
        }
    }

    @Test
    @DisplayName("a rejected /nc join rolls the optimistic switch back (lite round-trip)")
    void rejectedJoinRollsBackOptimisticSwitch() throws Exception {
        // A second player fills the single VIP slot; a full-channel rejection
        // must reach the dispatcher AFTER the optimistic switch, then restore
        // the previous channel.
        FakePlayer bob = platform.add("Bob");
        try (LocalAuthorityClient client = LocalAuthorityClient.create(platform)) {
            client.bind(authority());
            chatCore.rebindIncomingHandlers(client);

            ProxyCommandCore commands = commands(client);
            ProxyCommandSender sender = sender(alice);
            ProxyCommandSender bobSender = sender(bob);

            commands.execute(bobSender, new String[]{"join", VIP});
            awaitMember(VIP, 1);
            commands.execute(sender, new String[]{"join", VIP});

            // The optimistic switch happens synchronously inside handleJoin.
            assertThat(chatCore.getState(alice.id).getActiveChannel()).isEqualTo(VIP);

            awaitMessage(alice, "NC-431");
            assertThat(chatCore.getState(alice.id).getActiveChannel())
                    .as("the rejected join must roll back to the previous channel")
                    .isEqualTo(LOCAL);
        }
    }

    @Test
    @DisplayName("a successful /nc join and /nc leave round-trip through the local authority")
    void joinAndLeaveRoundTrip() throws Exception {
        try (LocalAuthorityClient client = LocalAuthorityClient.create(platform)) {
            client.bind(authority());
            chatCore.rebindIncomingHandlers(client);
            ProxyCommandCore commands = commands(client);
            ProxyCommandSender sender = sender(alice);

            commands.execute(sender, new String[]{"join", GLOBAL});
            awaitMember(GLOBAL, 1);
            awaitMessage(alice, "已加入频道");

            commands.execute(sender, new String[]{"leave", GLOBAL});
            awaitMessage(alice, "已离开频道");
            assertThat(authorityMemberCount(GLOBAL)).isZero();
        }
    }

    @Test
    @DisplayName("/nc list shows the channels declared in lite.channels")
    void listShowsLiteChannels() {
        try (LocalAuthorityClient client = LocalAuthorityClient.create(platform)) {
            client.bind(authority());
            commands(client).execute(sender(alice), new String[]{"list"});

            String rendered = String.join("\n", alice.messages);
            assertThat(rendered).contains(GLOBAL).contains(LOCAL).contains(VIP);
        }
    }

    @Test
    @DisplayName("joining a second channel switches the active channel but keeps both memberships")
    void joinSwitchesActiveChannel() throws Exception {
        try (LocalAuthorityClient client = LocalAuthorityClient.create(platform)) {
            client.bind(authority());
            chatCore.rebindIncomingHandlers(client);
            ProxyCommandSender sender = sender(alice);
            ProxyCommandCore commands = commands(client);

            commands.execute(sender, new String[]{"join", GLOBAL});
            awaitMember(GLOBAL, 1);
            commands.execute(sender, new String[]{"join", LOCAL});
            awaitMember(LOCAL, 1);

            PlayerChannelState state = chatCore.getState(alice.id);
            assertThat(state.getActiveChannel()).isEqualTo(LOCAL);
            assertThat(state.isJoined(GLOBAL)).isTrue();
            assertThat(state.isJoined(LOCAL)).isTrue();

            // Both channels now reach Alice; only LOCAL is active for sending.
            sendChatViaAuthority(GLOBAL, "from global");
            sendChatViaAuthority(LOCAL, "from local");
            assertThat(alice.messages).noneMatch(line -> line.contains("from global"));
            assertThat(alice.messages).anyMatch(line -> line.contains("from local"));
        }
    }

    /** Runs a chat packet through the bound authority and delivers downstream. */
    private void sendChatViaAuthority(String channelId, String content) {
        for (Packet packet : authority().handle(chat(alice, channelId, content))) {
            if (packet instanceof ChatMessagePacket chat) {
                chatCore.handleIncomingMessage(chat);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private ProxyCommandCore commands(LocalAuthorityClient client) {
        KnownChannelRegistry registry = new KnownChannelRegistry();
        registry.replaceAll(Set.of(GLOBAL, LOCAL, VIP));
        return new ProxyCommandCore(
                platform,
                chatCore,
                ChannelCommandService.forPlatform(() -> client, PlatformType.VELOCITY),
                new IgnoreListService(),
                privateMessageService,
                registry,
                () -> chatCore.getStates().getConfig());
    }

    private ProxyCommandSender sender(FakePlayer player) {
        return new ProxyCommandSender() {
            @Override public UUID getPlayerId() { return player.id; }
            @Override public String getName() { return player.name; }
            @Override public boolean hasPermission(String permission) { return true; }
            @Override public void sendMessage(String legacyText) { player.messages.add(legacyText); }
        };
    }

    private int authorityMemberCount(String channelId) {
        return authority().memberCount(channelId);
    }

    /** Waits until the authority reports {@code expected} members for a channel. */
    private void awaitMember(String channelId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (authorityMemberCount(channelId) == expected) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("timed out waiting for " + expected + " member(s) in " + channelId
                + "; got " + authorityMemberCount(channelId));
    }

    private void awaitState(FakePlayer player, String channel) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            PlayerChannelState state = chatCore.getState(player.id);
            if (state != null && channel.equals(state.getActiveChannel())) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("timed out waiting for active channel " + channel);
    }

    private void awaitMessage(FakePlayer player, String fragment) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (player.messages.stream().anyMatch(line -> line.contains(fragment))) {
                return;
            }
            Thread.sleep(10L);
        }
        fail("timed out waiting for message containing '" + fragment
                + "'; got " + player.messages);
    }

    // ---------------------------------------------------------------- fixtures

    private LocalAuthorityClient client(java.util.function.Consumer<Runnable> scheduler) {
        return new LocalAuthorityClient((task, delayMillis) -> scheduler.accept(task), 0L, message -> { });
    }

    /** The authority bound into the client under test (lazily created). */
    private LocalAuthority authority() {
        if (boundAuthority == null) {
            boundAuthority = new LocalAuthority(platform, chatCore, null);
        }
        return boundAuthority;
    }

    private static ChannelActionPacket action(FakePlayer player, ChannelAction action, String channel) {
        ChannelActionPacket packet = new ChannelActionPacket(action, channel);
        packet.addExtra("playerId", player.id.toString());
        packet.addExtra("playerName", player.name);
        return packet;
    }

    private static ChatMessagePacket chat(FakePlayer sender, String channelId, String content) {
        ChatMessagePacket packet = new ChatMessagePacket(
                sender.id, sender.name, "proxy-1", channelId, content);
        packet.addPlaceholder("player", sender.name);
        return packet;
    }

    private static final class FakePlayer implements ProxyPlayer {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private final Set<String> permissions = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();

        private FakePlayer(String name) {
            this.name = name;
        }

        @Override public UUID getUniqueId() { return id; }
        @Override public String getName() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public String getCurrentServer() { return null; }
        @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    }

    private static final class FakePlatform implements ProxyPlatform {
        private final Map<UUID, FakePlayer> players = new LinkedHashMap<>();

        private FakePlayer add(String name) {
            FakePlayer player = new FakePlayer(name);
            players.put(player.id, player);
            return player;
        }

        @Override public Collection<? extends ProxyPlayer> getOnlinePlayers() { return List.copyOf(players.values()); }
        @Override public ProxyPlayer getPlayer(UUID playerId) { return players.get(playerId); }
        @Override public void sendMessage(ProxyPlayer player, String legacyText) {
            ((FakePlayer) player).messages.add(legacyText);
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
        // The shared dispatcher hops through execute(); run inline so the
        // rendered confirmations land in the fake players deterministically.
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void runAsync(Runnable task) { task.run(); }
        @Override public void runLater(Runnable task, long delaySeconds) { task.run(); }
        @Override public void logInfo(String message) { }
        @Override public void logWarn(String message) { }
        @Override public void logDebug(String message) { }
        @Override public void logError(String message) { }
        @Override public void logError(String message, Throwable cause) { }
        @Override public PlatformType getPlatformType() { return PlatformType.VELOCITY; }
        @Override public void reloadConfiguration() { }
    }

    /** Minimal lite ConfigSource (flat keys + nested maps, see LocalAuthorityTest). */
    private static final class LocalAuthorityTestSource
            implements com.nova.chat.proxy.config.ConfigSource {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private LocalAuthorityTestSource(Map<String, Object> values) {
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

        @Override public String requiredString(String path) throws ConfigException {
            Object value = lookup(path);
            if (value == null) {
                throw new ConfigException("missing " + path);
            }
            return value.toString();
        }

        @Override public int requiredInt(String path) throws ConfigException {
            Object value = lookup(path);
            if (!(value instanceof Number number)) {
                throw new ConfigException("missing int " + path);
            }
            return number.intValue();
        }

        @Override public boolean requiredBoolean(String path) throws ConfigException {
            Object value = lookup(path);
            if (!(value instanceof Boolean flag)) {
                throw new ConfigException("missing boolean " + path);
            }
            return flag;
        }

        @Override public Map<String, Object> requiredTable(String path) throws ConfigException {
            Object value = lookup(path);
            if (!(value instanceof Map<?, ?> map)) {
                throw new ConfigException("missing table " + path);
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
            return copy;
        }

        @Override public boolean contains(String path) { return lookup(path) != null; }
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
                LOCAL, "[{channel_name}] {player}: {message}"));
        values.put("debug", false);

        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put(GLOBAL, Map.of(
                "display-name", "全服", "permission", "novachat.channel.global",
                "max-capacity", 1000L, "slow-mode", 0L));
        channels.put(LOCAL, Map.of(
                "display-name", "本地", "max-capacity", 0L, "slow-mode", 0L));
        channels.put(VIP, Map.of(
                "display-name", "VIP", "max-capacity", 1L, "slow-mode", 0L));
        values.put("lite.channels", channels);
        values.put("lite.storage", Map.of("type", "memory"));
        return values;
    }
}
