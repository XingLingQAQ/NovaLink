package com.nova.chat.proxy.lite;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.config.LiteStorageConfig;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mute enforcement and persistence at the authority level.
 *
 * <p>These tests drive {@link LocalAuthority} the way the proxy does �?one
 * packet in, downstream packets out �?and then check the observable outcome
 * (did the message fan out?) rather than internal fields. That matters because
 * the bug these guard against is silent: a muted player's line still reaching
 * the other players.
 *
 * <p>The packets are built exactly as the platform {@code MuteCommand} builds
 * them: {@code operatorId} + {@code operatorName} + {@code targetName} +
 * {@code duration} in seconds, with {@code targetId} usually absent because the
 * target is normally on another sub-server. A resolver that only reads
 * {@code targetId} passes a naive test and fails in production.
 */
@DisplayName("Lite authority mutes")
class LocalAuthorityMuteTest {

    private static final String GLOBAL = "global";
    private static final String LOCAL = "local";

    private static final String MODERATOR = "novachat.mute";

    @TempDir
    Path tempDir;

    private final List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();

    private FakePlatform platform;
    private ProxyChatCore chatCore;
    private LocalAuthority authority;
    private Locale previousLocale;

    private FakePlayer alice;
    private FakePlayer bob;
    private FakePlayer moderator;

    @BeforeEach
    void setUp() throws Exception {
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        platform = new FakePlatform();
        alice = platform.add("Alice");
        bob = platform.add("Bob");
        moderator = platform.add("Mod");
        moderator.permissions.add(MODERATOR);

        authority = authority(LiteChannelStore.memory());
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    // --------------------------------------------------------------- enforcement

    @Test
    @DisplayName("a mute blocks the muted player's line in that channel")
    void muteBlocksChatInThatChannel() {
        joinAll(GLOBAL);

        assertThat(mute(moderator, bob, GLOBAL, "10m").isSuccess()).isTrue();
        assertThat(chatReaches(bob, GLOBAL, "hello"))
                .as("a muted player's line must not fan out")
                .isFalse();
    }

    @Test
    @DisplayName("a mute in one channel leaves the muted player's other channels alone")
    void muteIsScopedToItsChannel() {
        joinAll(GLOBAL, LOCAL);

        mute(moderator, bob, GLOBAL, "10m");

        assertThat(chatReaches(bob, LOCAL, "still fine"))
                .as("a global-channel mute must not silence the local channel")
                .isTrue();
        assertThat(chatReaches(bob, GLOBAL, "not fine")).isFalse();
    }

    @Test
    @DisplayName("a global-scope mute blocks the player everywhere")
    void globalMuteBlocksEverywhere() {
        joinAll(GLOBAL, LOCAL);

        // The client cannot request a global mute today, but the provider's
        // nullable-channel convention is part of the shared model, so the
        // authority must honour a restored global row.
        authority.restoreMutesForTests(bob.id, List.of(
                new com.nova.link.database.MuteInfo(null, 0L, "network", moderator.id)));

        assertThat(chatReaches(bob, GLOBAL, "a")).isFalse();
        assertThat(chatReaches(bob, LOCAL, "b")).isFalse();
    }

    @Test
    @DisplayName("unmute restores delivery")
    void unmuteRestoresDelivery() {
        joinAll(GLOBAL);
        mute(moderator, bob, GLOBAL, "10m");
        assertThat(chatReaches(bob, GLOBAL, "blocked")).isFalse();

        assertThat(unmute(moderator, bob, GLOBAL).isSuccess()).isTrue();
        assertThat(chatReaches(bob, GLOBAL, "allowed")).isTrue();
    }

    @Test
    @DisplayName("an expired mute does not block")
    void expiredMuteDoesNotBlock() {
        joinAll(GLOBAL);
        // Already past its deadline: written straight into the table the way a
        // stale row would arrive from the database.
        authority.restoreMutesForTests(bob.id, List.of(
                new com.nova.link.database.MuteInfo(GLOBAL,
                        System.currentTimeMillis() - 60_000L, "stale", moderator.id)));

        assertThat(chatReaches(bob, GLOBAL, "after expiry")).isTrue();
    }

    @Test
    @DisplayName("a permanently muted player stays muted")
    void permanentMuteNeverExpires() {
        joinAll(GLOBAL);
        mute(moderator, bob, GLOBAL, "0");

        assertThat(chatReaches(bob, GLOBAL, "forever blocked")).isFalse();
    }

    // ---------------------------------------------------------------- resolution

    @Test
    @DisplayName("a mute resolves a target that arrives by name only")
    void muteResolvesTargetByName() {
        joinAll(GLOBAL);

        // No targetId at all: this is what a cross-server mute looks like.
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.MUTE, GLOBAL);
        packet.addExtra("operatorId", moderator.id.toString());
        packet.addExtra("operatorName", moderator.name);
        packet.addExtra("targetName", bob.name);
        packet.addExtra("duration", "600");

        ChannelActionResponsePacket response = (ChannelActionResponsePacket) send(packet).get(0);
        assertThat(response.isSuccess()).isTrue();
        assertThat(chatReaches(bob, GLOBAL, "blocked by name")).isFalse();
    }

    @Test
    @DisplayName("a mute for an offline target is refused, not silently accepted")
    void muteForUnknownTargetIsRefused() {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.MUTE, GLOBAL);
        packet.addExtra("operatorId", moderator.id.toString());
        packet.addExtra("targetName", "NobodyHome");

        ChannelActionResponsePacket response = (ChannelActionResponsePacket) send(packet).get(0);
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.TARGET_OFFLINE.getCode());
    }

    // ---------------------------------------------------------------- permission

    @Test
    @DisplayName("a plain member cannot mute anyone")
    void plainMemberCannotMute() {
        joinAll(GLOBAL);

        ChannelActionResponsePacket response = mute(alice, bob, GLOBAL, "10m");
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode());
        assertThat(chatReaches(bob, GLOBAL, "still allowed")).isTrue();
    }

    @Test
    @DisplayName("the channel's join permission is not a moderation permission")
    void joinPermissionGrantsNoModeration() {
        // Alice may join GLOBAL (novachat.channel.global) but must not moderate.
        alice.permissions.add("novachat.channel.global");
        joinAll(GLOBAL);

        assertThat(mute(alice, bob, GLOBAL, "10m").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("an unknown channel is rejected before any state changes")
    void unknownChannelIsRejected() {
        ChannelActionResponsePacket response = mute(moderator, bob, "nope", "10m");
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    // ------------------------------------------------------------- word filter

    @Test
    @DisplayName("the word filter rewrites a message before fan-out")
    void wordFilterRewritesOutgoingChat() {
        joinAll(GLOBAL);
        authority.setWordFilter(LiteWordFilter.of(List.of("damn"), null));

        List<Packet> out = send(chat(bob, GLOBAL, "that is damn rude"));

        ChatMessagePacket delivered = out.stream()
                .filter(ChatMessagePacket.class::isInstance)
                .map(ChatMessagePacket.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(delivered.getContent())
                .as("the viewer must never see the raw word")
                .isEqualTo("that is *** rude");
    }

    @Test
    @DisplayName("a filter that matches nothing leaves the message alone")
    void wordFilterLeavesCleanMessagesAlone() {
        joinAll(GLOBAL);
        authority.setWordFilter(LiteWordFilter.of(List.of("damn"), null));

        List<Packet> out = send(chat(bob, GLOBAL, "perfectly polite"));

        ChatMessagePacket delivered = out.stream()
                .filter(ChatMessagePacket.class::isInstance)
                .map(ChatMessagePacket.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(delivered.getContent()).isEqualTo("perfectly polite");
    }

    @Test
    @DisplayName("a muted player's line is dropped before the filter runs")
    void muteBeatsFilter() {
        joinAll(GLOBAL);
        authority.setWordFilter(LiteWordFilter.of(List.of("damn"), null));
        mute(moderator, bob, GLOBAL, "10m");

        assertThat(chatReaches(bob, GLOBAL, "damn")).isFalse();
    }
    // -------------------------------------------------------------- persistence

    @Test
    @DisplayName("a mute survives a restart through the store")
    void muteSurvivesRestart() {
        Path database = tempDir.resolve("authority-mute.db");

        try (LiteChannelStore store = sqliteStore(database)) {
            store.open();
            LocalAuthority first = authority(store);
            joinAll(first, GLOBAL);
            assertThat(mute(first, moderator, bob, GLOBAL, "10m").isSuccess()).isTrue();
            assertThat(store.flush(30_000L)).isTrue();
        }

        try (LiteChannelStore reopened = sqliteStore(database)) {
            reopened.open();
            LocalAuthority second = authority(reopened);
            joinAll(second, GLOBAL);
            second.onPlayerConnect(bob.id);
            assertThat(reopened.flush(30_000L)).isTrue();

            assertThat(chatReaches(second, bob, GLOBAL, "blocked after restart"))
                    .as("the persisted mute must re-apply on the new authority")
                    .isFalse();
        }
    }
    @Test
    @DisplayName("memory storage enforces mutes without a database")
    void memoryStorageStillEnforces() {
        joinAll(GLOBAL);
        assertThat(mute(moderator, bob, GLOBAL, "10m").isSuccess()).isTrue();
        assertThat(chatReaches(bob, GLOBAL, "blocked in memory")).isFalse();
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Builds an authority over its own chat core.
     *
     * <p>The online roster is deliberately *not* rebuilt: the players are the
     * same objects across a simulated restart, which is what makes the
     * persistence assertion meaningful — a persisted row is keyed by UUID, so a
     * fresh round of fake players with fresh UUIDs would test nothing.
     */
    private LocalAuthority authority(LiteChannelStore store) {
        try {
            ProxyConfig config = ProxyConfig.from(new MapSource(liteDefaults()));
            KnownChannelRegistry registry = new KnownChannelRegistry();
            registry.replaceAll(Set.of(GLOBAL, LOCAL));
            chatCore = new ProxyChatCore(platform, config, new IgnoreListService(),
                    new PrivateMessageService(), registry, () -> null);
            chatCore.rebindIncomingHandlers(new LocalAuthorityClient(
                    (task, delayMillis) -> task.run(), 0L, message -> { }));
            return new LocalAuthority(platform, chatCore, new IgnoreListService(), store);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build the test authority", e);
        }
    }

    private LiteChannelStore sqliteStore(Path database) {
        Map<String, com.nova.chat.proxy.config.LiteChannelConfig> declared = new LinkedHashMap<>();
        declared.put(GLOBAL, new com.nova.chat.proxy.config.LiteChannelConfig(
                GLOBAL, GLOBAL, null, 0, 0));
        declared.put(LOCAL, new com.nova.chat.proxy.config.LiteChannelConfig(
                LOCAL, LOCAL, null, 0, 0));
        return LiteChannelStore.create(
                LiteStorageConfig.forTests(LiteStorageConfig.Type.SQLITE,
                        database.toString(), 0, null),
                tempDir,
                declared,
                "VELOCITY",
                warnings::add,
                message -> { });
    }

    private void joinAll(String... channels) {
        joinAll(authority, channels);
    }

    private void joinAll(LocalAuthority target, String... channels) {
        for (String channel : channels) {
            send(target, action(alice, ChannelAction.JOIN, channel));
            send(target, action(bob, ChannelAction.JOIN, channel));
            send(target, action(moderator, ChannelAction.JOIN, channel));
        }
    }

    private ChannelActionResponsePacket mute(FakePlayer operator, FakePlayer target,
                                             String channelId, String durationSeconds) {
        return mute(authority, operator, target, channelId, durationSeconds);
    }

    private ChannelActionResponsePacket mute(LocalAuthority target, FakePlayer operator,
                                             FakePlayer victim, String channelId,
                                             String durationSeconds) {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.MUTE, channelId);
        packet.addExtra("operatorId", operator.id.toString());
        packet.addExtra("operatorName", operator.name);
        packet.addExtra("targetId", victim.id.toString());
        packet.addExtra("targetName", victim.name);
        packet.addExtra("duration", durationSeconds);
        return (ChannelActionResponsePacket) send(target, packet).get(0);
    }

    private ChannelActionResponsePacket unmute(FakePlayer operator, FakePlayer target,
                                               String channelId) {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.UNMUTE, channelId);
        packet.addExtra("operatorId", operator.id.toString());
        packet.addExtra("targetId", target.id.toString());
        packet.addExtra("targetName", target.name);
        return (ChannelActionResponsePacket) send(packet).get(0);
    }

    /**
     * Sends one line as {@code sender} and reports whether it fanned out.
     *
     * @return {@code true} when a chat packet reached the downstream handlers
     */
    private boolean chatReaches(FakePlayer sender, String channelId, String content) {
        return chatReaches(authority, sender, channelId, content);
    }

    private boolean chatReaches(LocalAuthority target, FakePlayer sender,
                                String channelId, String content) {
        ChatMessagePacket packet = new ChatMessagePacket(
                sender.id, sender.name, "proxy-1", channelId, content);
        packet.addPlaceholder("player", sender.name);
        packet.addPlaceholder("display_name", sender.name);
        List<Packet> out = send(target, packet);
        return out.stream().anyMatch(ChatMessagePacket.class::isInstance);
    }

    private static ChatMessagePacket chat(FakePlayer sender, String channelId, String content) {
        ChatMessagePacket packet = new ChatMessagePacket(
                sender.id, sender.name, "proxy-1", channelId, content);
        packet.addPlaceholder("player", sender.name);
        packet.addPlaceholder("display_name", sender.name);
        return packet;
    }
    private List<Packet> send(Packet packet) {
        return send(authority, packet);
    }

    private List<Packet> send(LocalAuthority target, Packet packet) {
        List<Packet> out = target.handle(packet);
        for (Packet response : out) {
            if (response instanceof ChatMessagePacket chat) {
                chatCore.handleIncomingMessage(chat);
            } else if (response instanceof MentionPacket mention) {
                chatCore.handleMention(mention);
            } else if (response instanceof PrivateMessagePacket message) {
                chatCore.handlePrivateMessage(message);
            } else if (response instanceof ChannelActionResponsePacket action) {
                chatCore.handleChannelActionResponse(action);
            }
        }
        return out;
    }

    private static ChannelActionPacket action(FakePlayer player, ChannelAction action,
                                              String channel) {
        ChannelActionPacket packet = new ChannelActionPacket(action, channel);
        packet.addExtra("playerId", player.id.toString());
        packet.addExtra("playerName", player.name);
        packet.addExtra("previousChannel", LOCAL);
        return packet;
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
        channels.put(GLOBAL, channel(GLOBAL));
        channels.put(LOCAL, channel(LOCAL));
        values.put("lite.channels", channels);
        values.put("lite.storage", Map.of("type", "memory"));
        return values;
    }

    private static Map<String, Object> channel(String id) {
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("display-name", id);
        channel.put("max-capacity", 0L);
        channel.put("slow-mode", 0L);
        return channel;
    }

    // ------------------------------------------------------------- fakes

    private static final class FakePlayer implements ProxyPlayer {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private final Set<String> permissions = ConcurrentHashMap.newKeySet();
        private final List<String> messages = new ArrayList<>();

        private FakePlayer(String name) {
            this.name = name;
        }

        @Override public UUID getUniqueId() { return id; }
        @Override public String getName() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public String getCurrentServer() { return null; }
        @Override public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }
    }

    private static final class FakePlatform implements ProxyPlatform {
        private final Map<UUID, FakePlayer> players = new LinkedHashMap<>();

        private FakePlayer add(String name) {
            FakePlayer player = new FakePlayer(name);
            players.put(player.id, player);
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
            ((FakePlayer) player).messages.add(legacyText);
        }

        @Override
        public void sendHoverableMessage(ProxyPlayer player, String legacyText, String hoverText) {
            ((FakePlayer) player).messages.add(legacyText);
        }

        @Override
        public void showTitle(ProxyPlayer player, String title, String subtitle,
                              int fadeIn, int stay, int fadeOut) { }

        @Override
        public void playSound(ProxyPlayer player, String soundId, float volume, float pitch) { }

        @Override
        public void notifyMention(ProxyPlayer player, String highlight, String subtitle) { }

        @Override
        public void sendActionBar(ProxyPlayer player, String legacyText) { }

        @Override
        public void showChannelStatusBar(ProxyPlayer player, String channelId, ChatMode mode) { }

        @Override
        public void notifyKickMute(ProxyPlayer player, ChannelAction action, String channelId,
                                   String operator, String durationText) { }

        @Override
        public boolean consoleHasPermission(String permission) { return true; }

        @Override
        public void execute(Runnable task) { task.run(); }

        @Override
        public void runAsync(Runnable task) { task.run(); }

        @Override
        public void runLater(Runnable task, long delaySeconds) { task.run(); }

        @Override public void logInfo(String message) { }
        @Override public void logWarn(String message) { }
        @Override public void logDebug(String message) { }
        @Override public void logError(String message) { }
        @Override public void logError(String message, Throwable cause) { }

        @Override
        public PlatformType getPlatformType() {
            return PlatformType.VELOCITY;
        }

        @Override
        public void reloadConfiguration() { }
    }

    /** Flat-key ConfigSource mirroring the bundled lite template. */
    private static final class MapSource implements com.nova.chat.proxy.config.ConfigSource {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private MapSource(Map<String, Object> values) {
            this.values.putAll(values);
        }

        private Object lookup(String path) {
            String[] segments = path.split("\\.");
            for (int length = segments.length; length > 0; length--) {
                String prefix = String.join(".",
                        java.util.Arrays.copyOf(segments, length));
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
        public String optionalString(String path) {
            Object value = lookup(path);
            return value != null ? value.toString() : null;
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
    }
}
