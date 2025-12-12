package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

/**
 * Helper class for formatting and sending messages to players.
 * Supports color codes and provides consistent message formatting.
 * 
 * Requirements: 27.1-27.4
 */
public class MessageHelper {

    private final NovaChatBukkit plugin;
    
    /** Message prefix */
    private static final String PREFIX = "&8[&bNovaChat&8]&r ";
    
    /** Error prefix */
    private static final String ERROR_PREFIX = "&8[&cNovaChat&8]&r ";
    
    /** Success prefix */
    private static final String SUCCESS_PREFIX = "&8[&aNovaChat&8]&r ";

    public MessageHelper(NovaChatBukkit plugin) {
        this.plugin = plugin;
    }

    /**
     * Sends a formatted message to the sender.
     *
     * @param sender  the command sender
     * @param message the message (supports color codes)
     */
    public void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(colorize(PREFIX + message));
    }

    /**
     * Sends a success message to the sender.
     *
     * @param sender  the command sender
     * @param message the message
     */
    public void sendSuccess(CommandSender sender, String message) {
        sender.sendMessage(colorize(SUCCESS_PREFIX + "&a" + message));
    }

    /**
     * Sends an error message to the sender.
     *
     * @param sender  the command sender
     * @param message the error message
     */
    public void sendError(CommandSender sender, String message) {
        sender.sendMessage(colorize(ERROR_PREFIX + "&c" + message));
    }

    /**
     * Sends a suggestion message to the sender.
     *
     * @param sender     the command sender
     * @param suggestion the suggestion
     */
    public void sendSuggestion(CommandSender sender, String suggestion) {
        sender.sendMessage(colorize("  &7提示: &f" + suggestion));
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
     * Colorizes a string by replacing & color codes with ChatColor.
     * Also supports hex colors in the format &#RRGGBB.
     *
     * @param message the message to colorize
     * @return the colorized message
     */
    public static String colorize(String message) {
        if (message == null) {
            return "";
        }
        
        // Handle hex colors (&#RRGGBB format)
        message = translateHexColors(message);
        
        // Handle standard color codes
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * Translates hex color codes in the format &#RRGGBB to Bukkit format.
     *
     * @param message the message
     * @return the message with translated hex colors
     */
    private static String translateHexColors(String message) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        
        while (i < message.length()) {
            if (i + 8 <= message.length() && message.charAt(i) == '&' && message.charAt(i + 1) == '#') {
                // Check if next 6 characters are valid hex
                String hex = message.substring(i + 2, i + 8);
                if (hex.matches("[0-9A-Fa-f]{6}")) {
                    // Convert to Bukkit hex format: §x§R§R§G§G§B§B
                    result.append("§x");
                    for (char c : hex.toCharArray()) {
                        result.append("§").append(Character.toLowerCase(c));
                    }
                    i += 8;
                    continue;
                }
            }
            result.append(message.charAt(i));
            i++;
        }
        
        return result.toString();
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
