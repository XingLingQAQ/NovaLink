package com.nova.chat.mod.platform;

import java.util.UUID;

/**
 * Context for command execution
 */
public class CommandContext {
    private final UUID playerId;
    private final String playerName;
    private final Platform platform;
    private final boolean isAdmin;
    
    public CommandContext(UUID playerId, String playerName, Platform platform, boolean isAdmin) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.platform = platform;
        this.isAdmin = isAdmin;
    }
    
    public UUID getPlayerId() {
        return playerId;
    }
    
    public String getPlayerName() {
        return playerName;
    }
    
    public Platform getPlatform() {
        return platform;
    }
    
    public boolean isAdmin() {
        return isAdmin;
    }
    
    public void sendMessage(String message) {
        // Send message to player (platform-specific implementation)
        platform.sendMessage(playerId, null);
    }
}
