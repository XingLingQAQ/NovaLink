package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.ChannelStateCleanup;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelAdmissionLifecycleTest {
    private static final String ID = "reused";

    @Test
    void anObservedGlobalChannelCannotAuthorizeItsPrivateReplacement() throws Exception {
        var db = new MemoryProvider();
        db.initialize();
        var channels = new ChannelManager() {
            boolean replace;
            @Override public Channel getChannel(String id) {
                Channel observed = super.getChannel(id);
                if (replace && ID.equals(id)) {
                    replace = false;
                    deleteChannel(id);
                    createChannel(privateConfig().build());
                }
                return observed;
            }
        };
        var players = new PlayerStateManager(db);
        var permissions = new PermissionManager();
        var handler = new ChannelActionHandler(channels, players, db, new PrivateChannelManager(channels),
                new InvitationManager(db, channels), permissions);
        UUID player = UUID.randomUUID();
        channels.createChannel(ChannelConfig.builder().id(ID).scope(ChannelScope.GLOBAL).build());
        channels.replace = true;
        try {
            var response = handler.handle(connection(), joinPacket(player));
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-404");
            assertThat(channels.getChannel(ID).isMember(player)).isFalse();
            assertThat(players.getPlayerState(player)).isNull();
        } finally {
            db.shutdown();
        }
    }

    @ParameterizedTest(name = "private manager directly={0}")
    @ValueSource(booleans = {false, true})
    void deletionCannotBeFollowedByLateMembershipOrPlayerState(boolean directPrivate) throws Exception {
        var db = new MemoryProvider();
        db.initialize();
        var channels = new ChannelManager();
        var players = new PlayerStateManager(db);
        channels.setDeletionCleanup(channel -> ChannelStateCleanup.clearMemberships(channel, players));
        Channel retired = channels.createChannel(directPrivate ? privateConfig().build()
                : ChannelConfig.builder().id(ID).scope(ChannelScope.GLOBAL).build());
        var permissions = new PermissionManager();
        UUID player = UUID.randomUUID();
        var checked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var privateChannels = new PrivateChannelManager(channels) {
            @Override public PrivateChannelAccessResult validateAccess(String id, String client, String password) {
                var result = super.validateAccess(id, client, password);
                pause(checked, release);
                return result;
            }
        };
        var bans = new BanManager(db, permissions, channels) {
            @Override public boolean isBanned(UUID id, String channelId) {
                boolean result = super.isBanned(id, channelId);
                pause(checked, release);
                return result;
            }
        };
        var handler = new ChannelActionHandler(channels, players, db, privateChannels,
                new InvitationManager(db, channels), permissions, null, bans);
        ClientConnection connection = connection();
        var joining = new FutureTask<>(() -> directPrivate
                ? privateChannels.joinPrivateChannel(ID, player, "primary", "old-password").isGranted()
                : handler.handle(connection, joinPacket(player)).isSuccess());
        var replacing = new FutureTask<>(() -> {
            assertThat(channels.deleteChannel(ID)).isTrue();
            return channels.createChannel(privateConfig().password("new-password").build());
        });
        Thread joiner = new Thread(joining, "channel-join-test");
        Thread replacer = new Thread(replacing, "channel-replace-test");
        try {
            joiner.start();
            assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
            replacer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waitingForJoin = false;
            var threads = ManagementFactory.getThreadMXBean();
            while (!replacing.isDone() && System.nanoTime() < deadline) {
                var state = threads.getThreadInfo(replacer.getId());
                if (state != null && state.getThreadState() == Thread.State.BLOCKED
                        && state.getLockOwnerId() == joiner.getId()) {
                    waitingForJoin = true;
                    break;
                }
                Thread.yield();
            }
            assertThat(replacing.isDone() || waitingForJoin).isTrue();
            release.countDown();
            joining.get(5, TimeUnit.SECONDS);
            Channel replacement = replacing.get(5, TimeUnit.SECONDS);
            assertThat(replacement.isMember(player)).isFalse();
            assertThat(retired.isMember(player)).isFalse();
            var state = players.getPlayerState(player);
            if (state != null) {
                assertThat(state.hasJoinedChannel(ID)).isFalse();
                assertThat(state.getActiveChannel()).isNull();
            }
        } finally {
            release.countDown();
            joiner.join(5_000);
            replacer.join(5_000);
            if (joiner.isAlive()) joiner.interrupt();
            if (replacer.isAlive()) replacer.interrupt();
            joiner.join(5_000);
            replacer.join(5_000);
            db.shutdown();
            assertThat(joiner.isAlive() || replacer.isAlive()).isFalse();
        }
    }

    private static ChannelConfig.Builder privateConfig() {
        return ChannelConfig.builder().id(ID).scope(ChannelScope.PRIVATE).clientId("primary")
                .password("old-password");
    }

    private static ChannelActionPacket joinPacket(UUID player) {
        var packet = new ChannelActionPacket(ChannelAction.JOIN, ID);
        packet.addExtra("playerId", player.toString());
        packet.addExtra("playerName", "Player");
        return packet;
    }

    private static ClientConnection connection() {
        var connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn("primary");
        return connection;
    }

    private static void pause(CountDownLatch checked, CountDownLatch release) {
        checked.countDown();
        try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Join gate timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
