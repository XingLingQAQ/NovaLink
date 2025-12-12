package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.nukkit.NovaChatNukkit;

/**
 * Abstract base class for sub-commands providing common functionality.
 * 
 * Adapted from Bukkit version for Nukkit API.
 */
public abstract class AbstractSubCommand implements SubCommand {

    protected final NovaChatNukkit plugin;
    protected final MessageHelper messageHelper;

    public AbstractSubCommand(NovaChatNukkit plugin) {
        this.plugin = plugin;
        this.messageHelper = plugin.getMessageHelper();
    }

    /**
     * Gets the player from a command sender, or null if not a player.
     *
     * @param sender the command sender
     * @return the player, or null
     */
    protected Player getPlayer(CommandSender sender) {
        return sender instanceof Player ? (Player) sender : null;
    }

    /**
     * Checks if the sender is a player.
     *
     * @param sender the command sender
     * @return true if the sender is a player
     */
    protected boolean isPlayer(CommandSender sender) {
        return sender instanceof Player;
    }

    /**
     * Sends an error message to the sender.
     *
     * @param sender  the command sender
     * @param message the error message
     */
    protected void sendError(CommandSender sender, String message) {
        messageHelper.sendError(sender, message);
    }

    /**
     * Sends a success message to the sender.
     *
     * @param sender  the command sender
     * @param message the success message
     */
    protected void sendSuccess(CommandSender sender, String message) {
        messageHelper.sendSuccess(sender, message);
    }

    /**
     * Sends a message to the sender.
     *
     * @param sender  the command sender
     * @param message the message
     */
    protected void sendMessage(CommandSender sender, String message) {
        messageHelper.sendMessage(sender, message);
    }
}
