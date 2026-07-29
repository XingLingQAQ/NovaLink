package com.nova.chat.velocity.chat;

import com.nova.chat.client.format.FormatTemplateEngine;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats chat messages with color codes and placeholders.
 * Supports legacy color codes (&) and hex colors (&#RRGGBB).
 *
 * <p>Placeholder substitution is delegated to {@link FormatTemplateEngine};
 * Adventure color/component conversion remains platform-specific.
 *
 * Requirements: 10.1-10.6
 */
public class MessageFormatter {

    private final NovaChatVelocity plugin;
    private NovaChatConfig config;

    /** Pattern for hex color codes like &#RRGGBB */
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    /** Legacy component serializer for & color codes */
    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
        LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    /**
     * Creates a new MessageFormatter.
     *
     * @param plugin the plugin instance
     */
    public MessageFormatter(NovaChatVelocity plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
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
     * @return the formatted message as a Component
     */
    public Component formatChatMessage(Player player, String channelId, String channelName,
                                        String senderName, String content, Map<String, String> placeholders) {
        String format = config.getChannelFormat(channelId);

        String formatted = FormatTemplateEngine.apply(
                format,
                senderName,
                channelId,
                channelName,
                content,
                placeholders);

        // Convert hex colors and legacy codes to Component
        return parseColors(formatted);
    }

    /**
     * Formats a system message (prefix + content).
     *
     * @param message the message content
     * @return the formatted message as a Component
     */
    public Component formatSystemMessage(String message) {
        String formatted = config.getPrefix() + message;
        return parseColors(formatted);
    }

    /**
     * Formats an error message.
     *
     * @param message the error message
     * @return the formatted error as a Component
     */
    public Component formatError(String message) {
        String formatted = config.getPrefix()
                + FormatTemplateEngine.apply(
                        config.getErrorFormat(),
                        Map.of(FormatTemplateEngine.KEY_MESSAGE, message != null ? message : ""));
        return parseColors(formatted);
    }

    /**
     * Formats a success message.
     *
     * @param message the success message
     * @return the formatted success as a Component
     */
    public Component formatSuccess(String message) {
        String formatted = config.getPrefix()
                + FormatTemplateEngine.apply(
                        config.getSuccessFormat(),
                        Map.of(FormatTemplateEngine.KEY_MESSAGE, message != null ? message : ""));
        return parseColors(formatted);
    }

    /**
     * Parses color codes in a string and returns a Component.
     * Supports both legacy (&) codes and hex (&#RRGGBB) codes.
     *
     * @param text the text to parse
     * @return the parsed Component
     */
    public Component parseColors(String text) {
        // Convert &#RRGGBB to &x&R&R&G&G&B&B format for legacy serializer
        String converted = convertHexColors(text);
        return LEGACY_SERIALIZER.deserialize(converted);
    }

    /**
     * Converts &#RRGGBB hex colors to &x&R&R&G&G&B&B format.
     *
     * @param text the text to convert
     * @return the converted text
     */
    private String convertHexColors(String text) {
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("&x");
            for (char c : hex.toCharArray()) {
                replacement.append('&').append(c);
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
        stripped = stripped.replaceAll("&[0-9a-fk-or]", "");
        return stripped;
    }

    /**
     * Reloads the formatter configuration.
     */
    public void reload() {
        this.config = plugin.getConfig();
    }
}
