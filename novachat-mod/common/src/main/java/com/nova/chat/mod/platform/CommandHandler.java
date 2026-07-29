package com.nova.chat.mod.platform;

/**
 * Interface for handling individual commands
 */
public interface CommandHandler {
    
    /**
     * Execute the command
     * @param args the command arguments
     * @param context the command context
     * @return true if the command was executed successfully
     */
    boolean execute(String[] args, CommandContext context);
    
    /**
     * Get the command description
     * @return the command description
     */
    String getDescription();
    
    /**
     * Get the command usage
     * @return the command usage string
     */
    String getUsage();
}
