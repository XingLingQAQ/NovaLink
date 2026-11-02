package com.nova.chat.pnx.chat;

import org.powernukkitx.Player;
import org.powernukkitx.Server;
import org.powernukkitx.level.Level;
import org.powernukkitx.scheduler.ServerScheduler;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.config.NovaChatConfig;
import com.nova.chat.pnx.network.NetworkClient;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ChannelReconnectTest {
    private final UUID playerId = UUID.randomUUID();
    private final NovaChatPNX plugin = mock(NovaChatPNX.class);
    private final NovaChatConfig config = mock(NovaChatConfig.class);
    private final NetworkClient client = mock(NetworkClient.class);
    private final Server server = mock(Server.class);
    private final ServerScheduler scheduler = mock(ServerScheduler.class);
    private final Player player = mock(Player.class);
    private final Level level = mock(Level.class);
    private final ChannelResponseTracker tracker = new ChannelResponseTracker();
    private final ChannelResponseDispatcher.ChannelResponseAdapter adapter =
            mock(ChannelResponseDispatcher.ChannelResponseAdapter.class);
    private final ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);
    private final Map<UUID, Player> online = new HashMap<>();
    private final List<ChannelActionPacket> wire = new ArrayList<>();
    private final ArrayDeque<Runnable> deadlines = new ArrayDeque<>();
    private boolean allowUse = true;
    private boolean allowChannel = true;
    private ChatInterceptor interceptor;

    @BeforeEach
    void setUp() {
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(config.getDefaultChannel()).thenReturn("global");
        when(plugin.getNetworkClient()).thenReturn(client);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getOnlinePlayers()).thenReturn(online);
        when(server.getPlayer(playerId)).thenAnswer(invocation -> Optional.ofNullable(online.get(playerId)));
        when(client.isAuthenticated()).thenReturn(true);
        when(client.getChannelResponseTracker()).thenReturn(tracker);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("RecoverPlayer");
        when(player.getLevel()).thenReturn(level);
        when(level.getName()).thenReturn("world");
        when(player.hasPermission(anyString())).thenAnswer(invocation -> {
            String permission = invocation.getArgument(0);
            return permission.equals("novachat.use") ? allowUse
                    : permission.equals("novachat.channel.global")
                    || permission.equals("novachat.channel.local") && allowChannel;
        });
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(scheduler).scheduleTask(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            deadlines.add(invocation.getArgument(1));
            return null;
        }).when(scheduler).scheduleDelayedTask(eq(plugin), any(Runnable.class), anyInt());
        doAnswer(invocation -> {
            Packet packet = invocation.getArgument(0);
            if (packet instanceof ChannelActionPacket action) {
                wire.add(action);
                tracker.track(action);
            }
            return null;
        }).when(client).sendPacket(any(Packet.class));
        doAnswer(invocation -> {
            interceptor.getState(invocation.getArgument(0)).setActiveChannel(invocation.getArgument(1));
            return null;
        }).when(adapter).setActiveChannel(any(UUID.class), anyString());
        online.put(playerId, player);
        interceptor = new ChatInterceptor(plugin);
    }

    @Test
    void restoresSelectionOnlyAfterFreshJoinSuccess() throws Exception {
        disconnectWithLocalSelection();
        assertThat(interceptor.getState(playerId)).isNull();
        assertThat(wire).extracting(ChannelActionPacket::getChannelId).contains("local", "global");
        assertThat(wire).allSatisfy(packet -> {
            assertThat(packet.getAction()).isEqualTo(ChannelAction.LEAVE);
            assertThat(packet.getExtra("logout")).isEqualTo("true");
        });

        reconnect();
        ChannelActionPacket request = joinRequests().get(0);
        assertThat(request.getChannelId()).isEqualTo("local");
        assertThat(request.getExtra("world")).isEqualTo("world");
        assertThat(request.getExtra("playerId")).isEqualTo(playerId.toString());
        assertThat(interceptor.getState(playerId).getActiveChannel()).isEqualTo("global");
        assertThat(interceptor.getState(playerId).getJoinedChannels()).doesNotContain("local");

        respond(request, true, "");
        assertThat(interceptor.getState(playerId).getActiveChannel()).isEqualTo("local");
        assertThat(interceptor.getState(playerId).getJoinedChannels()).contains("local");
    }

    @Test
    void backendRejectionFallsBackWithoutRestoringMembership() throws Exception {
        disconnectWithLocalSelection();
        reconnect();
        respond(joinRequests().get(0), false, "NC-403");
        assertThat(interceptor.getState(playerId).getActiveChannel()).isEqualTo("global");
        assertThat(interceptor.getState(playerId).getJoinedChannels()).doesNotContain("local");
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("local", "global");
        respond(joinRequests().get(1), true, "");
        assertThat(interceptor.getState(playerId).getActiveChannel()).isEqualTo("global");
    }

    @Test
    void revokedUsePermissionDoesNotRejoinSelection() {
        disconnectWithLocalSelection();
        allowUse = false;
        reconnect();
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("global");
        assertThat(interceptor.getState(playerId).getJoinedChannels()).doesNotContain("local");
    }

    @Test
    void rechecksPnxChannelPermissionBeforeJoining() {
        disconnectWithLocalSelection();
        allowChannel = false;
        reconnect();
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("global");
        assertThat(interceptor.getState(playerId).getJoinedChannels()).doesNotContain("local");
    }

    @Test
    void timeoutWithdrawsUnconfirmedJoinAndIgnoresLateSuccess() throws Exception {
        disconnectWithLocalSelection();
        reconnect();
        ChannelActionPacket request = joinRequests().get(0);
        deadlines.removeFirst().run();
        assertThat(wire).anySatisfy(packet -> {
            assertThat(packet.getAction()).isEqualTo(ChannelAction.LEAVE);
            assertThat(packet.getChannelId()).isEqualTo("local");
            assertThat(packet.getExtra("logout")).isNull();
        });
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("local", "global");
        assertThat(tracker.consume(request.getRequestId())).isNull();
        respond(request, true, "");
        assertThat(interceptor.getState(playerId).getActiveChannel()).isEqualTo("global");
        assertThat(interceptor.getState(playerId).getJoinedChannels()).doesNotContain("local");
    }

    @Test
    void disconnectCancelsPendingRestoreAndCannotReviveNextSession() throws Exception {
        disconnectWithLocalSelection();
        reconnect();
        ChannelActionPacket request = joinRequests().get(0);
        disconnect();
        assertThat(interceptor.getState(playerId)).isNull();
        assertThat(tracker.consume(request.getRequestId())).isNull();
        assertThat(wire).anySatisfy(packet -> {
            assertThat(packet.getAction()).isEqualTo(ChannelAction.LEAVE);
            assertThat(packet.getChannelId()).isEqualTo("local");
            assertThat(packet.getExtra("logout")).isEqualTo("true");
        });
        online.put(playerId, player);
        interceptor.getOrCreateState(player);
        respond(request, true, "");
        deadlines.removeFirst().run();
        assertThat(interceptor.getState(playerId).getActiveChannel()).isEqualTo("global");
        assertThat(interceptor.getState(playerId).getJoinedChannels()).doesNotContain("local");
    }

    @Test
    void newerSelectionWinsAndConfirmedMembershipStillGetsLogoutCleanup() throws Exception {
        disconnectWithLocalSelection();
        reconnect();
        ChannelActionPacket request = joinRequests().get(0);
        interceptor.getState(playerId).setActiveChannel("other");
        respond(request, true, "");
        assertThat(interceptor.getState(playerId).getActiveChannel()).isEqualTo("other");
        wire.clear();
        disconnect();
        assertThat(wire).anySatisfy(packet -> {
            assertThat(packet.getAction()).isEqualTo(ChannelAction.LEAVE);
            assertThat(packet.getChannelId()).isEqualTo("local");
            assertThat(packet.getExtra("logout")).isEqualTo("true");
        });
    }

    @Test
    void waitsForBackendAuthenticationWithoutConsumingSelection() {
        disconnectWithLocalSelection();
        when(client.isAuthenticated()).thenReturn(false);
        reconnect();
        assertThat(joinRequests()).isEmpty();
        when(client.isAuthenticated()).thenReturn(true);
        interceptor.restorePlayerChannels(player, false);
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("local");
    }

    @Test
    void newAuthenticationRetriesPendingSelectionWithANewRequest() throws Exception {
        disconnectWithLocalSelection();
        reconnect();
        ChannelActionPacket old = joinRequests().get(0);
        interceptor.restorePlayerChannels(player, false);
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("local", "local");
        assertThat(tracker.consume(old.getRequestId())).isNull();
        respond(old, true, "");
        assertThat(interceptor.getState(playerId).getActiveChannel()).isEqualTo("global");
        respond(joinRequests().get(1), true, "");
        assertThat(interceptor.getState(playerId).getActiveChannel()).isEqualTo("local");
    }

    @Test
    void backendAuthenticationCannotRecreateQuitPlayer() {
        disconnectWithLocalSelection();
        wire.clear();
        interceptor.restorePlayerChannels(player, false);
        assertThat(interceptor.getState(playerId)).isNull();
        assertThat(wire).isEmpty();
        reconnect();
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("local");
    }

    @Test
    void reauthenticationWaitsForReversedMembershipRepliesBeforeSelecting() throws Exception {
        var state = interceptor.getOrCreateState(player);
        state.setActiveChannel("local");
        state.joinChannel("trade");
        when(player.hasPermission("novachat.channel.trade")).thenReturn(true);

        interceptor.restorePlayerChannels(player, false);
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("global", "trade");
        ChannelActionPacket global = joinRequests().get(0);
        ChannelActionPacket trade = joinRequests().get(1);
        respond(trade, true, "");
        assertThat(state.getActiveChannel()).isEqualTo("local");
        assertThat(joinRequests()).hasSize(2);
        respond(global, true, "");
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("global", "trade", "local");
        respond(joinRequests().get(2), true, "");

        assertThat(state.getActiveChannel()).isEqualTo("local");
        assertThat(state.getJoinedChannels()).containsExactlyInAnyOrder("global", "trade", "local");
        assertThat(tracker.size()).isZero();
        assertThat(joinRequests()).allSatisfy(request -> {
            assertThat(request.getExtra("playerId")).isEqualTo(playerId.toString());
            assertThat(request.getExtra("playerName")).isEqualTo("RecoverPlayer");
            assertThat(request.getExtra("platform")).isEqualTo("POWERNUKKITX");
            assertThat(request.getExtra("world")).isEqualTo("world");
        });
    }

    @Test
    void reauthenticationRejectionDropsMembershipAndConfirmsDefault() throws Exception {
        var state = interceptor.getOrCreateState(player);
        state.setActiveChannel("local");
        interceptor.restorePlayerChannels(player, false);
        respond(joinRequests().get(0), true, "");
        respond(joinRequests().get(1), false, "NC-403");

        assertThat(state.getJoinedChannels()).containsExactly("global");
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("global", "local", "global");
        respond(joinRequests().get(2), true, "");
        assertThat(state.getActiveChannel()).isEqualTo("global");
    }

    @Test
    void reauthenticationTimeoutDropsSelectionAndIgnoresLateReply() throws Exception {
        var state = interceptor.getOrCreateState(player);
        state.setActiveChannel("local");
        interceptor.restorePlayerChannels(player, false);
        respond(joinRequests().get(0), true, "");
        ChannelActionPacket selected = joinRequests().get(1);
        deadlines.removeFirst().run();
        deadlines.removeFirst().run();

        respond(selected, true, "");
        respond(joinRequests().get(2), true, "");
        assertThat(state.getActiveChannel()).isEqualTo("global");
        assertThat(state.getJoinedChannels()).containsExactly("global");
    }

    @Test
    void manualSelectionDuringMembershipRestoreKeepsAllMemberships() throws Exception {
        var state = interceptor.getOrCreateState(player);
        state.setActiveChannel("local");
        when(player.hasPermission("novachat.channel.other")).thenReturn(true);
        interceptor.restorePlayerChannels(player, false);
        state.setActiveChannel("other");
        respond(joinRequests().get(0), true, "");
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("global", "local");
        respond(joinRequests().get(1), true, "");
        assertThat(state.getActiveChannel()).isEqualTo("other");
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("global", "local", "other");
        respond(joinRequests().get(2), true, "");
        assertThat(state.getActiveChannel()).isEqualTo("other");
        assertThat(state.getJoinedChannels()).containsExactlyInAnyOrder("global", "local", "other");
    }

    @Test
    void manualSelectionDuringFinalJoinIsAlsoConfirmedOnBackend() throws Exception {
        var state = interceptor.getOrCreateState(player);
        state.setActiveChannel("local");
        when(player.hasPermission("novachat.channel.other")).thenReturn(true);
        interceptor.restorePlayerChannels(player, false);
        respond(joinRequests().get(0), true, "");
        state.setActiveChannel("other");
        respond(joinRequests().get(1), true, "");

        assertThat(state.getActiveChannel()).isEqualTo("other");
        assertThat(joinRequests()).extracting(ChannelActionPacket::getChannelId).containsExactly("global", "local", "other");
        respond(joinRequests().get(2), true, "");
        assertThat(state.getActiveChannel()).isEqualTo("other");
    }

    @Test
    void reauthenticationRepliesCannotSelectForANewSession() throws Exception {
        interceptor.getOrCreateState(player).setActiveChannel("local");
        interceptor.restorePlayerChannels(player, false);
        respond(joinRequests().get(0), true, "");
        ChannelActionPacket selected = joinRequests().get(1);
        disconnect();
        online.put(playerId, player);
        var replacement = interceptor.getOrCreateState(player);

        respond(selected, true, "");
        deadlines.forEach(Runnable::run);
        assertThat(replacement.getActiveChannel()).isEqualTo("global");
        assertThat(replacement.getJoinedChannels()).containsExactly("global");
    }

    @Test
    void selectionExpiresAndIsConsumedOnlyOnce() {
        AtomicLong now = new AtomicLong();
        RecentChannelSelections selections = new RecentChannelSelections(now::get);
        selections.remember(playerId, "local");
        assertThat(selections.take(playerId)).isEqualTo("local");
        assertThat(selections.take(playerId)).isNull();
        selections.remember(playerId, "local");
        now.set(RecentChannelSelections.TTL_NANOS);
        assertThat(selections.take(playerId)).isNull();
    }

    @Test
    void selectionCacheEvictsOldestAtItsFixedCapacity() {
        RecentChannelSelections selections = new RecentChannelSelections(() -> 0);
        selections.remember(playerId, "local");
        UUID newest = null;
        for (int i = 0; i < RecentChannelSelections.MAX_ENTRIES; i++) {
            newest = UUID.randomUUID();
            selections.remember(newest, "local");
        }
        assertThat(selections.take(playerId)).isNull();
        assertThat(selections.take(newest)).isEqualTo("local");
    }

    private void disconnectWithLocalSelection() {
        interceptor.getOrCreateState(player).setActiveChannel("local");
        disconnect();
    }

    private void disconnect() {
        online.remove(playerId);
        interceptor.removeState(player);
    }

    private void reconnect() {
        online.put(playerId, player);
        wire.clear();
        interceptor.getOrCreateState(player);
        interceptor.restorePlayerChannels(player, true);
    }

    private List<ChannelActionPacket> joinRequests() {
        return wire.stream().filter(packet -> packet.getAction() == ChannelAction.JOIN).toList();
    }

    private void respond(ChannelActionPacket request, boolean success, String errorCode) throws Exception {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                success, ChannelAction.JOIN, request.getChannelId(), errorCode, "");
        response.setRequestId(request.getRequestId());
        if (!interceptor.handleChannelRestoreResponse(response)) {
            dispatcher.handle(response);
        }
    }
}
