package com.nova.chat.mod.chat;

import com.nova.chat.client.command.MessagePrefixes;
import com.nova.chat.client.format.LegacyColorCodes;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Formats chat messages and command feedback for the NovaChat mod common layer.
 *
 * <p>Platform-agnostic: all output is a {@code String} carrying {@code &}-style
 * color codes. Each mod loader (fabric / forge / neoforge / quilt) converts the
 * returned string to its native {@code Component} via {@code LegacyColorCodes}
 * (or the platform's own color parser) before sending to the player.
 *
 * <p>Channel-message templates come from the mod {@code formats} config map;
 * command feedback (success / error / system) is resolved through the shared
 * {@link I18n} service so a player sees text in their own client locale, and
 * color prefixes come from {@link MessagePrefixes}.
 */
public class MessageFormatter {
    private final Map<String, String> formatTemplates;
    private final String defaultFormat;

    public MessageFormatter(Map<String, String> formatTemplates, String defaultFormat) {
        this.formatTemplates = new HashMap<>(formatTemplates != null ? formatTemplates : new HashMap<>());
        this.defaultFormat = defaultFormat != null ? defaultFormat : "{player}: {message}";
    }

    /**
     * Format a channel chat message for display.
     *
     * @param channelName the channel name/id
     * @param playerName  the sender display name
     * @param message     the message content
     * @return the formatted message with {@code &}-style color codes
     */
    public String formatMessage(String channelName, String playerName, String message) {
        String template = formatTemplates.getOrDefault(channelName, defaultFormat);
        return template
            .replace("{channel_name}", channelName != null ? channelName : "")
            .replace("{player}", playerName != null ? playerName : "")
            .replace("{message}", message != null ? message : "");
    }

    /**
     * Format a message with the default template.
     */
    public String formatMessageDefault(String playerName, String message) {
        return defaultFormat
            .replace("{player}", playerName != null ? playerName : "")
            .replace("{message}", message != null ? message : "");
    }

    /**
     * Renders a success feedback line (brand prefix + body). The prefix is a
     * shared color-code constant (no natural language, so not i18n'd).
     *
     * @param playerId the player's UUID (reserved for future i18n body; may be null)
     * @param text     the success body (already localized by the caller)
     * @return the prefixed, colorized string
     */
    public String formatSuccess(UUID playerId, String text) {
        return MessagePrefixes.SUCCESS_PREFIX + (text != null ? text : "");
    }

    /**
     * Renders an error feedback line (brand prefix + body).
     */
    public String formatError(UUID playerId, String text) {
        return MessagePrefixes.ERROR_PREFIX + (text != null ? text : "");
    }

    /**
     * Renders a system/info feedback line (brand prefix + body).
     */
    public String formatSystemMessage(UUID playerId, String text) {
        return MessagePrefixes.PREFIX + (text != null ? text : "");
    }

    /**
     * Convenience: parse {@code &}-style color codes to {@code §} for platforms
     * that consume section-sign text (forge/neoforge legacy) or as a shared
     * pre-step before Component construction.
     *
     * @param text the text with {@code &} color codes
     * @return the text with {@code §} color codes
     */
    public String parseColors(String text) {
        return LegacyColorCodes.ampersandToSection(text);
    }

    public void setTemplate(String channelName, String template) {
        formatTemplates.put(channelName, template);
    }

    public String getTemplate(String channelName) {
        return formatTemplates.getOrDefault(channelName, defaultFormat);
    }
}
