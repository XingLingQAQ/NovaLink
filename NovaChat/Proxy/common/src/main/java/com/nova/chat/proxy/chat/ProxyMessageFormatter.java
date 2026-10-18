package com.nova.chat.proxy.chat;

import com.nova.chat.client.format.FormatTemplateEngine;
import com.nova.chat.client.format.MessageFormatService;
import com.nova.chat.proxy.config.ProxyConfigView;

import java.util.Map;

/**
 * String-level message assembly shared by the proxy family.
 *
 * <p>Both proxy plugins carried an identical {@code MessageFormatter} whose only
 * platform-specific part was the color renderer. This class keeps the string
 * assembly once; the platform shell keeps the (tiny) renderer adapter and turns
 * the produced legacy-coded strings into Adventure {@code Component}s or
 * {@code BaseComponent[]}.
 *
 * <p>Every method returns a legacy-coded string (never a platform object), so the
 * core can build text and hand it to {@code ProxyPlatform} for rendering.
 */
public final class ProxyMessageFormatter {

    private final ProxyConfigView config;

    /**
     * @param config the current configuration snapshot
     */
    public ProxyMessageFormatter(ProxyConfigView config) {
        this.config = config;
    }

    /**
     * Formats a channel chat line.
     *
     * @param channelId the channel id
     * @param channelName the channel display name
     * @param senderName the sender's name
     * @param content the (already mention-highlighted) message body
     * @param placeholders extra placeholders from the backend packet
     * @return the legacy-coded line
     */
    public String formatChatMessage(String channelId, String channelName, String senderName,
                                    String content, Map<String, String> placeholders) {
        String format = config.getChannelFormat(channelId);
        return FormatTemplateEngine.apply(
                format, senderName, channelId, channelName, content, placeholders);
    }

    /**
     * @param message the message body
     * @return {@code prefix + message}
     */
    public String formatSystemMessage(String message) {
        return MessageFormatService.buildSystemMessage(config.getPrefix(), message);
    }

    /**
     * @param message the error body
     * @return {@code prefix + errorFormat(message)}
     */
    public String formatError(String message) {
        return MessageFormatService.buildError(
                config.getPrefix(), config.getErrorFormat(), message);
    }

    /**
     * @param message the success body
     * @return {@code prefix + successFormat(message)}
     */
    public String formatSuccess(String message) {
        return MessageFormatService.buildSuccess(
                config.getPrefix(), config.getSuccessFormat(), message);
    }

    /**
     * @param text legacy-coded text
     * @return the same text (rendering happens in the platform shell)
     */
    public String parseColors(String text) {
        return text;
    }

    /**
     * @param text legacy-coded text
     * @return the text with color markup stripped
     */
    public String stripColors(String text) {
        return MessageFormatService.stripColors(text);
    }
}
