package com.nova.chat.bukkit.network;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.chat.ChatInterceptor;
import com.nova.chat.bukkit.chat.MessageFormatter;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.channel.RenderCoordinationProtocol;
import com.nova.chat.channel.ProxyChannelProtocol;
import com.nova.chat.channel.RenderPacketCodec;
import com.nova.chat.channel.RenderCoordinationProtocol.Frame;
import com.nova.chat.channel.RenderCoordinationProtocol.Kind;
import com.nova.chat.bukkit.channel.ProxyChannelClient;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A downstream plugin-message packet must not render to players the proxy has
 * already served.
 *
 * <p>Relay fan-out sends every backend-originated packet down the
 * {@code novachat:main} channel to every sub-server, carrying the proxy's own
 * roster in the exclusion list. The proxy already rendered the packet to those
 * players, so the sub-server must skip them or the line appears twice. Chat had
 * this filter from the start; the other renderable types are covered here
 * because they were routed downstream at the same time.
 */
@DisplayName("Bukkit proxy fan-out exclusion (title / mention / private message)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NetworkClientChannelExclusionTest {

    private static final UUID PROXIED_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID DIRECT_ID = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb");
    private static final UUID SENDER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private NovaChatBukkit plugin;
    @Mock private NovaChatConfig config;
    @Mock private Server server;
    @Mock private BukkitScheduler scheduler;
    @Mock private PluginManager pluginManager;
    @Mock private org.bukkit.World world;
    @Mock private MessageFormatter formatter;
    @Mock private Player proxied;
    @Mock private Player direct;
    @Mock private IgnoreListService ignoreListService;

    private NetworkClient client;
    private ChatInterceptor chatInterceptor;
    private CoreNetworkClient core;
    private final Deque<Runnable> mainTasks = new ArrayDeque<>();
    private Locale previousLocale;
    private static final String RENDER_SECRET = "render-regression-test-key-0123456789";
    private ProxyChatCore proxyCore;
    private ProxyPlatform proxyPlatform;
    private ProxyChannelClient bridge;
    private Object replySource;
    private final Deque<Runnable> proxyToBukkit = new ArrayDeque<>();
    private final Deque<Runnable> bukkitToProxy = new ArrayDeque<>();
    private final List<Runnable> retryTasks = new ArrayList<>();
    private final List<Runnable> offerRetries = new ArrayList<>();
    private final List<byte[]> controls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        mainTasks.clear();
        proxyToBukkit.clear();
        bukkitToProxy.clear();
        retryTasks.clear();
        offerRetries.clear();
        controls.clear();
        replySource = null;
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getRenderCoordinationServer()).thenReturn(new com.nova.chat.bukkit.channel.RenderCoordinationServer(plugin));
        when(server.getVersion()).thenReturn("test-server");
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.getChannelFormat(anyString())).thenReturn("[{channel}] {player}: {message}");
        when(config.toClientConnectionConfig())
                .thenReturn(ClientConnectionConfig.builder().build());
        chatInterceptor = spy(new ChatInterceptor(plugin));
        when(plugin.getChatInterceptor()).thenReturn(chatInterceptor);
        when(plugin.getIgnoreListService()).thenReturn(ignoreListService);
        when(plugin.getPrivateMessageService()).thenReturn(new PrivateMessageService());
        when(chatInterceptor.getMessageFormatter()).thenReturn(formatter);
        when(formatter.translateColorCodes(anyString()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).replace('&', '§'));
        doReturn(List.of(proxied, direct)).when(server).getOnlinePlayers();

        when(proxied.getUniqueId()).thenReturn(PROXIED_ID);
        when(direct.getUniqueId()).thenReturn(DIRECT_ID);
        when(proxied.getWorld()).thenReturn(world);
        when(direct.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        chatInterceptor.getOrCreateState(proxied);
        chatInterceptor.getOrCreateState(direct);

        // Neither player is reachable by the sub-server's own lookup when it is
        // excluded, so the DM handler's "is local online" test is the filter.
        when(server.getPlayer(PROXIED_ID)).thenReturn(proxied);
        when(server.getPlayer(DIRECT_ID)).thenReturn(direct);

        client = new NetworkClient(plugin, config, new KnownChannelRegistry());
        when(plugin.getNetworkClient()).thenReturn(client);
        chatInterceptor.rebindIncomingHandlers();
        Field coreField = AbstractPlatformNetworkClient.class.getDeclaredField("core");
        coreField.setAccessible(true);
        core = (CoreNetworkClient) coreField.get(client);
        doAnswer(invocation -> {
            mainTasks.add(invocation.getArgument(1));
            return null;
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> { offerRetries.add(invocation.getArgument(1)); return null; })
                .when(scheduler).runTaskLater(eq(plugin), any(Runnable.class), org.mockito.ArgumentMatchers.anyLong());
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    @Test
    @DisplayName("a title skips the player the proxy already showed it to")
    void titleSkipsProxiedPlayer() {
        client.dispatchChannelPacket(
                new TitlePacket("global", "Hello", "World", SENDER_ID), Set.of(PROXIED_ID));
        runMainThreadTask();

        verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(direct).sendTitle("Hello", "World", 10, 70, 20);
    }

    @Test
    @DisplayName("a mention skips the proxied player and notifies the direct one")
    void mentionSkipsProxiedPlayer() {
        client.dispatchChannelPacket(new MentionPacket(SENDER_ID, "Alice", PROXIED_ID,
                "global", "hi", System.currentTimeMillis()), Set.of(PROXIED_ID));
        runMainThreadTask();

        verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a mention for a direct player still notifies")
    void mentionReachesDirectPlayer() {
        client.dispatchChannelPacket(new MentionPacket(SENDER_ID, "Alice", DIRECT_ID,
                "global", "hi", System.currentTimeMillis()), Set.of(PROXIED_ID));
        try {
            runMainThreadTask();
        } catch (Throwable soundRegistryUnavailable) {
            // org.bukkit.Sound's static init needs a live server; the title send
            // happens first, so the assertion below is still meaningful.
        }

        verify(direct).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a private message renders for the direct receiver, not the proxied one")
    void privateMessageSkipsProxiedPlayer() {
        PrivateMessagePacket packet = new PrivateMessagePacket(SENDER_ID, "Alice",
                "survival-01", "Dave", DIRECT_ID, "psst", System.currentTimeMillis());

        client.dispatchChannelPacket(packet, Set.of(PROXIED_ID));
        runMainThreadTask();

        // The proxied player is the sender here: the proxy rendered the echo, so
        // the sub-server must not repeat it.
        verify(proxied, never()).sendMessage(anyString());
        verify(direct).sendMessage(anyString());
    }

    @Test
    @DisplayName("with no exclusions both players are rendered to")
    void noExclusionsRendersEveryone() {
        client.dispatchChannelPacket(
                new TitlePacket("global", "Hello", "World", SENDER_ID), Set.of());
        runMainThreadTask();

        verify(proxied).sendTitle("Hello", "World", 10, 70, 20);
        verify(direct).sendTitle("Hello", "World", 10, 70, 20);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void titleCopiesRenderDirectOnceWithoutLeakingToProxy(boolean backendFirst) {
        learnRoster(Set.of(PROXIED_ID));
        TitlePacket backend = title();
        TitlePacket downstream = title();
        downstream.setRequestId(backend.getRequestId());

        if (backendFirst) {
            core.handlePacket(backend);
        } else {
            client.dispatchChannelPacket(downstream, Set.of(PROXIED_ID));
        }
        runMainThreadTask();
        if (backendFirst) {
            client.dispatchChannelPacket(downstream, Set.of(PROXIED_ID));
        } else {
            core.handlePacket(backend);
        }
        runMainThreadTask();

        verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(direct, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void missingProxyCopyStillRendersDirectWithoutLeakingToProxy() {
        learnRoster(Set.of(PROXIED_ID));
        core.handlePacket(title());
        runMainThreadTask();

        verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(direct).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void titleRosterAlsoProtectsQueuedBackendChat() {
        core.handlePacket(new ChatMessagePacket(SENDER_ID, "Alice", "survival-01", "global", "hello"));
        client.dispatchChannelPacket(title(), Set.of(PROXIED_ID));
        runMainThreadTask();

        verify(proxied, never()).sendMessage(anyString());
        verify(direct).sendMessage(anyString());
        verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(direct).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void reconnectingDirectlyClearsProxyOwnershipWithoutAnotherRoster() {
        learnRoster(Set.of(PROXIED_ID));
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(proxied);
        chatInterceptor.onPlayerQuit(quit);
        Player reconnected = mock(Player.class);
        when(reconnected.getUniqueId()).thenReturn(PROXIED_ID);
        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(reconnected);
        chatInterceptor.onPlayerJoin(join);
        doReturn(List.of(reconnected, direct)).when(server).getOnlinePlayers();

        core.handlePacket(title());
        runMainThreadTask();
        verify(reconnected).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void anEmptyAuthoritativeRosterReleasesPreviousOwnership() {
        learnRoster(Set.of(PROXIED_ID));
        learnRoster(Set.of());
        core.handlePacket(title());
        runMainThreadTask();

        verify(proxied).sendTitle("Hello", "World", 3, 37, 9);
        verify(direct).sendTitle("Hello", "World", 3, 37, 9);
    }

    private void learnRoster(Set<UUID> playerIds) {
        client.dispatchChannelPacket(new ChannelActionResponsePacket(
                true, ChannelAction.WHO, "global", "", ""), playerIds);
    }

    private void coordinatedPeers() {
        when(config.isPluginMessagingEnabled()).thenReturn(true);
        when(config.getRenderCoordinationSecret()).thenReturn(RENDER_SECRET);
        when(config.getPluginMessagingServerId()).thenReturn("bukkit");
        bridge = new ProxyChannelClient(plugin, (sender, packet, excluded) -> client.dispatchChannelPacket(packet, excluded));
        when(plugin.getProxyChannelClient()).thenReturn(bridge);
        proxyPlatform = mock(ProxyPlatform.class);
        ProxyPlayer player = mock(ProxyPlayer.class);
        when(player.getUniqueId()).thenReturn(PROXIED_ID);
        when(player.getName()).thenReturn("Proxied");
        when(player.getCurrentServer()).thenReturn("bukkit");
        when(proxyPlatform.getPlayer(PROXIED_ID)).thenReturn(player);
        doReturn(List.of(player)).when(proxyPlatform).getOnlinePlayers();
        when(proxyPlatform.getPlayerConnectionIdentity(PROXIED_ID)).thenReturn(new Object());
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
                .when(proxyPlatform).execute(any(Runnable.class));
        doAnswer(invocation -> { retryTasks.add(invocation.getArgument(0)); return null; })
                .when(proxyPlatform).runLater(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong());
        when(proxyPlatform.sendPlayerPluginMessage(eq(PROXIED_ID), any(Object.class), eq(RenderCoordinationProtocol.CHANNEL_ID), any(byte[].class)))
                .thenAnswer(invocation -> {
                    byte[] payload = invocation.getArgument(3);
                    controls.add(payload);
                    Object source = invocation.getArgument(1);
                    proxyToBukkit.add(() -> {
                        replySource = source;
                        try { bridge.onPluginMessageReceived(RenderCoordinationProtocol.CHANNEL_ID, proxied, payload); }
                        finally { replySource = null; }
                    });
                    return true;
                });
        doAnswer(invocation -> {
            byte[] payload = invocation.getArgument(2);
            Object source = replySource != null ? replySource : proxyPlatform.getPlayerConnectionIdentity(PROXIED_ID);
            bukkitToProxy.add(() -> proxyCore.handleRenderControl("bukkit", PROXIED_ID, source, payload));
            return null;
        }).when(proxied).sendPluginMessage(eq(plugin), eq(RenderCoordinationProtocol.CHANNEL_ID), any(byte[].class));
        doAnswer(invocation -> {
            proxied.sendTitle(invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3),
                    invocation.getArgument(4), invocation.getArgument(5));
            return null;
        }).when(proxyPlatform).showTitle(any(ProxyPlayer.class), anyString(), anyString(), anyInt(), anyInt(), anyInt());
        doAnswer(invocation -> { proxied.sendMessage((String) invocation.getArgument(1)); return null; })
                .when(proxyPlatform).sendMessage(any(ProxyPlayer.class), anyString());
        ProxyConfig proxyConfig = new ProxyConfig.Builder().defaultChannel("global")
                .defaultFormat("[{channel}] {player}: {message}")
                .renderCoordinationSecret("bukkit", RENDER_SECRET).build();
        proxyCore = new ProxyChatCore(proxyPlatform, proxyConfig, ignoreListService, null,
                new KnownChannelRegistry(), () -> null);
        proxyCore.getOrCreateState(player);
    }

    private void drainControl() {
        while (!proxyToBukkit.isEmpty() || !bukkitToProxy.isEmpty()) {
            while (!proxyToBukkit.isEmpty()) proxyToBukkit.removeFirst().run();
            while (!bukkitToProxy.isEmpty()) bukkitToProxy.removeFirst().run();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void negotiatedColdTitleRendersExactlyOnceInEitherOrder(boolean backendFirst) {
        coordinatedPeers();
        TitlePacket packet = title();
        if (backendFirst) { core.handlePacket(packet); runMainThreadTask(); }
        proxyCore.handleTitle(packet);
        drainControl();
        if (!backendFirst) { core.handlePacket(packet); runMainThreadTask(); }
        client.dispatchChannelPacket(packet, Set.of(PROXIED_ID));
        runMainThreadTask();
        verify(proxied, times(1)).sendTitle("Hello", "World", 3, 37, 9);
        verify(direct, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void negotiatedColdChatRendersExactlyOnceInEitherOrder(boolean backendFirst) {
        coordinatedPeers();
        ChatMessagePacket packet = new ChatMessagePacket(SENDER_ID, "Alice", "server", "global", "first line");
        if (backendFirst) { core.handlePacket(packet); runMainThreadTask(); }
        proxyCore.handleIncomingMessage(packet);
        drainControl();
        if (!backendFirst) { core.handlePacket(packet); runMainThreadTask(); }
        client.dispatchChannelPacket(packet, Set.of(PROXIED_ID));
        runMainThreadTask();
        verify(proxied, times(1)).sendMessage(anyString());
        verify(direct, times(1)).sendMessage(anyString());
    }

    @Test
    void lostDecisionRetainsTheOriginalProxyOwnerOnRetry() throws Exception {
        coordinatedPeers();
        TitlePacket packet = title();
        proxyCore.handleTitle(packet);
        proxyToBukkit.removeFirst().run();
        bukkitToProxy.removeFirst().run();
        proxyToBukkit.removeFirst().run();
        bukkitToProxy.removeFirst().run();
        proxyToBukkit.removeFirst().run();
        bukkitToProxy.clear();
        core.handlePacket(packet);
        runMainThreadTask();
        verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        retryTasks.remove(0).run();
        assertThat(controls.size()).isGreaterThan(3);
        drainControl();
        verify(proxied, times(1)).sendTitle("Hello", "World", 3, 37, 9);
        verify(direct, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void staleControlAndSnapshotCannotSuppressAFreshDirectConnection() throws Exception {
        coordinatedPeers();
        proxyCore.handleTitle(title());
        drainControl();
        byte[] oldClaim = controls.stream().filter(bytes -> {
            try { return RenderCoordinationProtocol.decode(bytes, RENDER_SECRET).kind() == Kind.CLAIM; }
            catch (Exception error) { throw new AssertionError(error); }
        }).findFirst().orElseThrow();
        PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(proxied);
        chatInterceptor.onPlayerQuit(quit);
        proxyCore.onPlayerDisconnect(PROXIED_ID);
        Player fresh = mock(Player.class);
        when(fresh.getUniqueId()).thenReturn(PROXIED_ID);
        when(fresh.getWorld()).thenReturn(world);
        when(server.getPlayer(PROXIED_ID)).thenReturn(fresh);
        doReturn(List.of(fresh, direct)).when(server).getOnlinePlayers();
        PlayerJoinEvent join = mock(PlayerJoinEvent.class);
        when(join.getPlayer()).thenReturn(fresh);
        chatInterceptor.onPlayerJoin(join);
        bridge.onPluginMessageReceived(RenderCoordinationProtocol.CHANNEL_ID, fresh, controls.get(0));
        bridge.onPluginMessageReceived(RenderCoordinationProtocol.CHANNEL_ID, fresh, oldClaim);
        learnRoster(Set.of(PROXIED_ID));
        core.handlePacket(title());
        runMainThreadTask();
        verify(fresh, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void signedControlCannotClaimAnotherPlayersConnection() throws Exception {
        coordinatedPeers();
        proxyCore.handleTitle(title());
        byte[] probe = controls.get(0);
        bridge.onPluginMessageReceived(RenderCoordinationProtocol.CHANNEL_ID, direct, probe);
        assertThat(bukkitToProxy).isEmpty();
        byte[] forged = probe.clone();
        forged[forged.length - 1] ^= 1;
        bridge.onPluginMessageReceived(RenderCoordinationProtocol.CHANNEL_ID, proxied, forged);
        assertThat(bukkitToProxy).isEmpty();
        drainControl();
        verify(proxied, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void requiredCoordinationNeverFallsBackWhenThePeerIsSilent() {
        coordinatedPeers();
        proxyCore.handleTitle(title());
        for (int i = 0; i < 5; i++) retryTasks.remove(0).run();
        assertThat(controls).hasSize(6);
        verify(proxyPlatform, never()).showTitle(any(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(proxyPlatform).logWarn(org.mockito.ArgumentMatchers.contains("no legacy fallback"));
    }

    @Test
    void oldDecisionCannotRenderAfterThePhysicalConnectionChanges() {
        coordinatedPeers();
        proxyCore.handleTitle(title());
        proxyToBukkit.removeFirst().run();
        bukkitToProxy.removeFirst().run();
        proxyToBukkit.removeFirst().run();
        bukkitToProxy.removeFirst().run();
        proxyToBukkit.removeFirst().run();
        when(proxyPlatform.getPlayerConnectionIdentity(PROXIED_ID)).thenReturn(new Object());
        proxyCore.handleTitle(new TitlePacket("global", "Fresh", "Session", SENDER_ID));
        bukkitToProxy.removeFirst().run();
        drainControl();
        verify(proxied, never()).sendTitle(eq("Hello"), anyString(), anyInt(), anyInt(), anyInt());
        verify(proxied, times(1)).sendTitle("Fresh", "Session", 10, 70, 20);
    }

    @Test
    void legacyBackendChatKeepsThePreviouslyLearnedProxyRoster() {
        learnRoster(Set.of(PROXIED_ID));
        core.handlePacket(new ChatMessagePacket(SENDER_ID, "Alice", "server", "global", "legacy"));
        runMainThreadTask();
        verify(proxied, never()).sendMessage(anyString());
        verify(direct, times(1)).sendMessage(anyString());
    }

    private void negotiateOnly() {
        proxyCore.recordServer(proxyPlatform.getPlayer(PROXIED_ID), "bukkit");
        drainControl();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void confirmedBackendOnlyCopyUsesTheRealProxyRendererOnce(boolean chat) {
        coordinatedPeers();
        negotiateOnly();
        com.nova.chat.common.protocol.Packet packet = chat
                ? new ChatMessagePacket(SENDER_ID, "Alice", "server", "global", "backend only") : title();
        core.handlePacket(packet);
        runMainThreadTask();
        if (chat) verify(proxied, never()).sendMessage(anyString());
        else verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        drainControl();
        core.handlePacket(packet);
        runMainThreadTask();
        drainControl();
        if (chat) {
            verify(proxied, times(1)).sendMessage(anyString());
            verify(direct, times(1)).sendMessage(anyString());
        } else {
            verify(proxied, times(1)).sendTitle("Hello", "World", 3, 37, 9);
            verify(direct, times(1)).sendTitle("Hello", "World", 3, 37, 9);
        }
    }

    @Test
    void backendOfferRespectsTheProxyChannelInsteadOfLeakingToAnOutsider() {
        coordinatedPeers();
        negotiateOnly();
        proxyCore.getPlayerState(PROXIED_ID).setActiveChannel("local");
        TitlePacket packet = title();
        core.handlePacket(packet);
        runMainThreadTask();
        drainControl();
        core.handlePacket(packet);
        runMainThreadTask();
        drainControl();
        verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        verify(direct, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void bridgeGenerationRecoveryKeepsPendingRequestsAndOriginalClaimDecisions() {
        coordinatedPeers();
        proxyCore.handleTitle(title());
        proxyToBukkit.removeFirst().run();
        bukkitToProxy.removeFirst().run();
        proxyToBukkit.removeFirst().run();
        bukkitToProxy.removeFirst().run();
        proxyToBukkit.removeFirst().run();
        bukkitToProxy.clear();
        bridge = new ProxyChannelClient(plugin, (sender, packet, excluded) -> client.dispatchChannelPacket(packet, excluded));
        when(plugin.getProxyChannelClient()).thenReturn(bridge);
        retryTasks.remove(0).run();
        drainControl();
        verify(proxied, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void unchangedConfigReloadKeepsAnAcceptedQueuedRender() {
        coordinatedPeers();
        List<Runnable> renders = new ArrayList<>();
        doAnswer(inv -> { renders.add(inv.getArgument(0)); return null; })
                .when(proxyPlatform).execute(any(Runnable.class));
        proxyCore.handleTitle(title());
        drainControl();
        proxyCore.setConfig(new ProxyConfig.Builder().defaultChannel("global")
                .renderCoordinationSecret("bukkit", RENDER_SECRET).build());
        renders.forEach(Runnable::run);
        verify(proxied, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void oldPhysicalSourceCannotUseAnOtherwiseCurrentAuthenticatedDecision() throws Exception {
        coordinatedPeers();
        proxyCore.handleTitle(title());
        proxyToBukkit.removeFirst().run();
        bukkitToProxy.removeFirst().run();
        proxyToBukkit.removeFirst().run();
        bukkitToProxy.removeFirst().run();
        Frame claim = RenderCoordinationProtocol.decode(controls.get(controls.size() - 1), RENDER_SECRET);
        byte[] decision = RenderCoordinationProtocol.encode(new Frame(Kind.DECISION, claim.playerId(),
                claim.proxySession(), claim.bukkitSession(), claim.requestId(), claim.packetId(), true), RENDER_SECRET);
        proxyCore.handleRenderControl("bukkit", PROXIED_ID, new Object(), decision);
        verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        drainControl();
        verify(proxied, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void oldHelloCannotRollBackARecoveredGeneration() throws Exception {
        coordinatedPeers();
        proxyCore.handleTitle(title());
        proxyToBukkit.removeFirst().run();
        Runnable helloB1 = bukkitToProxy.removeFirst();
        bridge = new ProxyChannelClient(plugin, (sender, packet, excluded) -> client.dispatchChannelPacket(packet, excluded));
        when(plugin.getProxyChannelClient()).thenReturn(bridge);
        Frame probe = RenderCoordinationProtocol.decode(controls.get(0), RENDER_SECRET);
        proxyCore.handleRenderControl("bukkit", PROXIED_ID, proxyPlatform.getPlayerConnectionIdentity(PROXIED_ID),
                RenderCoordinationProtocol.encode(new Frame(Kind.HELLO, PROXIED_ID, probe.proxySession(),
                        UUID.randomUUID(), new UUID(0, 0), 0, false), RENDER_SECRET));
        helloB1.run();
        drainControl();
        helloB1.run();
        proxyCore.handleTitle(new TitlePacket("global", "Fresh", "Epoch", SENDER_ID));
        drainControl();
        verify(proxied, times(1)).sendTitle("Hello", "World", 3, 37, 9);
        verify(proxied, times(1)).sendTitle("Fresh", "Epoch", 10, 70, 20);
    }

    @Test
    void oldV1RosterCannotBypassPerMessageOwnership() {
        coordinatedPeers();
        negotiateOnly();
        TitlePacket packet = title();
        client.dispatchChannelPacket(packet, Set.of());
        runMainThreadTask();
        verify(proxied, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        proxyCore.handleTitle(packet);
        drainControl();
        verify(proxied, times(1)).sendTitle("Hello", "World", 3, 37, 9);
        verify(direct, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void oldBukkitCarrierCannotBorrowTheCurrentPlayersAuthentication() throws Exception {
        coordinatedPeers();
        negotiateOnly();
        Player stale = mock(Player.class);
        when(stale.getUniqueId()).thenReturn(PROXIED_ID);
        byte[] payload = ProxyChannelProtocol.encode("proxy", title());
        bridge.onPluginMessageReceived(ProxyChannelProtocol.CHANNEL_ID, stale, payload);
        runMainThreadTask();
        verify(direct, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
        bridge.onPluginMessageReceived(ProxyChannelProtocol.CHANNEL_ID, proxied, payload);
        runMainThreadTask();
        verify(direct, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @Test
    void backendOnlyOfferSurvivesBridgeReconstructionBeforeProxyReceivesIt() {
        coordinatedPeers();
        negotiateOnly();
        core.handlePacket(title());
        runMainThreadTask();
        bukkitToProxy.clear();
        bridge = new ProxyChannelClient(plugin, (sender, packet, excluded) -> client.dispatchChannelPacket(packet, excluded));
        when(plugin.getProxyChannelClient()).thenReturn(bridge);
        offerRetries.remove(0).run();
        drainControl();
        verify(proxied, times(1)).sendTitle("Hello", "World", 3, 37, 9);
        verify(direct, times(1)).sendTitle("Hello", "World", 3, 37, 9);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {62, 63, 64, 160})
    void largeLegalBackendOnlyChatReassemblesInEitherChunkOrder(int fields) throws Exception {
        coordinatedPeers();
        negotiateOnly();
        ChatMessagePacket packet = new ChatMessagePacket(SENDER_ID, "Alice", "server", "global", "large backend only");
        for (int i = 0; i < fields; i++) packet.addPlaceholder("key" + i, "x".repeat(512));
        byte[] encoded = RenderPacketCodec.encode(packet);
        assertThat(RenderPacketCodec.decode(encoded).getRequestId()).isEqualTo(packet.getRequestId());
        core.handlePacket(packet);
        runMainThreadTask();
        List<Runnable> parts = new ArrayList<>(bukkitToProxy);
        bukkitToProxy.clear();
        assertThat(parts.size()).isEqualTo((encoded.length + RenderCoordinationProtocol.MAX_PART_DATA_BYTES - 1)
                / RenderCoordinationProtocol.MAX_PART_DATA_BYTES);
        java.util.Collections.reverse(parts);
        parts.forEach(Runnable::run);
        parts.forEach(Runnable::run);
        drainControl();
        verify(proxied, times(1)).sendMessage(anyString());
        verify(direct, times(1)).sendMessage(anyString());
    }

    private TitlePacket title() {
        return new TitlePacket("global", "Hello", "World", SENDER_ID, 3, 37, 9);
    }

    private void runMainThreadTask() {
        while (!mainTasks.isEmpty()) {
            mainTasks.removeFirst().run();
        }
    }
}
