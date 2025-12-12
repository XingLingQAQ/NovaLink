package com.nova.chat.nukkit.chat;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.assertj.core.api.Assertions;

import java.util.UUID;

/**
 * Property-based tests for PlayerChatState in Nukkit module.
 */
class PlayerChatStatePropertyTest {

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Property
    void playerIdShouldBeImmutable(@ForAll("uuids") UUID playerId, 
                                    @ForAll @StringLength(min = 1, max = 30) String channel) {
        PlayerChatState state = new PlayerChatState(playerId, channel, ChatMode.HYBRID);
        Assertions.assertThat(state.getPlayerId()).isEqualTo(playerId);
    }

    @Property
    void activeChannelShouldBeSettable(@ForAll("uuids") UUID playerId,
                                        @ForAll @StringLength(min = 1, max = 30) String defaultChannel,
                                        @ForAll @StringLength(min = 1, max = 30) String newChannel) {
        PlayerChatState state = new PlayerChatState(playerId, defaultChannel, ChatMode.HYBRID);
        state.setActiveChannel(newChannel);
        Assertions.assertThat(state.getActiveChannel()).isEqualTo(newChannel);
    }

    @Property
    void toggleModeShouldAlternateBetweenModes(@ForAll("uuids") UUID playerId,
                                                @ForAll @StringLength(min = 1, max = 30) String channel) {
        PlayerChatState state = new PlayerChatState(playerId, channel, ChatMode.HYBRID);
        
        // First toggle: HYBRID -> REPLACE
        ChatMode afterFirst = state.toggleMode();
        Assertions.assertThat(afterFirst).isEqualTo(ChatMode.REPLACE);
        Assertions.assertThat(state.isModeOverridden()).isTrue();
        
        // Second toggle: REPLACE -> HYBRID
        ChatMode afterSecond = state.toggleMode();
        Assertions.assertThat(afterSecond).isEqualTo(ChatMode.HYBRID);
    }

    @Property
    void toggleFromReplaceShouldGoToHybrid(@ForAll("uuids") UUID playerId,
                                           @ForAll @StringLength(min = 1, max = 30) String channel) {
        PlayerChatState state = new PlayerChatState(playerId, channel, ChatMode.REPLACE);
        
        ChatMode afterToggle = state.toggleMode();
        Assertions.assertThat(afterToggle).isEqualTo(ChatMode.HYBRID);
    }

    @Property
    void modeOverriddenShouldBeFalseInitially(@ForAll("uuids") UUID playerId,
                                               @ForAll @StringLength(min = 1, max = 30) String channel,
                                               @ForAll ChatMode mode) {
        PlayerChatState state = new PlayerChatState(playerId, channel, mode);
        Assertions.assertThat(state.isModeOverridden()).isFalse();
    }

    @Property
    void toggleModeShouldSetModeOverridden(@ForAll("uuids") UUID playerId,
                                            @ForAll @StringLength(min = 1, max = 30) String channel,
                                            @ForAll ChatMode mode) {
        PlayerChatState state = new PlayerChatState(playerId, channel, mode);
        state.toggleMode();
        Assertions.assertThat(state.isModeOverridden()).isTrue();
    }

    @Property
    void chatModeShouldBeSettableDirectly(@ForAll("uuids") UUID playerId,
                                           @ForAll @StringLength(min = 1, max = 30) String channel,
                                           @ForAll ChatMode initialMode,
                                           @ForAll ChatMode newMode) {
        PlayerChatState state = new PlayerChatState(playerId, channel, initialMode);
        state.setChatMode(newMode);
        Assertions.assertThat(state.getChatMode()).isEqualTo(newMode);
    }

    @Property
    void multipleTogglesPreserveAlternation(@ForAll("uuids") UUID playerId,
                                             @ForAll @StringLength(min = 1, max = 30) String channel,
                                             @ForAll @IntRange(min = 1, max = 10) int toggleCount) {
        PlayerChatState state = new PlayerChatState(playerId, channel, ChatMode.HYBRID);
        
        for (int i = 0; i < toggleCount; i++) {
            state.toggleMode();
        }
        
        // After odd toggles: REPLACE, after even toggles: HYBRID
        ChatMode expected = (toggleCount % 2 == 1) ? ChatMode.REPLACE : ChatMode.HYBRID;
        Assertions.assertThat(state.getChatMode()).isEqualTo(expected);
    }

    @Property
    void nukkitPlayerChatStateCreation(@ForAll("uuids") UUID playerId,
                                        @ForAll @StringLength(min = 1, max = 30) String channel) {
        // Nukkit version doesn't have currentServer field (single server)
        PlayerChatState state = new PlayerChatState(playerId, channel, ChatMode.HYBRID);
        Assertions.assertThat(state.getPlayerId()).isNotNull();
        Assertions.assertThat(state.getActiveChannel()).isNotNull();
        Assertions.assertThat(state.getChatMode()).isNotNull();
    }
}
