package com.nova.chat.common.extension;

import java.util.Collections;
import java.util.List;

/**
 * Represents a command registered by an extension.
 * 
 * <p>Extensions can register commands to add custom functionality:
 * <pre>
 * commandRegistry.register("my-extension", new ExtensionCommand() {
 *     @Override
 *     public String getName() { return "mycommand"; }
 *     
 *     @Override
 *     public boolean execute(CommandContext context) {
 *         context.sendMessage("Hello from my extension!");
 *         return true;
 *     }
 * });
 * </pre>
 * 
 * @see ExtensionCommandRegistry
 * @see CommandContext
 */
public interface ExtensionCommand {
    
    /**
     * Gets the name of this command (without the leading slash).
     * 
     * @return the command name
     */
    String getName();
    
    /**
     * Gets the description of this command.
     * 
     * @return the command description
     */
    default String getDescription() {
        return "";
    }
    
    /**
     * Gets the usage string for this command.
     * 
     * @return the usage string
     */
    default String getUsage() {
        return "/" + getName();
    }
    
    /**
     * Gets the permission required to use this command.
     * 
     * @return the permission node, or null if no permission required
     */
    default String getPermission() {
        return null;
    }
    
    /**
     * Gets the aliases for this command.
     * 
     * @return list of command aliases
     */
    default List<String> getAliases() {
        return Collections.emptyList();
    }
    
    /**
     * Executes this command.
     * 
     * @param context the command execution context
     * @return true if the command was executed successfully
     */
    boolean execute(CommandContext context);
    
    /**
     * Provides tab completion suggestions for this command.
     * 
     * @param context the command context
     * @param args the current arguments
     * @return list of suggestions
     */
    default List<String> tabComplete(CommandContext context, String[] args) {
        return Collections.emptyList();
    }
}
