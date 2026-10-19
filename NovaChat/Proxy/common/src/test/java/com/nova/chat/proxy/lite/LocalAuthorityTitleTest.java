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
@DisplayName("Lite authority titles")
class LocalAuthorityTitleTest {

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
        moderator.permissions.add("novachat.title");

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
    @DisplayName("a title reaches the players in that channel")
    void titleReachesChannelMembers() {
        joinAll(GLOBAL);

        send(title(moderator, GLOBAL, "Hello", "World"));

        assertThat(alice.titles).contains("Hello|World");
        assertThat(bob.titles).contains("Hello|World");
    }

    @Test
    @DisplayName("a title does not reach players in another channel")
    void titleIsScopedToItsChannel() {
        send(action(alice, ChannelAction.JOIN, GLOBAL));
        send(action(bob, ChannelAction.JOIN, LOCAL));

        send(title(moderator, GLOBAL, "OnlyGlobal", ""));

        assertThat(alice.titles).contains("OnlyGlobal|");
        assertThat(bob.titles).as("a local player must not see a global title").isEmpty();
    }

    @Test
    @DisplayName("the command answers with a success response")
    void titleAnswersSuccess() {
        joinAll(GLOBAL);

        List<Packet> out = send(title(moderator, GLOBAL, "Hi", ""));

        assertThat(out).anyMatch(AdminActionResponsePacket.class::isInstance);
        AdminActionResponsePacket response = out.stream()
                .filter(AdminActionResponsePacket.class::isInstance)
                .map(AdminActionResponsePacket.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(response.getMessage()).isEqualTo("Title sent");
    }

    @Test
    @DisplayName("tick overrides are applied")
    void tickOverridesApply() {
        joinAll(GLOBAL);
        AdminActionPacket packet = title(moderator, GLOBAL, "Timed", "");
        packet.addExtra("fadeIn", "5");
        packet.addExtra("stay", "40");
        packet.addExtra("fadeOut", "15");

        send(packet);

        assertThat(alice.titleTimings).contains("5/40/15");
    }

    @Test
    @DisplayName("an unparseable tick override is ignored, not treated as zero")
    void badTickOverrideIsIgnored() {
        joinAll(GLOBAL);
        AdminActionPacket packet = title(moderator, GLOBAL, "Timed", "");
        packet.addExtra("stay", "not-a-number");

        send(packet);

        assertThat(alice.titleTimings).as("the default stay is kept").contains("10/70/20");
    }

    @Test
    @DisplayName("a member without the title node is refused")
    void memberWithoutPermissionIsRefused() {
        joinAll(GLOBAL);

        List<Packet> out = send(title(alice, GLOBAL, "Nope", ""));

        assertThat(alice.titles).isEmpty();
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
        List<Packet> out = send(title(moderator, "nope", "Hi", ""));

        AdminActionResponsePacket response = out.stream()
                .filter(AdminActionResponsePacket.class::isInstance)
                .map(AdminActionResponsePacket.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("a missing title is refused")
    void missingTitleIsRefused() {
        AdminActionPacket packet = new AdminActionPacket();
        packet.setAction(AdminAction.STATUS);
        packet.setPlayerId(moderator.id);
        packet.setTarget(GLOBAL);
        packet.addExtra("type", "TITLE");

        List<Packet> out = send(packet);

        AdminActionResponsePacket response = out.stream()
                .filter(AdminActionResponsePacket.class::isInstance)
                .map(AdminActionResponsePacket.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(response.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST.getCode());
    }

    // ---------------------------------------------------------------- fixtures

    private AdminActionPacket title(FakePlayer operator, String channelId, String title,
                                    String subtitle) {
        AdminActionPacket packet = new AdminActionPacket();
        packet.setAction(AdminAction.STATUS);
        packet.setPlayerId(operator.id);
        packet.setTarget(channelId);
        packet.addExtra("type", "TITLE");
        packet.addExtra("operatorName", operator.name);
        packet.addExtra("title", title);
        packet.addExtra("subtitle", subtitle);
        return packet;
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
