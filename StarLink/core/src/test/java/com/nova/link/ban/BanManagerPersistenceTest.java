package com.nova.link.ban;

import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.database.BanInfo;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the "persisted bans survive a backend restart" fix.
 *
 * <p>Simulates a restart by keeping the {@link MemoryProvider} (the "database")
 * alive while constructing a brand-new {@link BanManager} and calling
 * {@link BanManager#loadAllBans()}, which is what NovaLinkMain now does at
 * startup.
 */
@DisplayName("BanManager restart persistence tests")
class BanManagerPersistenceTest {

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
    @DisplayName("loadAllBans - restores persisted bans into a fresh manager")
    void loadAllBans_afterRestart_restoresActiveBans() throws DatabaseException {
        UUID globallyBanned = UUID.randomUUID();
        UUID channelBanned = UUID.randomUUID();
        long future = System.currentTimeMillis() + 3600_000;

        provider.saveBan(globallyBanned, new BanInfo(null, 0, "perm", UUID.randomUUID()));
        provider.saveBan(channelBanned, new BanInfo("test-channel", future, "timed", UUID.randomUUID()));

        // "Restart": brand-new manager backed by the same database.
        BanManager restarted = new BanManager(provider, permissionManager, channelManager);
        assertThat(restarted.isBanned(globallyBanned, "any-channel")).isFalse();

        int loaded = restarted.loadAllBans();

        assertThat(loaded).isEqualTo(2);
        assertThat(restarted.isBanned(globallyBanned, "any-channel")).isTrue();
        assertThat(restarted.isBanned(channelBanned, "test-channel")).isTrue();
        assertThat(restarted.isBanned(channelBanned, "other-channel")).isFalse();
    }

    @Test
    @DisplayName("loadAllBans - skips expired bans")
    void loadAllBans_expiredBans_notLoaded() throws DatabaseException {
        UUID expiredPlayer = UUID.randomUUID();
        long past = System.currentTimeMillis() - 10_000;
        provider.saveBan(expiredPlayer, new BanInfo("test-channel", past, "old", UUID.randomUUID()));

        BanManager restarted = new BanManager(provider, permissionManager, channelManager);
        int loaded = restarted.loadAllBans();

        assertThat(loaded).isZero();
        assertThat(restarted.isBanned(expiredPlayer, "test-channel")).isFalse();
    }

    @Test
    @DisplayName("loadAllBans - loaded bans appear in getAllActiveBans for REST listings")
    void loadAllBans_loadedBans_visibleInGetAllActiveBans() throws DatabaseException {
        UUID offlinePlayer = UUID.randomUUID();
        provider.saveBan(offlinePlayer, new BanInfo(null, 0, "perm", UUID.randomUUID()));

        BanManager restarted = new BanManager(provider, permissionManager, channelManager);
        restarted.loadAllBans();

        assertThat(restarted.getAllActiveBans()).containsOnlyKeys(offlinePlayer);
        assertThat(restarted.getAllActiveBans().get(offlinePlayer))
                .extracting(BanInfo::getReason)
                .containsExactly("perm");
    }

    @Test
    @DisplayName("loadAllBans - null database provider is a no-op")
    void loadAllBans_noDatabase_returnsZero() {
        BanManager noDb = new BanManager(null, permissionManager, channelManager);
        assertThat(noDb.loadAllBans()).isZero();
    }
}
