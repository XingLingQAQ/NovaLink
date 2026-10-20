package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.TextFormat;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.config.NovaChatConfig;

/**
 * Helper class for formatting and sending messages to players.
 * 
 * Adapted from Bukkit version for Nukkit API.
 */
public class MessageHelper {

    private final NovaChatNukkit plugin;
    /** Latest immutable config snapshot; volatile for cross-thread visibility. */
    private volatile NovaChatConfig config;

    public MessageHelper(NovaChatNukkit plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();
    }

    /**
     * Installs a new {@link NovaChatConfig} snapshot. Prefix and error/success
     * formats are read on every call, so swapping the reference here applies
     * the reload semantics without further state resets.
     *
     * @param newConfig the freshly loaded config snapshot
     */
    public void setConfig(NovaChatConfig newConfig) {
        if (newConfig == null) {
            return;
        }
        this.config = newConfig;
    }

    /**
     * Sends a formatted message to a command sender.
     *
     * @param sender  the command sender
     * @param message the message (supports color codes)
     */
    public void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(translateColors(config.getPrefix() + message));
    }

    /**
     * Sends a raw message without prefix.
     *
     * @param sender  the command sender
     * @param message the message (supports color codes)
     */
    public void sendRawMessage(CommandSender sender, String message) {
        sender.sendMessage(translateColors(message));
    }

    /**
     * Sends the usage line for a command.
     *
     * @param sender the command sender
     * @param usage  the usage string
     */
    public void sendUsage(CommandSender sender, String usage) {
        sender.sendMessage(translateColors("&c"
                + I18n.tr(playerIdOf(sender), "chat.error.usage_prefix", usage)));
    }

    /**
     * Sends a hint line under a previous message.
     *
     * @param sender     the command sender
     * @param suggestion the hint
     */
    public void sendSuggestion(CommandSender sender, String suggestion) {
        sender.sendMessage(translateColors("  &7"
                + I18n.tr(playerIdOf(sender), "error.suggestion_prefix") + " &f" + suggestion));
    }

    /**
     * Resolves a sender's player UUID, or {@code null} for console/RCON so
     * {@link I18n#tr(java.util.UUID, String, Object...)} falls back to the
     * default locale.
     *
     * @param sender the command sender
     * @return the UUID for a player, otherwise {@code null}
     */
    private static java.util.UUID playerIdOf(CommandSender sender) {
        return sender instanceof Player ? ((Player) sender).getUniqueId() : null;
    }

    /**
     * Sends an error message to a command sender.
     *
     * @param sender  the command sender
     * @param message the error message
     */
    public void sendError(CommandSender sender, String message) {
        String formatted = config.getErrorFormat().replace("{message}", message);
        sender.sendMessage(translateColors(config.getPrefix() + formatted));
    }

    /**
     * Sends a success message to a command sender.
     *
     * @param sender  the command sender
     * @param message the success message
     */
    public void sendSuccess(CommandSender sender, String message) {
        String formatted = config.getSuccessFormat().replace("{message}", message);
        sender.sendMessage(translateColors(config.getPrefix() + formatted));
    }

    /**
     * Translates color codes in a message.
     *
     * @param message the message with color codes
     * @return the translated message
     */
    public String translateColors(String message) {
        return TextFormat.colorize('&', message);
    }

    /**
     * Checks if a sender is a player.
     *
     * @param sender the command sender
     * @return true if the sender is a player
     */
    public boolean isPlayer(CommandSender sender) {
        return sender instanceof Player;
    }

    /**
     * Gets the player from a command sender.
     *
     * @param sender the command sender
     * @return the player, or null if not a player
     */
    public Player getPlayer(CommandSender sender) {
        return sender instanceof Player ? (Player) sender : null;
    }
}
