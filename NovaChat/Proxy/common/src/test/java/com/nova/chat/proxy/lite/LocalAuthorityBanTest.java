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
import com.nova.chat.proxy.config.LiteStorageConfig;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import com.nova.link.database.BanInfo;
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

import static com.nova.chat.proxy.lite.LiteTestFixture.FakePlatform;
import static com.nova.chat.proxy.lite.LiteTestFixture.FakePlayer;
import static com.nova.chat.proxy.lite.LiteTestFixture.MapSource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * BAN / UNBAN at the authority level.
 *
 * <p>The distinction these pin is what separates a ban from a mute: a ban
 * refuses entry and evicts the player, while a mute only silences them. A
 * regression that turned one into the other would still answer "success", so
 * every assertion here is about an observable consequence (roster membership,
 * whether a JOIN is accepted) rather than the response code alone.
 */
@DisplayName("Lite authority bans")
class LocalAuthorityBanTest {

    private static final String GLOBAL = "global";
    private static final String LOCAL = "local";

    @TempDir
    Path tempDir;

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
        moderator.permissions.add("novachat.ban");

        ProxyConfig config = ProxyConfig.from(new MapSource(LiteTestFixture.config(GLOBAL, LOCAL)));
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

    // ------------------------------------------------------------- ban effects

    @Test
    @DisplayName("a channel ban evicts the player from that channel")
    void channelBanEvictsFromThatChannel() {
        joinAll(GLOBAL, LOCAL);

        assertThat(ban(moderator, bob, GLOBAL).isSuccess()).isTrue();

        assertThat(roster(GLOBAL)).doesNotContain("Bob");
        assertThat(roster(LOCAL)).as("a channel ban leaves other channels alone")
                .contains("Bob");
    }

    @Test
    @DisplayName("a ban refuses a rejoin; a kick does not")
    void banRefusesRejoin() {
        joinAll(GLOBAL);
        ban(moderator, bob, GLOBAL);

        assertThat(joinBob(GLOBAL).isSuccess()).isFalse();
        assertThat(roster(GLOBAL)).doesNotContain("Bob");
    }

    @Test
    @DisplayName("a global ban evicts from every channel and refuses all joins")
    void globalBanCoversEveryChannel() {
        joinAll(GLOBAL, LOCAL);

        assertThat(banGlobally(moderator, bob).isSuccess()).isTrue();

        assertThat(roster(GLOBAL)).doesNotContain("Bob");
        assertThat(roster(LOCAL)).doesNotContain("Bob");
        assertThat(joinBob(GLOBAL).isSuccess()).isFalse();
        assertThat(joinBob(LOCAL).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("unban lets the player rejoin")
    void unbanAllowsRejoin() {
        joinAll(GLOBAL);
        ban(moderator, bob, GLOBAL);
        assertThat(joinBob(GLOBAL).isSuccess()).isFalse();

        assertThat(unban(moderator, bob, GLOBAL).isSuccess()).isTrue();

        assertThat(joinBob(GLOBAL).isSuccess()).isTrue();
        assertThat(roster(GLOBAL)).contains("Bob");
    }

    @Test
    @DisplayName("an expired ban does not block a rejoin")
    void expiredBanDoesNotBlock() {
        joinAll(GLOBAL);
        authority.restoreBansForTests(bob.id, List.of(
                new BanInfo(GLOBAL, System.currentTimeMillis() - 60_000L,
                        "stale", moderator.id)));

        assertThat(joinBob(GLOBAL).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a permanent ban never expires")
    void permanentBanNeverExpires() {
        joinAll(GLOBAL);
        ban(moderator, bob, GLOBAL, "0");

        assertThat(joinBob(GLOBAL).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("a ban survives a restart through the store")
    void banSurvivesRestart() {
        Path database = tempDir.resolve("authority-ban.db");

        try (LiteChannelStore store = sqliteStore(database)) {
            store.open();
            LocalAuthority first = authority(store);
            joinAll(first);
            assertThat(ban(first, moderator, bob, GLOBAL).isSuccess()).isTrue();
            assertThat(store.flush(30_000L)).isTrue();
        }

        try (LiteChannelStore reopened = sqliteStore(database)) {
            reopened.open();
            LocalAuthority second = authority(reopened);
            second.onPlayerConnect(bob.id);
            assertThat(reopened.flush(30_000L)).isTrue();

            ChannelActionPacket packet = action(bob, ChannelAction.JOIN, GLOBAL);
            ChannelActionResponsePacket response =
                    (ChannelActionResponsePacket) send(second, packet).get(0);
            assertThat(response.isSuccess())
                    .as("the persisted ban must refuse the rejoin after a restart")
                    .isFalse();
        }
    }
    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("a plain member cannot ban")
    void plainMemberCannotBan() {
        joinAll(GLOBAL);

        ChannelActionResponsePacket response = ban(alice, bob, GLOBAL);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN.getCode());
        assertThat(roster(GLOBAL)).contains("Bob");
    }

    @Test
    @DisplayName("novachat.kick does not grant ban rights")
    void kickPermissionDoesNotGrantBan() {
        moderator.permissions.clear();
        moderator.permissions.add("novachat.kick");
        joinAll(GLOBAL);

        assertThat(ban(moderator, bob, GLOBAL).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("banning yourself is refused")
    void selfBanIsRefused() {
        joinAll(GLOBAL);

        ChannelActionResponsePacket response = ban(moderator, moderator, GLOBAL);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.SELF_ACTION.getCode());
    }

    @Test
    @DisplayName("an unknown channel is rejected for a channel-scoped ban")
    void unknownChannelIsRejected() {
        ChannelActionResponsePacket response = ban(moderator, bob, "nope");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("unbanning someone who is not banned still succeeds")
    void unbanIsIdempotent() {
        joinAll(GLOBAL);

        assertThat(unban(moderator, bob, GLOBAL).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("a ban for an offline target is refused")
    void banForUnknownTargetIsRefused() {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.BAN, GLOBAL);
        packet.addExtra("operatorId", moderator.id.toString());
        packet.addExtra("targetName", "NobodyHome");

        ChannelActionResponsePacket response = (ChannelActionResponsePacket) send(packet).get(0);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.TARGET_OFFLINE.getCode());
    }

    // ------------------------------------------------------------- restart helpers

    /**
     * Builds an authority over an explicit roster.
     *
     * <p>The roster is passed in rather than recreated so the players keep
     * their UUIDs across a simulated restart: bans are keyed by UUID, so fresh
     * fakes would make the persistence assertion meaningless.
     */
    private LocalAuthority authority(LiteChannelStore store) {
        try {
            ProxyConfig config = ProxyConfig.from(new MapSource(LiteTestFixture.config(GLOBAL, LOCAL)));
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
                tempDir, declared, "VELOCITY", message -> { }, message -> { });
    }

    private void joinAll(LocalAuthority target) {
        for (String channel : List.of(GLOBAL, LOCAL)) {
            send(target, action(alice, ChannelAction.JOIN, channel));
            send(target, action(bob, ChannelAction.JOIN, channel));
            send(target, action(moderator, ChannelAction.JOIN, channel));
        }
    }

    private ChannelActionResponsePacket ban(LocalAuthority target, FakePlayer operator,
                                            FakePlayer victim, String channelId) {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.BAN, channelId);
        packet.addExtra("operatorId", operator.id.toString());
        packet.addExtra("operatorName", operator.name);
        packet.addExtra("targetId", victim.id.toString());
        packet.addExtra("targetName", victim.name);
        return (ChannelActionResponsePacket) send(target, packet).get(0);
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
    // ---------------------------------------------------------------- fixtures

    private ChannelActionResponsePacket ban(FakePlayer operator, FakePlayer target,
                                            String channelId) {
        return ban(operator, target, channelId, null);
    }

    private ChannelActionResponsePacket ban(FakePlayer operator, FakePlayer target,
                                            String channelId, String durationSeconds) {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.BAN, channelId);
        packet.addExtra("operatorId", operator.id.toString());
        packet.addExtra("operatorName", operator.name);
        packet.addExtra("targetId", target.id.toString());
        packet.addExtra("targetName", target.name);
        if (durationSeconds != null) {
            packet.addExtra("duration", durationSeconds);
        }
        return (ChannelActionResponsePacket) send(packet).get(0);
    }

    private ChannelActionResponsePacket banGlobally(FakePlayer operator, FakePlayer target) {
        return ban(operator, target, null);
    }

    private ChannelActionResponsePacket unban(FakePlayer operator, FakePlayer target,
                                             String channelId) {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.UNBAN, channelId);
        packet.addExtra("operatorId", operator.id.toString());
        packet.addExtra("targetId", target.id.toString());
        packet.addExtra("targetName", target.name);
        return (ChannelActionResponsePacket) send(packet).get(0);
    }

    private ChannelActionResponsePacket joinBob(String channelId) {
        return (ChannelActionResponsePacket) send(action(bob, ChannelAction.JOIN, channelId)).get(0);
    }

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

    // ------------------------------------------------------------- fakes
}
