package com.nova.chat.mod.network;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for network communication with the backend
 */
public interface NetworkClient {
    
    /**
     * Connect to the backend server
     * @param host the server host
     * @param port the server port
     * @return a future that completes when connection is established
     */
    CompletableFuture<Boolean> connect(String host, int port);
    
    /**
     * Disconnect from the backend server
     */
    void disconnect();
    
    /**
     * Send a chat message to the backend
     * @param playerId the player UUID
     * @param playerName the player name
     * @param channelId the channel ID
     * @param message the message content
     */
    void sendChatMessage(UUID playerId, String playerName, String channelId, String message);
    
    /**
     * Check if connected to the backend
     * @return true if connected
     */
    boolean isConnected();
    
    /**
     * Get the connection status
     * @return the connection status
     */
    ConnectionStatus getStatus();
    
    /**
     * Register a packet handler
     * @param handler the packet handler
     */
    void registerPacketHandler(PacketHandler handler);
}
