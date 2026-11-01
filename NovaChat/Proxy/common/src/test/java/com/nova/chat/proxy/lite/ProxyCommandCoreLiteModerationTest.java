package com.nova.chat.proxy.lite;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.command.ProxyCommandCore;
import com.nova.chat.proxy.command.ProxyCommandSender;
import com.nova.chat.proxy.config.ProxyConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import static com.nova.chat.proxy.lite.LiteTestFixture.FakePlatform;
import static com.nova.chat.proxy.lite.LiteTestFixture.FakePlayer;
import static com.nova.chat.proxy.lite.LiteTestFixture.MapSource;
import static org.assertj.core.api.Assertions.assertThat;

class ProxyCommandCoreLiteModerationTest {

    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final FakePlatform platform = new FakePlatform();
    private final FakePlayer moderator = platform.add("Moderator");
    private final FakePlayer alice = platform.add("Alice");
    private final FakePlayer bob = platform.add("Bob");
    private final RecordingSender console = new RecordingSender(null);
    private ProxyChatCore chatCore;
    private ProxyCommandCore commands;
    private LocalAuthorityClient client;

    @BeforeEach
    void setUp() throws Exception {
        moderator.permissions.addAll(Set.of("novachat.mute", "novachat.kick", "novachat.ban"));
        ProxyConfig config = ProxyConfig.from(new MapSource(LiteTestFixture.config("global", "local")));
        KnownChannelRegistry channels = new KnownChannelRegistry();
        channels.replaceAll(Set.of("global", "local"));
        IgnoreListService ignores = new IgnoreListService();
        PrivateMessageService privateMessages = new PrivateMessageService();
        client = new LocalAuthorityClient((task, delay) -> tasks.add(task), 0L, message -> { });
        chatCore = new ProxyChatCore(platform, config, ignores, privateMessages, channels, () -> client);
        client.bind(new LocalAuthority(platform, chatCore, ignores));
        chatCore.rebindIncomingHandlers(client);
        commands = new ProxyCommandCore(platform, chatCore, new ChannelCommandService(packet -> {
            client.sendPacket(packet);
            return true;
        }), ignores, privateMessages, channels, () -> config);
        for (FakePlayer player : List.of(moderator, alice, bob)) {
            execute(new RecordingSender(player), "join", "local");
            execute(new RecordingSender(player), "join", "global");
        }
        alice.messages.clear();
        bob.messages.clear();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void consoleMuteAndUnmuteChangeDeliveryWithoutBackend() {
        say(alice, "global", "before-mute");
        assertThat(bob.messages).anyMatch(line -> line.contains("before-mute"));

        commands.execute(console, new String[]{"mute", "Alice", "10m", "global"});
        assertThat(console.messages).isEmpty();
        drain();
        assertThat(console.messages).hasSize(1);
        assertThat(console.messages).noneMatch(line -> line.contains("NC-"));
        say(alice, "global", "blocked-muted");
        assertThat(alice.messages).anyMatch(line -> line.contains("NC-436"));
        assertThat(bob.messages).noneMatch(line -> line.contains("blocked-muted"));
        execute(new RecordingSender(alice), "join", "local");
        say(alice, "local", "other-channel-still-works");
        assertThat(alice.messages).anyMatch(line -> line.contains("other-channel-still-works"));

        execute(console, "unmute", "Alice", "global");
        execute(new RecordingSender(alice), "join", "global");
        say(alice, "global", "after-unmute");
        assertThat(bob.messages).anyMatch(line -> line.contains("after-unmute"));
    }

    @Test
    void ordinaryPlayersCannotExecuteAnyModerationAction() {
        RecordingSender ordinary = new RecordingSender(bob);
        for (String action : List.of("mute", "unmute", "kick", "ban", "unban")) {
            ordinary.messages.clear();
            execute(ordinary, action, "Alice", "global");
            assertThat(ordinary.messages).anyMatch(line -> line.contains("NC-403"));
            say(alice, "global", "still-allowed-" + action);
            assertThat(bob.messages).anyMatch(line -> line.contains("still-allowed-" + action));
        }
        assertThat(chatCore.getState(alice.id).getJoinedChannels()).contains("global", "local");
    }

    @Test
    void rejectedUnmuteAndUnbanDoNotClearExistingRestrictions() {
        RecordingSender ordinary = new RecordingSender(bob);
        execute(console, "mute", "Alice", "10m", "global");
        execute(ordinary, "unmute", "Alice", "global");
        say(alice, "global", "still-muted");
        assertThat(bob.messages).noneMatch(line -> line.contains("still-muted"));
        execute(console, "unmute", "Alice", "global");
        execute(console, "ban", "Alice", "10m", "global");
        execute(ordinary, "unban", "Alice", "global");
        execute(new RecordingSender(alice), "join", "global");
        assertThat(chatCore.getState(alice.id).getJoinedChannels()).doesNotContain("global");
    }

    @Test
    void authorityRechecksThePlayerPermission() {
        RecordingSender stalePermission = new RecordingSender(bob) {
            @Override
            public boolean hasPermission(String permission) {
                return true;
            }
        };
        execute(stalePermission, "mute", "Alice", "10m", "global");
        assertThat(stalePermission.messages).anyMatch(line -> line.contains("NC-403"));
        say(alice, "global", "authority-refused");
        assertThat(bob.messages).anyMatch(line -> line.contains("authority-refused"));
    }

    @Test
    void moderatorUsesOnlyTheActionPermissionAndActiveChannel() {
        RecordingSender sender = new RecordingSender(moderator);
        moderator.permissions.remove("novachat.ban");
        execute(sender, "mute", "Alice", "1h");
        say(alice, "global", "active-channel-muted");
        assertThat(bob.messages).noneMatch(line -> line.contains("active-channel-muted"));
        execute(sender, "ban", "Alice", "global");
        assertThat(sender.messages).anyMatch(line -> line.contains("NC-403"));
        assertThat(chatCore.getState(alice.id).getJoinedChannels()).contains("global");
    }

    @Test
    void kickRemovesOnlyItsChannelAndAllowsRejoinWithoutDisconnect() {
        execute(console, "kick", "Alice", "global");
        assertThat(platform.getPlayer(alice.id)).isSameAs(alice);
        assertThat(chatCore.getState(alice.id).getJoinedChannels()).contains("local").doesNotContain("global");
        say(alice, "global", "kicked-line");
        assertThat(bob.messages).noneMatch(line -> line.contains("kicked-line"));
        execute(new RecordingSender(alice), "join", "global");
        say(alice, "global", "rejoined-after-kick");
        assertThat(bob.messages).anyMatch(line -> line.contains("rejoined-after-kick"));
    }

    @Test
    void kickAndBanEvictImplicitDefaultMembership() {
        FakePlayer fresh = platform.add("Fresh");
        chatCore.getOrCreateState(fresh);
        say(fresh, "global", "implicit-default-works");
        assertThat(bob.messages).anyMatch(line -> line.contains("implicit-default-works"));
        execute(console, "kick", "Fresh", "global");
        assertThat(chatCore.getState(fresh.id).getActiveChannel()).isNull();
        say(fresh, "global", "implicit-kicked");
        assertThat(bob.messages).noneMatch(line -> line.contains("implicit-kicked"));

        FakePlayer banned = platform.add("FreshBanned");
        chatCore.getOrCreateState(banned);
        execute(console, "ban", "FreshBanned", "global");
        assertThat(chatCore.getState(banned.id).getActiveChannel()).isNull();
        say(banned, "global", "implicit-banned");
        assertThat(bob.messages).noneMatch(line -> line.contains("implicit-banned"));
        say(bob, "global", "banned-target-must-not-receive");
        assertThat(banned.messages).noneMatch(line -> line.contains("banned-target-must-not-receive"));
    }

    @Test
    void scopedBanBlocksOnlyItsChannelAndUnbanRestoresJoin() {
        execute(console, "ban", "Alice", "10m", "global");
        assertThat(chatCore.getState(alice.id).getJoinedChannels()).contains("local").doesNotContain("global");
        say(alice, "global", "banned-direct-channel-message");
        assertThat(bob.messages).noneMatch(line -> line.contains("banned-direct-channel-message"));
        assertThat(alice.messages).anyMatch(line -> line.contains("NC-403"));
        execute(new RecordingSender(alice), "join", "global");
        assertThat(chatCore.getState(alice.id).getJoinedChannels()).doesNotContain("global");
        say(alice, "local", "other-channel-unbanned");
        assertThat(alice.messages).anyMatch(line -> line.contains("other-channel-unbanned"));
        execute(console, "unban", "Alice", "global");
        execute(new RecordingSender(alice), "join", "global");
        say(alice, "global", "rejoined-after-unban");
        assertThat(bob.messages).anyMatch(line -> line.contains("rejoined-after-unban"));
    }

    @Test
    void missingBanScopeIsGlobalAndOfflineUnbanAcceptsUuid() {
        execute(console, "ban", "Alice");
        assertThat(chatCore.getState(alice.id).getJoinedChannels()).isEmpty();
        execute(new RecordingSender(alice), "join", "global");
        assertThat(chatCore.getState(alice.id).getJoinedChannels()).isEmpty();
        assertThat(chatCore.getState(alice.id).getActiveChannel()).isNull();
        say(bob, "global", "globally-banned-target-must-not-receive");
        assertThat(alice.messages).noneMatch(line -> line.contains("globally-banned-target-must-not-receive"));
        platform.remove(alice);
        execute(console, "unban", alice.id.toString());
        assertThat(console.messages).hasSize(2).noneMatch(line -> line.contains("NC-"));
    }

    @Test
    void invalidDurationsAndUnknownChannelsDoNotApplyMute() {
        for (String duration : List.of("-1", "10x", "999999999999999999999h", "9223372036854775807d")) {
            console.messages.clear();
            execute(console, "mute", "Alice", duration, "global");
            assertThat(console.messages).anyMatch(line -> line.contains("NC-439"));
        }
        execute(console, "mute", "Alice", "10m", "unknown");
        assertThat(console.messages).anyMatch(line -> line.contains("NC-404"));
        say(alice, "global", "invalid-input-did-nothing");
        assertThat(bob.messages).anyMatch(line -> line.contains("invalid-input-did-nothing"));
    }

    @Test
    void consoleNeedsExplicitScopeForMuteUnmuteAndKick() {
        for (String action : List.of("mute", "unmute", "kick")) {
            console.messages.clear();
            if (action.equals("mute")) {
                execute(console, action, "Alice", "10m");
            } else {
                execute(console, action, "Alice");
            }
            assertThat(console.messages).anyMatch(line -> line.contains("NC-400"));
        }
    }

    @Test
    void selfActionsAndMissingTargetsReturnActualAuthorityErrors() {
        RecordingSender sender = new RecordingSender(moderator);
        execute(sender, "kick", "Moderator", "global");
        assertThat(sender.messages).anyMatch(line -> line.contains(ErrorCode.SELF_ACTION.getCode()));
        execute(console, "mute", "NotOnline", "10m", "global");
        assertThat(console.messages).anyMatch(line -> line.contains(ErrorCode.TARGET_OFFLINE.getCode()));
    }

    @Test
    void closedLiteClientReturnsUnavailableWithoutSuccess() {
        client.close();
        execute(console, "mute", "Alice", "10m", "global");
        assertThat(console.messages).hasSize(1).allMatch(line -> line.contains("NC-503"));
    }

    @Test
    void moderationCompletionsRespectActionPermissions() {
        RecordingSender ordinary = new RecordingSender(alice);
        assertThat(commands.suggest(ordinary, new String[]{"mu"})).isEmpty();
        assertThat(commands.suggest(ordinary, new String[]{"mute", ""})).isEmpty();
        RecordingSender admin = new RecordingSender(moderator);
        assertThat(commands.suggest(admin, new String[]{"un"})).contains("unmute", "unban");
        assertThat(commands.suggest(admin, new String[]{"mute", "Al"})).containsExactly("Alice");
        assertThat(commands.suggest(admin, new String[]{"mute", "Alice", "1"})).contains("10m", "1h", "1d");
        assertThat(commands.suggest(admin, new String[]{"mute", "Alice", "1h", "lo"})).containsExactly("local");
    }

    private void execute(ProxyCommandSender sender, String... args) {
        commands.execute(sender, args);
        drain();
    }

    private void say(FakePlayer player, String channel, String content) {
        execute(new RecordingSender(player), channel, content);
    }

    private void drain() {
        while (!tasks.isEmpty()) {
            tasks.remove().run();
        }
    }

    private static class RecordingSender implements ProxyCommandSender {
        private final FakePlayer player;
        final List<String> messages = new ArrayList<>();

        RecordingSender(FakePlayer player) {
            this.player = player;
        }

        @Override
        public UUID getPlayerId() {
            return player != null ? player.id : null;
        }

        @Override
        public String getName() {
            return player != null ? player.name : "CONSOLE";
        }

        @Override
        public boolean hasPermission(String permission) {
            return player == null || player.hasPermission(permission);
        }

        @Override
        public void sendMessage(String text) {
            messages.add(text);
        }
    }
}
