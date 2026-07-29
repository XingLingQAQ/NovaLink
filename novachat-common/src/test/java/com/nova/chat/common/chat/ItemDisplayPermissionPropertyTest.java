package com.nova.chat.common.chat;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for display permission enforcement.
 * 
 * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
 * **Validates: Requirements 12.5, 13.5, 14.5**
 * 
 * This test verifies that for any player without display permission,
 * [item], [inv], [ec] tags should be treated as plain text.
 */
class ItemDisplayPermissionPropertyTest {

    // ==================== Property 6: Display Permission Enforcement ====================

    /**
     * Property 6: Display Permission Enforcement - No Item Permission
     * 
     * For any player without item display permission, [item] and [i] tags
     * should not be processed (shouldProcessItemDisplay returns false).
     * 
     * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
     * **Validates: Requirements 12.5**
     */
    @Property(tries = 100)
    void playerWithoutItemPermissionCannotDisplayItem(
            @ForAll("validPlayerId") String playerId,
            @ForAll("messageWithItemTags") String message) {
        
        // Create a permission checker that denies item permission
        ItemDisplayPermissionChecker checker = new ItemDisplayPermissionChecker(
            new ItemDisplayPermissionChecker.AlwaysDenyPermissionProvider()
        );
        
        // Verify item display is not allowed
        assertThat(checker.canDisplayItem(playerId)).isFalse();
        assertThat(checker.shouldProcessItemDisplay(playerId, message)).isFalse();
    }

    /**
     * Property 6: Display Permission Enforcement - With Item Permission
     * 
     * For any player with item display permission, [item] and [i] tags
     * should be processed.
     * 
     * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
     * **Validates: Requirements 12.5**
     */
    @Property(tries = 100)
    void playerWithItemPermissionCanDisplayItem(
            @ForAll("validPlayerId") String playerId,
            @ForAll("messageWithItemTags") String message) {
        
        // Create a permission checker that allows all permissions
        ItemDisplayPermissionChecker checker = new ItemDisplayPermissionChecker(
            new ItemDisplayPermissionChecker.AlwaysAllowPermissionProvider()
        );
        
        // Verify item display is allowed
        assertThat(checker.canDisplayItem(playerId)).isTrue();
        assertThat(checker.shouldProcessItemDisplay(playerId, message)).isTrue();
    }

    /**
     * Property 6: Display Permission Enforcement - No Inventory Permission
     * 
     * For any player without inventory display permission, [inv] and [inventory] tags
     * should not be processed.
     * 
     * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
     * **Validates: Requirements 13.5**
     */
    @Property(tries = 100)
    void playerWithoutInventoryPermissionCannotDisplayInventory(
            @ForAll("validPlayerId") String playerId) {
        
        // Create a permission checker that denies inventory permission
        ItemDisplayPermissionChecker checker = new ItemDisplayPermissionChecker(
            new ItemDisplayPermissionChecker.AlwaysDenyPermissionProvider()
        );
        
        // Verify inventory display is not allowed
        assertThat(checker.canDisplayInventory(playerId)).isFalse();
    }

    /**
     * Property 6: Display Permission Enforcement - With Inventory Permission
     * 
     * For any player with inventory display permission, [inv] and [inventory] tags
     * should be processed.
     * 
     * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
     * **Validates: Requirements 13.5**
     */
    @Property(tries = 100)
    void playerWithInventoryPermissionCanDisplayInventory(
            @ForAll("validPlayerId") String playerId) {
        
        // Create a permission checker that allows all permissions
        ItemDisplayPermissionChecker checker = new ItemDisplayPermissionChecker(
            new ItemDisplayPermissionChecker.AlwaysAllowPermissionProvider()
        );
        
        // Verify inventory display is allowed
        assertThat(checker.canDisplayInventory(playerId)).isTrue();
    }

    /**
     * Property 6: Display Permission Enforcement - No Enderchest Permission
     * 
     * For any player without enderchest display permission, [ec] and [enderchest] tags
     * should not be processed.
     * 
     * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
     * **Validates: Requirements 14.5**
     */
    @Property(tries = 100)
    void playerWithoutEnderchestPermissionCannotDisplayEnderchest(
            @ForAll("validPlayerId") String playerId) {
        
        // Create a permission checker that denies enderchest permission
        ItemDisplayPermissionChecker checker = new ItemDisplayPermissionChecker(
            new ItemDisplayPermissionChecker.AlwaysDenyPermissionProvider()
        );
        
        // Verify enderchest display is not allowed
        assertThat(checker.canDisplayEnderchest(playerId)).isFalse();
    }

    /**
     * Property 6: Display Permission Enforcement - With Enderchest Permission
     * 
     * For any player with enderchest display permission, [ec] and [enderchest] tags
     * should be processed.
     * 
     * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
     * **Validates: Requirements 14.5**
     */
    @Property(tries = 100)
    void playerWithEnderchestPermissionCanDisplayEnderchest(
            @ForAll("validPlayerId") String playerId) {
        
        // Create a permission checker that allows all permissions
        ItemDisplayPermissionChecker checker = new ItemDisplayPermissionChecker(
            new ItemDisplayPermissionChecker.AlwaysAllowPermissionProvider()
        );
        
        // Verify enderchest display is allowed
        assertThat(checker.canDisplayEnderchest(playerId)).isTrue();
    }

    /**
     * Property 6: Display Permission Enforcement - processMessage consistency
     * 
     * For any player and message, processMessage should correctly reflect
     * the player's permissions for all display types.
     * 
     * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
     * **Validates: Requirements 12.5, 13.5, 14.5**
     */
    @Property(tries = 100)
    void processMessageReflectsAllPermissions(
            @ForAll("validPlayerId") String playerId,
            @ForAll("anyMessage") String message) {
        
        // Test with all permissions denied
        ItemDisplayPermissionChecker deniedChecker = new ItemDisplayPermissionChecker(
            new ItemDisplayPermissionChecker.AlwaysDenyPermissionProvider()
        );
        ItemDisplayPermissionChecker.DisplayProcessResult deniedResult = 
            deniedChecker.processMessage(playerId, message);
        
        assertThat(deniedResult.isItemDisplayAllowed()).isFalse();
        assertThat(deniedResult.isInventoryDisplayAllowed()).isFalse();
        assertThat(deniedResult.isEnderchestDisplayAllowed()).isFalse();
        assertThat(deniedResult.hasAnyPermission()).isFalse();
        
        // Test with all permissions allowed
        ItemDisplayPermissionChecker allowedChecker = new ItemDisplayPermissionChecker(
            new ItemDisplayPermissionChecker.AlwaysAllowPermissionProvider()
        );
        ItemDisplayPermissionChecker.DisplayProcessResult allowedResult = 
            allowedChecker.processMessage(playerId, message);
        
        assertThat(allowedResult.isItemDisplayAllowed()).isTrue();
        assertThat(allowedResult.isInventoryDisplayAllowed()).isTrue();
        assertThat(allowedResult.isEnderchestDisplayAllowed()).isTrue();
        assertThat(allowedResult.hasAnyPermission()).isTrue();
    }

    /**
     * Property 6: Display Permission Enforcement - Selective permissions
     * 
     * For any player with only item permission (not inventory or enderchest),
     * only item display should be allowed.
     * 
     * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
     * **Validates: Requirements 12.5, 13.5, 14.5**
     */
    @Property(tries = 100)
    void selectivePermissionsAreRespected(
            @ForAll("validPlayerId") String playerId,
            @ForAll("anyMessage") String message) {
        
        // Create a permission checker that only allows item permission
        ItemDisplayPermissionChecker itemOnlyChecker = new ItemDisplayPermissionChecker(
            (pid, permission) -> ItemDisplayPermissionChecker.PERMISSION_ITEM.equals(permission)
        );
        
        ItemDisplayPermissionChecker.DisplayProcessResult result = 
            itemOnlyChecker.processMessage(playerId, message);
        
        assertThat(result.isItemDisplayAllowed()).isTrue();
        assertThat(result.isInventoryDisplayAllowed()).isFalse();
        assertThat(result.isEnderchestDisplayAllowed()).isFalse();
        assertThat(result.hasAnyPermission()).isTrue();
    }

    /**
     * Property 6: Display Permission Enforcement - Empty/null message handling
     * 
     * For any player, empty or null messages should be handled gracefully
     * and return appropriate results.
     * 
     * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
     * **Validates: Requirements 12.5, 13.5, 14.5**
     */
    @Property(tries = 100)
    void emptyMessageHandledGracefully(
            @ForAll("validPlayerId") String playerId) {
        
        ItemDisplayPermissionChecker checker = new ItemDisplayPermissionChecker(
            new ItemDisplayPermissionChecker.AlwaysAllowPermissionProvider()
        );
        
        // Test with empty message
        ItemDisplayPermissionChecker.DisplayProcessResult emptyResult = 
            checker.processMessage(playerId, "");
        assertThat(emptyResult.getMessage()).isEmpty();
        
        // Test with null message
        ItemDisplayPermissionChecker.DisplayProcessResult nullResult = 
            checker.processMessage(playerId, null);
        assertThat(nullResult.getMessage()).isNull();
        
        // shouldProcessItemDisplay should return false for empty/null messages
        assertThat(checker.shouldProcessItemDisplay(playerId, "")).isFalse();
        assertThat(checker.shouldProcessItemDisplay(playerId, null)).isFalse();
    }

    /**
     * Property 6: Display Permission Enforcement - Message without tags
     * 
     * For any message without display tags, shouldProcessItemDisplay should
     * return false regardless of permissions.
     * 
     * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
     * **Validates: Requirements 12.5**
     */
    @Property(tries = 100)
    void messageWithoutTagsNotProcessed(
            @ForAll("validPlayerId") String playerId,
            @ForAll("messageWithoutTags") String message) {
        
        ItemDisplayPermissionChecker checker = new ItemDisplayPermissionChecker(
            new ItemDisplayPermissionChecker.AlwaysAllowPermissionProvider()
        );
        
        // Even with permission, messages without tags should not be processed
        assertThat(checker.shouldProcessItemDisplay(playerId, message)).isFalse();
    }

    // ==================== Generators ====================

    @Provide
    Arbitrary<String> validPlayerId() {
        return Arbitraries.oneOf(
            // UUID format
            Arbitraries.longs().tuple2()
                .map(tuple -> new UUID(tuple.get1(), tuple.get2()).toString()),
            // Player name format
            Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .withChars('_')
                .ofMinLength(3)
                .ofMaxLength(16)
                .filter(s -> !s.isEmpty() && Character.isLetter(s.charAt(0)))
        );
    }

    @Provide
    Arbitrary<String> messageWithItemTags() {
        Arbitrary<String> textParts = Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('A', 'Z')
            .withChars(' ', '!', '?')
            .ofMinLength(0)
            .ofMaxLength(20);
        
        Arbitrary<String> itemTags = Arbitraries.of("[item]", "[i]", "[ITEM]", "[I]", "[Item]");
        
        return Combinators.combine(textParts, itemTags, textParts)
            .as((before, tag, after) -> before + tag + after);
    }

    @Provide
    Arbitrary<String> anyMessage() {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('A', 'Z')
            .withCharRange('0', '9')
            .withChars(' ', '!', '?', '[', ']')
            .ofMinLength(1)
            .ofMaxLength(100);
    }

    @Provide
    Arbitrary<String> messageWithoutTags() {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('A', 'Z')
            .withCharRange('0', '9')
            .withChars(' ', '!', '?', '.', ',')
            .ofMinLength(1)
            .ofMaxLength(100)
            .filter(s -> !s.toLowerCase().contains("[item]") && 
                        !s.toLowerCase().contains("[i]") &&
                        !s.toLowerCase().contains("[inv]") &&
                        !s.toLowerCase().contains("[inventory]") &&
                        !s.toLowerCase().contains("[ec]") &&
                        !s.toLowerCase().contains("[enderchest]"));
    }
}
