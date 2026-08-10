package com.nova.chat.bungee.chat;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.assertj.core.api.Assertions;

import java.util.UUID;

/**
 * Property-based tests for shared {@link PlayerChannelState} from the BungeeCord module.
 */
class PlayerChatStatePropertyTest {

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    /** Non-blank channel / server ids (PlayerChannelState rejects blank). */
    @Provide
    Arbitrary<String> channelIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(30);
    }

    @Provide
    Arbitrary<String> serverIds() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(50);
    }

    @Property
    void playerIdShouldBeImmutable(@ForAll("uuids") UUID playerId,
                                    @ForAll("channelIds") String channel) {
        PlayerChannelState state = new PlayerChannelState(playerId, channel, ChatMode.HYBRID);
        Assertions.assertThat(state.getPlayerId()).isEqualTo(playerId);
    }

    @Property
    void activeChannelShouldBeSettable(@ForAll("uuids") UUID playerId,
                                        @ForAll("channelIds") String defaultChannel,
                                        @ForAll("channelIds") String newChannel) {
        PlayerChannelState state = new PlayerChannelState(playerId, defaultChannel, ChatMode.HYBRID);
        state.setActiveChannel(newChannel);
        Assertions.assertThat(state.getActiveChannel()).isEqualTo(newChannel);
    }

    @Property
    void toggleModeShouldAlternateBetweenModes(@ForAll("uuids") UUID playerId,
                                                @ForAll("channelIds") String channel) {
        PlayerChannelState state = new PlayerChannelState(playerId, channel, ChatMode.HYBRID);

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
                                           @ForAll("channelIds") String channel) {
        PlayerChannelState state = new PlayerChannelState(playerId, channel, ChatMode.REPLACE);

        ChatMode afterToggle = state.toggleMode();
        Assertions.assertThat(afterToggle).isEqualTo(ChatMode.HYBRID);
    }

    @Property
    void modeOverriddenShouldBeFalseInitially(@ForAll("uuids") UUID playerId,
                                               @ForAll("channelIds") String channel,
                                               @ForAll ChatMode mode) {
        PlayerChannelState state = new PlayerChannelState(playerId, channel, mode);
        Assertions.assertThat(state.isModeOverridden()).isFalse();
    }

    @Property
    void toggleModeShouldSetModeOverridden(@ForAll("uuids") UUID playerId,
                                            @ForAll("channelIds") String channel,
                                            @ForAll ChatMode mode) {
        PlayerChannelState state = new PlayerChannelState(playerId, channel, mode);
        state.toggleMode();
        Assertions.assertThat(state.isModeOverridden()).isTrue();
    }

    @Property
    void currentServerShouldBeNullInitially(@ForAll("uuids") UUID playerId,
                                             @ForAll("channelIds") String channel) {
        PlayerChannelState state = new PlayerChannelState(playerId, channel, ChatMode.HYBRID);
        Assertions.assertThat(state.getCurrentServer()).isNull();
    }

    @Property
    void currentServerShouldBeSettable(@ForAll("uuids") UUID playerId,
                                        @ForAll("channelIds") String channel,
                                        @ForAll("serverIds") String server) {
        PlayerChannelState state = new PlayerChannelState(playerId, channel, ChatMode.HYBRID);
        state.setCurrentServer(server);
        Assertions.assertThat(state.getCurrentServer()).isEqualTo(server);
    }

    @Property
    void chatModeShouldBeSettableDirectly(@ForAll("uuids") UUID playerId,
                                           @ForAll("channelIds") String channel,
                                           @ForAll ChatMode initialMode,
                                           @ForAll ChatMode newMode) {
        PlayerChannelState state = new PlayerChannelState(playerId, channel, initialMode);
        state.setChatMode(newMode);
        Assertions.assertThat(state.getChatMode()).isEqualTo(newMode);
    }

    @Property
    void multipleTogglesPreserveAlternation(@ForAll("uuids") UUID playerId,
                                             @ForAll("channelIds") String channel,
                                             @ForAll @IntRange(min = 1, max = 10) int toggleCount) {
        PlayerChannelState state = new PlayerChannelState(playerId, channel, ChatMode.HYBRID);

        for (int i = 0; i < toggleCount; i++) {
            state.toggleMode();
        }

        // After odd toggles: REPLACE, after even toggles: HYBRID
        ChatMode expected = (toggleCount % 2 == 1) ? ChatMode.REPLACE : ChatMode.HYBRID;
        Assertions.assertThat(state.getChatMode()).isEqualTo(expected);
    }
}
