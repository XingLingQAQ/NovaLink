package com.nova.chat.common.extension.events;

import com.nova.chat.common.extension.ExtensionEvent;

import java.util.UUID;

/**
 * Event fired when a player joins a channel.
 * 
 * <p>Extensions can listen for this event to perform actions when players join channels.
 */
public class PlayerJoinChannelEvent extends ExtensionEvent {
    
    private final UUID playerId;
    private final String playerName;
    private final String channelId;
    
    /**
     * Creates a new PlayerJoinChannelEvent.
     * 
     * @param playerId the UUID of the player
     * @param playerName the name of the player
     * @param channelId the ID of the channel being joined
     */
    public PlayerJoinChannelEvent(UUID playerId, String playerName, String channelId) {
        super(true); // Cancellable
        this.playerId = playerId;
        this.playerName = playerName;
        this.channelId = channelId;
    }
    
    /**
     * Gets the player's UUID.
     * 
     * @return the player UUID
     */
    public UUID getPlayerId() {
        return playerId;
    }
    
    /**
     * Gets the player's name.
     * 
     * @return the player name
     */
    public String getPlayerName() {
        return playerName;
    }
    
    /**
     * Gets the channel ID.
     * 
     * @return the channel ID
     */
    public String getChannelId() {
        return channelId;
    }
}
