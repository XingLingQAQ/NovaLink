package com.nova.link.mute;

import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.MuteInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the "persisted mutes survive a backend restart" fix.
 *
 * <p>Simulates a restart by keeping the {@link MemoryProvider} (the "database")
 * alive while constructing a brand-new {@link MuteManager} and calling
 * {@link MuteManager#loadAllMutes()}, which is what NovaLinkMain now does at
 * startup.
 */
@DisplayName("MuteManager restart persistence tests")
class MuteManagerPersistenceTest {

    private MemoryProvider provider;
    private PermissionManager permissionManager;
    private ChannelManager channelManager;

    @BeforeEach
    void setUp() throws DatabaseException {
        provider = new MemoryProvider();
        provider.initialize();
        permissionManager = new PermissionManager();
        channelManager = new ChannelManager();
    }

    @Test
    @DisplayName("loadAllMutes - restores persisted mutes into a fresh manager")
    void loadAllMutes_afterRestart_restoresActiveMutes() throws DatabaseException {
        UUID globallyMuted = UUID.randomUUID();
        UUID channelMuted = UUID.randomUUID();
        long future = System.currentTimeMillis() + 3600_000;

        provider.saveMute(globallyMuted, new MuteInfo(null, 0, "perm", UUID.randomUUID()));
        provider.saveMute(channelMuted, new MuteInfo("test-channel", future, "timed", UUID.randomUUID()));

        // "Restart": brand-new manager backed by the same database.
        MuteManager restarted = new MuteManager(provider, permissionManager, channelManager);
        assertThat(restarted.isMuted(globallyMuted, "any-channel")).isFalse();

        int loaded = restarted.loadAllMutes();

        assertThat(loaded).isEqualTo(2);
        assertThat(restarted.isMuted(globallyMuted, "any-channel")).isTrue();
        assertThat(restarted.isMuted(channelMuted, "test-channel")).isTrue();
        assertThat(restarted.isMuted(channelMuted, "other-channel")).isFalse();
    }

    @Test
    @DisplayName("loadAllMutes - skips expired mutes")
    void loadAllMutes_expiredMutes_notLoaded() throws DatabaseException {
        UUID expiredPlayer = UUID.randomUUID();
        long past = System.currentTimeMillis() - 10_000;
        provider.saveMute(expiredPlayer, new MuteInfo("test-channel", past, "old", UUID.randomUUID()));

        MuteManager restarted = new MuteManager(provider, permissionManager, channelManager);
        int loaded = restarted.loadAllMutes();

        assertThat(loaded).isZero();
        assertThat(restarted.isMuted(expiredPlayer, "test-channel")).isFalse();
    }

    @Test
    @DisplayName("loadAllMutes - loaded mutes appear in getAllActiveMutes for REST listings")
    void loadAllMutes_loadedMutes_visibleInGetAllActiveMutes() throws DatabaseException {
        UUID offlinePlayer = UUID.randomUUID();
        provider.saveMute(offlinePlayer, new MuteInfo(null, 0, "perm", UUID.randomUUID()));

        MuteManager restarted = new MuteManager(provider, permissionManager, channelManager);
        restarted.loadAllMutes();

        assertThat(restarted.getAllActiveMutes()).containsOnlyKeys(offlinePlayer);
        assertThat(restarted.getAllActiveMutes().get(offlinePlayer))
                .extracting(MuteInfo::getReason)
                .containsExactly("perm");
    }

    @Test
    @DisplayName("loadAllMutes - null database provider is a no-op")
    void loadAllMutes_noDatabase_returnsZero() {
        MuteManager noDb = new MuteManager(null, permissionManager, channelManager);
        assertThat(noDb.loadAllMutes()).isZero();
    }
}
