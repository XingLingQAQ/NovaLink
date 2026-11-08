package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.ChannelStateCleanup;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelAdmissionCommitTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retiredCreationCannotPublishOwnerPermissionsStateOrOverwriteReplacement(boolean beforeOwner) throws Exception {
        var db = new MemoryProvider();
        db.initialize();
        var permissions = new PermissionManager();
        var players = new PlayerStateManager(db);
        var armed = new AtomicBoolean(true);
        var retired = new AtomicReference<Channel>();
        var channels = new ChannelManager() {
            @Override public Channel createChannel(ChannelConfig config) {
                Channel channel = super.createChannel(config);
                if (beforeOwner && armed.getAndSet(false)) {
                    retired.set(channel);
                    replace(this, db, channel);
                }
                return channel;
            }
        };
        var privateChannels = new PrivateChannelManager(channels) {
            @Override public PrivateChannelCreationResult createPrivateChannel(String name, String client,
                                                                               UUID owner, String password) {
                var result = super.createPrivateChannel(name, client, owner, password);
                if (!beforeOwner && armed.getAndSet(false)) {
                    retired.set(result.getChannel());
                    replace(channels, db, result.getChannel());
                }
                return result;
            }
        };
        channels.setDeletionCleanup(channel -> {
            ChannelStateCleanup.clearMemberships(channel, players);
            permissions.clearChannelAdmins(channel.getId());
            privateChannels.removeTrackedId(channel.getId());
        });
        var handler = new ChannelActionHandler(channels, players, db, privateChannels,
                new InvitationManager(db, channels), permissions);
        UUID owner = UUID.randomUUID();
        var packet = packet(ChannelAction.CREATE, "Private", owner);
        try {
            var response = handler.handle(connection(), packet);
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-404");
            Channel old = retired.get();
            assertThat(old.isMember(owner)).isFalse();
            assertThat(channels.getChannel(old.getId()).isMember(owner)).isFalse();
            assertThat(permissions.isChannelAdmin(old.getId(), owner)).isFalse();
            assertThat(privateChannels.wasIdGenerated(old.getId())).isFalse();
            assertThat(players.getPlayerState(owner)).isNull();
            assertThat(db.loadChannel(old.getId()).orElseThrow().getDisplayName()).isEqualTo("Replacement");
        } finally {
            db.shutdown();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ChannelAction.class, names = {"CREATE", "ACCEPT"})
    void deletionCannotFinishBeforeAdmissionStateIsPublished(ChannelAction action) throws Exception {
        var db = new MemoryProvider();
        db.initialize();
        var channels = new ChannelManager(db);
        var publishing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var players = new PlayerStateManager(db) {
            @Override public PlayerState getOrCreateState(UUID id, String name) {
                PlayerState state = super.getOrCreateState(id, name);
                publishing.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("State gate timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return state;
            }
        };
        var permissions = new PermissionManager();
        var privateChannels = new PrivateChannelManager(channels);
        var invitations = new InvitationManager(db, channels);
        channels.setDeletionCleanup(channel -> {
            ChannelStateCleanup.clearMemberships(channel, players);
            permissions.clearChannelAdmins(channel.getId());
            privateChannels.removeTrackedId(channel.getId());
        });
        var handler = new ChannelActionHandler(channels, players, db, privateChannels, invitations, permissions);
        UUID player = UUID.randomUUID();
        String target = "Private";
        if (action == ChannelAction.ACCEPT) {
            channels.createChannel(ChannelConfig.builder().id("target").scope(ChannelScope.GLOBAL).build());
            target = invitations.createInvitation("target", UUID.randomUUID()).getCode();
        }
        var packet = packet(action, target, player);
        var connection = connection();
        var admitting = new FutureTask<>(() -> handler.handle(connection, packet));
        Thread admitter = new Thread(admitting, "admission-state-test");
        var deleting = new FutureTask<>(() -> {
            Channel channel = channels.getAllChannels().iterator().next();
            assertThat(channels.deleteChannel(channel.getId())).isTrue();
            return channel;
        });
        Thread deleter = new Thread(deleting, "admission-delete-test");
        try {
            admitter.start();
            assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
            deleter.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waitingForAdmission = false;
            var threads = ManagementFactory.getThreadMXBean();
            while (!deleting.isDone() && System.nanoTime() < deadline) {
                var state = threads.getThreadInfo(deleter.getId());
                if (state != null && state.getThreadState() == Thread.State.BLOCKED
                        && state.getLockOwnerId() == admitter.getId()) {
                    waitingForAdmission = true;
                    break;
                }
                Thread.yield();
            }
            assertThat(deleting.isDone() || waitingForAdmission).isTrue();
            release.countDown();
            assertThat(admitting.get(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            Channel retired = deleting.get(5, TimeUnit.SECONDS);
            assertThat(retired.getMembers()).isEmpty();
            assertThat(players.getPlayerState(player).getJoinedChannels()).isEmpty();
            assertThat(players.getPlayerState(player).getActiveChannel()).isNull();
            assertThat(permissions.isChannelAdmin(retired.getId(), player)).isFalse();
            assertThat(privateChannels.wasIdGenerated(retired.getId())).isFalse();
            assertThat(db.loadChannel(retired.getId())).isEmpty();
        } finally {
            release.countDown();
            admitter.join(5_000);
            deleter.join(5_000);
            if (admitter.isAlive()) admitter.interrupt();
            if (deleter.isAlive()) deleter.interrupt();
            admitter.join(5_000);
            deleter.join(5_000);
            db.shutdown();
            assertThat(admitter.isAlive() || deleter.isAlive()).isFalse();
        }
    }

    private static void replace(ChannelManager channels, MemoryProvider db, Channel old) {
        assertThat(channels.deleteChannel(old.getId())).isTrue();
        Channel replacement = channels.createChannel(ChannelConfig.builder().id(old.getId())
                .displayName("Replacement").scope(ChannelScope.PRIVATE).clientId("secondary")
                .ownerId(UUID.randomUUID()).password("replacement").build());
        try {
            db.saveChannel(replacement);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static ChannelActionPacket packet(ChannelAction action, String channelId, UUID player) {
        var packet = new ChannelActionPacket(action, channelId);
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
}
