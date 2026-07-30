package com.nova.chat.nukkit.chat;

import cn.nukkit.Player;
import cn.nukkit.utils.TextFormat;
import com.nova.chat.client.format.FormatTemplateEngine;
import com.nova.chat.client.format.LegacyColorCodes;
import com.nova.chat.client.format.MessageFormatService;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.config.NovaChatConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles message formatting including color codes for Bedrock clients.
 *
 * Supports:
 * - Legacy color codes (&a, &b, etc.)
 * - Hex color codes (&#RRGGBB) - converted to nearest Bedrock color
 * - Custom placeholders ({player}, {channel}, etc.)
 *
 * Placeholder substitution is delegated to {@link FormatTemplateEngine};
 * Bedrock color approximation remains platform-specific (true hex is not available
 * on Bedrock, so {@code LegacyColorCodes} is not used for the hex path).
 *
 * Note: Bedrock Edition has limited color support compared to Java Edition.
 * Hex colors are approximated to the nearest standard color.
 *
 * Requirements: 10.1-10.6, 23.4
 */
public class MessageFormatter {

    private final NovaChatNukkit plugin;
    private final NovaChatConfig config;

    /** Pattern for hex color codes: &#RRGGBB */
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    /** Pattern for legacy color codes: &X where X is 0-9, a-f, k-o, r */
    private static final Pattern LEGACY_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    /**
     * Creates a new MessageFormatter.
     *
     * @param plugin the plugin instance
     */
    public MessageFormatter(NovaChatNukkit plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();
    }

    /**
     * Formats a chat message with all placeholders and color codes.
     *
     * @param player the player sending/receiving the message
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param message the raw message content
     * @return the formatted message
     */
    public String formatChatMessage(Player player, String channelId, String channelName,
                                    String senderName, String message) {
        String format = config.getChannelFormat(channelId);

        Map<String, String> extras = platformExtras(player, senderName);
        String result = FormatTemplateEngine.apply(
                format,
                senderName,
                channelId,
                channelName != null ? channelName : channelId,
                message,
                extras);

        // Apply color codes (Bedrock-compatible)
        result = translateColorCodes(result);

        return result;
    }

    /**
     * Formats a chat message with custom placeholders map.
     *
     * @param player the player (can be null for console)
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param message the raw message content
     * @param placeholders additional placeholders to apply
     * @return the formatted message
     */
    public String formatChatMessage(Player player, String channelId, String channelName,
                                    String senderName, String message,
                                    Map<String, String> placeholders) {
        String format = config.getChannelFormat(channelId);

        // Preserve prior semantics: custom map first, then standard/platform keys overwrite.
        Map<String, String> values = new LinkedHashMap<>();
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) {
                    continue;
                }
                values.put(entry.getKey(), entry.getValue());
            }
        }
        values.put(FormatTemplateEngine.KEY_PLAYER, senderName);
        values.put(FormatTemplateEngine.KEY_CHANNEL, channelId);
        values.put(FormatTemplateEngine.KEY_CHANNEL_NAME,
                channelName != null ? channelName : channelId);
        values.put(FormatTemplateEngine.KEY_MESSAGE, message);
        values.putAll(platformExtras(player, senderName));

        String result = FormatTemplateEngine.apply(format, values);

        // Apply color codes (Bedrock-compatible)
        result = translateColorCodes(result);

        return result;
    }

    /**
     * Formats a system message (success, error, etc.).
     *
     * @param type the message type ("error", "success", or custom)
     * @param message the message content
     * @return the formatted message
     */
    public String formatSystemMessage(String type, String message) {
        String result = MessageFormatService.buildTypedSystem(
                config.getPrefix(), config.getErrorFormat(), config.getSuccessFormat(), type, message);
        return translateColorCodes(result);
    }

    /**
     * Builds platform-only extras ({@code display_name}, {@code world}, {@code server}).
     */
    private Map<String, String> platformExtras(Player player, String senderName) {
        Map<String, String> extras = new LinkedHashMap<>(4);
        extras.put("display_name", player != null ? player.getDisplayName() : senderName);
        extras.put("world", player != null ? player.getLevel().getName() : "");
        extras.put("server", plugin.getServer().getName());
        return extras;
    }

    /**
     * Translates all color codes in a string.
     * Supports both legacy (&X) and hex (&#RRGGBB) formats.
     * Hex colors are approximated to nearest Bedrock color.
     *
     * @param text the text to translate
     * @return the translated text with color codes applied
     */
    public String translateColorCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // First, convert hex colors to nearest Bedrock color
        text = translateHexColors(text);

        // Then, translate legacy colors (&X to §X)
        text = translateLegacyColors(text);

        return text;
    }

    /**
     * Translates hex color codes (&#RRGGBB) to nearest Bedrock color.
     * Bedrock doesn't support true hex colors, so we approximate.
     *
     * @param text the text to translate
     * @return the translated text
     */
    private String translateHexColors(String text) {
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String hex = matcher.group(1);
            // Convert hex to nearest Bedrock color code
            String nearestColor = hexToNearestBedrockColor(hex);
            matcher.appendReplacement(buffer, nearestColor);
        }
        matcher.appendTail(buffer);

        return buffer.toString();
    }

    /**
     * Converts a hex color to the nearest Bedrock color code.
     *
     * @param hex the hex color (RRGGBB)
     * @return the nearest Bedrock color code (§X)
     */
    private String hexToNearestBedrockColor(String hex) {
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);

        // Bedrock color palette (approximate RGB values)
        int[][] colors = {
            {0, 0, 0},       // 0 - Black
            {0, 0, 170},     // 1 - Dark Blue
            {0, 170, 0},     // 2 - Dark Green
            {0, 170, 170},   // 3 - Dark Aqua
            {170, 0, 0},     // 4 - Dark Red
            {170, 0, 170},   // 5 - Dark Purple
            {255, 170, 0},   // 6 - Gold
            {170, 170, 170}, // 7 - Gray
            {85, 85, 85},    // 8 - Dark Gray
            {85, 85, 255},   // 9 - Blue
            {85, 255, 85},   // a - Green
            {85, 255, 255},  // b - Aqua
            {255, 85, 85},   // c - Red
            {255, 85, 255},  // d - Light Purple
            {255, 255, 85},  // e - Yellow
            {255, 255, 255}  // f - White
        };

        String[] codes = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};

        int minDistance = Integer.MAX_VALUE;
        String nearestCode = "f";

        for (int i = 0; i < colors.length; i++) {
            int dr = r - colors[i][0];
            int dg = g - colors[i][1];
            int db = b - colors[i][2];
            int distance = dr * dr + dg * dg + db * db;

            if (distance < minDistance) {
                minDistance = distance;
                nearestCode = codes[i];
            }
        }

        return "§" + nearestCode;
    }

    /**
     * Translates legacy color codes (&X) to Minecraft format (§X).
     *
     * @param text the text to translate
     * @return the translated text
     */
    private String translateLegacyColors(String text) {
        return TextFormat.colorize('&', text);
    }

    /**
     * Strips all color codes from a string.
     *
     * @param text the text to strip
     * @return the text without color codes
     */
    public String stripColors(String text) {
        if (text == null) {
            return null;
        }
        // Shared pure strip removes hash-hex, ampersand-x, section-x, and simple legacy codes;
        // then TextFormat.clean removes any remaining Bedrock-format codes.
        return TextFormat.clean(LegacyColorCodes.strip(text));
    }

    /**
     * Reloads the formatter configuration.
     */
    public void reload() {
        // Nothing to reload for Nukkit version
        // PlaceholderAPI is not available on Bedrock
    }
}
