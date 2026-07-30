package com.nova.chat.pnx.chat;

import cn.nukkit.Player;
import cn.nukkit.utils.TextFormat;
import com.nova.chat.client.format.FormatTemplateEngine;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats chat messages using TextFormat for Bedrock color codes.
 * Handles placeholder replacement and color code conversion.
 * 
 * Requirements: 28.7
 */
public class MessageFormatter {

    private final NovaChatPNX plugin;
    
    // Pattern for Minecraft color codes (§ or &)
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("[§&]([0-9a-fk-or])");
    
    // Pattern for hex color codes (&#RRGGBB or §x§R§R§G§G§B§B)
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("&#([0-9A-Fa-f]{6})");

    public MessageFormatter(NovaChatPNX plugin) {
        this.plugin = plugin;
    }

    /**
     * Format a chat message with placeholders and color codes.
     *
     * @param format the format string
     * @param player the player (can be null for system messages)
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param message the message content
     * @return the formatted message
     */
    public String formatMessage(String format, Player player, String channelId,
                                String channelName, String message) {
        // Build placeholder map (auto-resolves {channel_color} via shared engine).
        Map<String, String> values = new LinkedHashMap<>(8);
        if (player != null) {
            values.put("player", player.getName());
            values.put("display_name", player.getDisplayName());
            values.put("world", player.getLevel().getName());
        }
        values.put(FormatTemplateEngine.KEY_CHANNEL, channelId);
        values.put(FormatTemplateEngine.KEY_CHANNEL_NAME,
                channelName != null ? channelName : channelId);
        values.put(FormatTemplateEngine.KEY_MESSAGE, message);
        values.put("server", plugin.getNovaChatConfig().getBackendUsername());

        String result = FormatTemplateEngine.apply(format, values);

        // Convert color codes
        return colorize(result);
    }

    /**
     * Format a chat message from an incoming backend message.
     *
     * @param senderName the sender's name
     * @param channelId the channel ID
     * @param content the message content
     * @return the formatted message
     */
    public String formatIncomingMessage(String senderName, String channelId, String content) {
        String format = plugin.getNovaChatConfig().getChannelFormat(channelId);

        Map<String, String> values = new LinkedHashMap<>(8);
        values.put(FormatTemplateEngine.KEY_PLAYER, senderName);
        values.put("display_name", senderName);
        values.put(FormatTemplateEngine.KEY_CHANNEL, channelId);
        values.put(FormatTemplateEngine.KEY_CHANNEL_NAME, channelId);
        values.put(FormatTemplateEngine.KEY_MESSAGE, content);
        values.put("server", plugin.getNovaChatConfig().getBackendUsername());

        String result = FormatTemplateEngine.apply(format, values);

        return colorize(result);
    }

    /**
     * Format a system message (error, success, info).
     *
     * @param type the message type ("error", "success", or "info")
     * @param message the message content
     * @return the formatted message
     */
    public String formatSystemMessage(String type, String message) {
        String prefix = plugin.getNovaChatConfig().getFormatPrefix();
        String format;
        
        switch (type.toLowerCase()) {
            case "error":
                format = plugin.getNovaChatConfig().getFormatError();
                break;
            case "success":
                format = plugin.getNovaChatConfig().getFormatSuccess();
                break;
            default:
                format = "{message}";
        }
        
        return colorize(prefix + format.replace("{message}", message));
    }

    /**
     * Format an error message.
     *
     * @param message the error message
     * @return the formatted error message
     */
    public String formatError(String message) {
        return formatSystemMessage("error", message);
    }

    /**
     * Format a success message.
     *
     * @param message the success message
     * @return the formatted success message
     */
    public String formatSuccess(String message) {
        return formatSystemMessage("success", message);
    }

    /**
     * Convert color codes to Minecraft format.
     * Supports both § and & prefixes.
     * Converts hex colors to nearest standard color (Bedrock doesn't support hex).
     *
     * @param text the text to colorize
     * @return the colorized text
     */
    public String colorize(String text) {
        if (text == null) {
            return "";
        }
        
        // First, convert hex colors to nearest standard color
        text = convertHexColors(text);
        
        // Then use TextFormat.colorize for standard color codes
        return TextFormat.colorize(text);
    }

    /**
     * Convert hex color codes to the nearest standard Minecraft color.
     * Bedrock Edition doesn't support hex colors, so we approximate.
     *
     * @param text the text containing hex colors
     * @return the text with hex colors converted
     */
    private String convertHexColors(String text) {
        Matcher matcher = HEX_COLOR_PATTERN.matcher(text);
        StringBuffer result = new StringBuffer();
        
        while (matcher.find()) {
            String hex = matcher.group(1);
            char nearestColor = findNearestColor(hex);
            matcher.appendReplacement(result, "§" + nearestColor);
        }
        matcher.appendTail(result);
        
        return result.toString();
    }

    /**
     * Find the nearest standard Minecraft color for a hex color.
     *
     * @param hex the hex color (RRGGBB)
     * @return the nearest color code character
     */
    private char findNearestColor(String hex) {
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        
        // Standard Minecraft colors with their RGB values
        int[][] colors = {
            {0, 0, 0, '0'},       // Black
            {0, 0, 170, '1'},     // Dark Blue
            {0, 170, 0, '2'},     // Dark Green
            {0, 170, 170, '3'},   // Dark Aqua
            {170, 0, 0, '4'},     // Dark Red
            {170, 0, 170, '5'},   // Dark Purple
            {255, 170, 0, '6'},   // Gold
            {170, 170, 170, '7'}, // Gray
            {85, 85, 85, '8'},    // Dark Gray
            {85, 85, 255, '9'},   // Blue
            {85, 255, 85, 'a'},   // Green
            {85, 255, 255, 'b'}, // Aqua
            {255, 85, 85, 'c'},   // Red
            {255, 85, 255, 'd'}, // Light Purple
            {255, 255, 85, 'e'}, // Yellow
            {255, 255, 255, 'f'} // White
        };
        
        int minDistance = Integer.MAX_VALUE;
        char nearestColor = 'f';
        
        for (int[] color : colors) {
            int distance = (r - color[0]) * (r - color[0]) +
                          (g - color[1]) * (g - color[1]) +
                          (b - color[2]) * (b - color[2]);
            
            if (distance < minDistance) {
                minDistance = distance;
                nearestColor = (char) color[3];
            }
        }
        
        return nearestColor;
    }

    /**
     * Strip all color codes from text.
     *
     * @param text the text to strip
     * @return the text without color codes
     */
    public String stripColors(String text) {
        if (text == null) {
            return "";
        }
        return TextFormat.clean(text);
    }

    /**
     * Apply placeholders from a map to a format string.
     *
     * @param format the format string
     * @param placeholders the placeholder map
     * @return the formatted string
     */
    public String applyPlaceholders(String format, Map<String, String> placeholders) {
        String result = format;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return colorize(result);
    }
}
