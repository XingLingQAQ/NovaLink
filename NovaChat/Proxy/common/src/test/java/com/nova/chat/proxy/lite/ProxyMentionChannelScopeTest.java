package com.nova.chat.proxy.lite;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.config.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static com.nova.chat.proxy.lite.LiteTestFixture.FakePlatform;
import static com.nova.chat.proxy.lite.LiteTestFixture.FakePlayer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code @name} mention must only ping a proxy player who was actually served
 * the line.
 *
 * <p>Every proxy render path ({@code handleIncomingMessage}, {@code handleTitle},
 * {@code handleItemDisplay}) only renders to a player whose active channel
 * equals the packet channel, so a player focused elsewhere never saw the line.
 * Pinging them raised a sound/title for text they could not read. The backend
 * fixed the same asymmetry in {@code MentionResolver#isInChannel}; this pins the
 * proxy half of the rule, including its fail-open case.
 */
@DisplayName("ProxyChatCore mention channel scope")
class ProxyMentionChannelScopeTest {

    private static final String GLOBAL = "global";
    private static final String LOCAL = "local";

    private FakePlatform platform;
    private FakePlayer mentioned;
    private FakePlayer mentioner;
    private IgnoreListService ignoreListService;
    private ProxyConfig config;
    private ProxyChatCore chatCore;
    private Locale previousLocale;

    @BeforeEach
    void setUp() throws com.nova.chat.proxy.config.ConfigSource.ConfigException {
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        platform = new FakePlatform();
        mentioner = platform.add("Alice");
        mentioned = platform.add("Bob");

        config = ProxyConfig.from(new LiteTestFixture.MapSource(
                LiteTestFixture.config(GLOBAL, LOCAL)));
        ignoreListService = new IgnoreListService();
        KnownChannelRegistry registry = new KnownChannelRegistry();
        registry.replaceAll(Set.of(GLOBAL, LOCAL));
        chatCore = new ProxyChatCore(platform, config, ignoreListService,
                new PrivateMessageService(), registry, () -> null);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    /** Pushes one mention packet through the production handler. */
    private void mention(String channelId) {
        chatCore.handleMention(new MentionPacket(mentioner.id, mentioner.name, mentioned.id,
                channelId, "hi @" + mentioned.name, System.currentTimeMillis()));
    }

    @Test
    @DisplayName("a mention is not delivered when the player's active channel differs")
    void mentionNotDeliveredWhenFocusedElsewhere() {
        chatCore.getOrCreateState(mentioned).setActiveChannel(LOCAL);

        mention(GLOBAL);

        assertThat(mentioned.mentions)
                .as("the player never saw the line, so the ping is a phantom notification")
                .isEmpty();
    }

    @Test
    @DisplayName("a mention is delivered when the active channel matches")
    void mentionDeliveredWhenInChannel() {
        chatCore.getOrCreateState(mentioned).setActiveChannel(GLOBAL);

        mention(GLOBAL);

        assertThat(mentioned.mentions).hasSize(1);
        assertThat(mentioned.mentions.get(0)).contains(mentioner.name);
    }

    @Test
    @DisplayName("a mention is delivered when the player's state is unknown (fail open)")
    void mentionDeliveredWhenStateUnknown() {
        // A direct-connect player the proxy knows only from packets: no state,
        // so the proxy cannot tell where they are focused. Dropping the ping
        // would be a new silence bug, so the rule fails open.
        assertThat(chatCore.getState(mentioned.id)).isNull();

        mention(GLOBAL);

        assertThat(mentioned.mentions).hasSize(1);
    }

    @Test
    @DisplayName("a mention is delivered when the recorded active channel is blank (fail open)")
    void mentionDeliveredWhenActiveChannelBlank() {
        PlayerChannelState state = chatCore.getOrCreateState(mentioned);
        // Created state with no active channel: the backend's isInChannel
        // treats a blank active channel as in-channel for the same reason.
        state.rollbackJoin(state.getActiveChannel(), null);
        assertThat(state.getActiveChannel()).isNull();

        mention(GLOBAL);

        assertThat(mentioned.mentions).hasSize(1);
    }

    @Test
    @DisplayName("a mention with a blank channel is delivered regardless of the active channel")
    void mentionDeliveredWhenPacketChannelBlank() {
        chatCore.getOrCreateState(mentioned).setActiveChannel(LOCAL);

        mention("");

        assertThat(mentioned.mentions).hasSize(1);
    }

    @Test
    @DisplayName("a self-mention still notifies: self-suppression lives in the sender, not the renderer")
    void selfMentionDeliveryIsUnchanged() {
        // MentionNotifier.createMentionPackets filters the sender out on the
        // produce side. The receive side has never checked, and this fix does
        // not add such a check — pinning it keeps the scope of the change honest.
        chatCore.getOrCreateState(mentioner).setActiveChannel(GLOBAL);

        chatCore.handleMention(new MentionPacket(mentioner.id, mentioner.name, mentioner.id,
                GLOBAL, "hi me", System.currentTimeMillis()));

        assertThat(mentioner.mentions).hasSize(1);
    }

    @Test
    @DisplayName("an @all fan-out is unaffected by the name-mention channel filter")
    void allMentionFanOutUnaffected() {
        // @all arrives as one MentionPacket per fan-out recipient, each already
        // channel-scoped by the sender (LocalAuthority.activeMembers). The
        // name-mention filter must not add a second, narrower rule.
        chatCore.getOrCreateState(mentioned).setActiveChannel(GLOBAL);

        chatCore.handleMention(new MentionPacket(mentioner.id, mentioner.name, mentioned.id,
                GLOBAL, "hi @all", System.currentTimeMillis()));

        assertThat(mentioned.mentions).hasSize(1);
    }

    @Test
    @DisplayName("an offline mentioned player is skipped before the channel check")
    void offlinePlayerIsSkipped() {
        chatCore.getOrCreateState(mentioned).setActiveChannel(GLOBAL);
        platform.remove(mentioned);

        mention(GLOBAL);

        assertThat(mentioned.mentions).isEmpty();
    }

    @Test
    @DisplayName("an ignored mentioner is still skipped when the player is in channel")
    void ignoredMentionerIsSkipped() {
        chatCore.getOrCreateState(mentioned).setActiveChannel(GLOBAL);
        ignoreListService.ignore(mentioned.id, mentioned.name, mentioner.name);

        mention(GLOBAL);

        assertThat(mentioned.mentions).isEmpty();
    }
}
