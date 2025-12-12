package com.nova.chat.bukkit.chat;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.config.NovaChatConfig;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.regex.Matcher;
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
 * Requirements: 10.1-10.6
 */
public class MessageFormatter {
    
    private final NovaChatBukkit plugin;
    private final NovaChatConfig config;
    
    /** Pattern for hex color codes: &#RRGGBB */
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    
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
        // Get the format template for this channel
        String format = config.getChannelFormat(channelId);
        
        // Replace custom placeholders
        String result = format
            .replace("{player}", senderName)
            .replace("{display_name}", player != null ? player.getDisplayName() : senderName)
            .replace("{channel}", channelId)
            .replace("{channel_name}", channelName != null ? channelName : channelId)
            .replace("{message}", message)
            .replace("{world}", player != null ? player.getWorld().getName() : "")
            .replace("{server}", plugin.getServer().getName());
        
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
        // Get the format template for this channel
        String format = config.getChannelFormat(channelId);
        
        // Replace custom placeholders from map first
        String result = format;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        
        // Replace standard placeholders
        result = result
            .replace("{player}", senderName)
            .replace("{display_name}", player != null ? player.getDisplayName() : senderName)
            .replace("{channel}", channelId)
            .replace("{channel_name}", channelName != null ? channelName : channelId)
            .replace("{message}", message)
            .replace("{world}", player != null ? player.getWorld().getName() : "")
            .replace("{server}", plugin.getServer().getName());
        
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
        
        String result = config.getPrefix() + format.replace("{message}", message);
        return translateColorCodes(result);
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
        
        // First, translate hex colors (&#RRGGBB)
        text = translateHexColors(text);
        
        // Then, translate legacy colors (&X)
        text = translateLegacyColors(text);
        
        return text;
    }
    
    /**
     * Translates hex color codes (&#RRGGBB) to Minecraft format.
     *
     * @param text the text to translate
     * @return the translated text
     */
    private String translateHexColors(String text) {
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        
        while (matcher.find()) {
            String hex = matcher.group(1);
            // Convert to Minecraft hex format: §x§R§R§G§G§B§B
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append("§").append(Character.toLowerCase(c));
            }
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);
        
        return buffer.toString();
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
        text = HEX_PATTERN.matcher(text).replaceAll("");
        
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
