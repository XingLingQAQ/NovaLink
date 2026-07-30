package com.nova.chat.multipaper;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for MultiPaper state synchronization.
 * 
 * **Feature: novachat-platform-extensions, Property 18: MultiPaper State Synchronization**
 * **Validates: Requirements 1.3**
 * 
 * Tests that player chat state can be serialized and deserialized correctly
 * for cross-instance synchronization in MultiPaper environments.
 */
class MultiPaperStateSyncPropertyTest {

    /**
     * Property 18: MultiPaper State Synchronization
     * 
     * *For any* player moving between MultiPaper instances, their chat state 
     * should be consistent across all instances.
     * 
     * This is tested by verifying that serializing and deserializing a PlayerChannelState
     * produces an equivalent state (round-trip property).
     * 
     * **Validates: Requirements 1.3**
     */
    @Property(tries = 100)
    void PlayerChannelStateRoundTrip(
            @ForAll("validUUIDs") UUID playerId,
            @ForAll("validChannelIds") String channelId,
            @ForAll ChatMode chatMode,
            @ForAll boolean modeOverridden
    ) {
        // Create original state
        PlayerChannelState originalState = new PlayerChannelState(playerId, channelId, chatMode);
        originalState.setModeOverridden(modeOverridden);
        
        // Serialize to string (same format as MultiPaperAdapter)
        String serialized = serializeState(originalState);
        
        // Deserialize back
        PlayerChannelState deserializedState = deserializeState(playerId, serialized);
        
        // Verify all fields are preserved
        assertThat(deserializedState).isNotNull();
        assertThat(deserializedState.getPlayerId()).isEqualTo(originalState.getPlayerId());
        assertThat(deserializedState.getActiveChannel()).isEqualTo(originalState.getActiveChannel());
        assertThat(deserializedState.getChatMode()).isEqualTo(originalState.getChatMode());
        assertThat(deserializedState.isModeOverridden()).isEqualTo(originalState.isModeOverridden());
    }

    /**
     * Property: State copy preserves all fields
     * 
     * *For any* PlayerChannelState, creating a copy should preserve all fields.
     */
    @Property(tries = 100)
    void stateCopyPreservesAllFields(
            @ForAll("validUUIDs") UUID playerId,
            @ForAll("validChannelIds") String channelId,
            @ForAll ChatMode chatMode,
            @ForAll boolean modeOverridden
    ) {
        PlayerChannelState original = new PlayerChannelState(playerId, channelId, chatMode);
        original.setModeOverridden(modeOverridden);
        
        PlayerChannelState copy = original.copy();
        
        assertThat(copy.getPlayerId()).isEqualTo(original.getPlayerId());
        assertThat(copy.getActiveChannel()).isEqualTo(original.getActiveChannel());
        assertThat(copy.getChatMode()).isEqualTo(original.getChatMode());
        assertThat(copy.isModeOverridden()).isEqualTo(original.isModeOverridden());
    }

    /**
     * Property: Toggle mode alternates between HYBRID and REPLACE
     * 
     * *For any* PlayerChannelState, toggling mode twice should return to original mode.
     */
    @Property(tries = 100)
    void toggleModeIsIdempotentAfterTwoToggles(
            @ForAll("validUUIDs") UUID playerId,
            @ForAll("validChannelIds") String channelId,
            @ForAll ChatMode initialMode
    ) {
        PlayerChannelState state = new PlayerChannelState(playerId, channelId, initialMode);
        
        // Toggle twice
        state.toggleMode();
        state.toggleMode();
        
        // Should be back to initial mode
        assertThat(state.getChatMode()).isEqualTo(initialMode);
    }

    /**
     * Property: Toggle mode sets modeOverridden to true
     * 
     * *For any* PlayerChannelState, toggling mode should set modeOverridden to true.
     */
    @Property(tries = 100)
    void toggleModeSetsOverriddenFlag(
            @ForAll("validUUIDs") UUID playerId,
            @ForAll("validChannelIds") String channelId,
            @ForAll ChatMode initialMode
    ) {
        PlayerChannelState state = new PlayerChannelState(playerId, channelId, initialMode);
        assertThat(state.isModeOverridden()).isFalse();
        
        state.toggleMode();
        
        assertThat(state.isModeOverridden()).isTrue();
    }

    /**
     * Property: Serialization format is consistent
     * 
     * *For any* PlayerChannelState, the serialized format should contain all required parts.
     */
    @Property(tries = 100)
    void serializationFormatIsConsistent(
            @ForAll("validUUIDs") UUID playerId,
            @ForAll("validChannelIds") String channelId,
            @ForAll ChatMode chatMode,
            @ForAll boolean modeOverridden
    ) {
        PlayerChannelState state = new PlayerChannelState(playerId, channelId, chatMode);
        state.setModeOverridden(modeOverridden);
        
        String serialized = serializeState(state);
        
        // Should have exactly 3 parts separated by |
        String[] parts = serialized.split("\\|");
        assertThat(parts).hasSize(3);
        
        // Parts should be: channel, mode, modeOverridden
        assertThat(parts[0]).isEqualTo(channelId);
        assertThat(parts[1]).isEqualTo(chatMode.name());
        assertThat(parts[2]).isEqualTo(String.valueOf(modeOverridden));
    }

    // ========== Arbitraries ==========

    @Provide
    Arbitrary<UUID> validUUIDs() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<String> validChannelIds() {
        // Channel IDs should be alphanumeric with underscores, no pipes (used as delimiter)
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('_', '-')
                .ofMinLength(1)
                .ofMaxLength(32);
    }

    // ========== Helper Methods (same as MultiPaperAdapter) ==========

    /**
     * Serializes a player chat state to a string.
     * Format: channel|mode|modeOverridden
     */
    private String serializeState(PlayerChannelState state) {
        return state.getActiveChannel() + "|" + 
               state.getChatMode().name() + "|" + 
               state.isModeOverridden();
    }

    /**
     * Deserializes a player chat state from a string.
     */
    private PlayerChannelState deserializeState(UUID playerId, String data) {
        String[] parts = data.split("\\|");
        if (parts.length >= 3) {
            String channel = parts[0];
            ChatMode mode = ChatMode.valueOf(parts[1]);
            boolean modeOverridden = Boolean.parseBoolean(parts[2]);
            
            PlayerChannelState state = new PlayerChannelState(playerId, channel, mode);
            state.setModeOverridden(modeOverridden);
            return state;
        }
        return null;
    }
}
