package com.nova.chat.bungee.chat;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.bungee.network.NetworkClient;
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
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
 * Concurrency stress evidence for the Bungee {@link ChatListener}'s registered
 * packet handlers (VERIFY-003, automatable "our code" slice).
 *
 * <p><b>What is proven here:</b> invoking the handlers the listener registers on
 * {@link NetworkClient} — title, incoming chat, item display, private message,
 * and mention — concurrently from many threads produces (a) zero handler
 * exceptions, (b) exactly the expected number of player-API sends (no drops,
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
 *   <li>{@code NovaChat/Proxy/bungee/src/main/java/com/nova/chat/bungee/chat/ChatListener.java}
 *       L117-L118: "no scheduler hop is needed: BungeeCord's player send path is
 *       thread-safe from the Netty callback." (repeated for the title path at
 *       L179-L181 and the item-display path at L139-L141).</li>
 *   <li>{@code NovaChat/Proxy/velocity/src/main/java/com/nova/chat/velocity/chat/ChatListener.java}
 *       L117: "the thread-safe Adventure send needs no scheduler hop" (and L136,
 *       L165-L166 for the title and item-display paths).</li>
 * </ul>
 * Pinned API versions (root {@code build.gradle}): {@code bungeecordVersion =
 * '1.21-R0.4'}, {@code velocityVersion = '4.1.0-SNAPSHOT'}. Review of those
 * pinned surfaces: Velocity 4.x {@code Player} is an Adventure {@code Audience};
 * Adventure documents its sending API as safe to call from any thread (sends are
 * forwarded asynchronously). BungeeCord 1.21 {@code ProxiedPlayer#sendMessage}
 * and the {@link Title} API serialize packets and hand them straight to the
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
 * {@code Scheduler#runAsync} (GAP-3), so a mock-driven run would only exercise
 * mocked scheduler plumbing, not listener-owned state.
 */
@DisplayName("Bungee ChatListener concurrent dispatch stress")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
// The bungee module's junit-platform.properties flips the default to per_class;
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
    private NovaChatBungee plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ChannelResponseTracker tracker;
    @Mock
    private ProxyServer proxy;
    @Mock
    private Title title;

    private final List<ProxiedPlayer> players = new ArrayList<>();
    private final Map<UUID, ProxiedPlayer> playersById = new HashMap<>();

    /** Vararg chat-line sends per player (chat + item + pm + mention paths). */
    private final Map<UUID, AtomicInteger> lineSendCounts = new ConcurrentHashMap<>();
    /** Title#send(ProxiedPlayer) calls per player. */
    private final Map<UUID, AtomicInteger> titleSendCounts = new ConcurrentHashMap<>();
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

        when(plugin.getPluginConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(plugin.getProxy()).thenReturn(proxy);
        when(plugin.getPrivateMessageService()).thenReturn(privateMessageService);
        when(plugin.getIgnoreListService()).thenReturn(ignoreListService);
        when(config.getDefaultChannel()).thenReturn(CHANNEL);
        when(config.isReplaceVanilla()).thenReturn(false);
        when(config.getChannelFormat(any(String.class))).thenReturn("{player}: {message}");

        // Fluent title builder: every setter returns the shared Title mock.
        when(proxy.createTitle()).thenReturn(title);
        when(title.title(any(BaseComponent[].class))).thenReturn(title);
        when(title.subTitle(any(BaseComponent[].class))).thenReturn(title);
        when(title.fadeIn(any(int.class))).thenReturn(title);
        when(title.stay(any(int.class))).thenReturn(title);
        when(title.fadeOut(any(int.class))).thenReturn(title);
        doAnswer(inv -> {
            ProxiedPlayer recipient = inv.getArgument(0);
            titleSendCounts.computeIfAbsent(recipient.getUniqueId(),
                    id -> new AtomicInteger()).incrementAndGet();
            senderThreads.add(Thread.currentThread());
            return null;
        }).when(title).send(any(ProxiedPlayer.class));

        for (int i = 0; i < PLAYER_COUNT; i++) {
            players.add(newCountedLinePlayer("Player" + i,
                    UUID.fromString(String.format("00%06d-aaaa-bbbb-cccc-dddddddddddd", i))));
        }
        doReturn(List.copyOf(players)).when(proxy).getPlayers();
        when(proxy.getPlayer(any(UUID.class)))
                .thenAnswer(inv -> playersById.get(inv.getArgument(0, UUID.class)));

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
        for (ProxiedPlayer player : players) {
            listener.getOrCreateState(player);
            assertThat(listener.getState(player.getUniqueId())).isNotNull();
        }
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    /**
     * Builds a player mock whose two chat-line {@code sendMessage} overloads both
     * count into the same per-player counter and record the calling thread.
     * The listener uses BOTH forms: the chat / private-message / mention /
     * welcome paths pass a {@code BaseComponent[]} through the vararg overload,
     * while the item-display path passes a single {@code BaseComponent} through
     * {@code sendMessage(BaseComponent)} — so both must be instrumented or the
     * storm undercounts by design.
     */
    private ProxiedPlayer newCountedLinePlayer(String name, UUID id) {
        ProxiedPlayer player = mock(ProxiedPlayer.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.getDisplayName()).thenReturn(name);
        AtomicInteger count = new AtomicInteger();
        lineSendCounts.put(id, count);
        playersById.put(id, player);
        doAnswer(inv -> {
            count.incrementAndGet();
            senderThreads.add(Thread.currentThread());
            return null;
        }).when(player).sendMessage(any(BaseComponent[].class));
        doAnswer(inv -> {
            count.incrementAndGet();
            senderThreads.add(Thread.currentThread());
            return null;
        }).when(player).sendMessage(any(BaseComponent.class));
        return player;
    }

    /**
     * Runs {@code bodies[workerIndex]} on each of {@link #THREADS} pooled threads
     * behind a start latch, joins with a hard timeout (deadlock fails instead of
     * hanging CI), and fails fast if any handler threw.
     */
    private void runConcurrently(java.util.function.IntConsumer[] bodies) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final java.util.function.IntConsumer body = bodies[t];
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
        } catch (java.util.concurrent.ExecutionException e) {
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
        java.util.function.IntConsumer[] bodies = new java.util.function.IntConsumer[THREADS];
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

        // (b) No lost sends, no duplicated sends. Per-player expectations
        // (chat + item + private message + mention all reach player0):
        //     player0: chat + item + pm-echo + mention = 4 x DISPATCHES
        //     player1: chat + item + pm-received       = 3 x DISPATCHES
        //     others:  chat + item                     = 2 x DISPATCHES
        //     titles:  everyone on "global" receives   = DISPATCHES
        // The line counter sums BOTH sendMessage overloads (array and single).
        for (int i = 0; i < PLAYER_COUNT; i++) {
            UUID id = players.get(i).getUniqueId();
            int expectedLines = (i == 0) ? 4 * DISPATCHES : (i == 1) ? 3 * DISPATCHES : 2 * DISPATCHES;
            assertThat(lineSendCounts.get(id))
                    .as("chat-line sends to player%d (chat+item+pm[/mention])", i)
                    .hasValue(expectedLines);
            assertThat(titleSendCounts.get(id))
                    .as("title sends to player%d", i)
                    .hasValue(DISPATCHES);
        }

        // (a, continued) dispatch genuinely ran off this thread, on >= 2 workers.
        assertThat(senderThreads).doesNotContain(mainThread);
        assertThat(senderThreads.size()).as("distinct sending threads").isGreaterThanOrEqualTo(2);

        // (c) Listener-owned state stays consistent after the storm: no entry
        // lost or corrupted (a ConcurrentModificationException would already
        // have surfaced as a handler failure above).
        for (ProxiedPlayer player : players) {
            assertThat(listener.getState(player.getUniqueId()))
                    .as("state of %s survives the storm", player.getName())
                    .isNotNull();
            assertThat(listener.getState(player.getUniqueId()).getActiveChannel())
                    .isEqualTo(CHANNEL);
        }
    }

    @Test
    @DisplayName("welcome race: concurrent first-contact bursts deliver exactly one welcome per player")
    void concurrentWelcomeRacesDeliverExactlyOneWelcomePerPlayer() throws InterruptedException {
        // pushWelcomeIfFirst consults plugin.getChatListener() for the formatter;
        // wire that self-reference so the public entry point is drivable.
        when(plugin.getChatListener()).thenReturn(listener);

        List<ProxiedPlayer> fresh = new ArrayList<>();
        for (int i = 0; i < PLAYER_COUNT; i++) {
            fresh.add(newCountedLinePlayer("Fresh" + i,
                    UUID.fromString(String.format("0f%06d-bbbb-cccc-dddd-eeeeeeeeeeee", i))));
        }

        AtomicInteger welcomeSends = new AtomicInteger();
        for (ProxiedPlayer player : fresh) {
            doAnswer(inv -> {
                welcomeSends.incrementAndGet();
                senderThreads.add(Thread.currentThread());
                return null;
            }).when(player).sendMessage(org.mockito.ArgumentMatchers.any(BaseComponent[].class));
        }

        java.util.function.IntConsumer[] bodies = new java.util.function.IntConsumer[THREADS];
        for (int t = 0; t < THREADS; t++) {
            bodies[t] = who -> {
                for (int round = 0; round < 25 && handlerFailures.isEmpty(); round++) {
                    try {
                        listener.pushWelcomeIfFirst(fresh.get((who * 25 + round) % PLAYER_COUNT));
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
        assertThat(senderThreads).doesNotContain(mainThread);
    }

    @Test
    @DisplayName("churn: concurrent disconnect/reconnect cycles keep the state store consistent")
    void concurrentDisconnectReconnectChurnKeepsStoreConsistent() throws InterruptedException {
        // Phase A: every thread disconnects every player; the store must drain.
        runConcurrently(phaseBodies(who -> {
            for (ProxiedPlayer player : players) {
                PlayerDisconnectEvent event = mock(PlayerDisconnectEvent.class);
                when(event.getPlayer()).thenReturn(player);
                listener.onPlayerDisconnect(event);
            }
        }));
        for (ProxiedPlayer player : players) {
            assertThat(listener.getState(player.getUniqueId()))
                    .as("state of %s removed after disconnect phase", player.getName())
                    .isNull();
        }

        // Phase B: every thread reconnects every player; the store must repopulate
        // with the default channel and the reported server.
        runConcurrently(phaseBodies(who -> {
            for (ProxiedPlayer player : players) {
                ServerConnectedEvent event = mock(ServerConnectedEvent.class);
                when(event.getPlayer()).thenReturn(player);
                Server server = mock(Server.class);
                ServerInfo info = mock(ServerInfo.class);
                when(event.getServer()).thenReturn(server);
                when(server.getInfo()).thenReturn(info);
                when(info.getName()).thenReturn("lobby");
                listener.onServerConnected(event);
            }
        }));
        for (ProxiedPlayer player : players) {
            PlayerChannelState state = listener.getState(player.getUniqueId());
            assertThat(state).as("state of %s restored after reconnect phase", player.getName())
                    .isNotNull();
            assertThat(state.getActiveChannel()).isEqualTo(CHANNEL);
            assertThat(state.getCurrentServer()).isEqualTo("lobby");
        }

        // Phase C: title dispatch races disconnect/reconnect churn. Send counts
        // are legitimately nondeterministic here (a title aimed at a player whose
        // state is momentarily removed is correctly skipped), so the assertions
        // are zero handler exceptions plus a structurally valid store afterwards.
        java.util.function.IntConsumer[] bodies = new java.util.function.IntConsumer[THREADS];
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
                            for (ProxiedPlayer player : players) {
                                PlayerDisconnectEvent off = mock(PlayerDisconnectEvent.class);
                                when(off.getPlayer()).thenReturn(player);
                                listener.onPlayerDisconnect(off);
                                ServerConnectedEvent on = mock(ServerConnectedEvent.class);
                                when(on.getPlayer()).thenReturn(player);
                                Server server = mock(Server.class);
                                ServerInfo info = mock(ServerInfo.class);
                                when(on.getServer()).thenReturn(server);
                                when(server.getInfo()).thenReturn(info);
                                when(info.getName()).thenReturn("lobby");
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
        for (ProxiedPlayer player : players) {
            PlayerChannelState state = listener.getState(player.getUniqueId());
            if (state != null) {
                assertThat(state.getActiveChannel()).isEqualTo(CHANNEL);
            }
        }
    }

    /** Wraps per-worker bodies so {@link #runConcurrently} can index them. */
    private static java.util.function.IntConsumer[] phaseBodies(java.util.function.IntConsumer body) {
        java.util.function.IntConsumer[] bodies = new java.util.function.IntConsumer[THREADS];
        for (int t = 0; t < THREADS; t++) {
            bodies[t] = body;
        }
        return bodies;
    }
}
