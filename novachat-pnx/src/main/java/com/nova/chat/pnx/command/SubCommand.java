package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;

import java.util.List;

/**
 * Interface for NovaChat sub-commands.
 * 
 * Requirements: 29.1, 29.2
 */
public interface SubCommand {

    /**
     * Gets the name of this sub-command.
     *
     * @return the sub-command name
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
     * Checks if this sub-command can only be executed by players.
     *
     * @return true if player-only
     */
    boolean isPlayerOnly();

    /**
     * Checks if this sub-command should be hidden from help.
     *
     * @return true if hidden
     */
    boolean isHidden();

    /**
     * Checks if the sender has permission to use this sub-command.
     *
     * @param sender the command sender
     * @return true if the sender has permission
     */
    default boolean hasPermission(CommandSender sender) {
        String permission = getPermission();
        return permission == null || sender.hasPermission(permission);
    }

    /**
     * Executes this sub-command.
     *
     * @param sender the command sender
     * @param args   the command arguments (excluding the sub-command name)
     * @return true if the command was executed successfully
     */
    boolean execute(CommandSender sender, String[] args);

    /**
     * Gets tab completion suggestions for this sub-command.
     *
     * @param sender the command sender
     * @param args   the current arguments
     * @return list of suggestions
     */
    default List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
