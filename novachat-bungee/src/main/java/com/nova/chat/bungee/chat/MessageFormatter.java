package com.nova.chat.bungee.chat;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.client.format.FormatTemplateEngine;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats chat messages with color codes and placeholders.
 * Supports legacy color codes (&) and hex colors (&#RRGGBB).
 * 
 * Requirements: 10.1-10.6
 */
public class MessageFormatter {
    
    private final NovaChatBungee plugin;
    private NovaChatConfig config;
    
    /** Pattern for hex color codes like &#RRGGBB */
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    
    /**
     * Creates a new MessageFormatter.
     *
     * @param plugin the plugin instance
     */
    public MessageFormatter(NovaChatBungee plugin) {
        this.plugin = plugin;
        this.config = plugin.getPluginConfig();
    }
    
    /**
     * Formats a chat message for display.
     *
     * @param player the receiving player (for placeholders)
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param content the message content
     * @param placeholders additional placeholders
     * @return the formatted message as BaseComponent array
     */
    public BaseComponent[] formatChatMessage(ProxiedPlayer player, String channelId, String channelName,
                                              String senderName, String content, Map<String, String> placeholders) {
        // Get format template for this channel
        String format = config.getChannelFormat(channelId);

        // Build placeholder map: standard keys first, then caller extras (extras win on clash).
        Map<String, String> values = new LinkedHashMap<>(8);
        values.put(FormatTemplateEngine.KEY_PLAYER, senderName);
        values.put(FormatTemplateEngine.KEY_CHANNEL, channelId);
        values.put(FormatTemplateEngine.KEY_CHANNEL_NAME, channelName);
        values.put(FormatTemplateEngine.KEY_MESSAGE, content);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) {
                    continue;
                }
                values.put(entry.getKey(), entry.getValue());
            }
        }

        // Replace placeholders via shared engine (auto-resolves {channel_color}).
        String formatted = FormatTemplateEngine.apply(format, values);
        
        // Convert hex colors and legacy codes to BaseComponent
        return parseColors(formatted);
    }
    
    /**
     * Formats a system message (prefix + content).
     *
     * @param message the message content
     * @return the formatted message as BaseComponent array
     */
    public BaseComponent[] formatSystemMessage(String message) {
        String formatted = config.getPrefix() + message;
        return parseColors(formatted);
    }
    
    /**
     * Formats an error message.
     *
     * @param message the error message
     * @return the formatted error as BaseComponent array
     */
    public BaseComponent[] formatError(String message) {
        String formatted = config.getPrefix() + config.getErrorFormat().replace("{message}", message);
        return parseColors(formatted);
    }
    
    /**
     * Formats a success message.
     *
     * @param message the success message
     * @return the formatted success as BaseComponent array
     */
    public BaseComponent[] formatSuccess(String message) {
        String formatted = config.getPrefix() + config.getSuccessFormat().replace("{message}", message);
        return parseColors(formatted);
    }
    
    /**
     * Parses color codes in a string and returns BaseComponent array.
     * Supports both legacy (&) codes and hex (&#RRGGBB) codes.
     *
     * @param text the text to parse
     * @return the parsed BaseComponent array
     */
    public BaseComponent[] parseColors(String text) {
        // Convert &#RRGGBB to BungeeCord format
        String converted = convertHexColors(text);
        // Translate & color codes
        converted = ChatColor.translateAlternateColorCodes('&', converted);
        return TextComponent.fromLegacyText(converted);
    }
    
    /**
     * Converts &#RRGGBB hex colors to BungeeCord ChatColor format.
     *
     * @param text the text to convert
     * @return the converted text
     */
    private String convertHexColors(String text) {
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        
        while (matcher.find()) {
            String hex = matcher.group(1);
            // BungeeCord uses ChatColor.of("#RRGGBB") but for legacy text we need to use §x§R§R§G§G§B§B
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append('§').append(c);
            }
            matcher.appendReplacement(result, replacement.toString());
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Strips all color codes from a string.
     *
     * @param text the text to strip
     * @return the text without color codes
     */
    public String stripColors(String text) {
        // Remove hex colors
        String stripped = HEX_PATTERN.matcher(text).replaceAll("");
        // Remove legacy color codes
        stripped = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', stripped));
        return stripped;
    }
    
    /**
     * Reloads the formatter configuration.
     */
    public void reload() {
        this.config = plugin.getPluginConfig();
    }
}
