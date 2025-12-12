package com.nova.chat.common.chat;

import java.util.Objects;

/**
 * Handles permission checking for mention functionality.
 * 
 * This class provides the core logic for checking if a player has permission
 * to use mention features. Platform-specific implementations should provide
 * a PermissionProvider to check actual permissions.
 * 
 * Requirements: 11.5 - When a player doesn't have mention permission,
 * the system SHALL treat @ symbols as plain text.
 * 
 * Permission nodes:
 * - novachat.feature.mention - Basic mention permission
 * - novachat.feature.mention.all - Permission to use @all
 */
public class MentionPermissionChecker {

    /** Permission node for basic mention functionality */
    public static final String PERMISSION_MENTION = "novachat.feature.mention";
    
    /** Permission node for @all mention functionality */
    public static final String PERMISSION_MENTION_ALL = "novachat.feature.mention.all";

    private final PermissionProvider permissionProvider;

    /**
     * Creates a new MentionPermissionChecker with the given permission provider.
     *
     * @param permissionProvider the provider for checking permissions
     */
    public MentionPermissionChecker(PermissionProvider permissionProvider) {
        this.permissionProvider = Objects.requireNonNull(permissionProvider, 
            "permissionProvider cannot be null");
    }

    /**
     * Checks if a player has permission to use basic mentions (@playername).
     *
     * @param playerId the player identifier (UUID string or name)
     * @return true if the player can use mentions
     */
    public boolean canMention(String playerId) {
        return permissionProvider.hasPermission(playerId, PERMISSION_MENTION);
    }

    /**
     * Checks if a player has permission to use @all mentions.
     *
     * @param playerId the player identifier (UUID string or name)
     * @return true if the player can use @all
     */
    public boolean canMentionAll(String playerId) {
        return permissionProvider.hasPermission(playerId, PERMISSION_MENTION_ALL);
    }

    /**
     * Processes a message based on the player's mention permissions.
     * If the player doesn't have mention permission, @ symbols are escaped.
     * If the player doesn't have @all permission, @all is treated as plain text.
     *
     * @param playerId the player identifier
     * @param message the original message
     * @return the processed message with mentions handled according to permissions
     */
    public MentionProcessResult processMessage(String playerId, String message) {
        if (message == null || message.isEmpty()) {
            return new MentionProcessResult(message, false, false);
        }

        boolean hasMentionPermission = canMention(playerId);
        boolean hasMentionAllPermission = canMentionAll(playerId);

        // If player has no mention permission at all, treat all @ as plain text
        if (!hasMentionPermission) {
            return new MentionProcessResult(message, false, false);
        }

        // If player has mention but not @all permission, check for @all
        MentionParser parser = new MentionParser();
        boolean hasAllMention = parser.hasAllMention(message);
        
        if (hasAllMention && !hasMentionAllPermission) {
            // Player tried to use @all without permission
            return new MentionProcessResult(message, true, false);
        }

        return new MentionProcessResult(message, true, hasAllMention && hasMentionAllPermission);
    }

    /**
     * Checks if a message should have mentions processed based on permissions.
     *
     * @param playerId the player identifier
     * @param message the message to check
     * @return true if mentions should be processed
     */
    public boolean shouldProcessMentions(String playerId, String message) {
        return canMention(playerId) && message != null && message.contains("@");
    }

    /**
     * Checks if @all in a message should be processed based on permissions.
     *
     * @param playerId the player identifier
     * @param message the message to check
     * @return true if @all should be processed
     */
    public boolean shouldProcessAllMention(String playerId, String message) {
        if (!canMentionAll(playerId)) {
            return false;
        }
        MentionParser parser = new MentionParser();
        return parser.hasAllMention(message);
    }

    /**
     * Functional interface for checking player permissions.
     * Platform-specific implementations should provide this.
     */
    @FunctionalInterface
    public interface PermissionProvider {
        /**
         * Checks if a player has a specific permission.
         *
         * @param playerId the player identifier (UUID string or name)
         * @param permission the permission node to check
         * @return true if the player has the permission
         */
        boolean hasPermission(String playerId, String permission);
    }

    /**
     * Result of processing a message for mentions.
     */
    public static class MentionProcessResult {
        private final String message;
        private final boolean mentionsAllowed;
        private final boolean allMentionAllowed;

        public MentionProcessResult(String message, boolean mentionsAllowed, boolean allMentionAllowed) {
            this.message = message;
            this.mentionsAllowed = mentionsAllowed;
            this.allMentionAllowed = allMentionAllowed;
        }

        /**
         * Gets the processed message.
         */
        public String getMessage() {
            return message;
        }

        /**
         * Returns true if basic mentions (@playername) are allowed.
         */
        public boolean areMentionsAllowed() {
            return mentionsAllowed;
        }

        /**
         * Returns true if @all mention is allowed.
         */
        public boolean isAllMentionAllowed() {
            return allMentionAllowed;
        }

        @Override
        public String toString() {
            return "MentionProcessResult{" +
                    "message='" + message + '\'' +
                    ", mentionsAllowed=" + mentionsAllowed +
                    ", allMentionAllowed=" + allMentionAllowed +
                    '}';
        }
    }

    /**
     * A simple permission provider that always grants permissions.
     * Useful for testing or when permissions are not needed.
     */
    public static class AlwaysAllowPermissionProvider implements PermissionProvider {
        @Override
        public boolean hasPermission(String playerId, String permission) {
            return true;
        }
    }

    /**
     * A simple permission provider that always denies permissions.
     * Useful for testing.
     */
    public static class AlwaysDenyPermissionProvider implements PermissionProvider {
        @Override
        public boolean hasPermission(String playerId, String permission) {
            return false;
        }
    }
}
