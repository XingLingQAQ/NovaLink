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

import static com.nova.chat.proxy.lite.LiteTestFixture.FakePlatform;
import static com.nova.chat.proxy.lite.LiteTestFixture.FakePlayer;
import static com.nova.chat.proxy.lite.LiteTestFixture.MapSource;
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

    // ------------------------------------------------------------- fakes
}
