package com.nova.chat.mod.chat;

import java.util.HashMap;
import java.util.Map;

/**
 * Formats chat messages according to configured templates
 */
public class MessageFormatter {
    private final Map<String, String> formatTemplates;
    private final String defaultFormat;
    
    public MessageFormatter(Map<String, String> formatTemplates, String defaultFormat) {
        this.formatTemplates = new HashMap<>(formatTemplates);
        this.defaultFormat = defaultFormat;
    }
    
    /**
     * Format a message for a specific channel
     * @param channelName the channel name
     * @param playerName the player name
     * @param message the message content
     * @return the formatted message
     */
    public String formatMessage(String channelName, String playerName, String message) {
        String template = formatTemplates.getOrDefault(channelName, defaultFormat);
        return template
            .replace("{channel_name}", channelName)
            .replace("{player}", playerName)
            .replace("{message}", message);
    }
    
    /**
     * Format a message with the default template
     * @param playerName the player name
     * @param message the message content
     * @return the formatted message
     */
    public String formatMessageDefault(String playerName, String message) {
        return defaultFormat
            .replace("{player}", playerName)
            .replace("{message}", message);
    }
    
    /**
     * Add or update a format template
     * @param channelName the channel name
     * @param template the format template
     */
    public void setTemplate(String channelName, String template) {
        formatTemplates.put(channelName, template);
    }
    
    /**
     * Get a format template
     * @param channelName the channel name
     * @return the format template, or the default if not found
     */
    public String getTemplate(String channelName) {
        return formatTemplates.getOrDefault(channelName, defaultFormat);
    }
}
