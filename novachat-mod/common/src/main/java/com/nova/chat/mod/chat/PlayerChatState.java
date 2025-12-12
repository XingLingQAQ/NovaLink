package com.nova.chat.mod.chat;

import java.util.UUID;

/**
 * Tracks the chat state of a player
 */
public class PlayerChatState {
    private final UUID playerId;
    private String playerName;
    private String currentChannel;
    private ChatMode chatMode;
    private boolean chatEnabled;
    
    public PlayerChatState(UUID playerId, String playerName) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.currentChannel = "local";
        this.chatMode = ChatMode.LOCAL;
        this.chatEnabled = true;
    }
    
    public UUID getPlayerId() {
        return playerId;
    }
    
    public String getPlayerName() {
        return playerName;
    }
    
    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }
    
    public String getCurrentChannel() {
        return currentChannel;
    }
    
    public void setCurrentChannel(String currentChannel) {
        this.currentChannel = currentChannel;
    }
    
    public ChatMode getChatMode() {
        return chatMode;
    }
    
    public void setChatMode(ChatMode chatMode) {
        if (chatMode != null) {
            this.chatMode = chatMode;
        }
    }
    
    public boolean isChatEnabled() {
        return chatEnabled;
    }
    
    public void setChatEnabled(boolean chatEnabled) {
        this.chatEnabled = chatEnabled;
    }
    
    public void toggleChat() {
        this.chatEnabled = !this.chatEnabled;
    }
}
