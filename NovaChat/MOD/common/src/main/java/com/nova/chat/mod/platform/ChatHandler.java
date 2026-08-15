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
     * Whether the platform should cancel the vanilla chat message for this
     * player. Platforms call this after {@link #onPlayerChat} to decide whether
     * to suppress vanilla chat. The decision is based on the player's effective
     * chat mode (global setting honouring any per-player override), not the
     * global config flag alone, so that a per-player HYBRID override does not
     * lose messages when the global mode is REPLACE.
     *
     * @param playerId the UUID of the player
     * @return true if vanilla chat should be canceled (REPLACE mode)
     */
    default boolean shouldReplaceVanillaChat(UUID playerId) {
        return false;
    }

    /**
     * Called when a message should be displayed to a player
     * @param playerId the UUID of the player
     * @param formattedMessage the formatted message to display
     */
    void displayMessage(UUID playerId, String formattedMessage);
}
