package com.nova.link.moderation;

import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.console.ConsoleSentinel;
import com.nova.link.database.BanInfo;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.MuteInfo;
import com.nova.link.mute.MuteManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationExpiryTest {

    @Test
    void expiredChannelMuteStillFallsBackToPermanentGlobalMute() {
        var manager = new ExpiryMute(null);
        UUID player = UUID.randomUUID();
        manager.seed(player, new MuteInfo("local", 1L, "expired", UUID.randomUUID()));
        manager.seed(player, new MuteInfo(null, 0L, "global", UUID.randomUUID()));

        assertThat(manager.isMuted(player, "local")).isTrue();
        assertThat(manager.getMuteInfo(player, "local").getReason()).isEqualTo("global");
    }

    @Test
    void expiredChannelBanStillFallsBackToPermanentGlobalBan() {
        var manager = new BanManager(null, new PermissionManager(), new ChannelManager()) {
            void seed(UUID player, BanInfo info) {
                putInCache(player, cacheKeyFor(info.getChannelId()), info);
            }
        };
        UUID player = UUID.randomUUID();
        manager.seed(player, new BanInfo("local", 1L, "expired", UUID.randomUUID()));
        manager.seed(player, new BanInfo(null, 0L, "global", UUID.randomUUID()));

        assertThat(manager.isBanned(player, "local")).isTrue();
        assertThat(manager.getBanInfo(player, "local").getReason()).isEqualTo("global");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void expiryCannotRemoveARenewalCommittedAfterTheOldRecordWasRead(boolean periodic) throws Exception {
        var provider = new MemoryProvider();
        provider.initialize();
        try {
            var manager = new ExpiryMute(provider);
            UUID player = UUID.randomUUID();
            var old = new MuteInfo("local", 1L, "expired", UUID.randomUUID());
            provider.saveMute(player, old);
            manager.seed(player, old);
            manager.beforeExpiry = () -> assertThat(manager.mutePlayer(ConsoleSentinel.CONSOLE_SENTINEL,
                    player, "local", 0L, "renewed", null).isSuccess()).isTrue();

            if (periodic) manager.sweep();
            else assertThat(manager.isMuted(player, "local")).isTrue();

            assertThat(manager.isMuted(player, "local")).isTrue();
            assertThat(manager.getMuteInfo(player, "local").getReason()).isEqualTo("renewed");
            assertThat(provider.loadMutes(player)).extracting(MuteInfo::getReason).containsExactly("renewed");
        } finally {
            provider.shutdown();
        }
    }

    private static class ExpiryMute extends MuteManager {
        Runnable beforeExpiry;

        ExpiryMute(MemoryProvider provider) {
            super(provider, new PermissionManager(), new ChannelManager());
        }

        void seed(UUID player, MuteInfo info) {
            putInCache(player, cacheKeyFor(info.getChannelId()), info);
        }

        void sweep() {
            cleanupExpired();
        }

        @Override
        protected boolean isExpired(MuteInfo info) {
            if (info.isExpired() && beforeExpiry != null) {
                Runnable action = beforeExpiry;
                beforeExpiry = null;
                action.run();
            }
            return super.isExpired(info);
        }
    }
}
