package com.nova.chat.common.chat;

import java.util.Objects;

/**
 * Handles permission checking for item display functionality.
 * 
 * This class provides the core logic for checking if a player has permission
 * to use item, inventory, and enderchest display features. Platform-specific 
 * implementations should provide a PermissionProvider to check actual permissions.
 * 
 * Requirements: 12.5, 13.5, 14.5 - When a player doesn't have display permission,
 * the system SHALL treat display tags as plain text.
 * 
 * Permission nodes:
 * - novachat.feature.item - Item display permission
 * - novachat.feature.inventory - Inventory display permission
 * - novachat.feature.enderchest - Enderchest display permission
 * 
 * **Feature: novachat-platform-extensions, Property 6: Display Permission Enforcement**
 * **Validates: Requirements 12.5, 13.5, 14.5**
 */
public class ItemDisplayPermissionChecker {

    /** Permission node for item display functionality */
    public static final String PERMISSION_ITEM = "novachat.feature.item";
    
    /** Permission node for inventory display functionality */
    public static final String PERMISSION_INVENTORY = "novachat.feature.inventory";
    
    /** Permission node for enderchest display functionality */
    public static final String PERMISSION_ENDERCHEST = "novachat.feature.enderchest";

    private final PermissionProvider permissionProvider;

    /**
     * Creates a new ItemDisplayPermissionChecker with the given permission provider.
     *
     * @param permissionProvider the provider for checking permissions
     */
    public ItemDisplayPermissionChecker(PermissionProvider permissionProvider) {
        this.permissionProvider = Objects.requireNonNull(permissionProvider, 
            "permissionProvider cannot be null");
    }

    /**
     * Checks if a player has permission to use item display ([item] or [i]).
     *
     * @param playerId the player identifier (UUID string or name)
     * @return true if the player can use item display
     */
    public boolean canDisplayItem(String playerId) {
        return permissionProvider.hasPermission(playerId, PERMISSION_ITEM);
    }

    /**
     * Checks if a player has permission to use inventory display ([inv] or [inventory]).
     *
     * @param playerId the player identifier (UUID string or name)
     * @return true if the player can use inventory display
     */
    public boolean canDisplayInventory(String playerId) {
        return permissionProvider.hasPermission(playerId, PERMISSION_INVENTORY);
    }

    /**
     * Checks if a player has permission to use enderchest display ([ec] or [enderchest]).
     *
     * @param playerId the player identifier (UUID string or name)
     * @return true if the player can use enderchest display
     */
    public boolean canDisplayEnderchest(String playerId) {
        return permissionProvider.hasPermission(playerId, PERMISSION_ENDERCHEST);
    }

    /**
     * Processes a message based on the player's display permissions.
     * Returns information about which display features are allowed.
     *
     * @param playerId the player identifier
     * @param message the original message
     * @return the result containing permission information
     */
    public DisplayProcessResult processMessage(String playerId, String message) {
        if (message == null || message.isEmpty()) {
            return new DisplayProcessResult(message, false, false, false);
        }

        boolean hasItemPermission = canDisplayItem(playerId);
        boolean hasInventoryPermission = canDisplayInventory(playerId);
        boolean hasEnderchestPermission = canDisplayEnderchest(playerId);

        return new DisplayProcessResult(message, hasItemPermission, 
            hasInventoryPermission, hasEnderchestPermission);
    }

    /**
     * Checks if a message contains item tags and the player has permission.
     *
     * @param playerId the player identifier
     * @param message the message to check
     * @return true if item display should be processed
     */
    public boolean shouldProcessItemDisplay(String playerId, String message) {
        if (!canDisplayItem(playerId)) {
            return false;
        }
        ItemDisplayParser parser = new ItemDisplayParser();
        return parser.hasItemTag(message);
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
     * Result of processing a message for display permissions.
     */
    public static class DisplayProcessResult {
        private final String message;
        private final boolean itemDisplayAllowed;
        private final boolean inventoryDisplayAllowed;
        private final boolean enderchestDisplayAllowed;

        public DisplayProcessResult(String message, boolean itemDisplayAllowed, 
                                   boolean inventoryDisplayAllowed, boolean enderchestDisplayAllowed) {
            this.message = message;
            this.itemDisplayAllowed = itemDisplayAllowed;
            this.inventoryDisplayAllowed = inventoryDisplayAllowed;
            this.enderchestDisplayAllowed = enderchestDisplayAllowed;
        }

        /**
         * Gets the original message.
         */
        public String getMessage() {
            return message;
        }

        /**
         * Returns true if item display ([item], [i]) is allowed.
         */
        public boolean isItemDisplayAllowed() {
            return itemDisplayAllowed;
        }

        /**
         * Returns true if inventory display ([inv], [inventory]) is allowed.
         */
        public boolean isInventoryDisplayAllowed() {
            return inventoryDisplayAllowed;
        }

        /**
         * Returns true if enderchest display ([ec], [enderchest]) is allowed.
         */
        public boolean isEnderchestDisplayAllowed() {
            return enderchestDisplayAllowed;
        }

        /**
         * Checks if any display feature is allowed.
         */
        public boolean hasAnyPermission() {
            return itemDisplayAllowed || inventoryDisplayAllowed || enderchestDisplayAllowed;
        }

        @Override
        public String toString() {
            return "DisplayProcessResult{" +
                    "message='" + message + '\'' +
                    ", itemDisplayAllowed=" + itemDisplayAllowed +
                    ", inventoryDisplayAllowed=" + inventoryDisplayAllowed +
                    ", enderchestDisplayAllowed=" + enderchestDisplayAllowed +
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
