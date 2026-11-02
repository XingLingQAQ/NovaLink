package com.nova.chat.proxy.chat;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.engine.ProxyServerSwitchCore;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import com.nova.chat.proxy.relay.BackendRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProxyChannelReconnectTest {
    private final UUID playerId = UUID.randomUUID();
    private final ProxyPlatform platform = mock(ProxyPlatform.class);
    private final ProxyConfigView config = mock(ProxyConfigView.class);
    private final ProxyPlayer player = mock(ProxyPlayer.class);
    private final RecordingClient client = new RecordingClient();
    private final ArrayDeque<Runnable> deadlines = new ArrayDeque<>();
    private ProxyChatCore core;
    private ProxyServerSwitchCore engine;

    @BeforeEach
    void setUp() {
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(true);
        when(platform.getPlayer(playerId)).thenReturn(player);
        when(platform.getPlatformType()).thenReturn(PlatformType.VELOCITY);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("RecoverJava");
        when(player.getCurrentServer()).thenReturn("primary");
        when(player.hasPermission("novachat.use")).thenReturn(true);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
                .when(platform).execute(any(Runnable.class));
        doAnswer(invocation -> { deadlines.add(invocation.getArgument(0)); return null; })
                .when(platform).runLater(any(Runnable.class), anyLong());
        core = new ProxyChatCore(platform, config, new IgnoreListService(), null,
                new KnownChannelRegistry(), () -> client);
        engine = new ProxyServerSwitchCore(platform, core);
    }

    @Test
    void reconnectRestoresConfirmedSelectionOnBothProxyAdapters() {
        for (PlatformType type : List.of(PlatformType.VELOCITY, PlatformType.BUNGEECORD)) {
            when(platform.getPlatformType()).thenReturn(type);
            selectAndDisconnect();
            core.onPlayerDisconnect(playerId);
            assertThat(core.getState(playerId)).isNull();
            assertThat(engine.onServerConnected(player, "primary")).isTrue();
            ChannelActionPacket request = lastJoin(client);
            assertThat(request.getChannelId()).isEqualTo("local");
            assertThat(request.getExtra("platform")).isEqualTo(type.name());
            assertThat(request.getExtra("server")).isEqualTo("primary");
            assertThat(core.getState(playerId).getActiveChannel()).isNull();
            assertThat(core.getState(playerId).getJoinedChannels()).isEmpty();
            respond(request, true);
            assertThat(core.getState(playerId).getActiveChannel()).isEqualTo("local");
            assertThat(core.getState(playerId).getJoinedChannels()).containsExactly("local");
        }
    }

    @Test
    void rejectionFallsBackThroughAnotherAuthoritativeJoin() {
        selectAndDisconnect();
        engine.onServerConnected(player, "primary");
        respond(lastJoin(client), false);
        ChannelActionPacket fallback = lastJoin(client);
        assertThat(fallback.getChannelId()).isEqualTo("global");
        assertThat(core.getState(playerId).getActiveChannel()).isNull();
        respond(fallback, true);
        assertThat(core.getState(playerId).getJoinedChannels()).containsExactly("global");
        assertThat(core.getState(playerId).getActiveChannel()).isEqualTo("global");
    }

    @Test
    void timeoutWithdrawsRequestAndLateSuccessCannotRestoreIt() {
        selectAndDisconnect();
        engine.onServerConnected(player, "primary");
        ChannelActionPacket original = lastJoin(client);
        deadlines.removeFirst().run();
        assertThat(client.wire).anySatisfy(packet -> {
            assertThat(packet.getAction()).isEqualTo(ChannelAction.LEAVE);
            assertThat(packet.getChannelId()).isEqualTo("local");
        });
        assertThat(client.tracker.consume(original.getRequestId())).isNull();
        respond(original, true);
        assertThat(core.getState(playerId).getActiveChannel()).isNull();
        respond(lastJoin(client), true);
        assertThat(core.getState(playerId).getActiveChannel()).isEqualTo("global");
    }

    @Test
    void disconnectedSessionCannotBeRevivedByItsResponseOrDeadline() {
        selectAndDisconnect();
        engine.onServerConnected(player, "primary");
        ChannelActionPacket original = lastJoin(client);
        core.onPlayerDisconnect(playerId);
        assertThat(client.wire).anySatisfy(packet -> {
            assertThat(packet.getAction()).isEqualTo(ChannelAction.LEAVE);
            assertThat(packet.getChannelId()).isEqualTo("local");
            assertThat(packet.getExtra("logout")).isEqualTo("true");
        });
        PlayerChannelState next = core.getOrCreateState(player);
        respond(original, true);
        deadlines.removeFirst().run();
        assertThat(core.getState(playerId)).isSameAs(next);
        assertThat(next.getActiveChannel()).isEqualTo("global");
        assertThat(next.getJoinedChannels()).doesNotContain("local");
    }

    @Test
    void backendAuthenticationSendsQueuedSelectionExactlyOnce() {
        selectAndDisconnect();
        client.authenticated = false;
        engine.onServerConnected(player, "primary");
        assertThat(client.wire).isEmpty();
        client.authenticated = true;
        doReturn(List.of(player)).when(platform).getOnlinePlayers();
        core.restoreOnlineMemberships(client);
        core.restoreOnlineMemberships(client);
        assertThat(client.wire).hasSize(1);
        assertThat(lastJoin(client).getChannelId()).isEqualTo("local");
    }

    @Test
    void serverChangeUsesNewLinkAndIgnoresOldConfirmation() {
        RecordingClient secondary = new RecordingClient();
        BackendRouter router = mock(BackendRouter.class);
        when(router.clientFor("primary")).thenReturn(client);
        when(router.clientFor("secondary")).thenReturn(secondary);
        core.setBackendRouter(router);
        selectAndDisconnect();
        engine.onServerConnected(player, "primary");
        ChannelActionPacket original = lastJoin(client);
        when(player.getCurrentServer()).thenReturn("secondary");
        engine.onServerConnected(player, "secondary");
        respond(original, true);
        assertThat(core.getState(playerId).getActiveChannel()).isNull();
        assertThat(lastJoin(secondary).getExtra("server")).isEqualTo("secondary");
        respond(lastJoin(secondary), true);
        assertThat(core.getState(playerId).getActiveChannel()).isEqualTo("local");
    }

    @Test
    void replacementBackendClientRetriesSelectionWithFreshCorrelation() {
        RecordingClient replacement = new RecordingClient();
        BackendRouter router = mock(BackendRouter.class);
        when(router.clientFor("primary")).thenReturn(client);
        core.setBackendRouter(router);
        selectAndDisconnect();
        engine.onServerConnected(player, "primary");
        ChannelActionPacket original = lastJoin(client);
        when(router.clientFor("primary")).thenReturn(replacement);
        doReturn(List.of(player)).when(platform).getOnlinePlayers();
        core.restoreOnlineMemberships(replacement);
        ChannelActionPacket retry = lastJoin(replacement);
        assertThat(retry.getChannelId()).isEqualTo("local");
        assertThat(retry.getRequestId()).isNotEqualTo(original.getRequestId());
        respond(original, true);
        assertThat(core.getState(playerId).getActiveChannel()).isNull();
        respond(retry, true);
        assertThat(core.getState(playerId).getActiveChannel()).isEqualTo("local");
    }

    @Test
    void manualChannelChoiceWinsWhileRestoreIsPending() {
        selectAndDisconnect();
        engine.onServerConnected(player, "primary");
        ChannelActionPacket request = lastJoin(client);
        core.getState(playerId).setActiveChannel("another");
        respond(request, true);
        assertThat(core.getState(playerId).getActiveChannel()).isEqualTo("another");
        assertThat(core.getState(playerId).getJoinedChannels()).contains("local", "another");
    }

    @Test
    void revokedPermissionAndExpiredChoiceUseDefault() {
        selectAndDisconnect();
        when(player.hasPermission("novachat.use")).thenReturn(false);
        engine.onServerConnected(player, "primary");
        assertThat(lastJoin(client).getChannelId()).isEqualTo("global");

        AtomicLong clock = new AtomicLong();
        ProxyPlayerStateStore states = new ProxyPlayerStateStore(config);
        ProxyChannelRecovery recovery = new ProxyChannelRecovery(platform, states, server -> client, clock::get);
        PlayerChannelState old = states.getOrCreateState(playerId);
        old.setActiveChannel("local");
        recovery.disconnected(playerId, old);
        states.removeState(playerId);
        clock.set(ProxyChannelRecovery.RETENTION_NANOS);
        when(player.hasPermission("novachat.use")).thenReturn(true);
        assertThat(recovery.connected(player, states.getOrCreateState(playerId), "primary")).isFalse();
    }

    private void selectAndDisconnect() {
        core.recordServer(player, "primary");
        core.getState(playerId).setActiveChannel("local");
        core.onPlayerDisconnect(playerId);
        client.wire.clear();
    }

    private ChannelActionPacket lastJoin(RecordingClient connection) {
        return connection.wire.stream().filter(packet -> packet.getAction() == ChannelAction.JOIN)
                .reduce((first, second) -> second).orElseThrow();
    }

    private void respond(ChannelActionPacket request, boolean success) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(success, ChannelAction.JOIN,
                request.getChannelId(), success ? "" : "NC-403", "test response");
        response.setRequestId(request.getRequestId());
        core.handleChannelActionResponse(response);
    }

    private static final class RecordingClient extends AbstractPlatformNetworkClient {
        final List<ChannelActionPacket> wire = new ArrayList<>();
        final ChannelResponseTracker tracker = new ChannelResponseTracker();
        boolean authenticated = true;

        @Override public boolean isAuthenticated() { return authenticated; }
        @Override public ChannelResponseTracker getChannelResponseTracker() { return tracker; }
        @Override public void sendPacket(Packet packet) {
            if (packet instanceof ChannelActionPacket action) {
                tracker.track(action);
                wire.add(action);
            }
        }
    }
}
