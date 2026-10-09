package com.nova.chat.bungee.chat;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.client.format.ColorRenderer;
import com.nova.chat.client.format.FormatTemplateEngine;
import com.nova.chat.client.format.MessageFormatService;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.Map;

/**
 * Formats chat messages with color codes and placeholders.
 * Supports legacy color codes (&) and hex colors (&#RRGGBB).
 *
 * <p>String assembly is delegated to {@link MessageFormatService}; the Bungee
 * {@link ColorRenderer} converts the legacy-coded string to a
 * {@link BaseComponent}[] via {@link ChatColor#translateAlternateColorCodes(char, String)}
 * and {@link TextComponent#fromLegacyText(String)}.
 *
 * Requirements: 10.1-10.6
 */
public class MessageFormatter {

    private final NovaChatBungee plugin;
    /** Latest immutable config snapshot; volatile — proxy thread reads. */
    private volatile NovaChatConfig config;
    private final ColorRenderer<BaseComponent[]> renderer;

    public MessageFormatter(NovaChatBungee plugin) {
        this.plugin = plugin;
        this.config = plugin.getPluginConfig();
        this.renderer = text -> {
            String converted = MessageFormatService.convertHexToSection(text);
            converted = ChatColor.translateAlternateColorCodes('&', converted);
            return TextComponent.fromLegacyText(converted);
        };
    }

    public BaseComponent[] formatChatMessage(ProxiedPlayer player, String channelId, String channelName,
                                              String senderName, String content, Map<String, String> placeholders) {
        String format = config.getChannelFormat(channelId);
        String formatted = FormatTemplateEngine.apply(
                format, senderName, channelId, channelName, content, placeholders);
        return renderer.render(formatted);
    }

    public BaseComponent[] formatSystemMessage(String message) {
        return renderer.render(MessageFormatService.buildSystemMessage(config.getPrefix(), message));
    }

    public BaseComponent[] formatError(String message) {
        return renderer.render(
                MessageFormatService.buildError(config.getPrefix(), config.getErrorFormat(), message));
    }

    public BaseComponent[] formatSuccess(String message) {
        return renderer.render(
                MessageFormatService.buildSuccess(config.getPrefix(), config.getSuccessFormat(), message));
    }

    public BaseComponent[] parseColors(String text) {
        return renderer.render(text);
    }

    public String stripColors(String text) {
        return MessageFormatService.stripColors(text);
    }

    public void reload() {
        setConfig(plugin.getPluginConfig());
    }

    /**
     * Installs a new {@link NovaChatConfig} snapshot. Hot-path channel/prefix
     * lookups read this reference on every call, so swapping it here is the
     * full reload semantics — no cached state needs rebuilding.
     *
     * @param newConfig the freshly loaded config snapshot
     */
    public void setConfig(NovaChatConfig newConfig) {
        if (newConfig == null) {
            return;
        }
        this.config = newConfig;
    }
}
