package com.nova.chat.bukkit.chat;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * A relay deployment can deliver the same chat line to a sub-server twice: the
 * proxy relays it over plugin messaging, and the server's own backend link
 * receives it directly. The backend fans one {@link ChatMessagePacket} instance
 * out to every authenticated connection and NovaProtocol writes its requestId
 * into every frame, so both copies share a requestId.
 *
 * <p>The two copies are <em>not</em> interchangeable: the plugin-messaging copy
 * carries the proxy's "already rendered" list and the backend copy carries
 * none, and which one arrives first is a race between the Netty event loop and
 * the server main thread. Suppression is therefore per recipient, not per
 * packet — these tests pin that, including the order independence that a
 * packet-level drop got wrong (it kept whichever copy won the race, so a
 * proxied player still saw the line twice in roughly half of all runs).
 */
@DisplayName("Bukkit ChatInterceptor inbound duplicate suppression")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorInboundDedupTest {

    private static final UUID SENDER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID PROXIED_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID DIRECT_ID = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb");

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private Server server;
    @Mock
    private BukkitScheduler scheduler;
    @Mock
    private PluginManager pluginManager;
    @Mock
    private Player proxied;
    @Mock
    private Player direct;
    @Mock
    private org.bukkit.World world;

    /** Render tasks queued by the interceptor; drained explicitly per test. */
    private final Deque<Runnable> mainTasks = new ArrayDeque<>();
    private ChatInterceptor interceptor;

    @BeforeEach
    void setUp() {
        mainTasks.clear();

        KnownChannelRegistry registry = new KnownChannelRegistry();
        registry.replaceAll(Set.of("global"));

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getKnownChannelRegistry()).thenReturn(registry);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.isPrimaryThread()).thenReturn(false);
        when(config.isReplaceVanilla()).thenReturn(false);
        when(config.getDefaultChannel()).thenReturn("global");

        when(proxied.getUniqueId()).thenReturn(PROXIED_ID);
        when(proxied.getName()).thenReturn("Proxied");
        when(proxied.getDisplayName()).thenReturn("Proxied");
        when(proxied.getWorld()).thenReturn(world);
        when(direct.getUniqueId()).thenReturn(DIRECT_ID);
        when(direct.getName()).thenReturn("Direct");
        when(direct.getDisplayName()).thenReturn("Direct");
        when(direct.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");

        doAnswer(invocation -> {
            mainTasks.add(invocation.getArgument(1));
            return null;
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));

        interceptor = new ChatInterceptor(plugin);
        // The render loop only serves players whose active channel matches, so
        // the fixtures must look like members of "global".
        interceptor.getOrCreateState(proxied).setActiveChannel("global");
        interceptor.getOrCreateState(direct).setActiveChannel("global");
    }

    @Test
    @DisplayName("plugin-messaging copy first: the proxied player is skipped there and once overall")
    void proxyCopyFirstServesEachPlayerOnce() {
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(proxied, direct));

        ChatMessagePacket proxyCopy = chat("line", null);
        ChatMessagePacket backendCopy = chat("line", proxyCopy.getRequestId());

        // The proxy already rendered it to the proxied player.
        interceptor.handleIncomingMessage(proxyCopy, Set.of(PROXIED_ID));
        // The backend copy arrives second and must not re-serve that player.
        interceptor.handleIncomingMessage(backendCopy, null);

        drain();
        org.mockito.Mockito.verify(direct, org.mockito.Mockito.times(1))
                .sendMessage(any(String.class));
        org.mockito.Mockito.verify(proxied, org.mockito.Mockito.never())
                .sendMessage(any(String.class));
    }

    @Test
    @DisplayName("backend copy first, proxy roster already known: each player still sees it once")
    void backendCopyFirstServesEachPlayerOnceOnceRosterIsKnown() {
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(proxied, direct));

        // An earlier line taught the interceptor which players the proxy owns;
        // every downstream payload repeats that list.
        interceptor.handleIncomingMessage(
                chat("earlier", null), Set.of(PROXIED_ID));
        drain();
        org.mockito.Mockito.clearInvocations(proxied, direct);

        ChatMessagePacket backendCopy = chat("line", null);
        ChatMessagePacket proxyCopy = chat("line", backendCopy.getRequestId());

        // The backend copy carries no exclusions, but the known roster covers
        // the proxied player, so only the direct player is served here.
        interceptor.handleIncomingMessage(backendCopy, null);
        // The proxy copy arrives second and is a duplicate for both.
        interceptor.handleIncomingMessage(proxyCopy, Set.of(PROXIED_ID));
        drain();

        org.mockito.Mockito.verify(proxied, org.mockito.Mockito.never())
                .sendMessage(any(String.class));
        org.mockito.Mockito.verify(direct, org.mockito.Mockito.times(1))
                .sendMessage(any(String.class));
    }

    @Test
    @DisplayName("cold start: both copies handed over before rendering still serve each player once")
    void coldStartServesEachPlayerOnce() {
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(proxied, direct));

        ChatMessagePacket backendCopy = chat("line", null);
        ChatMessagePacket proxyCopy = chat("line", backendCopy.getRequestId());

        // Both copies are handed over before either render hop runs, so the
        // exclusion the proxy reports is known by the time the backend copy
        // renders. That is the realistic timing: the two arrivals are
        // milliseconds apart while the render hop is queued behind them.
        interceptor.handleIncomingMessage(backendCopy, null);
        interceptor.handleIncomingMessage(proxyCopy, Set.of(PROXIED_ID));
        drain();

        // The proxied player was served by the proxy itself, never here; the
        // direct player is served exactly once even though both copies arrived.
        org.mockito.Mockito.verify(proxied, org.mockito.Mockito.never())
                .sendMessage(any(String.class));
        org.mockito.Mockito.verify(direct, org.mockito.Mockito.times(1))
                .sendMessage(any(String.class));
    }

    @Test
    @DisplayName("a genuinely repeated line with a new requestId renders twice")
    void repeatedContentWithDistinctRequestIdsRendersTwice() {
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(direct));

        interceptor.handleIncomingMessage(chat("gg", null), null);
        interceptor.handleIncomingMessage(chat("gg", null), null);

        drain();
        org.mockito.Mockito.verify(direct, org.mockito.Mockito.times(2))
                .sendMessage(any(String.class));
    }

    @Test
    @DisplayName("packets with no usable requestId are never collapsed")
    void anonymousPacketsAreNeverCollapsed() {
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(direct));

        UUID zero = new UUID(0L, 0L);
        interceptor.handleIncomingMessage(chat("no id", zero), null);
        interceptor.handleIncomingMessage(chat("no id", zero), null);

        drain();
        org.mockito.Mockito.verify(direct, org.mockito.Mockito.times(2))
                .sendMessage(any(String.class));
    }

    @Test
    @DisplayName("the remembered window stays bounded")
    void rememberedLinesStayBounded() {
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(direct));

        ChatMessagePacket veryFirst = chat("line 0", null);
        interceptor.handleIncomingMessage(veryFirst, null);
        int overflow = ChatInterceptor.RECENT_INBOUND_LIMIT + 25;
        for (int i = 1; i < overflow; i++) {
            interceptor.handleIncomingMessage(chat("line " + i, null), null);
        }
        drain();

        // The very first line was evicted, so a late second copy of it is served
        // again rather than accumulating state forever. That re-serve is the
        // observable proof the map stayed bounded instead of remembering all.
        interceptor.handleIncomingMessage(chat("line 0", veryFirst.getRequestId()), null);
        drain();
        org.mockito.Mockito.verify(direct, org.mockito.Mockito.times(overflow + 1))
                .sendMessage(any(String.class));
    }

    /** Runs the queued render hops, which is where recipient claims happen. */
    private void drain() {
        while (!mainTasks.isEmpty()) {
            mainTasks.poll().run();
        }
    }

    private static ChatMessagePacket chat(String content, UUID requestId) {
        ChatMessagePacket packet = requestId == null
                ? new ChatMessagePacket(SENDER_ID, "Alice", "survival-01", "global", content)
                : new ChatMessagePacket(requestId);
        if (requestId != null) {
            packet.setSenderId(SENDER_ID);
            packet.setSenderName("Alice");
            packet.setClientId("survival-01");
            packet.setChannelId("global");
            packet.setContent(content);
        }
        packet.addPlaceholder("channel_name", "Global");
        return packet;
    }
}
