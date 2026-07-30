package com.nova.chat.multipaper.command;

import com.nova.chat.client.command.MessagePrefixes;
import com.nova.chat.client.format.MessageFormatService;
import com.nova.chat.multipaper.NovaChatMultiPaper;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * Helper class for formatting and sending messages to players.
 * Supports color codes and provides consistent message formatting.
 *
 * <p>Color translation delegates hex expansion to the shared
 * {@link MessageFormatService}; only the final {@code &}-code to
 * {@code §}-code pass uses the Bukkit {@link ChatColor} API.
 *
 * Requirements: 1.1
 */
public class MessageHelper {

    private final NovaChatMultiPaper plugin;

    public MessageHelper(NovaChatMultiPaper plugin) {
        this.plugin = plugin;
    }

    /**
     * Sends a formatted message to the sender.
     *
     * @param sender  the command sender
     * @param message the message (supports color codes)
     */
    public void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(colorize(MessagePrefixes.PREFIX + message));
    }

    /**
     * Sends a success message to the sender.
     *
     * @param sender  the command sender
     * @param message the message
     */
    public void sendSuccess(CommandSender sender, String message) {
        sender.sendMessage(colorize(MessagePrefixes.SUCCESS_PREFIX + "&a" + message));
    }

    /**
     * Sends an error message to the sender.
     *
     * @param sender  the command sender
     * @param message the error message
     */
    public void sendError(CommandSender sender, String message) {
        sender.sendMessage(colorize(MessagePrefixes.ERROR_PREFIX + "&c" + message));
    }

    /**
     * Sends a raw message without prefix.
     *
     * @param sender  the command sender
     * @param message the message
     */
    public void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(colorize(message));
    }

    /**
     * Sends a usage message.
     *
     * @param sender the command sender
     * @param usage  the usage string
     */
    public void sendUsage(CommandSender sender, String usage) {
        sender.sendMessage(colorize("&c用法: &e" + usage));
    }

    /**
     * Sends a header line for help display.
     *
     * @param sender the command sender
     * @param title  the header title
     */
    public void sendHeader(CommandSender sender, String title) {
        sender.sendMessage(colorize("&8&m----------&r &b" + title + " &8&m----------"));
    }

    /**
     * Sends a footer line.
     *
     * @param sender the command sender
     */
    public void sendFooter(CommandSender sender) {
        sender.sendMessage(colorize("&8&m---------------------------------"));
    }

    /**
     * Sends a command help entry.
     *
     * @param sender      the command sender
     * @param command     the command
     * @param description the description
     */
    public void sendCommandHelp(CommandSender sender, String command, String description) {
        sender.sendMessage(colorize("&e" + command + " &8- &7" + description));
    }

    /**
     * Colorizes a string by expanding {@code &#RRGGBB} hex sequences via the shared
     * {@link MessageFormatService} and then translating remaining {@code &} color
     * codes with Bukkit's {@link ChatColor}.
     *
     * @param message the message to colorize
     * @return the colorized message
     */
    public static String colorize(String message) {
        if (message == null) {
            return "";
        }
        String expanded = MessageFormatService.convertHexToSection(message);
        return ChatColor.translateAlternateColorCodes('&', expanded);
    }

    /**
     * Strips all color codes from a message.
     *
     * @param message the message
     * @return the message without color codes
     */
    public static String stripColors(String message) {
        if (message == null) {
            return "";
        }
        return ChatColor.stripColor(colorize(message));
    }
}
