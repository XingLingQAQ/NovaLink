package com.nova.chat.sponge.chat;

import com.nova.chat.sponge.NovaChatSponge;
import com.nova.chat.sponge.config.NovaChatConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles message formatting using Sponge Text API (Adventure).
 * 
 * Supports:
 * - Legacy color codes (&a, &b, etc.)
 * - Hex color codes (&#RRGGBB)
 * - MiniMessage format (<color:red>, <bold>, etc.)
 * - Custom placeholders ({player}, {channel}, etc.)
 * 
 * Requirements: 3.4
 */
public class MessageFormatter {
    
    private final NovaChatSponge plugin;
    private final NovaChatConfig config;
    
    /** Pattern for hex color codes: &#RRGGBB */
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    
    /** Pattern for legacy color codes: &X where X is 0-9, a-f, k-o, r */
    private static final Pattern LEGACY_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    
    /** Legacy serializer for & codes */
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = 
        LegacyComponentSerializer.legacyAmpersand();
    
    /** MiniMessage serializer for modern formatting */
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    
    /**
     * Creates a new MessageFormatter.
     *
     * @param plugin the plugin instance
     */
    public MessageFormatter(NovaChatSponge plugin) {
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
     * @param placeholders additional placeholders to apply
     * @return the formatted message as a Component
     */
    public Component formatChatMessage(ServerPlayer player, String channelId, String channelName,
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
        String displayName = player != null ? 
            PlainTextComponentSerializer.plainText().serialize(player.displayName().get()) : senderName;
        String worldName = player != null ? player.world().key().value() : "";
        
        result = result
            .replace("{player}", senderName)
            .replace("{display_name}", displayName)
            .replace("{channel}", channelId)
            .replace("{channel_name}", channelName != null ? channelName : channelId)
            .replace("{message}", message)
            .replace("{world}", worldName)
            .replace("{server}", "Sponge");
        
        // Convert hex colors to MiniMessage format
        result = convertHexToMiniMessage(result);
        
        // Convert legacy colors to section symbol for legacy serializer
        result = convertLegacyColors(result);
        
        // Parse with legacy serializer (handles § codes)
        return LEGACY_SERIALIZER.deserialize(result);
    }
    
    /**
     * Formats a chat message without placeholders map.
     *
     * @param player the player sending/receiving the message
     * @param channelId the channel ID
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param message the raw message content
     * @return the formatted message as a Component
     */
    public Component formatChatMessage(ServerPlayer player, String channelId, String channelName,
                                       String senderName, String message) {
        return formatChatMessage(player, channelId, channelName, senderName, message, null);
    }
    
    /**
     * Formats a system message (success, error, etc.).
     *
     * @param type the message type ("error", "success", or custom)
     * @param message the message content
     * @return the formatted message as a Component
     */
    public Component formatSystemMessage(String type, String message) {
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
        result = convertHexToMiniMessage(result);
        result = convertLegacyColors(result);
        
        return LEGACY_SERIALIZER.deserialize(result);
    }
    
    /**
     * Formats an error message.
     *
     * @param message the error message
     * @return the formatted error component
     */
    public Component formatError(String message) {
        return formatSystemMessage("error", message);
    }
    
    /**
     * Formats a success message.
     *
     * @param message the success message
     * @return the formatted success component
     */
    public Component formatSuccess(String message) {
        return formatSystemMessage("success", message);
    }
    
    /**
     * Formats a plain message with the plugin prefix.
     *
     * @param message the message
     * @return the formatted component
     */
    public Component formatMessage(String message) {
        String result = config.getPrefix() + message;
        result = convertHexToMiniMessage(result);
        result = convertLegacyColors(result);
        return LEGACY_SERIALIZER.deserialize(result);
    }
    
    /**
     * Converts hex color codes (&#RRGGBB) to section symbol format.
     *
     * @param text the text to convert
     * @return the converted text
     */
    private String convertHexToMiniMessage(String text) {
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
     * Converts legacy color codes (&X) to section symbol format (§X).
     *
     * @param text the text to convert
     * @return the converted text
     */
    private String convertLegacyColors(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == '&' && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(chars[i + 1]) > -1) {
                chars[i] = '§';
                chars[i + 1] = Character.toLowerCase(chars[i + 1]);
            }
        }
        return new String(chars);
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
        
        // Remove legacy colors (both & and §)
        text = text.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
        
        // Remove hex format §x§R§R§G§G§B§B
        text = text.replaceAll("§x(§[0-9a-fA-F]){6}", "");
        
        return text;
    }
    
    /**
     * Reloads the formatter.
     */
    public void reload() {
        // Currently nothing to reload, but kept for consistency
        plugin.debug("MessageFormatter reloaded");
    }
}
