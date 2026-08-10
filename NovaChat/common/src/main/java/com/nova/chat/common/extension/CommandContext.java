package com.nova.chat.common.extension;

import java.util.UUID;

/**
 * Context for command execution.
 * 
 * <p>Provides information about the command sender and methods to interact with them.
 * 
 * @see ExtensionCommand
 */
public interface CommandContext {
    
    /**
     * Gets the UUID of the command sender.
     * 
     * @return the sender's UUID, or null if console
     */
    UUID getSenderId();
    
    /**
     * Gets the name of the command sender.
     * 
     * @return the sender's name
     */
    String getSenderName();
    
    /**
     * Checks if the sender is a player.
     * 
     * @return true if the sender is a player
     */
    boolean isPlayer();
    
    /**
     * Checks if the sender is the console.
     * 
     * @return true if the sender is the console
     */
    boolean isConsole();
    
    /**
     * Checks if the sender has a specific permission.
     * 
     * @param permission the permission to check
     * @return true if the sender has the permission
     */
    boolean hasPermission(String permission);
    
    /**
     * Sends a message to the command sender.
     * 
     * @param message the message to send
     */
    void sendMessage(String message);
    
    /**
     * Sends an error message to the command sender.
     * 
     * @param message the error message to send
     */
    void sendError(String message);
    
    /**
     * Gets the command arguments.
     * 
     * @return the command arguments
     */
    String[] getArgs();
    
    /**
     * Gets a specific argument by index.
     * 
     * @param index the argument index
     * @return the argument, or null if index is out of bounds
     */
    default String getArg(int index) {
        String[] args = getArgs();
        return index >= 0 && index < args.length ? args[index] : null;
    }
    
    /**
     * Gets the number of arguments.
     * 
     * @return the argument count
     */
    default int getArgCount() {
        return getArgs().length;
    }
    
    /**
     * Gets the command label (the actual command used).
     * 
     * @return the command label
     */
    String getLabel();
}
