package com.nova.link.channel;

import com.nova.link.database.MemoryProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ChannelManager} write-through persistence (P0-3) keeps
 * the database in sync with in-memory state across the REST and TCP paths, so
 * channels survive a backend restart.
 *
 * <p>Uses {@link MemoryProvider} as the persistence sink — its data survives
 * ChannelManager restarts within the same JVM (the provider keeps its maps
 * across {@code ChannelManager#clear()} calls, simulating a process restart
 * where the DB outlives the manager).
 */
@DisplayName("ChannelManager persistence (P0-3)")
class ChannelManagerPersistenceTest {

    private MemoryProvider databaseProvider;

    @BeforeEach
    void setUp() {
        databaseProvider = new MemoryProvider();
        try {
            databaseProvider.initialize();
        } catch (com.nova.link.database.DatabaseException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        databaseProvider.shutdown();
    }

    @Test
    @DisplayName("createChannel writes-through to the database")
    void createChannel_persistsToDb() throws Exception {
        ChannelManager manager = new ChannelManager(databaseProvider);

        manager.createChannel(ChannelConfig.builder()
                .id("rest-created")
                .displayName("REST Channel")
                .scope(ChannelScope.GLOBAL)
                .build());

        Optional<Channel> loaded = databaseProvider.loadChannel("rest-created");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getId()).isEqualTo("rest-created");
        assertThat(loaded.get().getDisplayName()).isEqualTo("REST Channel");
    }

    @Test
    @DisplayName("deleteChannel removes the channel from the database")
    void deleteChannel_removesFromDb() throws Exception {
        ChannelManager manager = new ChannelManager(databaseProvider);
        manager.createChannel(ChannelConfig.builder()
                .id("doomed")
                .scope(ChannelScope.GLOBAL)
                .build());
        assertThat(databaseProvider.loadChannel("doomed")).isPresent();

        boolean deleted = manager.deleteChannel("doomed");

        assertThat(deleted).isTrue();
        assertThat(databaseProvider.loadChannel("doomed")).isEmpty();
        assertThat(databaseProvider.getAllChannels()).isEmpty();
    }

    @Test
    @DisplayName("updateChannel writes-through the new properties to the database")
    void updateChannel_persistsChangesToDb() throws Exception {
        ChannelManager manager = new ChannelManager(databaseProvider);
        manager.createChannel(ChannelConfig.builder()
                .id("upd")
                .displayName("Old Name")
                .scope(ChannelScope.GLOBAL)
                .maxCapacity(50)
                .build());

        manager.updateChannel("upd", "New Name", 200, "nova.chat.admin");

        Optional<Channel> loaded = databaseProvider.loadChannel("upd");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDisplayName()).isEqualTo("New Name");
        assertThat(loaded.get().getMaxCapacity()).isEqualTo(200);
        assertThat(loaded.get().getPermission()).isEqualTo("nova.chat.admin");
    }

    @Test
    @DisplayName("REST-created channel is restored when a new ChannelManager loads from DB (restart)")
    void restCreatedChannel_survivesRestart() throws Exception {
        // First "process": REST path creates a channel via ChannelManager.
        ChannelManager firstManager = new ChannelManager(databaseProvider);
        firstManager.createChannel(ChannelConfig.builder()
                .id("survivor")
                .displayName("Survives Restart")
                .scope(ChannelScope.GLOBAL)
                .build());
        // Simulate process restart: discard the in-memory manager. The DB
        // outlives it.
        firstManager.clear();

        // Second "process": a fresh ChannelManager wired to the same DB.
        ChannelManager restarted = new ChannelManager(databaseProvider);
        // Simulate NovaLinkMain.loadPersistedChannels: detach the sink so
        // restoration does not rewrite, then re-attach.
        restarted.setDatabaseProvider(null);
        assertThat(restarted.getAllChannels()).isEmpty();
        for (Channel ch : databaseProvider.getAllChannels()) {
            if (ch == null || ch.getId() == null || ch.getId().isBlank()) continue;
            if (restarted.channelExists(ch.getId())) continue;
            restarted.createChannel(ChannelConfig.builder()
                    .id(ch.getId())
                    .displayName(ch.getDisplayName())
                    .scope(ch.getScope())
                    .clientId(ch.getClientId())
                    .permission(ch.getPermission())
                    .maxCapacity(ch.getMaxCapacity())
                    .allowedWorlds(ch.getAllowedWorlds())
                    .password(ch.getPassword())
                    .ownerId(ch.getOwnerId())
                    .build());
        }
        restarted.setDatabaseProvider(databaseProvider);

        assertThat(restarted.channelExists("survivor")).isTrue();
        Channel restored = restarted.getChannel("survivor");
        assertThat(restored).isNotNull();
        assertThat(restored.getDisplayName()).isEqualTo("Survives Restart");
    }

    @Test
    @DisplayName("REST-deleted channel does NOT revive on restart")
    void restDeletedChannel_doesNotRevive() throws Exception {
        ChannelManager firstManager = new ChannelManager(databaseProvider);
        firstManager.createChannel(ChannelConfig.builder()
                .id("ephemeral")
                .scope(ChannelScope.GLOBAL)
                .build());
        firstManager.deleteChannel("ephemeral");
        // Deleted via REST → DB no longer has it.
        assertThat(databaseProvider.loadChannel("ephemeral")).isEmpty();

        // Restart: a fresh manager loads from DB.
        firstManager.clear();
        ChannelManager restarted = new ChannelManager(databaseProvider);
        assertThat(restarted.channelExists("ephemeral")).isFalse();
    }

    @Test
    @DisplayName("DB failure during createChannel is swallowed (in-memory op still succeeds)")
    void createChannel_dbFailure_isNonFatal() {
        // A provider whose saveChannel throws.
        MemoryProvider throwingProvider = new MemoryProvider() {
            @Override
            public void saveChannel(Channel channel) throws com.nova.link.database.DatabaseException {
                throw new com.nova.link.database.DatabaseException("simulated DB outage");
            }
        };
        try { throwingProvider.initialize(); } catch (com.nova.link.database.DatabaseException e) { throw new RuntimeException(e); }
        ChannelManager manager = new ChannelManager(throwingProvider);

        // The create should still succeed in memory despite the DB failure.
        Channel channel = manager.createChannel(ChannelConfig.builder()
                .id("memory-only")
                .scope(ChannelScope.GLOBAL)
                .build());

        assertThat(channel).isNotNull();
        assertThat(manager.channelExists("memory-only")).isTrue();
    }

    @Test
    @DisplayName("no provider wired: behaves memory-only (legacy/tests)")
    void noProvider_memoryOnly() throws Exception {
        ChannelManager manager = new ChannelManager();
        manager.createChannel(ChannelConfig.builder()
                .id("mem-only")
                .scope(ChannelScope.GLOBAL)
                .build());

        assertThat(manager.channelExists("mem-only")).isTrue();
        // Nothing in the DB.
        assertThat(databaseProvider.loadChannel("mem-only")).isEmpty();
    }
}
