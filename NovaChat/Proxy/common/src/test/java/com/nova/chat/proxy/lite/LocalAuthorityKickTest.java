package com.nova.chat.proxy.lite;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
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
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KICK at the authority level.
 *
 * <p>These assert on membership, which is the thing a kick actually changes:
 * the target stops appearing in the channel's roster and its persisted
 * membership, and can rejoin afterwards (a kick is not a ban).
 *
 * <p>Requests are built the way the platform {@code KickCommand} builds them —
 * {@code operatorId}/{@code operatorName}/{@code targetName}, usually with no
 * {@code targetId} because the target sits on another sub-server.
 */
@DisplayName("Lite authority kicks")
class LocalAuthorityKickTest {

    private static final String GLOBAL = "global";
    private static final String LOCAL = "local";

    private static final String KICK_NODE = "novachat.kick";

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
        moderator.permissions.add(KICK_NODE);

        ProxyConfig config = ProxyConfig.from(new MapSource(liteDefaults()));
        KnownChannelRegistry registry = new KnownChannelRegistry();
        registry.replaceAll(Set.of(GLOBAL, LOCAL));
        chatCore = new ProxyChatCore(platform, config, new IgnoreListService(),
                new PrivateMessageService(), registry, () -> null);
        chatCore.rebindIncomingHandlers(new LocalAuthorityClient(
                (task, delayMillis) -> task.run(), 0L, message -> { }));
        authority = new LocalAuthority(platform, chatCore, new IgnoreListService(),
                LiteChannelStore.memory());
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    // ------------------------------------------------------------------ success

    @Test
    @DisplayName("a kick removes the target from the channel")
    void kickRemovesTargetFromChannel() {
        joinAll(GLOBAL);
        assertThat(roster(GLOBAL)).contains("Bob");

        ChannelActionResponsePacket response = kick(moderator, bob, GLOBAL);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.KICK);
        assertThat(roster(GLOBAL)).as("the roster no longer lists the kicked player")
                .doesNotContain("Bob");
    }

    @Test
    @DisplayName("a kick answers with targetId so the target can be notified")
    void kickStampsTargetId() {
        joinAll(GLOBAL);

        ChannelActionResponsePacket response = kick(moderator, bob, GLOBAL);

        assertThat(response.getExtra("targetId")).isEqualTo(bob.id.toString());
        assertThat(response.getExtra("operatorName")).isEqualTo(moderator.name);
    }

    @Test
    @DisplayName("a kicked player may rejoin; a kick is not a ban")
    void kickedPlayerCanRejoin() {
        joinAll(GLOBAL);
        kick(moderator, bob, GLOBAL);

        send(action(bob, ChannelAction.JOIN, GLOBAL));

        assertThat(roster(GLOBAL)).contains("Bob");
    }

    @Test
    @DisplayName("a kick resolves a target that only arrives by name")
    void kickResolvesTargetByName() {
        joinAll(GLOBAL);

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.KICK, GLOBAL);
        packet.addExtra("operatorId", moderator.id.toString());
        packet.addExtra("operatorName", moderator.name);
        packet.addExtra("targetName", bob.name);

        ChannelActionResponsePacket response = (ChannelActionResponsePacket) send(packet).get(0);

        assertThat(response.isSuccess()).isTrue();
        assertThat(roster(GLOBAL)).doesNotContain("Bob");
    }

    // ----------------------------------------------------------------- refusals

    @Test
    @DisplayName("kicking someone who is not in the channel is refused")
    void kickNonMemberIsRefused() {
        joinAll(GLOBAL);

        ChannelActionResponsePacket response = kick(moderator, alice, LOCAL);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_IN_CHANNEL.getCode());
    }

    @Test
    @DisplayName("a plain member cannot kick")
    void plainMemberCannotKick() {
        joinAll(GLOBAL);

        ChannelActionResponsePacket response = kick(alice, bob, GLOBAL);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode());
        assertThat(roster(GLOBAL)).as("the failed kick changed nothing").contains("Bob");
    }

    @Test
    @DisplayName("novachat.mute does not grant kick rights")
    void mutePermissionDoesNotGrantKick() {
        moderator.permissions.clear();
        moderator.permissions.add("novachat.mute");
        joinAll(GLOBAL);

        assertThat(kick(moderator, bob, GLOBAL).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("kicking yourself is refused")
    void selfKickIsRefused() {
        joinAll(GLOBAL);

        ChannelActionResponsePacket response = kick(moderator, moderator, GLOBAL);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.SELF_ACTION.getCode());
    }

    @Test
    @DisplayName("an unknown channel is rejected")
    void unknownChannelIsRejected() {
        ChannelActionResponsePacket response = kick(moderator, bob, "nope");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("a kick for an offline target is refused, not silently accepted")
    void kickForUnknownTargetIsRefused() {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.KICK, GLOBAL);
        packet.addExtra("operatorId", moderator.id.toString());
        packet.addExtra("targetName", "NobodyHome");

        ChannelActionResponsePacket response = (ChannelActionResponsePacket) send(packet).get(0);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.TARGET_OFFLINE.getCode());
    }

    // ---------------------------------------------------------------- fixtures

    private ChannelActionResponsePacket kick(FakePlayer operator, FakePlayer target,
                                             String channelId) {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.KICK, channelId);
        packet.addExtra("operatorId", operator.id.toString());
        packet.addExtra("operatorName", operator.name);
        packet.addExtra("targetId", target.id.toString());
        packet.addExtra("targetName", target.name);
        return (ChannelActionResponsePacket) send(packet).get(0);
    }

    /** Reads the channel roster through the authority's WHO answer. */
    private String roster(String channelId) {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, channelId);
        packet.addExtra("playerId", alice.id.toString());
        packet.addExtra("playerName", alice.name);
        ChannelActionResponsePacket response = (ChannelActionResponsePacket) send(packet).get(0);
        String members = response.getExtra("members");
        return members != null ? members : "";
    }

    private void joinAll(String... channels) {
        for (String channel : channels) {
            send(action(alice, ChannelAction.JOIN, channel));
            send(action(bob, ChannelAction.JOIN, channel));
            send(action(moderator, ChannelAction.JOIN, channel));
        }
    }

    private List<Packet> send(Packet packet) {
        List<Packet> out = authority.handle(packet);
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
