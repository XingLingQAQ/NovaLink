package com.nova.chat.proxy.lite;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
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
 * TITLE at the authority level.
 *
 * <p>The platform sends a title as an {@code AdminActionPacket} with a
 * {@code type=TITLE} extra, reusing {@code AdminAction.STATUS} for transport.
 * That indirection is the thing most likely to be got wrong, so these tests
 * build the packet exactly as {@code TitleCommand} does and assert on what the
 * players actually receive.
 *
 * <p>"Received" means a {@code showTitle} call on a player whose active channel
 * matches — the same recipient rule the rest of the chat core uses.
 */
@DisplayName("Lite authority announcements")
class LocalAuthorityAnnounceTest {

    private static final String GLOBAL = "global";
    private static final String LOCAL = "local";

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
        moderator.permissions.add("novachat.announce");

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

    @Test
    @DisplayName("an announcement reaches the players in that channel")
    void announceReachesChannelMembers() {
        joinAll(GLOBAL);

        send(announce(moderator, GLOBAL, "Server restart in 5 minutes"));

        assertThat(messagesFor(alice))
                .anyMatch(line -> line.contains("Server restart in 5 minutes"));
        assertThat(messagesFor(bob))
                .anyMatch(line -> line.contains("Server restart in 5 minutes"));
    }

    @Test
    @DisplayName("the announcement carries the same prefix the backend uses")
    void announceUsesBackendPrefix() {
        joinAll(GLOBAL);

        send(announce(moderator, GLOBAL, "hello"));

        assertThat(messagesFor(alice))
                .as("lite and relay must render announcements identically")
                .anyMatch(line -> line.contains("【公告】 hello"));
    }

    @Test
    @DisplayName("an announcement is attributed to the operator")
    void announceIsAttributedToOperator() {
        joinAll(GLOBAL);

        send(announce(moderator, GLOBAL, "hi"));

        assertThat(messagesFor(alice)).anyMatch(line -> line.contains("Mod"));
    }

    @Test
    @DisplayName("an announcement does not reach players in another channel")
    void announceIsScopedToItsChannel() {
        send(action(alice, ChannelAction.JOIN, GLOBAL));
        send(action(bob, ChannelAction.JOIN, LOCAL));

        send(announce(moderator, GLOBAL, "only-global"));

        assertThat(messagesFor(alice)).anyMatch(line -> line.contains("only-global"));
        assertThat(messagesFor(bob))
                .as("a local player must not see a global announcement")
                .noneMatch(line -> line.contains("only-global"));
    }

    @Test
    @DisplayName("the command answers with a success response")
    void announceAnswersSuccess() {
        joinAll(GLOBAL);

        List<Packet> out = send(announce(moderator, GLOBAL, "ok"));

        AdminActionResponsePacket response = out.stream()
                .filter(AdminActionResponsePacket.class::isInstance)
                .map(AdminActionResponsePacket.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(response.getMessage()).isEqualTo("Announcement sent");
    }

    @Test
    @DisplayName("a member without the announce node is refused")
    void memberWithoutPermissionIsRefused() {
        joinAll(GLOBAL);

        List<Packet> out = send(announce(alice, GLOBAL, "nope"));

        assertThat(messagesFor(alice)).noneMatch(line -> line.contains("nope"));
        AdminActionResponsePacket response = out.stream()
                .filter(AdminActionResponsePacket.class::isInstance)
                .map(AdminActionResponsePacket.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    @DisplayName("the title node does not grant announce rights")
    void titlePermissionDoesNotGrantAnnounce() {
        moderator.permissions.clear();
        moderator.permissions.add("novachat.title");
        joinAll(GLOBAL);

        List<Packet> out = send(announce(moderator, GLOBAL, "nope"));

        AdminActionResponsePacket response = out.stream()
                .filter(AdminActionResponsePacket.class::isInstance)
                .map(AdminActionResponsePacket.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode());
    }

    @Test
    @DisplayName("an unknown channel is refused")
    void unknownChannelIsRefused() {
        List<Packet> out = send(announce(moderator, "nope", "hi"));

        AdminActionResponsePacket response = out.stream()
                .filter(AdminActionResponsePacket.class::isInstance)
                .map(AdminActionResponsePacket.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("empty content is refused")
    void emptyContentIsRefused() {
        joinAll(GLOBAL);

        List<Packet> out = send(announce(moderator, GLOBAL, "   "));

        AdminActionResponsePacket response = out.stream()
                .filter(AdminActionResponsePacket.class::isInstance)
                .map(AdminActionResponsePacket.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
        assertThat(messagesFor(alice)).isEmpty();
    }
    // ---------------------------------------------------------------- fixtures

    private AdminActionPacket announce(FakePlayer operator, String channelId, String content) {
        AdminActionPacket packet = new AdminActionPacket();
        packet.setAction(AdminAction.STATUS);
        packet.setPlayerId(operator.id);
        packet.setTarget(channelId);
        packet.addExtra("type", "ANNOUNCE");
        packet.addExtra("operatorName", operator.name);
        packet.addExtra("content", content);
        return packet;
    }

    private static List<String> messagesFor(FakePlayer player) {
        return player.messages;
    }
    private void joinAll(String channel) {
        send(action(alice, ChannelAction.JOIN, channel));
        send(action(bob, ChannelAction.JOIN, channel));
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
            } else if (response instanceof TitlePacket title) {
                chatCore.handleTitle(title);
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
        private final List<String> titles = new ArrayList<>();
        private final List<String> titleTimings = new ArrayList<>();

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
                              int fadeIn, int stay, int fadeOut) {
            FakePlayer fake = (FakePlayer) player;
            fake.titles.add(title + "|" + subtitle);
            fake.titleTimings.add(fadeIn + "/" + stay + "/" + fadeOut);
        }

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
