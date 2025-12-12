package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.TextFormat;
import com.nova.chat.pnx.NovaChatPNX;

/**
 * Abstract base class for sub-commands providing common functionality.
 * 
 * Requirements: 29.1, 29.2
 */
public abstract class AbstractSubCommand implements SubCommand {

    protected final NovaChatPNX plugin;

    public AbstractSubCommand(NovaChatPNX plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isHidden() {
        return false;
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
        String prefix = plugin.getNovaChatConfig().getFormatPrefix();
        String format = plugin.getNovaChatConfig().getFormatError();
        sender.sendMessage(colorize(prefix + format.replace("{message}", message)));
    }

    /**
     * Sends a success message to the sender.
     *
     * @param sender  the command sender
     * @param message the success message
     */
    protected void sendSuccess(CommandSender sender, String message) {
        String prefix = plugin.getNovaChatConfig().getFormatPrefix();
        String format = plugin.getNovaChatConfig().getFormatSuccess();
        sender.sendMessage(colorize(prefix + format.replace("{message}", message)));
    }

    /**
     * Sends a message to the sender.
     *
     * @param sender  the command sender
     * @param message the message
     */
    protected void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(colorize(message));
    }

    /**
     * Colorizes a message using Minecraft color codes.
     *
     * @param message the message to colorize
     * @return the colorized message
     */
    protected String colorize(String message) {
        return TextFormat.colorize('&', message);
    }
}
