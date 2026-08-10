package com.nova.link.auth;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for PermissionManager.
 * 
 * Tests correctness properties defined in the design document.
 */
public class PermissionManagerPropertyTest {

    /**
     * **Feature: starchat-starlink, Property 4: Permission Hierarchy Enforcement**
     * 
     * For any operation requiring a specific permission level, users with lower
     * permission levels should receive NC-403 error.
     * 
     * **Validates: Requirements 2.7**
     */
    @Property(tries = 100)
    void lowerPermissionLevelsDeniedForHigherOperations(
            @ForAll("playerUuids") UUID playerId,
            @ForAll("channelIds") String channelId,
            @ForAll("permissionLevels") PermissionLevel requiredLevel,
            @ForAll("permissionLevels") PermissionLevel actualLevel
    ) {
        // Only test when actual level is lower than required
        Assume.that(actualLevel.getLevel() < requiredLevel.getLevel());

        PermissionManager manager = new PermissionManager();
        setupPlayerWithLevel(manager, playerId, channelId, actualLevel);

        // Check permission for the required level
        PermissionResult result = manager.checkPermission(playerId, channelId, requiredLevel);

        // Should be denied with NC-403
        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("NC-403");
    }

    /**
     * Property 4 (continued): Users with sufficient permission level should be allowed.
     * 
     * **Validates: Requirements 2.7**
     */
    @Property(tries = 100)
    void sufficientPermissionLevelsAllowed(
            @ForAll("playerUuids") UUID playerId,
            @ForAll("channelIds") String channelId,
            @ForAll("permissionLevels") PermissionLevel requiredLevel,
            @ForAll("permissionLevels") PermissionLevel actualLevel
    ) {
        // Only test when actual level is >= required
        Assume.that(actualLevel.getLevel() >= requiredLevel.getLevel());

        PermissionManager manager = new PermissionManager();
        setupPlayerWithLevel(manager, playerId, channelId, actualLevel);

        // Check permission for the required level
        PermissionResult result = manager.checkPermission(playerId, channelId, requiredLevel);

        // Should be allowed
        assertThat(result.isAllowed()).isTrue();
        assertThat(result.getErrorCode()).isNull();
    }

    /**
     * Property: Permission hierarchy is strictly ordered.
     * SUPER_ADMIN > CLIENT_ADMIN > CHANNEL_ADMIN > PLAYER
     */
    @Property(tries = 100)
    void permissionHierarchyIsStrictlyOrdered() {
        assertThat(PermissionLevel.SUPER_ADMIN.getLevel())
            .isGreaterThan(PermissionLevel.CLIENT_ADMIN.getLevel());
        assertThat(PermissionLevel.CLIENT_ADMIN.getLevel())
            .isGreaterThan(PermissionLevel.CHANNEL_ADMIN.getLevel());
        assertThat(PermissionLevel.CHANNEL_ADMIN.getLevel())
            .isGreaterThan(PermissionLevel.PLAYER.getLevel());
    }

    /**
     * Property: hasAtLeast is reflexive - a level always has at least itself.
     */
    @Property(tries = 100)
    void hasAtLeastIsReflexive(
            @ForAll("permissionLevels") PermissionLevel level
    ) {
        assertThat(level.hasAtLeast(level)).isTrue();
    }

    /**
     * Property: hasAtLeast is transitive.
     * If A >= B and B >= C, then A >= C.
     */
    @Property(tries = 100)
    void hasAtLeastIsTransitive(
            @ForAll("permissionLevels") PermissionLevel a,
            @ForAll("permissionLevels") PermissionLevel b,
            @ForAll("permissionLevels") PermissionLevel c
    ) {
        Assume.that(a.hasAtLeast(b) && b.hasAtLeast(c));
        assertThat(a.hasAtLeast(c)).isTrue();
    }

    /**
     * Property: Super admin session grants SUPER_ADMIN level.
     */
    @Property(tries = 100)
    void superAdminSessionGrantsHighestLevel(
            @ForAll("playerUuids") UUID playerId,
            @ForAll("channelIds") String channelId,
            @ForAll @StringLength(min = 8, max = 32) String password
    ) {
        PermissionManager manager = new PermissionManager();
        String passwordHash = AuthManager.hashPassword(password);
        
        // Register and authenticate super admin
        SuperAdminCredentials credentials = new SuperAdminCredentials(playerId, passwordHash);
        manager.registerSuperAdmin(credentials);
        AuthResult authResult = manager.authenticateSuperAdmin(playerId, passwordHash);
        
        assertThat(authResult.isSuccess()).isTrue();
        
        // Should have SUPER_ADMIN level
        PermissionLevel level = manager.getPermissionLevel(playerId, channelId);
        assertThat(level).isEqualTo(PermissionLevel.SUPER_ADMIN);
    }

    /**
     * Property: Client admin registration grants CLIENT_ADMIN level.
     */
    @Property(tries = 100)
    void clientAdminRegistrationGrantsClientAdminLevel(
            @ForAll("playerUuids") UUID playerId,
            @ForAll("clientIds") String clientId,
            @ForAll("channelIds") String channelId
    ) {
        PermissionManager manager = new PermissionManager();
        
        // Register as client admin
        manager.registerClientAdmin(playerId, clientId);
        
        // Should have CLIENT_ADMIN level
        PermissionLevel level = manager.getPermissionLevel(playerId, channelId);
        assertThat(level).isEqualTo(PermissionLevel.CLIENT_ADMIN);
    }

    /**
     * Property: Channel admin grant gives CHANNEL_ADMIN level for that channel.
     */
    @Property(tries = 100)
    void channelAdminGrantGivesChannelAdminLevel(
            @ForAll("playerUuids") UUID playerId,
            @ForAll("channelIds") String channelId
    ) {
        PermissionManager manager = new PermissionManager();
        
        // Grant channel admin
        manager.grantChannelAdmin(channelId, playerId);
        
        // Should have CHANNEL_ADMIN level for that channel
        PermissionLevel level = manager.getPermissionLevel(playerId, channelId);
        assertThat(level).isEqualTo(PermissionLevel.CHANNEL_ADMIN);
    }

    /**
     * Property: Channel admin is channel-specific.
     * Being admin of one channel doesn't make you admin of another.
     */
    @Property(tries = 100)
    void channelAdminIsChannelSpecific(
            @ForAll("playerUuids") UUID playerId,
            @ForAll("channelIds") String adminChannel,
            @ForAll("channelIds") String otherChannel
    ) {
        Assume.that(!adminChannel.equals(otherChannel));
        
        PermissionManager manager = new PermissionManager();
        
        // Grant channel admin for one channel
        manager.grantChannelAdmin(adminChannel, playerId);
        
        // Should be CHANNEL_ADMIN for admin channel
        assertThat(manager.getPermissionLevel(playerId, adminChannel))
            .isEqualTo(PermissionLevel.CHANNEL_ADMIN);
        
        // Should be PLAYER for other channel
        assertThat(manager.getPermissionLevel(playerId, otherChannel))
            .isEqualTo(PermissionLevel.PLAYER);
    }

    /**
     * Property: Default permission level is PLAYER.
     */
    @Property(tries = 100)
    void defaultPermissionLevelIsPlayer(
            @ForAll("playerUuids") UUID playerId,
            @ForAll("channelIds") String channelId
    ) {
        PermissionManager manager = new PermissionManager();
        
        // No permissions granted
        PermissionLevel level = manager.getPermissionLevel(playerId, channelId);
        assertThat(level).isEqualTo(PermissionLevel.PLAYER);
    }

    /**
     * Property: Super admin level overrides all other levels.
     */
    @Property(tries = 100)
    void superAdminOverridesOtherLevels(
            @ForAll("playerUuids") UUID playerId,
            @ForAll("clientIds") String clientId,
            @ForAll("channelIds") String channelId,
            @ForAll @StringLength(min = 8, max = 32) String password
    ) {
        PermissionManager manager = new PermissionManager();
        String passwordHash = AuthManager.hashPassword(password);
        
        // Grant all lower levels
        manager.registerClientAdmin(playerId, clientId);
        manager.grantChannelAdmin(channelId, playerId);
        
        // Also authenticate as super admin
        SuperAdminCredentials credentials = new SuperAdminCredentials(playerId, passwordHash);
        manager.registerSuperAdmin(credentials);
        manager.authenticateSuperAdmin(playerId, passwordHash);
        
        // Should have SUPER_ADMIN level (highest)
        PermissionLevel level = manager.getPermissionLevel(playerId, channelId);
        assertThat(level).isEqualTo(PermissionLevel.SUPER_ADMIN);
    }

    // ==================== Providers ====================

    @Provide
    Arbitrary<UUID> playerUuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    @Provide
    Arbitrary<String> channelIds() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(20)
                .map(s -> "channel-" + s);
    }

    @Provide
    Arbitrary<String> clientIds() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(20)
                .map(s -> "client-" + s);
    }

    @Provide
    Arbitrary<PermissionLevel> permissionLevels() {
        return Arbitraries.of(PermissionLevel.values());
    }

    // ==================== Helper Methods ====================

    /**
     * Sets up a player with the specified permission level.
     */
    private void setupPlayerWithLevel(PermissionManager manager, UUID playerId, 
                                       String channelId, PermissionLevel level) {
        switch (level) {
            case SUPER_ADMIN:
                String passwordHash = AuthManager.hashPassword("test-password");
                SuperAdminCredentials credentials = new SuperAdminCredentials(playerId, passwordHash);
                manager.registerSuperAdmin(credentials);
                manager.authenticateSuperAdmin(playerId, passwordHash);
                break;
            case CLIENT_ADMIN:
                manager.registerClientAdmin(playerId, "test-client");
                break;
            case CHANNEL_ADMIN:
                manager.grantChannelAdmin(channelId, playerId);
                break;
            case PLAYER:
                // Default level, no setup needed
                break;
        }
    }
}
