package com.nova.link.channel;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for WorldFilter.
 * 
 * Tests Property 8: World Filter Membership from the design document.
 * 
 * **Feature: starchat-starlink, Property 8: World Filter Membership**
 * For any channel with `allowed_worlds` configured, a player should be a member 
 * if and only if their current world is in the allowed list.
 * 
 * **Validates: Requirements 6.1, 6.2, 6.3, 6.4**
 */
public class WorldFilterPropertyTest {

    /**
     * **Feature: starchat-starlink, Property 8: World Filter Membership**
     * 
     * Property: When a channel has allowed_worlds configured, a player in one of those
     * worlds should be allowed (shouldBeMember returns true).
     * 
     * **Validates: Requirements 6.1, 6.2**
     */
    @Property(tries = 100)
    void playerInAllowedWorldShouldBeMember(
            @ForAll @Size(min = 1, max = 10) List<@StringLength(min = 1, max = 30) String> allowedWorlds
    ) {
        // Filter out empty/whitespace-only strings (they are not valid world names)
        Set<String> validWorlds = new HashSet<>();
        for (String world : allowedWorlds) {
            if (world != null && !world.trim().isEmpty()) {
                validWorlds.add(world.trim());
            }
        }
        
        // Ensure we have at least one valid world
        Assume.that(!validWorlds.isEmpty());
        
        // Create world filter with allowed worlds
        WorldFilter filter = new WorldFilter(new ArrayList<>(validWorlds));
        
        // PROPERTY: For any world in the allowed list, shouldBeMember returns true
        for (String world : validWorlds) {
            assertThat(filter.shouldBeMember(world))
                    .as("Player in allowed world '%s' should be a member", world)
                    .isTrue();
            
            assertThat(filter.isWorldAllowed(world))
                    .as("World '%s' should be allowed", world)
                    .isTrue();
        }
    }

    /**
     * **Feature: starchat-starlink, Property 8: World Filter Membership**
     * 
     * Property: When a channel has allowed_worlds configured, a player NOT in those
     * worlds should NOT be allowed (shouldBeMember returns false).
     * 
     * **Validates: Requirements 6.1, 6.3**
     */
    @Property(tries = 100)
    void playerNotInAllowedWorldShouldNotBeMember(
            @ForAll @Size(min = 1, max = 10) List<@StringLength(min = 1, max = 30) String> allowedWorlds,
            @ForAll @StringLength(min = 1, max = 30) String playerWorld
    ) {
        // Filter out empty/whitespace-only strings (they are not valid world names)
        Set<String> validWorlds = new HashSet<>();
        for (String world : allowedWorlds) {
            if (world != null && !world.trim().isEmpty()) {
                validWorlds.add(world.trim());
            }
        }
        
        // Ensure we have at least one valid world
        Assume.that(!validWorlds.isEmpty());
        
        // Ensure player's world is valid (non-empty) and NOT in the allowed list
        Assume.that(playerWorld != null && !playerWorld.trim().isEmpty());
        Assume.that(!validWorlds.contains(playerWorld.trim()));
        
        // Create world filter with allowed worlds
        WorldFilter filter = new WorldFilter(new ArrayList<>(validWorlds));
        
        // PROPERTY: Player in a world NOT in the allowed list should NOT be a member
        assertThat(filter.shouldBeMember(playerWorld))
                .as("Player in non-allowed world '%s' should NOT be a member", playerWorld)
                .isFalse();
        
        assertThat(filter.isWorldAllowed(playerWorld))
                .as("World '%s' should NOT be allowed", playerWorld)
                .isFalse();
    }

    /**
     * **Feature: starchat-starlink, Property 8: World Filter Membership**
     * 
     * Property: When a channel has NO allowed_worlds configured (empty filter),
     * ALL players should be allowed regardless of their world.
     * 
     * **Validates: Requirements 6.4**
     */
    @Property(tries = 100)
    void noWorldFilterAllowsAllWorlds(
            @ForAll @StringLength(min = 1, max = 30) String anyWorld
    ) {
        // Create empty world filter (no restrictions)
        WorldFilter filter = new WorldFilter();
        
        // PROPERTY: With no restrictions, any world should be allowed
        assertThat(filter.hasRestrictions())
                .as("Empty filter should have no restrictions")
                .isFalse();
        
        assertThat(filter.shouldBeMember(anyWorld))
                .as("Player in any world '%s' should be a member when no filter", anyWorld)
                .isTrue();
        
        assertThat(filter.isWorldAllowed(anyWorld))
                .as("Any world '%s' should be allowed when no filter", anyWorld)
                .isTrue();
    }

    /**
     * **Feature: starchat-starlink, Property 8: World Filter Membership**
     *
     * Discriminating property: membership must match the expected outcome
     * computed against the canonical world set (template mapping). Previously
     * this test only asserted determinism (same answer three times), which any
     * constant-true or constant-false implementation would also satisfy. Here
     * we compare the filter's verdict against the membership predicted from
     * the input worlds: when restrictions exist, exactly the worlds present in
     * the allowed list (and only those) must be members; when the filter has
     * no valid worlds, everything is a member.
     *
     * **Validates: Requirements 6.1, 6.2, 6.3, 6.4**
     */
    // Shrinking disabled: jqwik's string-list shrinker has a Long-standing
    // ClassCastException (String→Character) on this 3-parameter shape that
    // would mask the assertion failure in the report. The assertion itself
    // already discriminates; shrinking only picks a smaller counter-example.
    @Property(tries = 100, shrinking = ShrinkingMode.OFF)
    void worldFilterMembershipMatchesExpectedSet(
            @ForAll("allowedWorldLists") List<String> allowedWorlds,
            @ForAll("worldNames") String inCandidateWorld,
            @ForAll("worldNames") String outCandidateWorld
    ) {
        // Build canonical "expected" set the same way WorldFilter would.
        Set<String> expected = new HashSet<>();
        for (String world : allowedWorlds) {
            if (world != null && !world.trim().isEmpty()) {
                expected.add(world.trim());
            }
        }

        WorldFilter filter = new WorldFilter(allowedWorlds);

        // Two contrasting probes: one whose membership we know by construction
        // (in-set when non-empty), one whose non-membership we know by
        // construction (must not be in the set).
        Assume.that(inCandidateWorld != null && !inCandidateWorld.trim().isEmpty());
        Assume.that(outCandidateWorld != null && !outCandidateWorld.trim().isEmpty());
        Assume.that(!expected.contains(outCandidateWorld.trim()));

        String probeIn = inCandidateWorld.trim();
        String probeOut = outCandidateWorld.trim();

        boolean expectedInVerdict = expected.isEmpty() || expected.contains(probeIn);
        boolean expectedOutVerdict = expected.isEmpty() ? true : false;

        assertThat(filter.shouldBeMember(probeIn))
                .as("Membership for candidate '%s' must match expected verdict "
                        + "(allowed=%s)", probeIn, expected)
                .isEqualTo(expectedInVerdict);

        assertThat(filter.shouldBeMember(probeOut))
                .as("Membership for non-listed candidate '%s' must match expected "
                        + "verdict (allowed=%s)", probeOut, expected)
                .isEqualTo(expectedOutVerdict);

        // Determinism is a free by-product: invoke twice and confirm stability
        // so we still cover the original intent (same input -> same answer).
        assertThat(filter.shouldBeMember(probeIn))
                .as("Second invocation must agree with first for '%s'", probeIn)
                .isEqualTo(expectedInVerdict);
        assertThat(filter.shouldBeMember(probeOut))
                .as("Second invocation must agree with first for '%s'", probeOut)
                .isEqualTo(expectedOutVerdict);
    }

    /** Bounded alphabetic world names (avoids a jqwik unicode-shrink String→Char CastX). */
    @Provide
    Arbitrary<String> worldNames() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
    }

    /** Bounded list of allowed worlds, using the safe name arbitrary. */
    @Provide
    Arbitrary<List<String>> allowedWorldLists() {
        return worldNames().list().ofMinSize(0).ofMaxSize(10);
    }

    /**
     * **Feature: starchat-starlink, Property 8: World Filter Membership**
     *
     * Property: World filter correctly identifies whether it has restrictions.
     *
     * **Validates: Requirements 6.1, 6.4**
     */
    @Property(tries = 100)
    void hasRestrictionsReflectsAllowedWorldsState(
            @ForAll @Size(min = 0, max = 10) List<@StringLength(min = 1, max = 30) String> allowedWorlds
    ) {
        // Filter out empty/whitespace-only strings (they are ignored by WorldFilter)
        List<String> validWorlds = new ArrayList<>();
        for (String world : allowedWorlds) {
            if (world != null && !world.trim().isEmpty()) {
                validWorlds.add(world);
            }
        }
        
        // Create world filter
        WorldFilter filter = new WorldFilter(allowedWorlds);
        
        // PROPERTY: hasRestrictions should be true IFF there are valid allowed worlds
        boolean expectedHasRestrictions = !validWorlds.isEmpty();
        assertThat(filter.hasRestrictions())
                .as("hasRestrictions should reflect whether valid allowed worlds exist")
                .isEqualTo(expectedHasRestrictions);
    }

    /**
     * **Feature: starchat-starlink, Property 8: World Filter Membership**
     * 
     * Property: World filter membership is consistent with the allowed worlds set.
     * A world is allowed IFF it's in the allowed worlds set (when restrictions exist).
     * 
     * **Validates: Requirements 6.1, 6.2, 6.3, 6.4**
     */
    @Property(tries = 100)
    void membershipConsistentWithAllowedWorldsSet(
            @ForAll @Size(min = 1, max = 10) List<@StringLength(min = 1, max = 30) String> allowedWorlds,
            @ForAll @StringLength(min = 1, max = 30) String testWorld
    ) {
        // Ensure we have valid worlds
        Set<String> validWorlds = new HashSet<>();
        for (String world : allowedWorlds) {
            if (world != null && !world.trim().isEmpty()) {
                validWorlds.add(world.trim());
            }
        }
        Assume.that(!validWorlds.isEmpty());
        
        // Create world filter
        WorldFilter filter = new WorldFilter(allowedWorlds);
        
        // PROPERTY: shouldBeMember returns true IFF world is in allowed set
        boolean isInAllowedSet = validWorlds.contains(testWorld.trim());
        boolean shouldBeMember = filter.shouldBeMember(testWorld);
        
        assertThat(shouldBeMember)
                .as("shouldBeMember('%s') should equal whether world is in allowed set", testWorld)
                .isEqualTo(isInAllowedSet);
    }

    /**
     * **Feature: starchat-starlink, Property 8: World Filter Membership**
     * 
     * Property: Null or empty world names are not allowed when restrictions exist.
     * 
     * **Validates: Requirements 6.1, 6.3**
     */
    @Property(tries = 100)
    void nullOrEmptyWorldNotAllowedWithRestrictions(
            @ForAll @Size(min = 1, max = 10) List<@StringLength(min = 1, max = 30) String> allowedWorlds
    ) {
        // Ensure we have valid worlds
        Set<String> validWorlds = new HashSet<>();
        for (String world : allowedWorlds) {
            if (world != null && !world.trim().isEmpty()) {
                validWorlds.add(world.trim());
            }
        }
        Assume.that(!validWorlds.isEmpty());
        
        // Create world filter with restrictions
        WorldFilter filter = new WorldFilter(allowedWorlds);
        
        // PROPERTY: Null world should not be allowed
        assertThat(filter.shouldBeMember(null))
                .as("Null world should not be allowed when restrictions exist")
                .isFalse();
        
        // PROPERTY: Empty world should not be allowed
        assertThat(filter.shouldBeMember(""))
                .as("Empty world should not be allowed when restrictions exist")
                .isFalse();
        
        // PROPERTY: Whitespace-only world should not be allowed
        assertThat(filter.shouldBeMember("   "))
                .as("Whitespace-only world should not be allowed when restrictions exist")
                .isFalse();
    }

    /**
     * **Feature: starchat-starlink, Property 8: World Filter Membership**
     * 
     * Property: WorldFilter created from ChannelConfig correctly inherits allowed worlds.
     * 
     * **Validates: Requirements 6.1**
     */
    @Property(tries = 100)
    void worldFilterFromConfigInheritsAllowedWorlds(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String clientId,
            @ForAll @Size(min = 1, max = 10) List<@StringLength(min = 1, max = 30) String> allowedWorlds
    ) {
        // Create channel config with allowed worlds
        ChannelConfig config = ChannelConfig.builder()
                .id(channelId)
                .displayName("Test Channel")
                .scope(ChannelScope.SERVER)
                .clientId(clientId)
                .allowedWorlds(allowedWorlds)
                .build();
        
        // Create world filter from config
        WorldFilter filter = WorldFilter.fromConfig(config);
        
        // Filter out empty/whitespace-only strings
        Set<String> validWorlds = new HashSet<>();
        for (String world : allowedWorlds) {
            if (world != null && !world.trim().isEmpty()) {
                validWorlds.add(world.trim());
            }
        }
        
        // PROPERTY: Filter should have same allowed worlds as config
        assertThat(filter.getAllowedWorlds())
                .as("WorldFilter should inherit allowed worlds from config")
                .containsExactlyInAnyOrderElementsOf(validWorlds);
    }

    /**
     * **Feature: starchat-starlink, Property 8: World Filter Membership**
     * 
     * Property: WorldFilter created from Channel correctly inherits allowed worlds.
     * Note: WorldFilter filters out empty/whitespace strings, so we compare valid worlds only.
     * 
     * **Validates: Requirements 6.1**
     */
    @Property(tries = 100)
    void worldFilterFromChannelInheritsAllowedWorlds(
            @ForAll @StringLength(min = 1, max = 20) String channelId,
            @ForAll @StringLength(min = 1, max = 20) String clientId,
            @ForAll @Size(min = 1, max = 10) List<@StringLength(min = 1, max = 30) String> allowedWorlds
    ) {
        // Ensure channelId and clientId are valid (non-empty)
        Assume.that(channelId != null && !channelId.trim().isEmpty());
        Assume.that(clientId != null && !clientId.trim().isEmpty());
        
        // Create channel with allowed worlds
        Channel channel = new Channel(channelId, "Test Channel", ChannelScope.SERVER, clientId);
        channel.setAllowedWorlds(allowedWorlds);
        
        // Create world filter from channel
        WorldFilter filter = WorldFilter.fromChannel(channel);
        
        // Calculate expected valid worlds (WorldFilter filters out empty/whitespace strings)
        Set<String> expectedValidWorlds = new HashSet<>();
        for (String world : channel.getAllowedWorlds()) {
            if (world != null && !world.trim().isEmpty()) {
                expectedValidWorlds.add(world.trim());
            }
        }
        
        // PROPERTY: Filter should have same valid allowed worlds as channel
        assertThat(filter.getAllowedWorlds())
                .as("WorldFilter should inherit valid allowed worlds from channel")
                .containsExactlyInAnyOrderElementsOf(expectedValidWorlds);
    }

    /**
     * **Feature: starchat-starlink, Property 8: World Filter Membership**
     * 
     * Property: Two WorldFilters with the same allowed worlds should be equal.
     * 
     * **Validates: Requirements 6.1**
     */
    @Property(tries = 100)
    void worldFiltersWithSameWorldsAreEqual(
            @ForAll @Size(min = 0, max = 10) List<@StringLength(min = 1, max = 30) String> allowedWorlds
    ) {
        // Create two filters with the same worlds
        WorldFilter filter1 = new WorldFilter(allowedWorlds);
        WorldFilter filter2 = new WorldFilter(allowedWorlds);
        
        // PROPERTY: Filters with same worlds should be equal
        assertThat(filter1)
                .as("WorldFilters with same allowed worlds should be equal")
                .isEqualTo(filter2);
        
        assertThat(filter1.hashCode())
                .as("WorldFilters with same allowed worlds should have same hashCode")
                .isEqualTo(filter2.hashCode());
    }
}
