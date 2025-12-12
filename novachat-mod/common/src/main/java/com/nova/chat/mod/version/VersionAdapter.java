package com.nova.chat.mod.version;

import java.util.UUID;

/**
 * Version adapter interface for handling Minecraft version-specific API differences.
 * Each supported version range has its own implementation.
 * 
 * Requirements: 4.1, 4.3, 4.4
 */
public interface VersionAdapter {
    
    /**
     * Gets the Minecraft version this adapter supports.
     * @return the Minecraft version string (e.g., "1.20.4")
     */
    String getMinecraftVersion();
    
    /**
     * Gets the version range this adapter supports.
     * @return the version range (e.g., "1.20-1.21")
     */
    String getSupportedVersionRange();
    
    /**
     * Sends a chat message to a player using version-specific API.
     * @param player the player object (platform-specific type)
     * @param message the message to send
     */
    void sendChatMessage(Object player, String message);
    
    /**
     * Sends a system message to a player using version-specific API.
     * @param player the player object (platform-specific type)
     * @param message the message to send
     */
    void sendSystemMessage(Object player, String message);
    
    /**
     * Broadcasts a message to all players using version-specific API.
     * @param server the server object (platform-specific type)
     * @param message the message to broadcast
     */
    void broadcastMessage(Object server, String message);
    
    /**
     * Gets the player's current dimension/world name.
     * @param player the player object
     * @return the dimension name
     */
    String getPlayerDimension(Object player);
    
    /**
     * Gets the player's display name.
     * @param player the player object
     * @return the display name
     */
    String getPlayerDisplayName(Object player);
    
    /**
     * Gets the player's UUID.
     * @param player the player object
     * @return the player's UUID
     */
    UUID getPlayerUUID(Object player);
    
    /**
     * Checks if this adapter supports the given Minecraft version.
     * @param version the Minecraft version to check
     * @return true if supported
     */
    boolean supportsVersion(String version);
    
    /**
     * Creates a text component from a string with color codes.
     * @param text the text with color codes (e.g., "&cRed text")
     * @return the platform-specific text component
     */
    Object createTextComponent(String text);
    
    /**
     * Parses legacy color codes (& format) to the appropriate format.
     * @param text the text with & color codes
     * @return the converted text
     */
    String parseColorCodes(String text);
}
