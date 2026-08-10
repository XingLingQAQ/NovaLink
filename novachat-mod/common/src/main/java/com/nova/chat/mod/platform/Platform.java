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
     * @return the UUIDs of all online players, for broadcasting incoming channel
     *         messages to the subset whose active channel matches
     */
    java.util.Collection<UUID> getOnlinePlayerIds();

    /**
     * Get the platform type
     * @return the platform type (FABRIC, NEOFORGE, QUILT, FORGE)
     */
    PlatformType getPlatformType();

    /**
     * Runs a task asynchronously on the platform scheduler. Used by the shared
     * network layer ({@code SchedulerBridge}) so reconnect / keepalive tasks are
     * not bound to a Netty event loop on a mod loader that owns its own threads.
     *
     * @param task the task to run off the calling thread
     */
    void runAsync(Runnable task);

    /**
     * Runs a task after a delay on the platform scheduler (seconds-based, matching
     * the shared {@code SchedulerBridge} contract).
     *
     * @param task the task to run
     * @param delaySeconds the delay in seconds
     */
    void runLater(Runnable task, long delaySeconds);

    /**
     * Logs an info-level message through the platform logger.
     * @param message the message
     */
    void logInfo(String message);

    /**
     * Logs a warning-level message through the platform logger.
     * @param message the message
     */
    void logWarn(String message);

    /**
     * Logs a debug-level message through the platform logger when debug is enabled.
     * @param message the message
     */
    void logDebug(String message);

    /**
     * Logs an error-level message through the platform logger.
     * @param message the message
     */
    void logError(String message);

    /**
     * Logs an error-level message with a cause through the platform logger.
     * @param message the message
     * @param cause the cause, or null
     */
    void logError(String message, Throwable cause);

    /**
     * @return the Minecraft server version string sent in the backend handshake,
     *         or blank if unknown (before server start)
     */
    String getServerVersion();
}
