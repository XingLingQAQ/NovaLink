package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.link.api.WebhookManager;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PlayerDepartureOwnershipTest {
    @ParameterizedTest
    @EnumSource(value = ChannelScope.class, names = {"GLOBAL", "PRIVATE"})
    void anOldClientCannotRemoveTheNewClientsMembershipOrClaimItsPlayer(ChannelScope scope) throws Exception {
        var db = new MemoryProvider();
        db.initialize();
        var channels = new ChannelManager();
        var players = new PlayerStateManager(db);
        var permissions = new PermissionManager();
        var handler = new ChannelActionHandler(channels, players, db, new PrivateChannelManager(channels),
                new InvitationManager(db, channels), permissions);
        var broadcaster = mock(Runnable.class);
        var webhooks = mock(WebhookManager.class);
        handler.setPlayerUpdateBroadcaster(broadcaster);
        handler.setWebhookManager(webhooks);
        var channel = channels.createChannel(ChannelConfig.builder().id("target").scope(scope)
                .clientId(scope == ChannelScope.PRIVATE ? "old" : null).build());
        UUID player = UUID.randomUUID();
        var current = players.getOrCreateState(player, "Player");
        current.setClientId("new");
        current.setPlatform("VELOCITY");
        players.joinChannel(player, channel.getId());
        players.setActiveChannel(player, channel.getId());
        channel.addMember(player);
        assertThat(players.saveState(current)).isTrue();
        var leave = packet(ChannelAction.LEAVE, player);
        leave.addExtra("platform", "BUKKIT");
        try {
            var rejected = handler.handle(connection("old"), leave);

            assertThat(rejected.isSuccess()).isFalse();
            assertThat(rejected.getErrorCode()).isEqualTo("NC-403");
            assertThat(current.getClientId()).isEqualTo("new");
            assertThat(current.getPlatform()).isEqualTo("VELOCITY");
            assertThat(current.getActiveChannel()).isEqualTo("target");
            assertThat(current.getJoinedChannels()).containsExactly("target");
            assertThat(channel.isMember(player)).isTrue();
            assertThat(players.getDirtyCount()).isZero();
            verifyNoInteractions(broadcaster, webhooks);

            assertThat(handler.handle(connection("new"), leave).isSuccess()).isTrue();
            assertThat(channel.isMember(player)).isFalse();
            assertThat(current.getJoinedChannels()).isEmpty();
            assertThat(current.getActiveChannel()).isNull();
            assertThat(current.getClientId()).isEqualTo("new");
            verify(broadcaster).run();
            verify(webhooks).triggerWebhook(eq("player.leave"), any());
        } finally {
            db.shutdown();
        }
    }

    @Test
    void aTransferBeforeTheLeaveBoundaryPreventsTheOldLeaveFromChangingThePlayer() throws Exception {
        var db = new MemoryProvider();
        db.initialize();
        var channels = new ChannelManager();
        var players = new PlayerStateManager(db);
        var permissions = new PermissionManager();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var armed = new AtomicBoolean();
        var bans = new BanManager(db, permissions, channels, players) {
            @Override
            public <T> T withAdmission(UUID id, Supplier<T> operation) {
                if (armed.compareAndSet(true, false)) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Departure gate timed out");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                }
                return super.withAdmission(id, operation);
            }
        };
        var handler = new ChannelActionHandler(channels, players, db, new PrivateChannelManager(channels),
                new InvitationManager(db, channels), permissions, null, bans);
        var channel = channels.createChannel(ChannelConfig.builder().id("target").scope(ChannelScope.GLOBAL).build());
        UUID player = UUID.randomUUID();
        var old = connection("old");
        assertThat(handler.handle(old, packet(ChannelAction.JOIN, player)).isSuccess()).isTrue();
        var result = new FutureTask<>(() -> handler.handle(old, packet(ChannelAction.LEAVE, player)));
        var departing = new Thread(result, "player-leave");
        armed.set(true);
        try {
            departing.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(handler.handle(connection("new"), packet(ChannelAction.JOIN, player)).isSuccess()).isTrue();
            release.countDown();
            var response = result.get(5, TimeUnit.SECONDS);
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-403");
            assertThat(players.getPlayerState(player).getClientId()).isEqualTo("new");
            assertThat(players.getPlayerState(player).getActiveChannel()).isEqualTo("target");
            assertThat(channel.isMember(player)).isTrue();
        } finally {
            release.countDown();
            departing.join(5_000);
            if (departing.isAlive()) {
                departing.interrupt();
                departing.join(5_000);
            }
            db.shutdown();
            assertThat(departing.isAlive()).isFalse();
        }
    }

    private static ClientConnection connection(String id) {
        var connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn(id);
        return connection;
    }

    private static ChannelActionPacket packet(ChannelAction action, UUID player) {
        var packet = new ChannelActionPacket(action, "target");
        packet.addExtra("playerId", player.toString());
        packet.addExtra("playerName", "Player");
        return packet;
    }
}
