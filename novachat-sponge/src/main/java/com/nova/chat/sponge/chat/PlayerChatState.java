package com.nova.chat.sponge.chat;

import java.util.UUID;

/**
 * Tracks the chat state for a player, including their current channel and chat mode.
 * 
 * Requirements: 3.3
 */
public class PlayerChatState {
    
    /** Player UUID */
    private final UUID playerId;
    
    /** Player's current active channel */
    private String activeChannel;
    
    /** Player's chat mode (can override global setting) */
    private ChatMode chatMode;
    
    /** Whether the player has toggled their personal chat mode */
    private boolean modeOverridden;
    
    /**
     * Creates a new player chat state.
     *
     * @param playerId the player's UUID
     * @param defaultChannel the default channel to join
     * @param defaultMode the default chat mode
     */
    public PlayerChatState(UUID playerId, String defaultChannel, ChatMode defaultMode) {
        this.playerId = playerId;
        this.activeChannel = defaultChannel;
        this.chatMode = defaultMode;
        this.modeOverridden = false;
    }
    
    public UUID getPlayerId() {
        return playerId;
    }
    
    public String getActiveChannel() {
        return activeChannel;
    }
    
    public void setActiveChannel(String activeChannel) {
        this.activeChannel = activeChannel;
    }
    
    public ChatMode getChatMode() {
        return chatMode;
    }
    
    public void setChatMode(ChatMode chatMode) {
        this.chatMode = chatMode;
    }
    
    public boolean isModeOverridden() {
        return modeOverridden;
    }
    
    public void setModeOverridden(boolean modeOverridden) {
        this.modeOverridden = modeOverridden;
    }
    
    /**
     * Toggles the chat mode between HYBRID and REPLACE.
     * 
     * @return the new chat mode after toggling
     */
    public ChatMode toggleMode() {
        this.modeOverridden = true;
        this.chatMode = (chatMode == ChatMode.HYBRID) ? ChatMode.REPLACE : ChatMode.HYBRID;
        return this.chatMode;
    }
}
