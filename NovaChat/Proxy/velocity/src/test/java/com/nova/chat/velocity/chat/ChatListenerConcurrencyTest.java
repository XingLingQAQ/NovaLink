package com.nova.chat.velocity.chat;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.nova.chat.velocity.network.NetworkClient;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency stress evidence for the Velocity {@link ChatListener}'s
 * registered packet handlers (VERIFY-003, automatable "our code" slice).
 *
 * <p><b>What is proven here:</b> invoking the handlers the listener registers on
 * {@link NetworkClient} — title, incoming chat, item display, private message,
 * and mention — concurrently from many threads produces (a) zero handler
 * exceptions, (b) exactly the expected number of Adventure sends (no drops,
 * no duplicates), and (c) consistent listener-owned state ({@code playerStates},
 * {@code welcomedPlayers}, mention dedup) throughout. Every mocked player-API
 * call records {@link Thread#currentThread()} so the test also proves dispatch
 * really happens off the caller's thread (no hidden thread-affinity assumption
 * in our own code). Latches plus a hard {@code awaitTermination} deadline make
 * a deadlock fail the test instead of hanging CI.
 *
 * <p><b>VERIFY-003 static-evidence addendum (version-pinned claim review).</b>
 * The audit questions the in-source claims:
 * <ul>
 *   <li>{@code NovaChat/Proxy/velocity/src/main/java/com/nova/chat/velocity/chat/ChatListener.java}
 *       L117: "the thread-safe Adventure send needs no scheduler hop" (and L136,
 *       L165-L166 for the title and item-display paths).</li>
 *   <li>{@code NovaChat/Proxy/bungee/src/main/java/com/nova/chat/bungee/chat/ChatListener.java}
 *       L117-L118: "no scheduler hop is needed: BungeeCord's player send path is
 *       thread-safe from the Netty callback." (repeated at L139-L141 and
 *       L179-L181).</li>
 * </ul>
 * Pinned API versions (root {@code build.gradle}): {@code velocityVersion =
 * '4.1.0-SNAPSHOT'}, {@code bungeecordVersion = '1.21-R0.4'}. Review of those
 * pinned surfaces: Velocity 4.x {@code Player} is an Adventure {@code Audience};
 * Adventure documents its sending API as safe to call from any thread (sends are
 * forwarded asynchronously). BungeeCord 1.21 {@code ProxiedPlayer#sendMessage}
 * and its {@code Title} API serialize packets and hand them straight to the
 * player's Netty channel from the calling thread, and Netty channel writes are
 * thread-safe. <b>Honest caveat:</b> that is a documentation/source-level
 * reading of the pinned versions, not a runtime proof. Only a runtime
 * compatibility matrix on live BungeeCord/Velocity instances can close the
 * "proxy internals are thread-safe" slice of VERIFY-003 — that slice stays
 * OPEN. This test closes only the complementary slice: our listener code makes
 * no hidden thread-affinity assumptions of its own under concurrent dispatch.
 *
 * <p><b>Scope note:</b> the {@code ChannelActionResponsePacket} handler is
 * intentionally not driven here — its platform adapter deliberately hops via
 * {@code Scheduler#buildTask(...).schedule()} (GAP-3), so a mock-driven run
 * would only exercise mocked scheduler plumbing, not listener-owned state.
 */
@DisplayName("Velocity ChatListener concurrent dispatch stress")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
// The velocity module's junit-platform.properties flips the default to per_class;
// this suite accumulates per-test counters in instance fields, so pin it back
// to a fresh instance (and fresh listener/counters) per test method.
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ChatListenerConcurrencyTest {

    /** Worker threads hammering the handlers simultaneously. */
    private static final int THREADS = 16;
    /**
     * Handler dispatches per thread, per packet type. Sized so the total
     * Mockito invocation retention (each recorded call carries a captured
     * location stack trace) stays inside the test JVM's default 2 GB heap;
     * the contention quality of the storm comes from {@link #THREADS}.
     */
    private static final int ITERATIONS = 50;
    /** Mocked online players receiving broadcasts. */
    private static final int PLAYER_COUNT = 8;
    /** Total dispatches of each handler across the whole storm. */
    private static final int DISPATCHES = THREADS * ITERATIONS;
    /** Hard wall-clock budget for each storm phase; a deadlock fails here. */
    private static final long PHASE_TIMEOUT_SECONDS = 120;

    private static final long TS = 1_700_000_000_000L;
    private static final String CHANNEL = "global";
    private static final String ITEM_JSON_STONE =
            "{\"id\":\"minecraft:stone\",\"count\":1}";

    @Mock
    private NovaChatVelocity plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ChannelResponseTracker tracker;
    @Mock
    private ProxyServer proxyServer;

    private final List<Player> players = new ArrayList<>();
    private final Map<UUID, Player> playersById = new HashMap<>();

    /** sendMessage(Component) calls per player (chat + item + pm + mention). */
    private final Map<UUID, AtomicInteger> lineSendCounts = new ConcurrentHashMap<>();
    /** showTitle(Title) calls per player. */
    private final Map<UUID, AtomicInteger> titleShowCounts = new ConcurrentHashMap<>();
    /** playSound calls per player (mention path; best-effort on real proxies). */
    private final Map<UUID, AtomicInteger> soundPlayCounts = new ConcurrentHashMap<>();
    /** Every thread observed inside a player-API call. */
    private final Set<Thread> senderThreads = ConcurrentHashMap.newKeySet();
    /** Handler exceptions captured off the worker threads. */
    private final Queue<Throwable> handlerFailures = new ConcurrentLinkedQueue<>();

    private ChatListener listener;
    private Consumer<TitlePacket> titleHandler;
    private Consumer<ChatMessagePacket> chatHandler;
    private Consumer<ItemDisplayPacket> itemDisplayHandler;
    private Consumer<PrivateMessagePacket> privateMessageHandler;
    private Consumer<MentionPacket> mentionHandler;
    private Locale previousDefaultLocale;
    private Thread mainThread;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        mainThread = Thread.currentThread();

        // Real client-core services: their internal maps are part of the shared
        // state this test hammers (reply targets + ignore filters).
        PrivateMessageService privateMessageService = new PrivateMessageService();
        IgnoreListService ignoreListService = new IgnoreListService();

        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(plugin.getServer()).thenReturn(proxyServer);
        when(plugin.getPrivateMessageService()).thenReturn(privateMessageService);
        when(plugin.getIgnoreListService()).thenReturn(ignoreListService);
        when(config.getDefaultChannel()).thenReturn(CHANNEL);
        when(config.isReplaceVanilla()).thenReturn(false);
        when(config.getChannelFormat(any(String.class))).thenReturn("{player}: {message}");

        for (int i = 0; i < PLAYER_COUNT; i++) {
            players.add(newCountedPlayer("Player" + i,
                    UUID.fromString(String.format("00%06d-aaaa-bbbb-cccc-dddddddddddd", i))));
        }
        doReturn(List.copyOf(players)).when(proxyServer).getAllPlayers();
        when(proxyServer.getPlayer(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(playersById.get(inv.getArgument(0, UUID.class))));

        listener = new ChatListener(plugin);

        ArgumentCaptor<Consumer<TitlePacket>> titleCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(TitlePacket.class), titleCaptor.capture());
        titleHandler = titleCaptor.getValue();

        ArgumentCaptor<Consumer<ChatMessagePacket>> chatCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ChatMessagePacket.class), chatCaptor.capture());
        chatHandler = chatCaptor.getValue();

        ArgumentCaptor<Consumer<ItemDisplayPacket>> itemCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ItemDisplayPacket.class), itemCaptor.capture());
        itemDisplayHandler = itemCaptor.getValue();

        ArgumentCaptor<Consumer<PrivateMessagePacket>> pmCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(PrivateMessagePacket.class), pmCaptor.capture());
        privateMessageHandler = pmCaptor.getValue();

        ArgumentCaptor<Consumer<MentionPacket>> mentionCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(MentionPacket.class), mentionCaptor.capture());
        mentionHandler = mentionCaptor.getValue();

        // Seed all players onto channel "global" so every broadcast reaches them.
        for (Player player : players) {
            listener.getOrCreateState(player);
            assertThat(listener.getState(player.getUniqueId())).isNotNull();
        }
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    /**
     * Builds a player mock whose Adventure send methods count invocations and
     * record the calling thread.
     */
    private Player newCountedPlayer(String name, UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getUsername()).thenReturn(name);
        AtomicInteger lineCount = new AtomicInteger();
        lineSendCounts.put(id, lineCount);
        AtomicInteger titleCount = new AtomicInteger();
        titleShowCounts.put(id, titleCount);
        AtomicInteger soundCount = new AtomicInteger();
        soundPlayCounts.put(id, soundCount);

        doAnswer(inv -> {
            lineCount.incrementAndGet();
            senderThreads.add(Thread.currentThread());
            return null;
        }).when(player).sendMessage(any(net.kyori.adventure.text.Component.class));
        doAnswer(inv -> {
            titleCount.incrementAndGet();
            senderThreads.add(Thread.currentThread());
            return null;
        }).when(player).showTitle(any(Title.class));
        doAnswer(inv -> {
            soundCount.incrementAndGet();
            senderThreads.add(Thread.currentThread());
            return null;
        }).when(player).playSound(any(net.kyori.adventure.sound.Sound.class));

        playersById.put(id, player);
        return player;
    }

    /**
     * Runs {@code bodies[workerIndex]} on each of {@link #THREADS} pooled threads
     * behind a start latch, joins with a hard timeout (deadlock fails instead of
     * hanging CI), and fails fast if any handler threw.
     */
    private void runConcurrently(IntConsumer[] bodies) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final IntConsumer body = bodies[t];
                final int workerIndex = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    body.accept(workerIndex);
                    return null;
                }));
            }
            start.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(PHASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                fail("Worker pool did not terminate within " + PHASE_TIMEOUT_SECONDS
                        + "s -- suspected deadlock in ChatListener handlers");
            }
            for (Future<?> f : futures) {
                if (f.isDone() && !f.isCancelled()) {
                    // Exceptions thrown outside the per-dispatch catch land here.
                    f.get();
                }
            }
        } catch (ExecutionException e) {
            handlerFailures.add(e.getCause());
        } finally {
            pool.shutdownNow();
        }
        if (!handlerFailures.isEmpty()) {
            handlerFailures.peek().printStackTrace();
        }
        assertThat(handlerFailures)
                .as("handler exceptions across %d threads", THREADS)
                .isEmpty();
    }

    /** Deterministically unique mentioner id per (thread, iteration). */
    private static UUID uniqueMentioner(long threadIndex, int iteration) {
        return new UUID(0x5EEDL, (threadIndex << 32) | iteration);
    }

    private void dispatchOneOfEach(long threadIndex, int iteration) {
        titleHandler.accept(new TitlePacket(CHANNEL, "&6Storm", "&7sub",
                players.get(0).getUniqueId(), 5, 40, 10));
        chatHandler.accept(new ChatMessagePacket(
                UUID.fromString("99999999-9999-9999-9999-999999999999"), "Alex",
                "srv-1", CHANNEL, "hello " + iteration));
        itemDisplayHandler.accept(new ItemDisplayPacket(
                UUID.fromString("99999999-9999-9999-9999-999999999999"), "Steve",
                CHANNEL, ITEM_JSON_STONE, TS));
        privateMessageHandler.accept(new PrivateMessagePacket(
                players.get(0).getUniqueId(), "Player0", "srv-1", "Player1",
                players.get(1).getUniqueId(), "psst " + iteration, TS));
        mentionHandler.accept(new MentionPacket(
                uniqueMentioner(threadIndex, iteration), "Alex",
                players.get(0).getUniqueId(), CHANNEL, "hi @Player0", TS));
    }

    @Test
    @DisplayName("storm: 16 threads x 50 iterations across five handlers - no exceptions, no lost sends")
    void concurrentHandlerStormIsExceptionFreeAndLossless() throws InterruptedException {
        IntConsumer[] bodies = new IntConsumer[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final long threadIndex = t;
            bodies[t] = who -> {
                for (int i = 0; i < ITERATIONS && handlerFailures.isEmpty(); i++) {
                    try {
                        dispatchOneOfEach(threadIndex, i);
                    } catch (Throwable failure) {
                        handlerFailures.add(failure);
                        return;
                    }
                }
            };
        }
        runConcurrently(bodies);

        // (b) No lost sends, no duplicated sends. Velocity's mention path renders
        // title + sound (no chat line), so per-player line expectations:
        //     player0: chat + item + pm-echo                = 3 x DISPATCHES
        //     player1: chat + item + pm-received            = 3 x DISPATCHES
        //     others:  chat + item                          = 2 x DISPATCHES
        //     titles:  broadcast(1) + mention-title for p0  = DISPATCHES (+DISPATCHES for p0)
        //     sounds:  mention sound only to p0             = DISPATCHES for p0
        for (int i = 0; i < PLAYER_COUNT; i++) {
            UUID id = players.get(i).getUniqueId();
            int expectedLines = (i <= 1) ? 3 * DISPATCHES : 2 * DISPATCHES;
            assertThat(lineSendCounts.get(id))
                    .as("Adventure line sends to player%d", i)
                    .hasValue(expectedLines);
            int expectedTitles = DISPATCHES + (i == 0 ? DISPATCHES : 0); // broadcast titles + mention title
            assertThat(titleShowCounts.get(id))
                    .as("title shows to player%d", i)
                    .hasValue(expectedTitles);
            assertThat(soundPlayCounts.get(id))
                    .as("mention sounds to player%d", i)
                    .hasValue(i == 0 ? DISPATCHES : 0);
        }

        // (a, continued) dispatch genuinely ran off this thread, on >= 2 workers.
        assertThat(senderThreads).doesNotContain(mainThread);
        assertThat(senderThreads.size()).as("distinct sending threads").isGreaterThanOrEqualTo(2);

        // (c) Listener-owned state stays consistent after the storm: no entry
        // lost or corrupted (a ConcurrentModificationException would already
        // have surfaced as a handler failure above).
        for (Player player : players) {
            assertThat(listener.getState(player.getUniqueId()))
                    .as("state of %s survives the storm", player.getUsername())
                    .isNotNull();
            assertThat(listener.getState(player.getUniqueId()).getActiveChannel())
                    .isEqualTo(CHANNEL);
        }
    }

    @Test
    @DisplayName("welcome race: concurrent server-connected bursts deliver exactly one welcome per player")
    void concurrentWelcomeRacesDeliverExactlyOneWelcomePerPlayer() throws InterruptedException {
        List<Player> fresh = new ArrayList<>();
        for (int i = 0; i < PLAYER_COUNT; i++) {
            fresh.add(newCountedPlayer("Fresh" + i,
                    UUID.fromString(String.format("0f%06d-bbbb-cccc-dddd-eeeeeeeeeeee", i))));
        }

        AtomicInteger welcomeSends = new AtomicInteger();
        for (Player player : fresh) {
            doAnswer(inv -> {
                welcomeSends.incrementAndGet();
                senderThreads.add(Thread.currentThread());
                return null;
            }).when(player).sendMessage(any(net.kyori.adventure.text.Component.class));
        }

        IntConsumer[] bodies = new IntConsumer[THREADS];
        for (int t = 0; t < THREADS; t++) {
            bodies[t] = who -> {
                for (int round = 0; round < 25 && handlerFailures.isEmpty(); round++) {
                    try {
                        Player player = fresh.get((who * 25 + round) % PLAYER_COUNT);
                        ServerConnectedEvent event = mock(ServerConnectedEvent.class);
                        when(event.getPlayer()).thenReturn(player);
                        RegisteredServer registered = mock(RegisteredServer.class);
                        ServerInfo info = new ServerInfo("lobby", java.net.InetSocketAddress.createUnresolved(
                                "lobby.example.test", 25565));
                        when(event.getServer()).thenReturn(registered);
                        when(registered.getServerInfo()).thenReturn(info);
                        listener.onServerConnected(event);
                    } catch (Throwable failure) {
                        handlerFailures.add(failure);
                        return;
                    }
                }
            };
        }
        runConcurrently(bodies);

        // ConcurrentHashMap.newKeySet#add is atomic: exactly one winner per UUID
        // despite THREADS concurrent racers, so each fresh player gets precisely
        // one welcome line and the rest are suppressed.
        assertThat(welcomeSends).as("total welcome lines (one per fresh player)")
                .hasValue(PLAYER_COUNT);
        for (Player player : fresh) {
            assertThat(listener.getState(player.getUniqueId()))
                    .as("state created for %s", player.getUsername())
                    .isNotNull();
        }
        assertThat(senderThreads).doesNotContain(mainThread);
    }

    @Test
    @DisplayName("churn: concurrent disconnect/reconnect cycles keep the state store consistent")
    void concurrentDisconnectReconnectChurnKeepsStoreConsistent() throws InterruptedException {
        runConcurrently(phaseBodies(who -> {
            for (Player player : players) {
                DisconnectEvent event = mock(DisconnectEvent.class);
                when(event.getPlayer()).thenReturn(player);
                listener.onPlayerDisconnect(event);
            }
        }));
        for (Player player : players) {
            assertThat(listener.getState(player.getUniqueId()))
                    .as("state of %s removed after disconnect phase", player.getUsername())
                    .isNull();
        }

        runConcurrently(phaseBodies(who -> {
            for (Player player : players) {
                ServerConnectedEvent event = mock(ServerConnectedEvent.class);
                when(event.getPlayer()).thenReturn(player);
                RegisteredServer registered = mock(RegisteredServer.class);
                ServerInfo info = new ServerInfo("lobby", java.net.InetSocketAddress.createUnresolved(
                        "lobby.example.test", 25565));
                when(event.getServer()).thenReturn(registered);
                when(registered.getServerInfo()).thenReturn(info);
                listener.onServerConnected(event);
            }
        }));
        for (Player player : players) {
            PlayerChannelState state = listener.getState(player.getUniqueId());
            assertThat(state).as("state of %s restored after reconnect phase", player.getUsername())
                    .isNotNull();
            assertThat(state.getActiveChannel()).isEqualTo(CHANNEL);
            assertThat(state.getCurrentServer()).isEqualTo("lobby");
        }

        // Phase C: title dispatch races disconnect/reconnect churn. Send counts
        // are legitimately nondeterministic here (a title aimed at a player whose
        // state is momentarily removed is correctly skipped), so the assertions
        // are zero handler exceptions plus a structurally valid store afterwards.
        IntConsumer[] bodies = new IntConsumer[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int kind = t % 2;
            bodies[t] = who -> {
                try {
                    if (kind == 0) {
                        for (int i = 0; i < 500 && handlerFailures.isEmpty(); i++) {
                            titleHandler.accept(new TitlePacket(CHANNEL, "&6Churn", "",
                                    players.get(0).getUniqueId(), 5, 40, 10));
                        }
                    } else {
                        for (int i = 0; i < 300 && handlerFailures.isEmpty(); i++) {
                            for (Player player : players) {
                                DisconnectEvent off = mock(DisconnectEvent.class);
                                when(off.getPlayer()).thenReturn(player);
                                listener.onPlayerDisconnect(off);
                                ServerConnectedEvent on = mock(ServerConnectedEvent.class);
                                when(on.getPlayer()).thenReturn(player);
                                RegisteredServer registered = mock(RegisteredServer.class);
                                ServerInfo info = new ServerInfo("lobby",
                                        java.net.InetSocketAddress.createUnresolved(
                                                "lobby.example.test", 25565));
                                when(on.getServer()).thenReturn(registered);
                                when(registered.getServerInfo()).thenReturn(info);
                                listener.onServerConnected(on);
                                listener.onServerConnected(on);
                            }
                        }
                    }
                } catch (Throwable failure) {
                    handlerFailures.add(failure);
                }
            };
        }
        runConcurrently(bodies);

        // Store holds only known players, each with a usable channel.
        for (Player player : players) {
            PlayerChannelState state = listener.getState(player.getUniqueId());
            if (state != null) {
                assertThat(state.getActiveChannel()).isEqualTo(CHANNEL);
            }
        }
    }

    /** Wraps per-worker bodies so {@link #runConcurrently} can index them. */
    private static IntConsumer[] phaseBodies(IntConsumer body) {
        IntConsumer[] bodies = new IntConsumer[THREADS];
        for (int t = 0; t < THREADS; t++) {
            bodies[t] = body;
        }
        return bodies;
    }
}
