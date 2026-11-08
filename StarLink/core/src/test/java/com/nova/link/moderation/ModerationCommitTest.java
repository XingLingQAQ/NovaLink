package com.nova.link.moderation;

import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.console.ConsoleSentinel;
import com.nova.link.database.BanInfo;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.MuteInfo;
import com.nova.link.mute.MuteManager;
import com.nova.link.notification.NotificationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ModerationCommitTest {

    enum Action { MUTE, UNMUTE, BAN, UNBAN }

    @ParameterizedTest
    @EnumSource(Action.class)
    void storageFailureKeepsTheCommittedRestrictionAndEmitsNoSuccess(Action action) throws Exception {
        var provider = new FailingProvider();
        provider.initialize();
        var channels = new ChannelManager();
        var channel = channels.createChannel(ChannelConfig.builder().id("local").scope(ChannelScope.GLOBAL).build());
        var permissions = new PermissionManager();
        var mutes = new MuteManager(provider, permissions, channels);
        var bans = new BanManager(provider, permissions, channels);
        var notifications = mock(NotificationStore.class);
        UUID player = UUID.randomUUID();
        UUID operator = ConsoleSentinel.CONSOLE_SENTINEL;
        try {
            mutes.mutePlayer(operator, player, "local", 0L, "committed", null);
            bans.banPlayer(operator, player, "local", 0L, "committed", null);
            channel.addMember(player);
            mutes.setNotificationStore(notifications);
            bans.setNotificationStore(notifications);
            provider.fail = true;

            boolean success;
            String code;
            switch (action) {
                case MUTE -> {
                    var result = mutes.mutePlayer(operator, player, "local", 0L, "unsaved", null);
                    success = result.isSuccess(); code = result.getErrorCode();
                }
                case UNMUTE -> {
                    var result = mutes.unmutePlayer(operator, player, "local", null);
                    success = result.isSuccess(); code = result.getErrorCode();
                }
                case BAN -> {
                    var result = bans.banPlayer(operator, player, "local", 0L, "unsaved", null);
                    success = result.isSuccess(); code = result.getErrorCode();
                }
                default -> {
                    var result = bans.unbanPlayer(operator, player, "local", null);
                    success = result.isSuccess(); code = result.getErrorCode();
                }
            }

            assertThat(success).isFalse();
            assertThat(code).isEqualTo("NC-510");
            assertThat(mutes.getMuteInfo(player, "local").getReason()).isEqualTo("committed");
            assertThat(bans.getBanInfo(player, "local").getReason()).isEqualTo("committed");
            assertThat(provider.loadMutes(player)).extracting(MuteInfo::getReason).containsExactly("committed");
            assertThat(provider.loadBans(player)).extracting(BanInfo::getReason).containsExactly("committed");
            assertThat(channel.isMember(player)).isTrue();
            verifyNoInteractions(notifications);
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void concurrentApplyAndRemoveCommitInTheSameOrderInCacheAndStorage() throws Exception {
        var saving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var provider = new MemoryProvider() {
            @Override
            public void saveMute(UUID player, MuteInfo info) throws DatabaseException {
                saving.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new DatabaseException("save gate timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new DatabaseException("save interrupted", error);
                }
                super.saveMute(player, info);
            }
        };
        provider.initialize();
        var manager = new MuteManager(provider, new PermissionManager(), new ChannelManager());
        UUID player = UUID.randomUUID();
        var applying = Executors.newSingleThreadExecutor();
        var removing = new FutureTask<>(() -> manager.unmutePlayer(ConsoleSentinel.CONSOLE_SENTINEL,
                player, "local", null));
        Thread remover = new Thread(removing, "moderation-remove-test");
        try {
            var applied = applying.submit(() -> manager.mutePlayer(ConsoleSentinel.CONSOLE_SENTINEL,
                    player, "local", 0L, "committed", null));
            assertThat(saving.await(5, TimeUnit.SECONDS)).isTrue();
            remover.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!removing.isDone() && remover.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertThat(removing.isDone() || remover.getState() == Thread.State.BLOCKED).isTrue();
            release.countDown();
            assertThat(applied.get(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(removing.get(5, TimeUnit.SECONDS).isSuccess()).isTrue();

            assertThat(manager.isMuted(player, "local")).isFalse();
            assertThat(provider.loadMutes(player)).isEmpty();
        } finally {
            release.countDown();
            applying.shutdownNow();
            remover.join(5000L);
            provider.shutdown();
        }
    }

    private static class FailingProvider extends MemoryProvider {
        boolean fail;

        private void checkWrite() throws DatabaseException {
            if (fail) throw new DatabaseException("simulated storage failure");
        }

        @Override public void saveMute(UUID id, MuteInfo info) throws DatabaseException {
            checkWrite(); super.saveMute(id, info);
        }
        @Override public void deleteMute(UUID id, String channelId) throws DatabaseException {
            checkWrite(); super.deleteMute(id, channelId);
        }
        @Override public void saveBan(UUID id, BanInfo info) throws DatabaseException {
            checkWrite(); super.saveBan(id, info);
        }
        @Override public void deleteBan(UUID id, String channelId) throws DatabaseException {
            checkWrite(); super.deleteBan(id, channelId);
        }
    }
}
