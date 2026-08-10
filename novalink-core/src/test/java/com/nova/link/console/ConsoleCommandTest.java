package com.nova.link.console;

import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.link.api.WebhookManager;
import com.nova.link.auth.ClientPermissionRegistry;
import com.nova.link.auth.AuthManager;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.i18n.I18n;
import com.nova.link.i18n.LocaleResolver;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.NettyServer;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.notification.NotificationStore;
import com.nova.link.spy.SpyManager;
import com.nova.link.websocket.WebSocketGateway;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the backend console command layer.
 *
 * <p>Builds a real {@link ConsoleCommandHandler} backed by real managers
 * (MemoryProvider DB) + a small embedded {@link BackendContext}, so each
 * command's output/behavior is exercised end-to-end without a live server.
 * The {@link ServerNetworkHandler} is mocked (with a captured connection) so
 * announce/title routing can be asserted.
 */
@DisplayName("ConsoleCommandHandler")
class ConsoleCommandTest {

    private ChannelManager channelManager;
    private PlayerStateManager playerStateManager;
    private PermissionManager permissionManager;
    private MuteManager muteManager;
    private SpyManager spyManager;
    private ServerNetworkHandler networkHandler;
    private MessageRouter messageRouter;
    private ConsoleCommandHandler handler;

    private UUID targetId;
    private ClientConnection capturedClient;

    @BeforeEach
    void setUp() throws Exception {
        // The console handler now resolves output via BackendI18n. The existing
        // assertions expect English text, so set en_US as the backend locale for
        // this test class. A separate test (consoleZhCNLocale) verifies zh_CN.
        I18n.setDefaultLocale(LocaleResolver.EN_US);

        DatabaseProvider db = new MemoryProvider();
        db.initialize();
        channelManager = new ChannelManager();
        playerStateManager = new PlayerStateManager(db);
        WebhookManager webhookManager = new WebhookManager();
        PrivateChannelManager privateChannelManager = new PrivateChannelManager(channelManager);
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        permissionManager = new PermissionManager();
        muteManager = new MuteManager(db, permissionManager, channelManager);
        BanManager banManager = new BanManager(db, permissionManager, channelManager);
        NotificationStore notificationStore = new NotificationStore(db);
        SensitiveWordFilter sensitiveWordFilter = new SensitiveWordFilter();

        // Seed a GLOBAL channel + a SERVER channel.
        channelManager.createChannel(ChannelConfig.builder()
                .id("staff")
                .displayName("Staff")
                .scope(ChannelScope.GLOBAL)
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("survival-chat")
                .displayName("Survival")
                .scope(ChannelScope.SERVER)
                .clientId("Survival")
                .build());

        // Mock network handler with one authenticated, captured connection so
        // announce (GLOBAL fan-out) + title (SERVER single-client) can be asserted.
        networkHandler = mock(ServerNetworkHandler.class);
        capturedClient = mock(ClientConnection.class);
        when(capturedClient.isAuthenticated()).thenReturn(true);
        when(capturedClient.isActive()).thenReturn(true);
        when(capturedClient.getClientId()).thenReturn("Survival");
        when(capturedClient.sendPacket(any(Packet.class))).thenReturn(CompletableFuture.completedFuture(null));
        when(networkHandler.getConnections()).thenReturn(Set.of(capturedClient));
        when(networkHandler.findByClientId("Survival")).thenReturn(capturedClient);
        doAnswer(inv -> {
            capturedClient.sendPacket(inv.getArgument(0));
            return null;
        }).when(networkHandler).broadcastAuthenticated(any(Packet.class));

        messageRouter = new MessageRouter(channelManager, networkHandler);
        messageRouter.setMuteManager(muteManager);
        messageRouter.setSensitiveWordFilter(sensitiveWordFilter);
        messageRouter.setPermissionChecker((c, p) -> true);

        spyManager = new SpyManager(permissionManager, channelManager, networkHandler);
        messageRouter.setSpyManager(spyManager);

        // Build a minimal context; netty server / ws gateway / config manager
        // are not exercised by these commands (reload uses a real ConfigManager
        // created lazily per-test where needed).
        BackendContext ctx = new BackendContext(
                new com.nova.link.config.ConfigManager(Path.of("novalink-test.yml")),
                new AuthManager(new IpBanManager(5, 60000)),
                permissionManager,
                new ClientPermissionRegistry(),
                db,
                channelManager,
                playerStateManager,
                webhookManager,
                privateChannelManager,
                invitationManager,
                muteManager,
                banManager,
                notificationStore,
                sensitiveWordFilter,
                networkHandler,
                messageRouter,
                spyManager,
                mock(NettyServer.class),
                mock(WebSocketGateway.class)
        );
        handler = new ConsoleCommandHandler(ctx);

        // Seed an online target player (cross-server name resolution target).
        targetId = UUID.randomUUID();
        PlayerState state = playerStateManager.getOrCreateState(targetId, "Steve");
        state.setClientId("Survival");
        state.setActiveChannel("survival-chat");
        channelManager.addMember("survival-chat", targetId);
    }

    @AfterEach
    void tearDown() {
        // Restore the backend default locale so tests don't leak into others.
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);
    }

    // ====================== locale-aware output ======================

    @Test
    @DisplayName("zh_CN locale: status/mute/help render Chinese text")
    void consoleZhCNLocale() {
        I18n.setDefaultLocale(LocaleResolver.ROOT_LOCALE);

        String status = handler.dispatch("status");
        assertThat(status).contains("NovaLink 状态");
        assertThat(status).contains("在线玩家");

        String muteOut = handler.dispatch("mute Steve survival-chat 10m 测试");
        assertThat(muteOut).contains("已禁言");
        assertThat(muteManager.isMuted(targetId, "survival-chat")).isTrue();

        String help = handler.dispatch("help");
        assertThat(help).contains("后端概览");

        // Restore en_US for the rest of the test class.
        I18n.setDefaultLocale(LocaleResolver.EN_US);
    }

    // ====================== mute / unmute ======================

    @Test
    @DisplayName("mute <name> <channel> 10m mutes target")
    void muteByName() {
        String out = handler.dispatch("mute Steve survival-chat 10m spamming");
        assertThat(out).contains("Muted");
        assertThat(muteManager.isMuted(targetId, "survival-chat")).isTrue();
    }

    @Test
    @DisplayName("mute accepts UUID + perm duration")
    void muteByUuidPerm() {
        String out = handler.dispatch("mute " + targetId + " staff perm");
        assertThat(out).contains("Muted");
        assertThat(muteManager.isMuted(targetId, "staff")).isTrue();
    }

    @Test
    @DisplayName("unmute <name> <channel> unmutes target")
    void unmuteByName() {
        handler.dispatch("mute Steve survival-chat 10m x");
        assertThat(muteManager.isMuted(targetId, "survival-chat")).isTrue();

        String out = handler.dispatch("unmute Steve survival-chat");
        assertThat(out).contains("Unmuted");
        assertThat(muteManager.isMuted(targetId, "survival-chat")).isFalse();
    }

    @Test
    @DisplayName("mute fails for unknown channel")
    void muteUnknownChannel() {
        String out = handler.dispatch("mute Steve nope 10m x");
        assertThat(out).contains("Channel not found");
    }

    @Test
    @DisplayName("mute rejects bad duration")
    void muteBadDuration() {
        String out = handler.dispatch("mute Steve staff 10x x");
        assertThat(out).contains("Invalid duration");
    }

    // ====================== mutes ======================

    @Test
    @DisplayName("mutes lists active mutes across players")
    void mutesListAll() {
        handler.dispatch("mute Steve staff 10m test");
        String out = handler.dispatch("mutes");
        assertThat(out).contains("Active mutes");
        assertThat(out).contains("staff");
        assertThat(out).contains("Total: 1");
    }

    @Test
    @DisplayName("mutes <name> lists a specific player's mutes")
    void mutesForPlayer() {
        handler.dispatch("mute Steve staff 1h x");
        String out = handler.dispatch("mutes Steve");
        assertThat(out).contains("Active mutes for Steve");
        assertThat(out).contains("staff");
    }

    // ====================== kick ======================

    @Test
    @DisplayName("kick <name> <channel> removes member + updates state")
    void kickByName() {
        assertThat(channelManager.getChannelMembers("survival-chat")).contains(targetId);

        String out = handler.dispatch("kick Steve survival-chat");
        assertThat(out).contains("Kicked");
        assertThat(channelManager.getChannelMembers("survival-chat")).doesNotContain(targetId);
        assertThat(playerStateManager.getPlayerState(targetId).getActiveChannel()).isNull();
    }

    @Test
    @DisplayName("kick of non-member reports not a member")
    void kickNonMember() {
        String out = handler.dispatch("kick Steve staff");
        assertThat(out).contains("not a member");
    }

    // ====================== announce ======================

    @Test
    @DisplayName("announce <channel> <msg> routes announcement message to clients")
    void announceRoutes() {
        final AtomicReference<Packet> sent = new AtomicReference<>();
        when(capturedClient.sendPacket(any(Packet.class))).thenAnswer(inv -> {
            sent.set(inv.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });

        String out = handler.dispatch("announce staff hello world");
        assertThat(out).contains("Announcement sent");
        assertThat(sent.get()).isInstanceOf(ChatMessagePacket.class);
        ChatMessagePacket pkt = (ChatMessagePacket) sent.get();
        // The announce prefix is locale-dependent ([Announcement] / 【公告】);
        // just assert the content carries the user's message and is prefixed.
        assertThat(pkt.getContent()).contains("hello world");
        assertThat(pkt.getChannelId()).isEqualTo("staff");
        // Under en_US (this test's locale), the prefix is "[Announcement]".
        assertThat(pkt.getContent()).startsWith("[Announcement]");
    }

    @Test
    @DisplayName("announce fails for unknown channel")
    void announceUnknownChannel() {
        String out = handler.dispatch("announce nope hi");
        assertThat(out).contains("Channel not found");
    }

    // ====================== title ======================

    @Test
    @DisplayName("title <channel> <title> [subtitle] sends TitlePacket (SERVER -> single client)")
    void titleServerChannel() {
        final AtomicReference<Packet> sent = new AtomicReference<>();
        when(capturedClient.sendPacket(any(Packet.class))).thenAnswer(inv -> {
            sent.set(inv.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });

        String out = handler.dispatch("title survival-chat Welcome NovaLink");
        assertThat(out).contains("Title sent");
        assertThat(sent.get()).isInstanceOf(TitlePacket.class);
        TitlePacket tp = (TitlePacket) sent.get();
        assertThat(tp.getTitle()).isEqualTo("Welcome");
        assertThat(tp.getSubtitle()).isEqualTo("NovaLink");
        assertThat(tp.getChannelId()).isEqualTo("survival-chat");
    }

    @Test
    @DisplayName("title <global channel> broadcasts to all authenticated")
    void titleGlobalChannel() {
        final AtomicReference<Packet> sent = new AtomicReference<>();
        when(capturedClient.sendPacket(any(Packet.class))).thenAnswer(inv -> {
            sent.set(inv.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });

        String out = handler.dispatch("title staff Announcement");
        assertThat(out).contains("Title sent to global channel");
        assertThat(sent.get()).isInstanceOf(TitlePacket.class);
        assertThat(((TitlePacket) sent.get()).getTitle()).isEqualTo("Announcement");
    }

    // ====================== status / players / channels / clients ======================

    @Test
    @DisplayName("status reports counts")
    void statusReportsCounts() {
        String out = handler.dispatch("status");
        assertThat(out).contains("NovaLink status");
        assertThat(out).contains("Online players : 1");
        assertThat(out).contains("Channels       : 2");
    }

    @Test
    @DisplayName("players lists seeded player")
    void playersLists() {
        String out = handler.dispatch("players");
        assertThat(out).contains("Steve");
        assertThat(out).contains(targetId.toString());
        assertThat(out).contains("survival-chat");
    }

    @Test
    @DisplayName("channels lists seeded channels")
    void channelsLists() {
        String out = handler.dispatch("channels");
        assertThat(out).contains("staff");
        assertThat(out).contains("survival-chat");
        assertThat(out).contains("GLOBAL");
        assertThat(out).contains("SERVER");
    }

    @Test
    @DisplayName("channel <id> shows detail + members")
    void channelDetail() {
        String out = handler.dispatch("channel survival-chat");
        assertThat(out).contains("Channel: survival-chat");
        assertThat(out).contains("member UUIDs:");
        assertThat(out).contains(targetId.toString());
    }

    @Test
    @DisplayName("clients lists authenticated connection")
    void clientsLists() {
        String out = handler.dispatch("clients");
        assertThat(out).contains("Survival");
        assertThat(out).contains("Authenticated game servers");
    }

    // ====================== create / delete ======================

    @Test
    @DisplayName("create <name> global creates a global channel")
    void createGlobal() {
        String out = handler.dispatch("create newglobal");
        assertThat(out).contains("Created global channel newglobal");
        assertThat(channelManager.channelExists("newglobal")).isTrue();
        assertThat(channelManager.getChannel("newglobal").getScope()).isEqualTo(ChannelScope.GLOBAL);
    }

    @Test
    @DisplayName("create <name> <password> private creates a private channel")
    void createPrivate() {
        String out = handler.dispatch("create party secret123 private");
        assertThat(out).contains("Created private channel");
        // The id is generated (NC-XXXX); find it by display name.
        boolean found = channelManager.getAllChannels().stream()
                .anyMatch(c -> c.getScope() == ChannelScope.PRIVATE
                        && "party".equals(c.getDisplayName())
                        && "secret123".equals(c.getPassword()));
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("delete <id> removes the channel")
    void deleteChannel() {
        handler.dispatch("create todelete");
        assertThat(channelManager.channelExists("todelete")).isTrue();

        String out = handler.dispatch("delete todelete");
        assertThat(out).contains("Deleted channel todelete");
        assertThat(channelManager.channelExists("todelete")).isFalse();
    }

    @Test
    @DisplayName("delete removes members + updates player state")
    void deleteRemovesMembers() {
        handler.dispatch("create todelete");
        channelManager.addMember("todelete", targetId);
        playerStateManager.getOrCreateState(targetId, "Steve").addJoinedChannel("todelete");

        handler.dispatch("delete todelete");
        assertThat(channelManager.channelExists("todelete")).isFalse();
        assertThat(playerStateManager.getPlayerState(targetId).hasJoinedChannel("todelete")).isFalse();
    }

    @Test
    @DisplayName("delete unknown channel reports not found")
    void deleteUnknown() {
        String out = handler.dispatch("delete nope");
        assertThat(out).contains("Channel not found");
    }

    // ====================== reload ======================

    @Test
    @DisplayName("reload increments config reload count")
    void reloadIncrementsCount() {
        // The test ConfigManager points at novalink-test.yml which doesn't exist;
        // reload() creates a default file + reloads it, so reloadCount increments.
        int before = handler.context().getConfigManager().getReloadCount();
        String out = handler.dispatch("reload");
        assertThat(out).contains("Configuration reloaded");
        assertThat(handler.context().getConfigManager().getReloadCount()).isGreaterThan(before);
    }

    // ====================== spy ======================

    @Test
    @DisplayName("spy start <channel> starts spy session for console sentinel")
    void spyStart() {
        String out = handler.dispatch("spy start staff");
        assertThat(out).contains("Spy started");
        assertThat(spyManager.isSpying(ConsoleSentinel.CONSOLE_SENTINEL, "staff")).isTrue();
    }

    @Test
    @DisplayName("spy off stops console sentinel's spy sessions")
    void spyOff() {
        handler.dispatch("spy start staff");
        assertThat(spyManager.isSpying(ConsoleSentinel.CONSOLE_SENTINEL, "staff")).isTrue();

        String out = handler.dispatch("spy off");
        assertThat(out).contains("Spy stopped");
        assertThat(spyManager.isSpying(ConsoleSentinel.CONSOLE_SENTINEL, "staff")).isFalse();
    }

    @Test
    @DisplayName("spies lists monitored channels")
    void spiesList() {
        handler.dispatch("spy start staff");
        String out = handler.dispatch("spies");
        assertThat(out).contains("Spy sessions: 1");
        assertThat(out).contains("staff");
    }

    // ====================== help ======================

    @Test
    @DisplayName("help lists all commands")
    void helpLists() {
        String out = handler.dispatch("help");
        assertThat(out).contains("mute");
        assertThat(out).contains("kick");
        assertThat(out).contains("spy");
        assertThat(out).contains("stop");
    }

    @Test
    @DisplayName("help <cmd> shows detailed usage")
    void helpDetailed() {
        String out = handler.dispatch("help mute");
        assertThat(out).contains("mute <player|name> <channel> <dur>");
        assertThat(out).contains("30s");
        assertThat(out).contains("perm");
    }

    // ====================== stop / unknown ======================

    @Test
    @DisplayName("stop returns the STOP token")
    void stopReturnsToken() {
        String out = handler.dispatch("stop");
        assertThat(out).isEqualTo(ConsoleCommandHandler.STOP_TOKEN);
    }

    @Test
    @DisplayName("shutdown alias also returns STOP token")
    void shutdownAlias() {
        String out = handler.dispatch("shutdown");
        assertThat(out).isEqualTo(ConsoleCommandHandler.STOP_TOKEN);
    }

    @Test
    @DisplayName("unknown command reports error")
    void unknownCommand() {
        String out = handler.dispatch("frobnicate foo");
        assertThat(out).contains("Unknown command");
        assertThat(out).contains("frobnicate");
    }

    @Test
    @DisplayName("blank input is a no-op")
    void blankInput() {
        assertThat(handler.dispatch("")).isEmpty();
        assertThat(handler.dispatch("   ")).isEmpty();
        assertThat(handler.dispatch(null)).isEmpty();
    }

    // ====================== tab completion ======================

    @Test
    @DisplayName("completer: first token -> command names")
    void completerFirstTokenCommands() {
        List<Candidate> candidates = complete("m");
        Set<String> values = candidateValues(candidates);
        assertThat(values).contains("mute", "mutes");
    }

    @Test
    @DisplayName("completer: help <TAB> -> command names")
    void completerHelpArg() {
        List<Candidate> candidates = complete("help ");
        Set<String> values = candidateValues(candidates);
        assertThat(values).contains("mute", "kick", "spy");
    }

    @Test
    @DisplayName("completer: mute <player> <TAB> -> channel ids")
    void completerMuteChannelAfterPlayer() {
        List<Candidate> candidates = complete("mute Steve ");
        Set<String> values = candidateValues(candidates);
        assertThat(values).contains("staff", "survival-chat");
    }

    @Test
    @DisplayName("completer: mute <TAB> at player position -> online player names + UUIDs")
    void completerMutePlayerPosition() {
        List<Candidate> candidates = complete("mute ");
        Set<String> values = candidateValues(candidates);
        assertThat(values).contains("Steve");
        assertThat(values).contains(targetId.toString());
    }

    @Test
    @DisplayName("completer: channel <TAB> -> channel ids")
    void completerChannelIds() {
        List<Candidate> candidates = complete("channel ");
        Set<String> values = candidateValues(candidates);
        assertThat(values).contains("staff", "survival-chat");
    }

    @Test
    @DisplayName("completer: spy start <channel> <TAB> -> channel ids")
    void completerSpyStartChannel() {
        List<Candidate> candidates = complete("spy start ");
        Set<String> values = candidateValues(candidates);
        assertThat(values).contains("staff", "survival-chat");
    }

    @Test
    @DisplayName("completer: spy off <TAB> -> admin UUIDs with sessions")
    void completerSpyOffAdmins() {
        handler.dispatch("spy start staff");
        List<Candidate> candidates = complete("spy off ");
        Set<String> values = candidateValues(candidates);
        assertThat(values).contains(ConsoleSentinel.CONSOLE_SENTINEL.toString());
    }

    @Test
    @DisplayName("completer: create <name> <password> <TAB> -> scope values")
    void completerCreateScope() {
        List<Candidate> candidates = complete("create name pass ");
        Set<String> values = candidateValues(candidates);
        assertThat(values).contains("global", "private");
    }

    // ====================== helpers ======================

    private List<Candidate> complete(String input) {
        BackendConsole.BackendCompleter completer = new BackendConsole.BackendCompleter(handler.context());
        List<Candidate> candidates = new ArrayList<>();
        ParsedLine parsed = new SimpleParsedLine(input);
        completer.complete(mock(LineReader.class), parsed, candidates);
        return candidates;
    }

    private static Set<String> candidateValues(List<Candidate> candidates) {
        Set<String> values = new HashSet<>();
        for (Candidate c : candidates) {
            values.add(c.value());
        }
        return values;
    }

    /**
     * Minimal ParsedLine implementation for completer tests: splits the input
     * into whitespace-delimited words and reports the cursor at the end.
     */
    private static final class SimpleParsedLine implements ParsedLine {
        private final String line;
        private final List<String> words;

        SimpleParsedLine(String line) {
            this.line = line;
            this.words = new ArrayList<>();
            for (String w : line.trim().split("\\s+")) {
                if (!w.isEmpty()) {
                    words.add(w);
                }
            }
            // A trailing space means an empty word is being started (next arg).
            if (line.endsWith(" ") && !line.trim().isEmpty()) {
                words.add("");
            }
        }

        @Override public String word() { return words.isEmpty() ? "" : words.get(words.size() - 1); }
        @Override public int wordCursor() { return word().length(); }
        @Override public int wordIndex() { return Math.max(0, words.size() - 1); }
        @Override public List<String> words() { return words; }
        @Override public String line() { return line; }
        @Override public int cursor() { return line.length(); }
    }
}
