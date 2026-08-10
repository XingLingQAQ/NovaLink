package com.nova.chat.nukkit.command;

import cn.nukkit.command.CommandSender;

import java.util.List;

/**
 * Base interface for all NovaChat sub-commands.
 * 
 * Adapted from Bukkit version for Nukkit API.
 * 
 * Requirements: 26.1-26.4
 */
public interface SubCommand {

    /**
     * Gets the name of this sub-command.
     *
     * @return the command name
     */
    String getName();

    /**
     * Gets the description of this sub-command.
     *
     * @return the description
     */
    String getDescription();

    /**
     * Gets the usage string for this sub-command.
     *
     * @return the usage string
     */
    String getUsage();

    /**
     * Gets the permission required to use this sub-command.
     *
     * @return the permission node, or null if no permission required
     */
    String getPermission();

    /**
     * Checks if this command can only be executed by players.
     *
     * @return true if player-only
     */
    boolean isPlayerOnly();

    /**
     * Checks if this command is hidden from help.
     *
     * @return true if hidden
     */
    default boolean isHidden() {
        return false;
    }

    /**
     * Checks if the sender has permission to use this command.
     *
     * @param sender the command sender
     * @return true if the sender has permission
     */
    default boolean hasPermission(CommandSender sender) {
        String permission = getPermission();
        return permission == null || sender.hasPermission(permission);
    }

    /**
     * Executes the sub-command.
     *
     * @param sender the command sender
     * @param args   the command arguments (excluding the sub-command name)
     * @return true if the command was handled
     */
    boolean execute(CommandSender sender, String[] args);

    /**
     * Provides tab completion for this sub-command.
     *
     * @param sender the command sender
     * @param args   the current arguments (excluding the sub-command name)
     * @return list of completions, or null for default behavior
     */
    default List<String> tabComplete(CommandSender sender, String[] args) {
        return null;
    }
}
