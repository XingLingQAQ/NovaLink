package com.nova.chat.mod.platform;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manager for registering and dispatching commands
 */
public class CommandManager {
    private final Map<String, CommandHandler> commands = new HashMap<>();
    private final Platform platform;

    public CommandManager(Platform platform) {
        this.platform = platform;
    }

    /**
     * Register a command handler
     * @param name the command name
     * @param handler the command handler
     */
    public void registerCommand(String name, CommandHandler handler) {
        commands.put(name.toLowerCase(), handler);
    }

    /**
     * Execute a command
     * @param name the command name
     * @param args the command arguments
     * @param context the command context
     * @return true if the command was executed successfully
     */
    public boolean executeCommand(String name, String[] args, CommandContext context) {
        CommandHandler handler = commands.get(name.toLowerCase());
        if (handler != null) {
            return handler.execute(args, context);
        }
        return false;
    }

    /**
     * Suggest completions for the current argument cursor of a command.
     *
     * <p>Loader-specific {@code CommandRegistrar} implementations should
     * forward their Brigadier suggestion provider to this method. Commands
     * that accept a channel-name argument can override
     * {@link CommandHandler#tabComplete(String[])} to surface the backend's
     * known channel list.
     *
     * @param name the command name
     * @param args the arguments typed so far (last element is the partial token)
     * @return suggested completions; empty if the command is unknown or offers none
     */
    public List<String> tabComplete(String name, String[] args) {
        CommandHandler handler = commands.get(name.toLowerCase());
        if (handler != null) {
            return handler.tabComplete(args);
        }
        return Collections.emptyList();
    }

    /**
     * Get a command handler
     * @param name the command name
     * @return the command handler, or null if not found
     */
    public CommandHandler getCommand(String name) {
        return commands.get(name.toLowerCase());
    }

    /**
     * Check if a command exists
     * @param name the command name
     * @return true if the command exists
     */
    public boolean hasCommand(String name) {
        return commands.containsKey(name.toLowerCase());
    }

    public Platform getPlatform() {
        return platform;
    }
}
