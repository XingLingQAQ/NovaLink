package com.nova.chat.velocity.chat;

import com.nova.chat.client.format.ColorRenderer;
import com.nova.chat.client.format.FormatTemplateEngine;
import com.nova.chat.client.format.MessageFormatService;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;

/**
 * Formats chat messages with color codes and placeholders.
 * Supports legacy color codes (&) and hex colors (&#RRGGBB).
 *
 * <p>String assembly is delegated to {@link MessageFormatService}; the Velocity
 * {@link ColorRenderer} converts the legacy-coded string (hex expanded to
 * {@code &x&r…}) to an Adventure {@link Component} via
 * {@link LegacyComponentSerializer} configured with the {@code &} character.
 *
 * Requirements: 10.1-10.6
 */
public class MessageFormatter {

    private final NovaChatVelocity plugin;
    private NovaChatConfig config;
    private final ColorRenderer<Component> renderer;

    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
        LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    public MessageFormatter(NovaChatVelocity plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        this.renderer = text -> {
            String converted = MessageFormatService.convertHexToAmpersand(text);
            return LEGACY_SERIALIZER.deserialize(converted);
        };
    }

    public Component formatChatMessage(Player player, String channelId, String channelName,
                                        String senderName, String content, Map<String, String> placeholders) {
        String format = config.getChannelFormat(channelId);
        String formatted = FormatTemplateEngine.apply(
                format, senderName, channelId, channelName, content, placeholders);
        return renderer.render(formatted);
    }

    public Component formatSystemMessage(String message) {
        return renderer.render(MessageFormatService.buildSystemMessage(config.getPrefix(), message));
    }

    public Component formatError(String message) {
        return renderer.render(
                MessageFormatService.buildError(config.getPrefix(), config.getErrorFormat(), message));
    }

    public Component formatSuccess(String message) {
        return renderer.render(
                MessageFormatService.buildSuccess(config.getPrefix(), config.getSuccessFormat(), message));
    }

    public Component parseColors(String text) {
        return renderer.render(text);
    }

    public String stripColors(String text) {
        return MessageFormatService.stripColors(text);
    }

    public void reload() {
        this.config = plugin.getConfig();
    }
}
