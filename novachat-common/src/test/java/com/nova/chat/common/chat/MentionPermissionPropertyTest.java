package com.nova.chat.common.chat;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for mention permission enforcement.
 * 
 * **Feature: novachat-platform-extensions, Property 2: Mention Permission Enforcement**
 * **Validates: Requirements 11.5**
 * 
 * This test verifies that for any player without mention permission,
 * @mentions in their messages should be treated as plain text and not trigger notifications.
 */
class MentionPermissionPropertyTest {

    // ==================== Property 2: Mention Permission Enforcement ====================

    /**
     * Property 2: Mention Permission Enforcement - No Permission
     * 
     * For any player without mention permission, @mentions should not be processed.
     * 
     * **Feature: novachat-platform-extensions, Property 2: Mention Permission Enforcement**
     * **Validates: Requirements 11.5**
     */
    @Property(tries = 100)
    void playerWithoutMentionPermissionCannotMention(
            @ForAll("validPlayerId") String playerId,
            @ForAll("messageWithMentions") String message) {
        
        // Create a permission checker that denies mention permission
        MentionPermissionChecker checker = new MentionPermissionChecker(
            new MentionPermissionChecker.AlwaysDenyPermissionProvider()
        );
        
        // Process the message
        MentionPermissionChecker.MentionProcessResult result = checker.processMessage(playerId, message);
        
        // Verify mentions are not allowed
        assertThat(result.areMentionsAllowed()).isFalse();
        assertThat(result.isAllMentionAllowed()).isFalse();
    }

    /**
     * Property 2: Mention Permission Enforcement - With Permission
     * 
     * For any player with mention permission, @mentions should be processed.
     * 
     * **Feature: novachat-platform-extensions, Property 2: Mention Permission Enforcement**
     * **Validates: Requirements 11.5**
     */
    @Property(tries = 100)
    void playerWithMentionPermissionCanMention(
            @ForAll("validPlayerId") String playerId,
            @ForAll("messageWithMentions") String message) {
        
        // Create a permission checker that allows all permissions
        MentionPermissionChecker checker = new MentionPermissionChecker(
            new MentionPermissionChecker.AlwaysAllowPermissionProvider()
        );
        
        // Process the message
        MentionPermissionChecker.MentionProcessResult result = checker.processMessage(playerId, message);
        
        // Verify mentions are allowed
        assertThat(result.areMentionsAllowed()).isTrue();
    }

    /**
     * Property 2: Mention Permission Enforcement - @all Without Permission
     * 
     * For any player with basic mention permission but without @all permission,
     * @all should not be processed.
     * 
     * **Feature: novachat-platform-extensions, Property 2: Mention Permission Enforcement**
     * **Validates: Requirements 11.5**
     */
    @Property(tries = 100)
    void playerWithoutAllPermissionCannotUseAll(
            @ForAll("validPlayerId") String playerId,
            @ForAll("messageWithAllMention") String message) {
        
        // Create a permission checker that allows mention but not @all
        MentionPermissionChecker checker = new MentionPermissionChecker(
            (pid, permission) -> {
                if (MentionPermissionChecker.PERMISSION_MENTION.equals(permission)) {
                    return true;
                }
                if (MentionPermissionChecker.PERMISSION_MENTION_ALL.equals(permission)) {
                    return false;
                }
                return false;
            }
        );
        
        // Process the message
        MentionPermissionChecker.MentionProcessResult result = checker.processMessage(playerId, message);
        
        // Verify basic mentions are allowed but @all is not
        assertThat(result.areMentionsAllowed()).isTrue();
        assertThat(result.isAllMentionAllowed()).isFalse();
    }

    /**
     * Property 2: Mention Permission Enforcement - @all With Permission
     * 
     * For any player with both mention and @all permission,
     * @all should be processed.
     * 
     * **Feature: novachat-platform-extensions, Property 2: Mention Permission Enforcement**
     * **Validates: Requirements 11.5**
     */
    @Property(tries = 100)
    void playerWithAllPermissionCanUseAll(
            @ForAll("validPlayerId") String playerId,
            @ForAll("messageWithAllMention") String message) {
        
        // Create a permission checker that allows all permissions
        MentionPermissionChecker checker = new MentionPermissionChecker(
            new MentionPermissionChecker.AlwaysAllowPermissionProvider()
        );
        
        // Process the message
        MentionPermissionChecker.MentionProcessResult result = checker.processMessage(playerId, message);
        
        // Verify both mentions and @all are allowed
        assertThat(result.areMentionsAllowed()).isTrue();
        assertThat(result.isAllMentionAllowed()).isTrue();
    }

    /**
     * Property 2: Mention Permission Enforcement - shouldProcessMentions consistency
     * 
     * For any player and message, shouldProcessMentions should return false
     * when the player doesn't have mention permission.
     * 
     * **Feature: novachat-platform-extensions, Property 2: Mention Permission Enforcement**
     * **Validates: Requirements 11.5**
     */
    @Property(tries = 100)
    void shouldProcessMentionsRespectsPermission(
            @ForAll("validPlayerId") String playerId,
            @ForAll("messageWithMentions") String message) {
        
        // Test with denied permission
        MentionPermissionChecker deniedChecker = new MentionPermissionChecker(
            new MentionPermissionChecker.AlwaysDenyPermissionProvider()
        );
        assertThat(deniedChecker.shouldProcessMentions(playerId, message)).isFalse();
        
        // Test with allowed permission
        MentionPermissionChecker allowedChecker = new MentionPermissionChecker(
            new MentionPermissionChecker.AlwaysAllowPermissionProvider()
        );
        assertThat(allowedChecker.shouldProcessMentions(playerId, message)).isTrue();
    }

    /**
     * Property 2: Mention Permission Enforcement - shouldProcessAllMention consistency
     * 
     * For any player and message with @all, shouldProcessAllMention should return false
     * when the player doesn't have @all permission.
     * 
     * **Feature: novachat-platform-extensions, Property 2: Mention Permission Enforcement**
     * **Validates: Requirements 11.5**
     */
    @Property(tries = 100)
    void shouldProcessAllMentionRespectsPermission(
            @ForAll("validPlayerId") String playerId,
            @ForAll("messageWithAllMention") String message) {
        
        // Test with denied @all permission
        MentionPermissionChecker deniedChecker = new MentionPermissionChecker(
            (pid, permission) -> !MentionPermissionChecker.PERMISSION_MENTION_ALL.equals(permission)
        );
        assertThat(deniedChecker.shouldProcessAllMention(playerId, message)).isFalse();
        
        // Test with allowed @all permission
        MentionPermissionChecker allowedChecker = new MentionPermissionChecker(
            new MentionPermissionChecker.AlwaysAllowPermissionProvider()
        );
        assertThat(allowedChecker.shouldProcessAllMention(playerId, message)).isTrue();
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
    Arbitrary<String> messageWithMentions() {
        Arbitrary<String> playerNames = Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('A', 'Z')
            .withCharRange('0', '9')
            .withChars('_')
            .ofMinLength(3)
            .ofMaxLength(16)
            .filter(s -> !s.isEmpty() && Character.isLetter(s.charAt(0)));
        
        Arbitrary<String> textParts = Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('A', 'Z')
            .withChars(' ', '!', '?')
            .ofMinLength(0)
            .ofMaxLength(20);
        
        return Combinators.combine(textParts, playerNames, textParts)
            .as((before, name, after) -> before + "@" + name + after);
    }

    @Provide
    Arbitrary<String> messageWithAllMention() {
        Arbitrary<String> textParts = Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('A', 'Z')
            .withChars(' ', '!', '?')
            .ofMinLength(0)
            .ofMaxLength(20);
        
        Arbitrary<String> allVariants = Arbitraries.of("all", "ALL", "All", "aLl");
        
        // Ensure @all is properly separated (followed by space or end of string)
        return Combinators.combine(textParts, allVariants, textParts)
            .as((before, all, after) -> {
                String prefix = before.isEmpty() ? "" : before + " ";
                String suffix = after.isEmpty() ? "" : " " + after;
                return prefix + "@" + all + suffix;
            });
    }
}
