package com.nova.link.database;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Player State Persistence.
 * 
 * Tests correctness properties defined in the design document.
 */
@PropertyDefaults(tries = 100)
public class PlayerStatePersistencePropertyTest {

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    /**
     * **Feature: starchat-starlink, Property 6: Player State Persistence Round-Trip**
     * 
     * For any player state, saving to database and loading back should produce
     * an equivalent state object.
     * 
     * **Validates: Requirements 3.3, 22.1, 22.4**
     */
    @Property
    void playerStatePersistenceRoundTrip(
            @ForAll("validPlayerStates") PlayerState originalState
    ) throws DatabaseException {
        // Setup - use MemoryProvider for testing
        MemoryProvider provider = new MemoryProvider();
        provider.initialize();

        try {
            // Save the state
            provider.savePlayerState(originalState);

            // Load it back
            Optional<PlayerState> loadedOpt = provider.loadPlayerState(originalState.getPlayerId());

            // Should be present
            assertThat(loadedOpt).isPresent();

            PlayerState loadedState = loadedOpt.get();

            // Verify all fields match
            assertThat(loadedState.getPlayerId()).isEqualTo(originalState.getPlayerId());
            assertThat(loadedState.getPlayerName()).isEqualTo(originalState.getPlayerName());
            assertThat(loadedState.getClientId()).isEqualTo(originalState.getClientId());
            assertThat(loadedState.getCurrentWorld()).isEqualTo(originalState.getCurrentWorld());
            assertThat(loadedState.getJoinedChannels()).isEqualTo(originalState.getJoinedChannels());
            assertThat(loadedState.getActiveChannel()).isEqualTo(originalState.getActiveChannel());
            assertThat(loadedState.getLastSeen()).isEqualTo(originalState.getLastSeen());
            
            // Verify mutes match
            assertThat(loadedState.getMutes()).isEqualTo(originalState.getMutes());
        } finally {
            provider.shutdown();
        }
    }

    /**
     * Property: Saving a state twice should overwrite the previous state.
     */
    @Property
    void savingStateTwiceOverwritesPrevious(
            @ForAll("validPlayerStates") PlayerState state1,
            @ForAll @StringLength(min = 1, max = 20) String newWorld
    ) throws DatabaseException {
        MemoryProvider provider = new MemoryProvider();
        provider.initialize();

        try {
            // Save initial state
            provider.savePlayerState(state1);

            // Modify and save again
            PlayerState state2 = new PlayerState(state1);
            state2.setCurrentWorld(newWorld);
            provider.savePlayerState(state2);

            // Load and verify it's the updated state
            Optional<PlayerState> loaded = provider.loadPlayerState(state1.getPlayerId());
            assertThat(loaded).isPresent();
            assertThat(loaded.get().getCurrentWorld()).isEqualTo(newWorld);
        } finally {
            provider.shutdown();
        }
    }

    /**
     * Property: Deleting a state should make it unavailable.
     */
    @Property
    void deletingStateMakesItUnavailable(
            @ForAll("validPlayerStates") PlayerState state
    ) throws DatabaseException {
        MemoryProvider provider = new MemoryProvider();
        provider.initialize();

        try {
            // Save the state
            provider.savePlayerState(state);

            // Verify it exists
            assertThat(provider.loadPlayerState(state.getPlayerId())).isPresent();

            // Delete it
            provider.deletePlayerState(state.getPlayerId());

            // Verify it's gone
            assertThat(provider.loadPlayerState(state.getPlayerId())).isEmpty();
        } finally {
            provider.shutdown();
        }
    }

    /**
     * Property: Loading a non-existent state should return empty.
     */
    @Property
    void loadingNonExistentStateReturnsEmpty(
            @ForAll("uuids") UUID playerId
    ) throws DatabaseException {
        MemoryProvider provider = new MemoryProvider();
        provider.initialize();

        try {
            // Load without saving
            Optional<PlayerState> loaded = provider.loadPlayerState(playerId);
            assertThat(loaded).isEmpty();
        } finally {
            provider.shutdown();
        }
    }

    /**
     * Property: Multiple player states should be independent.
     */
    @Property
    void multiplePlayerStatesAreIndependent(
            @ForAll("validPlayerStates") PlayerState state1,
            @ForAll("validPlayerStates") PlayerState state2
    ) throws DatabaseException {
        Assume.that(!state1.getPlayerId().equals(state2.getPlayerId()));

        MemoryProvider provider = new MemoryProvider();
        provider.initialize();

        try {
            // Save both states
            provider.savePlayerState(state1);
            provider.savePlayerState(state2);

            // Load and verify they're independent
            Optional<PlayerState> loaded1 = provider.loadPlayerState(state1.getPlayerId());
            Optional<PlayerState> loaded2 = provider.loadPlayerState(state2.getPlayerId());

            assertThat(loaded1).isPresent();
            assertThat(loaded2).isPresent();
            assertThat(loaded1.get().getPlayerId()).isEqualTo(state1.getPlayerId());
            assertThat(loaded2.get().getPlayerId()).isEqualTo(state2.getPlayerId());

            // Deleting one shouldn't affect the other
            provider.deletePlayerState(state1.getPlayerId());
            assertThat(provider.loadPlayerState(state1.getPlayerId())).isEmpty();
            assertThat(provider.loadPlayerState(state2.getPlayerId())).isPresent();
        } finally {
            provider.shutdown();
        }
    }

    /**
     * Property: Channel membership round-trip.
     */
    @Property
    void channelMembershipRoundTrip(
            @ForAll("uuids") UUID playerId,
            @ForAll @StringLength(min = 1, max = 20) String playerName,
            @ForAll @Size(min = 0, max = 5) Set<@StringLength(min = 1, max = 20) String> channels
    ) throws DatabaseException {
        MemoryProvider provider = new MemoryProvider();
        provider.initialize();

        try {
            // Create state with channels
            PlayerState state = new PlayerState(playerId, playerName);
            state.setJoinedChannels(channels);
            if (!channels.isEmpty()) {
                state.setActiveChannel(channels.iterator().next());
            }

            // Save and load
            provider.savePlayerState(state);
            Optional<PlayerState> loaded = provider.loadPlayerState(playerId);

            assertThat(loaded).isPresent();
            assertThat(loaded.get().getJoinedChannels()).isEqualTo(channels);
        } finally {
            provider.shutdown();
        }
    }

    /**
     * Property: Mute info round-trip.
     */
    @Property
    void muteInfoRoundTrip(
            @ForAll("uuids") UUID playerId,
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE - 1000000) long expireTime,
            @ForAll @StringLength(min = 0, max = 100) String reason,
            @ForAll("uuids") UUID operatorId
    ) throws DatabaseException {
        MemoryProvider provider = new MemoryProvider();
        provider.initialize();

        try {
            // Create mute info
            MuteInfo muteInfo = new MuteInfo(channelId, expireTime, reason, operatorId);

            // Save mute
            provider.saveMute(playerId, muteInfo);

            // Load mutes
            List<MuteInfo> mutes = provider.loadMutes(playerId);

            assertThat(mutes).hasSize(1);
            MuteInfo loaded = mutes.get(0);
            assertThat(loaded.getChannelId()).isEqualTo(channelId);
            assertThat(loaded.getExpireTime()).isEqualTo(expireTime);
            assertThat(loaded.getReason()).isEqualTo(reason);
            assertThat(loaded.getOperatorId()).isEqualTo(operatorId);
        } finally {
            provider.shutdown();
        }
    }

    /**
     * Property: PlayerStateManager correctly persists and retrieves state.
     */
    @Property
    void playerStateManagerRoundTrip(
            @ForAll("validPlayerStates") PlayerState originalState
    ) throws DatabaseException {
        MemoryProvider provider = new MemoryProvider();
        provider.initialize();
        PlayerStateManager manager = new PlayerStateManager(provider);

        try {
            // Save through manager
            manager.saveState(originalState);

            // Clear cache to force database load
            manager.clearCache(false);

            // Load through manager
            Optional<PlayerState> loaded = manager.loadState(originalState.getPlayerId());

            assertThat(loaded).isPresent();
            assertThat(loaded.get().getPlayerId()).isEqualTo(originalState.getPlayerId());
            assertThat(loaded.get().getPlayerName()).isEqualTo(originalState.getPlayerName());
            assertThat(loaded.get().getClientId()).isEqualTo(originalState.getClientId());
            assertThat(loaded.get().getJoinedChannels()).isEqualTo(originalState.getJoinedChannels());
        } finally {
            provider.shutdown();
        }
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<PlayerState> validPlayerStates() {
        return Combinators.combine(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(20).alpha(),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(20).alpha().optional(),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(20).alpha().optional(),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(20).alpha()
                        .set().ofMinSize(0).ofMaxSize(5),
                Arbitraries.longs().greaterOrEqual(0)
        ).as((playerId, playerName, clientId, currentWorld, channels, lastSeen) -> {
            PlayerState state = new PlayerState(playerId, playerName);
            clientId.ifPresent(state::setClientId);
            currentWorld.ifPresent(state::setCurrentWorld);
            state.setJoinedChannels(channels);
            if (!channels.isEmpty()) {
                state.setActiveChannel(channels.iterator().next());
            }
            state.setLastSeen(lastSeen);
            return state;
        });
    }
}
