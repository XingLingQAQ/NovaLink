package com.nova.chat.bukkit.world;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for auto-routing world change logic.
 * 
 * Tests Property 12: Auto-Routing World Change from the design document.
 * 
 * **Feature: starchat-starlink, Property 12: Auto-Routing World Change**
 * For any player changing worlds, they should automatically join the most specific 
 * applicable channel for the new world.
 * 
 * **Validates: Requirements 9.1, 9.3**
 */
public class AutoRoutingWorldChangePropertyTest {

    private static final String DEFAULT_CHANNEL = "local";

    /**
     * **Feature: starchat-starlink, Property 12: Auto-Routing World Change**
     * 
     * Property: When a player moves to a world with a specific channel,
     * they should be routed to that channel.
     * 
     * **Validates: Requirements 9.1**
     */
    @Property(tries = 100)
    void playerMovingToWorldWithSpecificChannelShouldBeRouted(
            @ForAll @StringLength(min = 1, max = 20) String worldChannelId,
            @ForAll @StringLength(min = 1, max = 20) String targetWorld,
            @ForAll @StringLength(min = 1, max = 20) String currentChannel
    ) {
        // Ensure valid inputs
        Assume.that(worldChannelId != null && !worldChannelId.trim().isEmpty());
        Assume.that(targetWorld != null && !targetWorld.trim().isEmpty());
        Assume.that(currentChannel != null && !currentChannel.trim().isEmpty());
        // Ensure current channel is different from world channel
        Assume.that(!currentChannel.equals(worldChannelId));
        
        AutoRoutingLogic logic = new AutoRoutingLogic(DEFAULT_CHANNEL);
        
        // Register a world-specific channel
        logic.registerWorldChannel(worldChannelId, Arrays.asList(targetWorld));
        
        // PROPERTY: Player should be routed to the world-specific channel
        AutoRoutingLogic.RoutingDecision decision = logic.determineTargetChannel(currentChannel, targetWorld);
        
        assertThat(decision.getTargetChannel())
                .as("Player moving to world '%s' should be routed to channel '%s'", targetWorld, worldChannelId)
                .isEqualTo(worldChannelId);
        
        assertThat(decision.isSwitchRequired())
                .as("Switch should be required when moving to world with specific channel")
                .isTrue();
    }

    /**
     * **Feature: starchat-starlink, Property 12: Auto-Routing World Change**
     * 
     * Property: When a player is already in the correct channel for a world,
     * no switch should be required.
     * 
     * **Validates: Requirements 9.1**
     */
    @Property(tries = 100)
    void playerAlreadyInCorrectChannelShouldNotSwitch(
            @ForAll @StringLength(min = 1, max = 20) String worldChannelId,
            @ForAll @StringLength(min = 1, max = 20) String targetWorld
    ) {
        // Ensure valid inputs
        Assume.that(worldChannelId != null && !worldChannelId.trim().isEmpty());
        Assume.that(targetWorld != null && !targetWorld.trim().isEmpty());
        
        AutoRoutingLogic logic = new AutoRoutingLogic(DEFAULT_CHANNEL);
        
        // Register a world-specific channel
        logic.registerWorldChannel(worldChannelId, Arrays.asList(targetWorld));
        
        // Player is already in the correct channel
        AutoRoutingLogic.RoutingDecision decision = logic.determineTargetChannel(worldChannelId, targetWorld);
        
        // PROPERTY: No switch should be required
        assertThat(decision.isSwitchRequired())
                .as("No switch should be required when already in correct channel")
                .isFalse();
        
        assertThat(decision.getTargetChannel())
                .as("Target channel should remain the same")
                .isEqualTo(worldChannelId);
    }

    /**
     * **Feature: starchat-starlink, Property 12: Auto-Routing World Change**
     * 
     * Property: When a player moves to a world without a specific channel,
     * and their current channel is world-restricted and doesn't include the new world,
     * they should fall back to the default channel.
     * 
     * **Validates: Requirements 9.3**
     */
    @Property(tries = 100)
    void playerLeavingWorldRestrictedChannelShouldFallbackToDefault(
            @ForAll @StringLength(min = 1, max = 20) String worldChannelId,
            @ForAll @StringLength(min = 1, max = 20) String restrictedWorld,
            @ForAll @StringLength(min = 1, max = 20) String newWorld
    ) {
        // Ensure valid inputs
        Assume.that(worldChannelId != null && !worldChannelId.trim().isEmpty());
        Assume.that(restrictedWorld != null && !restrictedWorld.trim().isEmpty());
        Assume.that(newWorld != null && !newWorld.trim().isEmpty());
        // Ensure new world is different from restricted world
        Assume.that(!newWorld.equals(restrictedWorld));
        
        AutoRoutingLogic logic = new AutoRoutingLogic(DEFAULT_CHANNEL);
        
        // Register a world-restricted channel
        logic.registerWorldChannel(worldChannelId, Arrays.asList(restrictedWorld));
        
        // Player is in the world-restricted channel and moves to a different world
        AutoRoutingLogic.RoutingDecision decision = logic.determineTargetChannel(worldChannelId, newWorld);
        
        // PROPERTY: Player should fall back to default channel
        assertThat(decision.getTargetChannel())
                .as("Player leaving world-restricted channel should fall back to default")
                .isEqualTo(DEFAULT_CHANNEL);
        
        assertThat(decision.isSwitchRequired())
                .as("Switch should be required when leaving world-restricted channel")
                .isTrue();
    }

    /**
     * **Feature: starchat-starlink, Property 12: Auto-Routing World Change**
     * 
     * Property: When a player moves to a world without a specific channel,
     * and their current channel is NOT world-restricted,
     * they should stay in their current channel.
     * 
     * **Validates: Requirements 9.3**
     */
    @Property(tries = 100)
    void playerInUnrestrictedChannelShouldStayWhenNoSpecificChannel(
            @ForAll @StringLength(min = 1, max = 20) String currentChannel,
            @ForAll @StringLength(min = 1, max = 20) String newWorld
    ) {
        // Ensure valid inputs
        Assume.that(currentChannel != null && !currentChannel.trim().isEmpty());
        Assume.that(newWorld != null && !newWorld.trim().isEmpty());
        
        AutoRoutingLogic logic = new AutoRoutingLogic(DEFAULT_CHANNEL);
        
        // No world-specific channels registered
        // Player is in an unrestricted channel
        AutoRoutingLogic.RoutingDecision decision = logic.determineTargetChannel(currentChannel, newWorld);
        
        // PROPERTY: Player should stay in current channel
        assertThat(decision.isSwitchRequired())
                .as("No switch should be required when in unrestricted channel and no specific channel for world")
                .isFalse();
        
        assertThat(decision.getTargetChannel())
                .as("Target channel should remain the same")
                .isEqualTo(currentChannel);
    }

    /**
     * **Feature: starchat-starlink, Property 12: Auto-Routing World Change**
     * 
     * Property: World-channel mappings are correctly maintained after registration.
     * 
     * **Validates: Requirements 9.1**
     */
    @Property(tries = 100)
    void worldChannelMappingsAreCorrectlyMaintained(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @Size(min = 1, max = 5) List<@StringLength(min = 1, max = 20) String> worlds
    ) {
        // Ensure valid inputs
        Assume.that(channelId != null && !channelId.trim().isEmpty());
        
        // Filter valid worlds
        Set<String> validWorlds = new HashSet<>();
        for (String world : worlds) {
            if (world != null && !world.trim().isEmpty()) {
                validWorlds.add(world.trim());
            }
        }
        Assume.that(!validWorlds.isEmpty());
        
        AutoRoutingLogic logic = new AutoRoutingLogic(DEFAULT_CHANNEL);
        logic.registerWorldChannel(channelId, new ArrayList<>(validWorlds));
        
        // PROPERTY: All registered worlds should map to the channel
        for (String world : validWorlds) {
            Set<String> channelsForWorld = logic.getChannelsForWorld(world);
            assertThat(channelsForWorld)
                    .as("World '%s' should map to channel '%s'", world, channelId)
                    .contains(channelId);
        }
        
        // PROPERTY: Channel should map to all registered worlds
        Set<String> worldsForChannel = logic.getWorldsForChannel(channelId);
        assertThat(worldsForChannel)
                .as("Channel '%s' should map to all registered worlds", channelId)
                .containsExactlyInAnyOrderElementsOf(validWorlds);
    }

    /**
     * **Feature: starchat-starlink, Property 12: Auto-Routing World Change**
     * 
     * Property: Unregistering a channel removes all its world mappings.
     * 
     * **Validates: Requirements 9.1**
     */
    @Property(tries = 100)
    void unregisteringChannelRemovesAllMappings(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @Size(min = 1, max = 5) List<@StringLength(min = 1, max = 20) String> worlds
    ) {
        // Ensure valid inputs
        Assume.that(channelId != null && !channelId.trim().isEmpty());
        
        // Filter valid worlds
        Set<String> validWorlds = new HashSet<>();
        for (String world : worlds) {
            if (world != null && !world.trim().isEmpty()) {
                validWorlds.add(world.trim());
            }
        }
        Assume.that(!validWorlds.isEmpty());
        
        AutoRoutingLogic logic = new AutoRoutingLogic(DEFAULT_CHANNEL);
        logic.registerWorldChannel(channelId, new ArrayList<>(validWorlds));
        
        // Unregister the channel
        logic.unregisterWorldChannel(channelId);
        
        // PROPERTY: Channel should no longer be world-restricted
        assertThat(logic.isWorldRestrictedChannel(channelId))
                .as("Unregistered channel should not be world-restricted")
                .isFalse();
        
        // PROPERTY: Worlds should no longer map to the channel
        for (String world : validWorlds) {
            Set<String> channelsForWorld = logic.getChannelsForWorld(world);
            assertThat(channelsForWorld)
                    .as("World '%s' should not map to unregistered channel '%s'", world, channelId)
                    .doesNotContain(channelId);
        }
    }

    /**
     * **Feature: starchat-starlink, Property 12: Auto-Routing World Change**
     * 
     * Property: Routing decisions are deterministic - same inputs produce same outputs.
     * 
     * **Validates: Requirements 9.1, 9.3**
     */
    @Property(tries = 100)
    void routingDecisionsAreDeterministic(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String world,
            @ForAll @StringLength(min = 1, max = 20) String currentChannel
    ) {
        // Ensure valid inputs
        Assume.that(channelId != null && !channelId.trim().isEmpty());
        Assume.that(world != null && !world.trim().isEmpty());
        Assume.that(currentChannel != null && !currentChannel.trim().isEmpty());
        
        AutoRoutingLogic logic = new AutoRoutingLogic(DEFAULT_CHANNEL);
        logic.registerWorldChannel(channelId, Arrays.asList(world));
        
        // Make multiple routing decisions with same inputs
        AutoRoutingLogic.RoutingDecision decision1 = logic.determineTargetChannel(currentChannel, world);
        AutoRoutingLogic.RoutingDecision decision2 = logic.determineTargetChannel(currentChannel, world);
        AutoRoutingLogic.RoutingDecision decision3 = logic.determineTargetChannel(currentChannel, world);
        
        // PROPERTY: All decisions should be identical
        assertThat(decision1)
                .as("Routing decisions should be deterministic")
                .isEqualTo(decision2)
                .isEqualTo(decision3);
    }

    /**
     * **Feature: starchat-starlink, Property 12: Auto-Routing World Change**
     * 
     * Property: Multiple channels can be registered for the same world.
     * 
     * **Validates: Requirements 9.1**
     */
    @Property(tries = 100)
    void multipleChannelsCanBeRegisteredForSameWorld(
            @ForAll @StringLength(min = 1, max = 20) String channel1,
            @ForAll @StringLength(min = 1, max = 20) String channel2,
            @ForAll @StringLength(min = 1, max = 20) String world
    ) {
        // Ensure valid and distinct inputs
        Assume.that(channel1 != null && !channel1.trim().isEmpty());
        Assume.that(channel2 != null && !channel2.trim().isEmpty());
        Assume.that(world != null && !world.trim().isEmpty());
        Assume.that(!channel1.equals(channel2));
        
        AutoRoutingLogic logic = new AutoRoutingLogic(DEFAULT_CHANNEL);
        logic.registerWorldChannel(channel1, Arrays.asList(world));
        logic.registerWorldChannel(channel2, Arrays.asList(world));
        
        // PROPERTY: Both channels should be registered for the world
        Set<String> channelsForWorld = logic.getChannelsForWorld(world);
        assertThat(channelsForWorld)
                .as("World '%s' should have both channels registered", world)
                .contains(channel1, channel2);
    }

    /**
     * **Feature: starchat-starlink, Property 12: Auto-Routing World Change**
     * 
     * Property: Invalid world names (null or empty) should not cause routing.
     * 
     * **Validates: Requirements 9.1**
     */
    @Property(tries = 100)
    void invalidWorldNamesShouldNotCauseRouting(
            @ForAll @StringLength(min = 1, max = 20) String currentChannel
    ) {
        // Ensure valid current channel
        Assume.that(currentChannel != null && !currentChannel.trim().isEmpty());
        
        AutoRoutingLogic logic = new AutoRoutingLogic(DEFAULT_CHANNEL);
        
        // PROPERTY: Null world should not cause switch
        AutoRoutingLogic.RoutingDecision nullDecision = logic.determineTargetChannel(currentChannel, null);
        assertThat(nullDecision.isSwitchRequired())
                .as("Null world should not cause switch")
                .isFalse();
        
        // PROPERTY: Empty world should not cause switch
        AutoRoutingLogic.RoutingDecision emptyDecision = logic.determineTargetChannel(currentChannel, "");
        assertThat(emptyDecision.isSwitchRequired())
                .as("Empty world should not cause switch")
                .isFalse();
        
        // PROPERTY: Whitespace-only world should not cause switch
        AutoRoutingLogic.RoutingDecision whitespaceDecision = logic.determineTargetChannel(currentChannel, "   ");
        assertThat(whitespaceDecision.isSwitchRequired())
                .as("Whitespace-only world should not cause switch")
                .isFalse();
    }

    /**
     * **Feature: starchat-starlink, Property 12: Auto-Routing World Change**
     * 
     * Property: Clearing mappings removes all world-channel associations.
     * 
     * **Validates: Requirements 9.1**
     */
    @Property(tries = 100)
    void clearingMappingsRemovesAllAssociations(
            @ForAll @Size(min = 1, max = 5) List<@StringLength(min = 1, max = 20) String> channels,
            @ForAll @Size(min = 1, max = 5) List<@StringLength(min = 1, max = 20) String> worlds
    ) {
        // Filter valid inputs
        Set<String> validChannels = new HashSet<>();
        for (String channel : channels) {
            if (channel != null && !channel.trim().isEmpty()) {
                validChannels.add(channel.trim());
            }
        }
        
        Set<String> validWorlds = new HashSet<>();
        for (String world : worlds) {
            if (world != null && !world.trim().isEmpty()) {
                validWorlds.add(world.trim());
            }
        }
        
        Assume.that(!validChannels.isEmpty() && !validWorlds.isEmpty());
        
        AutoRoutingLogic logic = new AutoRoutingLogic(DEFAULT_CHANNEL);
        
        // Register channels with worlds
        for (String channel : validChannels) {
            logic.registerWorldChannel(channel, new ArrayList<>(validWorlds));
        }
        
        // Clear all mappings
        logic.clearMappings();
        
        // PROPERTY: No channels should be world-restricted after clearing
        for (String channel : validChannels) {
            assertThat(logic.isWorldRestrictedChannel(channel))
                    .as("Channel '%s' should not be world-restricted after clearing", channel)
                    .isFalse();
        }
        
        // PROPERTY: No worlds should have channel mappings after clearing
        for (String world : validWorlds) {
            assertThat(logic.getChannelsForWorld(world))
                    .as("World '%s' should have no channel mappings after clearing", world)
                    .isEmpty();
        }
        
        // PROPERTY: Mapping count should be zero
        assertThat(logic.getMappingCount())
                .as("Mapping count should be zero after clearing")
                .isZero();
    }
}
