package com.nova.chat.mod.chat;

import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.ChatHandler;
import com.nova.chat.mod.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Intercepts and processes chat messages
 */
public class ChatInterceptor implements ChatHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatInterceptor.class);
    
    private final Platform platform;
    private final NetworkClient networkClient;
    private final MessageFormatter messageFormatter;
    private final Map<UUID, PlayerChatState> playerStates = new HashMap<>();
    
    public ChatInterceptor(Platform platform, NetworkClient networkClient, MessageFormatter messageFormatter) {
        this.platform = platform;
        this.networkClient = networkClient;
        this.messageFormatter = messageFormatter;
    }
    
    @Override
    public void onPlayerChat(UUID playerId, String playerName, String message) {
        PlayerChatState state = playerStates.computeIfAbsent(playerId, id -> new PlayerChatState(id, playerName));
        
        if (!state.isChatEnabled()) {
            LOGGER.debug("Chat disabled for player {}", playerName);
            return;
        }
        
        if (message.isEmpty()) {
            return;
        }
        
        // Update player name if changed
        if (!state.getPlayerName().equals(playerName)) {
            state.setPlayerName(playerName);
        }
        
        LOGGER.debug("Intercepted chat from {}: {} (channel: {})", playerName, message, state.getCurrentChannel());
        
        // Send to backend
        if (networkClient.isConnected()) {
            networkClient.sendChatMessage(playerId, playerName, state.getCurrentChannel(), message);
        } else {
            LOGGER.warn("Network client not connected, cannot send message from {}", playerName);
        }
    }
    
    @Override
    public void displayMessage(UUID playerId, String formattedMessage) {
        if (platform.isPlayerOnline(playerId)) {
            // Send message to player (platform-specific implementation)
            platform.sendMessage(playerId, null);
        }
    }
    
    /**
     * Get or create player chat state
     * @param playerId the player UUID
     * @param playerName the player name
     * @return the player chat state
     */
    public PlayerChatState getPlayerState(UUID playerId, String playerName) {
        return playerStates.computeIfAbsent(playerId, id -> new PlayerChatState(id, playerName));
    }
    
    /**
     * Remove player chat state (called when player leaves)
     * @param playerId the player UUID
     */
    public void removePlayerState(UUID playerId) {
        playerStates.remove(playerId);
    }
    
    /**
     * Get player chat state
     * @param playerId the player UUID
     * @return the player chat state, or null if not found
     */
    public PlayerChatState getPlayerState(UUID playerId) {
        return playerStates.get(playerId);
    }
}
