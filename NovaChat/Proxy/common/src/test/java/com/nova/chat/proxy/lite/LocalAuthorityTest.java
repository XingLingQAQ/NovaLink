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
 * Unit tests for the lite-mode local authority.
 *
 * <p>Downstream packets produced by {@link LocalAuthority#handle(Packet)} are fed
 * back through the real {@link ProxyChatCore} handlers, so these tests exercise
 * the production fan-out / formatting / ignore / mention paths instead of a
 * re-implementation. The platform is a small in-memory fake; no proxy API is
 * involved.
 */
@DisplayName("Lite LocalAuthority")
class LocalAuthorityTest {

    private static final String GLOBAL = "global";
    private static final String LOCAL = "local";
    private static final String VIP = "vip";
    private static final String TRADE = "trade";

    private FakePlatform platform;
    private ProxyChatCore chatCore;
    private IgnoreListService ignoreListService;
    private PrivateMessageService privateMessageService;
    private LocalAuthority authority;
    private Locale previousLocale;

    private FakePlayer alice;
    private FakePlayer bob;
    private FakePlayer carol;

    @BeforeEach
    void setUp() throws com.nova.chat.proxy.config.ConfigSource.ConfigException {
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        ProxyConfig config = ProxyConfig.from(new MapSource(liteDefaults()));
        ignoreListService = new IgnoreListService();
        privateMessageService = new PrivateMessageService();
        platform = new FakePlatform();

        alice = platform.add("Alice");
        bob = platform.add("Bob");
        carol = platform.add("Carol");
        alice.permissions.add("novachat.channel.global");
        bob.permissions.add("novachat.channel.global");

        KnownChannelRegistry registry = new KnownChannelRegistry();
        registry.replaceAll(Set.of(GLOBAL, LOCAL, VIP, TRADE));
        chatCore = new ProxyChatCore(platform, config, ignoreListService,
                privateMessageService, registry, () -> null);
        // Builds the shared ChannelResponseDispatcher against a client; the same
        // client object receives the downstream handlers (never bound to an
        // authority here — the test drives LocalAuthority directly).
        chatCore.rebindIncomingHandlers(new LocalAuthorityClient(
                (task, delayMillis) -> task.run(), 0L, message -> { }));

        authority = new LocalAuthority(platform, chatCore, ignoreListService);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    // ------------------------------------------------------------------- chat

    @Test
    @DisplayName("a message reaches every online player whose active channel matches")
    void chatReachesSameChannelMembers() {
        join(alice, GLOBAL);
        join(bob, GLOBAL);
        join(carol, LOCAL);

        send(chat(alice, GLOBAL, "hello channel"));

        assertThat(bob.messages).anyMatch(line -> line.contains("hello channel"));
        assertThat(alice.messages).anyMatch(line -> line.contains("hello channel"));
        assertThat(carol.messages).noneMatch(line -> line.contains("hello channel"));
    }

    @Test
    @DisplayName("players in another channel never receive the message")
    void chatDoesNotLeakAcrossChannels() {
        join(alice, GLOBAL);
        join(bob, LOCAL);

        send(chat(alice, GLOBAL, "only global"));

        assertThat(bob.messages).isEmpty();
    }

    @Test
    @DisplayName("the recipient's ignore list suppresses the sender's line")
    void chatRespectsIgnoreList() {
        join(alice, GLOBAL);
        join(bob, GLOBAL);
        ignoreListService.ignore(bob.id, bob.name, alice.name);

        send(chat(alice, GLOBAL, "you cannot see this"));

        assertThat(bob.messages).noneMatch(line -> line.contains("you cannot see this"));
    }

    @Test
    @DisplayName("@mentions highlight in the rendered line and notify the mentioned player")
    void chatHighlightsAndNotifiesMentions() {
        join(alice, GLOBAL);
        join(bob, GLOBAL);

        send(chat(alice, GLOBAL, "hey @Bob look"));

        assertThat(bob.messages).anyMatch(line -> line.contains("&e@Bob"));
        assertThat(bob.titles).anyMatch(title -> title.contains("Alice"));
    }

    @Test
    @DisplayName("an unknown channel drops the message and reports NC-404 to the sender")
    void chatToUnknownChannelIsRejected() {
        join(alice, LOCAL);

        send(chat(alice, "nope", "hi"));

        assertThat(alice.messages).anyMatch(line -> line.contains("NC-404"));
        assertThat(bob.messages).isEmpty();
    }

    @Test
    @DisplayName("slow mode accepts the first message and rejects the next one (NC-429)")
    void slowModeRejectsBurst() {
        join(alice, TRADE);
        join(bob, TRADE);

        send(chat(alice, TRADE, "first"));
        send(chat(alice, TRADE, "second"));

        assertThat(bob.messages).anyMatch(line -> line.contains("first"));
        assertThat(bob.messages).noneMatch(line -> line.contains("second"));
        assertThat(alice.messages).anyMatch(line -> line.contains("NC-429"));
    }

    @Test
    @DisplayName("a channel permission node is enforced locally (NC-403)")
    void chatPermissionIsEnforced() {
        join(alice, GLOBAL);
        // Carol has no novachat.channel.global node.
        chatCore.getOrCreateState(carol);

        send(chat(carol, GLOBAL, "i am not permitted"));

        assertThat(carol.messages).anyMatch(line -> line.contains("NC-403"));
        assertThat(alice.messages).noneMatch(line -> line.contains("i am not permitted"));
    }

    // ---------------------------------------------------------------- channels

    @Test
    @DisplayName("join records membership and makes the channel active")
    void joinSwitchesActiveChannel() {
        PlayerChannelState state = chatCore.getOrCreateState(alice);

        send(action(alice, ChannelAction.JOIN, GLOBAL));

        assertThat(state.isJoined(GLOBAL)).isTrue();
        assertThat(state.getActiveChannel()).isEqualTo(GLOBAL);
    }

    @Test
    @DisplayName("leave drops membership; the active channel falls back")
    void leaveRemovesMembership() {
        PlayerChannelState state = chatCore.getOrCreateState(alice);
        send(action(alice, ChannelAction.JOIN, GLOBAL));
        assertThat(authority.memberCount(GLOBAL)).isEqualTo(1);

        send(action(alice, ChannelAction.LEAVE, GLOBAL));

        assertThat(authority.memberCount(GLOBAL)).isZero();
        assertThat(state.isJoined(GLOBAL)).isFalse();
        assertThat(state.getActiveChannel()).isNotEqualTo(GLOBAL);
    }

    @Test
    @DisplayName("leave still succeeds after the client's optimistic local removal")
    void leaveWorksAfterOptimisticRemoval() {
        join(alice, GLOBAL);
        // ChannelCommandService.leave removes local membership BEFORE the
        // packed is sent; the authority must keep its own membership table or
        // every legitimate LEAVE would be rejected with NC-433.
        chatCore.getState(alice.id).leaveChannel(GLOBAL);

        ChannelActionResponsePacket response =
                (ChannelActionResponsePacket) send(action(alice, ChannelAction.LEAVE, GLOBAL)).get(0);

        assertThat(response.isSuccess()).isTrue();
        assertThat(authority.memberCount(GLOBAL)).isZero();
    }

    @Test
    @DisplayName("leaving a channel the player never joined reports NC-433")
    void leaveWithoutMembershipFails() {
        join(alice, LOCAL);

        ChannelActionResponsePacket response =
                (ChannelActionResponsePacket) send(action(alice, ChannelAction.LEAVE, GLOBAL)).get(0);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-433");
    }

    @Test
    @DisplayName("a full channel rejects further joins with NC-431")
    void fullChannelRejectsJoin() {
        // VIP has max-capacity 1.
        join(alice, VIP);

        ChannelActionResponsePacket response =
                (ChannelActionResponsePacket) send(action(bob, ChannelAction.JOIN, VIP)).get(0);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-431");
        assertThat(authority.memberCount(VIP)).isEqualTo(1);

        // The rejected player can still talk in a channel they may join.
        join(bob, LOCAL);
        send(chat(bob, LOCAL, "still works"));
        assertThat(bob.messages).anyMatch(line -> line.contains("still works"));
    }

    @Test
    @DisplayName("a channel the player already belongs to is not counted against capacity")
    void rejoinOfMemberIsIdempotent() {
        join(alice, VIP);

        ChannelActionResponsePacket response =
                (ChannelActionResponsePacket) send(action(alice, ChannelAction.JOIN, VIP)).get(0);

        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("join without the required permission reports NC-403")
    void joinWithoutPermissionFails() {
        ChannelActionResponsePacket response =
                (ChannelActionResponsePacket) send(action(carol, ChannelAction.JOIN, GLOBAL)).get(0);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-403");
    }

    @Test
    @DisplayName("who lists the online members of the channel")
    void whoListsMembers() {
        join(alice, GLOBAL);
        join(bob, GLOBAL);
        join(carol, LOCAL);

        ChannelActionResponsePacket response =
                (ChannelActionResponsePacket) send(action(alice, ChannelAction.WHO, GLOBAL)).get(0);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getExtra("members")).isEqualTo("Alice, Bob");
        assertThat(response.getExtra("memberCount")).isEqualTo("2");
        assertThat(response.getExtra("displayName")).isEqualTo("全服");
    }

    @Test
    @DisplayName("responses echo the request id of the action that produced them")
    void responsesEchoRequestId() {
        ChannelActionPacket request = action(alice, ChannelAction.JOIN, GLOBAL);

        ChannelActionResponsePacket response =
                (ChannelActionResponsePacket) send(request).get(0);

        assertThat(response.getRequestId()).isEqualTo(request.getRequestId());
    }

    @Test
    @DisplayName("unsupported actions answer NC-501 instead of pretending success")
    void unsupportedActionIsExplicit() {
        ChannelActionPacket request = action(alice, ChannelAction.CREATE, "x");

        ChannelActionResponsePacket response =
                (ChannelActionResponsePacket) send(request).get(0);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-501");
    }

    // ------------------------------------------------------------ private msgs

    @Test
    @DisplayName("a private message to an online player is completed and echoed to both sides")
    void privateMessageDelivers() {
        List<Packet> downstream = send(privateMessage(alice, "Bob", "psst"));

        assertThat(downstream).hasSize(1);
        PrivateMessagePacket completed = (PrivateMessagePacket) downstream.get(0);
        assertThat(completed.getTargetId()).isEqualTo(bob.id);
        assertThat(completed.getTargetName()).isEqualTo("Bob");
        assertThat(alice.messages).anyMatch(line -> line.contains("psst"));
        assertThat(bob.messages).anyMatch(line -> line.contains("psst"));
    }

    @Test
    @DisplayName("an offline target yields an explicit not_online error, never a fake success")
    void privateMessageToOfflineTargetFails() {
        List<Packet> downstream = send(privateMessage(alice, "Nobody", "hello?"));

        assertThat(downstream).hasSize(1);
        ChannelActionResponsePacket error = (ChannelActionResponsePacket) downstream.get(0);
        assertThat(error.isSuccess()).isFalse();
        assertThat(PrivateMessageService.isPrivateMessageError(error)).isTrue();
        assertThat(error.getExtra("detail")).isEqualTo("not_online");
        assertThat(alice.messages).anyMatch(line -> line.contains("不在线"));
        assertThat(bob.messages).isEmpty();
    }

    @Test
    @DisplayName("a target that ignores the sender looks offline to them")
    void ignoredSenderSeesNotOnline() {
        ignoreListService.ignore(bob.id, bob.name, alice.name);

        ChannelActionResponsePacket error =
                (ChannelActionResponsePacket) send(privateMessage(alice, "Bob", "hi")).get(0);

        assertThat(error.getExtra("detail")).isEqualTo("not_online");
    }

    @Test
    @DisplayName("a sender that ignores the target is told it is their own choice")
    void senderIgnoringTargetGetsIgnoredBySender() {
        ignoreListService.ignore(alice.id, alice.name, bob.name);

        ChannelActionResponsePacket error =
                (ChannelActionResponsePacket) send(privateMessage(alice, "Bob", "hi")).get(0);

        assertThat(error.getExtra("detail")).isEqualTo("ignored_by_sender");
    }

    @Test
    @DisplayName("a logout leave is clean-up and never answers NC-433")
    void logoutLeaveIsBestEffort() {
        ChannelActionPacket logout = action(alice, ChannelAction.LEAVE, GLOBAL);
        logout.addExtra("logout", "true");

        ChannelActionResponsePacket response =
                (ChannelActionResponsePacket) send(logout).get(0);

        assertThat(response.isSuccess()).isTrue();
        assertThat(alice.messages).noneMatch(line -> line.contains("NC-433"));
    }

    @Test
    @DisplayName("disconnect eviction removes the player from WHO and frees capacity")
    void disconnectEvictsMembership() {
        join(alice, VIP);
        assertThat(authority.memberCount(VIP)).isEqualTo(1);

        authority.onPlayerDisconnect(alice.id);

        assertThat(authority.memberCount(VIP)).isZero();
        // Capacity freed: Bob can now take the single slot.
        ChannelActionResponsePacket response =
                (ChannelActionResponsePacket) send(action(bob, ChannelAction.JOIN, VIP)).get(0);
        assertThat(response.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a delivered private message records the reply target for /nc r")
    void privateMessageEnablesReply() {
        // Alice -> Bob.
        send(privateMessage(alice, "Bob", "first"));
        assertThat(privateMessageService.getReplyTarget(alice.id)).contains("Bob");
        assertThat(privateMessageService.getReplyTarget(bob.id)).contains("Alice");

        // /nc r from Bob: PrivateMessageCommandService resolves the remembered
        // partner by name, exactly as the command layer does.
        List<String> receipts = com.nova.chat.client.command.PrivateMessageCommandService.reply(
                privateMessageService,
                packet -> {
                    send(packet);
                    return true;
                },
                bob.id, bob.name, "proxy-1", new String[]{"second"});

        assertThat(receipts).isEmpty();
        assertThat(alice.messages).anyMatch(line -> line.contains("second"));
        assertThat(bob.messages).anyMatch(line -> line.contains("second"));
    }

    @Test
    @DisplayName("messaging yourself is rejected")
    void selfPrivateMessageFails() {
        ChannelActionResponsePacket error =
                (ChannelActionResponsePacket) send(privateMessage(alice, "Alice", "me")).get(0);

        assertThat(error.getExtra("detail")).isEqualTo("self");
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Runs one upstream packet through the authority and feeds every downstream
     * packet through the production chat-core handler, mirroring
     * {@link LocalAuthorityClient}'s delivery loop.
     */
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

    private void join(FakePlayer player, String channel) {
        send(action(player, ChannelAction.JOIN, channel));
    }

    private static ChannelActionPacket action(FakePlayer player, ChannelAction action, String channel) {
        ChannelActionPacket packet = new ChannelActionPacket(action, channel);
        packet.addExtra("playerId", player.id.toString());
        packet.addExtra("playerName", player.name);
        packet.addExtra("previousChannel", LOCAL);
        return packet;
    }

    private static ChatMessagePacket chat(FakePlayer sender, String channelId, String content) {
        ChatMessagePacket packet = new ChatMessagePacket(
                sender.id, sender.name, "proxy-1", channelId, content);
        packet.addPlaceholder("player", sender.name);
        packet.addPlaceholder("display_name", sender.name);
        return packet;
    }

    private static PrivateMessagePacket privateMessage(FakePlayer sender, String target, String content) {
        return PrivateMessageService.buildPacket(
                sender.id, sender.name, "proxy-1", target, content);
    }

    /** In-memory player handle. */
    private static final class FakePlayer implements ProxyPlayer {
        private final UUID id = UUID.randomUUID();
        private final String name;
        private final Set<String> permissions = ConcurrentHashMap.newKeySet();
        private final List<String> messages = new ArrayList<>();
        private final List<String> titles = new ArrayList<>();

        private FakePlayer(String name) {
            this.name = name;
        }

        @Override public UUID getUniqueId() { return id; }
        @Override public String getName() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public String getCurrentServer() { return null; }
        @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    }

    /** Minimal platform: online roster, message capture, title capture. */
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
            ((FakePlayer) player).titles.add(title + "|" + subtitle);
        }

        @Override
        public void playSound(ProxyPlayer player, String soundId, float volume, float pitch) {
        }

        @Override
        public void notifyMention(ProxyPlayer player, String highlight, String subtitle) {
            ((FakePlayer) player).titles.add(highlight + "|" + subtitle);
        }

        @Override
        public void sendActionBar(ProxyPlayer player, String legacyText) {
        }

        @Override
        public void showChannelStatusBar(ProxyPlayer player, String channelId, ChatMode mode) {
        }

        @Override
        public void notifyKickMute(ProxyPlayer player, ChannelAction action, String channelId,
                                   String operator, String durationText) {
        }

        @Override
        public boolean consoleHasPermission(String permission) {
            return true;
        }

        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            task.run();
        }

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
        public void reloadConfiguration() {
        }
    }

    // ----------------------------------------------------------- config fixture

    /** Flat-key ConfigSource mirroring the bundled lite template. */
    private static final class MapSource implements com.nova.chat.proxy.config.ConfigSource {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private MapSource(Map<String, Object> values) {
            this.values.putAll(values);
        }

        /**
         * Resolves a dotted path against the longest flat-key prefix, then
         * walks the remaining segments through nested maps. The fixture mixes
         * both shapes ({@code lite.channels} is flat, {@code lite.storage} is a
         * nested map), matching the real dialect sources.
         */
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
                VIP, "[{channel_name}] {player}: {message}",
                TRADE, "[{channel_name}] {player}: {message}"));
        values.put("debug", false);

        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put(GLOBAL, channel("全服", "novachat.channel.global", 1000, 0));
        channels.put(LOCAL, channel("本地", null, 0, 0));
        channels.put(VIP, channel("VIP", null, 1, 0));
        channels.put(TRADE, channel("交易", null, 0, 30));
        values.put("lite.channels", channels);
        values.put("lite.storage", Map.of("type", "memory"));
        return values;
    }

    private static Map<String, Object> channel(String displayName, String permission,
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
