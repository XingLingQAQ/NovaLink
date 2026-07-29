package com.nova.chat.bukkit.chat;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.format.FormatTemplateEngine;
import com.nova.chat.client.format.LegacyColorCodes;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Handles message formatting including color codes and PlaceholderAPI variables.
 *
 * Supports:
 * - Legacy color codes (&a, &b, etc.)
 * - Hex color codes (&#RRGGBB)
 * - PlaceholderAPI variables (%placeholder%)
 * - Custom placeholders ({player}, {channel}, etc.)
 *
 * Placeholder substitution is delegated to {@link FormatTemplateEngine};
 * color translation and PlaceholderAPI remain platform-specific.
 *
 * Requirements: 10.1-10.6
 */
public class MessageFormatter {

    private final NovaChatBukkit plugin;
    private final NovaChatConfig config;

    /** Pattern for legacy color codes: &X where X is 0-9, a-f, k-o, r */
    private static final Pattern LEGACY_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    /** Whether PlaceholderAPI is available */
    private boolean placeholderApiAvailable = false;

    /**
     * Creates a new MessageFormatter.
     *
     * @param plugin the plugin instance
     */
    public MessageFormatter(NovaChatBukkit plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();

        // Check if PlaceholderAPI is available
        checkPlaceholderApi();
    }

    /**
     * Checks if PlaceholderAPI is available on the server.
     */
    private void checkPlaceholderApi() {
        try {
            if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
                placeholderApiAvailable = true;
                plugin.getLogger().info("PlaceholderAPI found, variable support enabled");
            }
        } catch (Exception e) {
            placeholderApiAvailable = false;
        }
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

        // Apply PlaceholderAPI if available
        if (placeholderApiAvailable && player != null) {
            result = setPlaceholders(player, result);
        }

        // Apply color codes
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

        // Apply PlaceholderAPI if available
        if (placeholderApiAvailable && player != null) {
            result = setPlaceholders(player, result);
        }

        // Apply color codes
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
        String format;
        switch (type.toLowerCase()) {
            case "error":
                format = config.getErrorFormat();
                break;
            case "success":
                format = config.getSuccessFormat();
                break;
            default:
                format = "{message}";
        }

        String result = config.getPrefix()
                + FormatTemplateEngine.apply(format, Map.of(FormatTemplateEngine.KEY_MESSAGE, message != null ? message : ""));
        return translateColorCodes(result);
    }

    /**
     * Builds platform-only extras ({@code display_name}, {@code world}, {@code server}).
     */
    private Map<String, String> platformExtras(Player player, String senderName) {
        Map<String, String> extras = new LinkedHashMap<>(4);
        extras.put("display_name", player != null ? player.getDisplayName() : senderName);
        extras.put("world", player != null ? player.getWorld().getName() : "");
        extras.put("server", plugin.getServer().getName());
        return extras;
    }

    /**
     * Translates all color codes in a string.
     * Supports both legacy (&X) and hex (&#RRGGBB) formats.
     *
     * @param text the text to translate
     * @return the translated text with color codes applied
     */
    public String translateColorCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // First, translate hex colors (&#RRGGBB) via shared pure transform
        text = translateHexColors(text);

        // Then, translate legacy colors (&X) with Bukkit ChatColor
        text = translateLegacyColors(text);

        return text;
    }

    /**
     * Translates hex color codes (&#RRGGBB) to Minecraft section-sign form.
     *
     * @param text the text to translate
     * @return the translated text
     */
    private String translateHexColors(String text) {
        return LegacyColorCodes.toSectionX(text);
    }

    /**
     * Translates legacy color codes (&X) to Minecraft format (§X).
     *
     * @param text the text to translate
     * @return the translated text
     */
    private String translateLegacyColors(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
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

        // Remove hex colors first
        text = LegacyColorCodes.HASH_HEX_PATTERN.matcher(text).replaceAll("");

        // Remove legacy colors
        text = ChatColor.stripColor(translateLegacyColors(text));

        // Remove any remaining & codes
        text = LEGACY_PATTERN.matcher(text).replaceAll("");

        return text;
    }

    /**
     * Sets PlaceholderAPI placeholders in a string.
     * This method is called via reflection to avoid hard dependency.
     *
     * @param player the player context
     * @param text the text with placeholders
     * @return the text with placeholders replaced
     */
    private String setPlaceholders(Player player, String text) {
        try {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
        } catch (Exception e) {
            plugin.debug("Failed to set PlaceholderAPI placeholders: " + e.getMessage());
            return text;
        }
    }

    /**
     * Checks if PlaceholderAPI is available.
     *
     * @return true if PlaceholderAPI is available
     */
    public boolean isPlaceholderApiAvailable() {
        return placeholderApiAvailable;
    }

    /**
     * Reloads the formatter (re-checks PlaceholderAPI availability).
     */
    public void reload() {
        checkPlaceholderApi();
    }
}
