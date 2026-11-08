package com.nova.link.moderation;

import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.console.ConsoleSentinel;
import com.nova.link.database.MemoryProvider;
import com.nova.link.mute.MuteManager;
import com.nova.link.notification.NotificationStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ModerationDurationTest {

    @Test
    void invalidDurationCannotReplaceCommittedRestrictions() throws Exception {
        var provider = new MemoryProvider();
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

            for (long duration : new long[] {-1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                var mute = mutes.mutePlayer(operator, player, "local", duration, "invalid", null);
                var ban = bans.banPlayer(operator, player, "local", duration, "invalid", null);
                assertThat(mute.isSuccess()).as("mute duration %s", duration).isFalse();
                assertThat(mute.getErrorCode()).isEqualTo("NC-400");
                assertThat(ban.isSuccess()).as("ban duration %s", duration).isFalse();
                assertThat(ban.getErrorCode()).isEqualTo("NC-400");
            }

            assertThat(mutes.getMuteInfo(player, "local").getReason()).isEqualTo("committed");
            assertThat(bans.getBanInfo(player, "local").getReason()).isEqualTo("committed");
            assertThat(provider.loadMutes(player)).extracting(info -> info.getReason()).containsExactly("committed");
            assertThat(provider.loadBans(player)).extracting(info -> info.getReason()).containsExactly("committed");
            assertThat(channel.isMember(player)).isTrue();
            verifyNoInteractions(notifications);
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void permanentAndPositiveDurationsKeepTheirMeaning() throws Exception {
        var provider = new MemoryProvider();
        provider.initialize();
        var channels = new ChannelManager();
        var permissions = new PermissionManager();
        var mutes = new MuteManager(provider, permissions, channels);
        var bans = new BanManager(provider, permissions, channels);
        UUID player = UUID.randomUUID();
        try {
            for (long duration : new long[] {0L, 60_000L}) {
                long started = System.currentTimeMillis();
                assertThat(mutes.mutePlayer(ConsoleSentinel.CONSOLE_SENTINEL, player, null,
                        duration, "valid", null).isSuccess()).isTrue();
                assertThat(bans.banPlayer(ConsoleSentinel.CONSOLE_SENTINEL, player, null,
                        duration, "valid", null).isSuccess()).isTrue();
                long muteExpiry = mutes.getMuteInfo(player, null).getExpireTime();
                long banExpiry = bans.getBanInfo(player, null).getExpireTime();
                if (duration == 0L) {
                    assertThat(muteExpiry).isZero();
                    assertThat(banExpiry).isZero();
                } else {
                    assertThat(muteExpiry).isBetween(started + duration, System.currentTimeMillis() + duration);
                    assertThat(banExpiry).isBetween(started + duration, System.currentTimeMillis() + duration);
                }
            }
        } finally {
            provider.shutdown();
        }
    }
}
