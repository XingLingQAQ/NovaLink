package com.nova.chat.mod.platform;

import java.util.UUID;

/**
 * Platform abstraction interface for cross-loader compatibility
 * Defines platform-specific operations that each loader must implement
 */
public interface Platform {
    
    /**
     * Register a chat listener for intercepting player messages
     * @param handler the chat handler to register
     */
    void registerChatListener(ChatHandler handler);
    
    /**
     * Register commands with the platform
     * @param manager the command manager
     */
    void registerCommands(CommandManager manager);
    
    /**
     * Send a message to a specific player
     * @param playerId the UUID of the player
     * @param message the message component to send (platform-specific type)
     */
    void sendMessage(UUID playerId, Object message);
    
    /**
     * Broadcast a message to all players
     * @param message the message component to broadcast (platform-specific type)
     */
    void broadcastMessage(Object message);
    
    /**
     * Get the current world/dimension of a player
     * @param playerId the UUID of the player
     * @return the world name
     */
    String getCurrentWorld(UUID playerId);
    
    /**
     * Get the player name from UUID
     * @param playerId the UUID of the player
     * @return the player name, or null if not found
     */
    String getPlayerName(UUID playerId);
    
    /**
     * Check if a player is online
     * @param playerId the UUID of the player
     * @return true if the player is online
     */
    boolean isPlayerOnline(UUID playerId);
    
    /**
     * Get the platform type
     * @return the platform type (FABRIC, NEOFORGE, QUILT, FORGE)
     */
    PlatformType getPlatformType();
}
