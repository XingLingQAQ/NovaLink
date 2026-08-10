package com.nova.chat.mod.platform;

import java.util.UUID;

/**
 * Interface for handling chat events across platforms
 */
public interface ChatHandler {
    
    /**
     * Called when a player sends a chat message
     * @param playerId the UUID of the player
     * @param playerName the name of the player
     * @param message the chat message content
     */
    void onPlayerChat(UUID playerId, String playerName, String message);
    
    /**
     * Called when a message should be displayed to a player
     * @param playerId the UUID of the player
     * @param formattedMessage the formatted message to display
     */
    void displayMessage(UUID playerId, String formattedMessage);
}
