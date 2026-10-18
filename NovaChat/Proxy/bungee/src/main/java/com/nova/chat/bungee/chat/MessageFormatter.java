package com.nova.chat.bungee.chat;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.client.format.ColorRenderer;
import com.nova.chat.client.format.MessageFormatService;
import com.nova.chat.proxy.chat.ProxyMessageFormatter;
import com.nova.chat.proxy.config.ProxyConfigView;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.Map;
import java.util.function.Supplier;

/**
 * BungeeCord-side message formatter shell.
 *
 * <p>String assembly is owned by the shared {@link ProxyMessageFormatter} (which
 * wraps the client-core {@link MessageFormatService}); this class only renders
 * the produced legacy-coded strings into md_5 {@link BaseComponent}s through the
 * same {@link ColorRenderer} pipeline the platform adapter uses.
 *
 * <p>Requirements: 10.1-10.6
 */
public class MessageFormatter {

    private final Supplier<? extends ProxyConfigView> configSupplier;
    private final ColorRenderer<BaseComponent[]> renderer;

    /**
     * @param plugin the plugin instance
     */
    public MessageFormatter(NovaChatBungee plugin) {
        this(plugin, plugin::getPluginConfig);
    }

    /**
     * @param plugin the plugin instance (retained for API compatibility)
     * @param configSupplier supplies the current configuration snapshot
     */
    public MessageFormatter(NovaChatBungee plugin, Supplier<? extends ProxyConfigView> configSupplier) {
        this.configSupplier = configSupplier;
        this.renderer = text -> {
            String converted = MessageFormatService.convertHexToSection(text != null ? text : "");
            converted = ChatColor.translateAlternateColorCodes('&', converted);
            return TextComponent.fromLegacyText(converted);
        };
    }

    /**
     * Formats a channel chat line.
     *
     * @param player the recipient (unused; kept for API compatibility)
     * @param channelId the channel id
     * @param channelName the channel display name
     * @param senderName the sender name
     * @param content the message body
     * @param placeholders backend placeholders
     * @return the rendered components
     */
    public BaseComponent[] formatChatMessage(net.md_5.bungee.api.connection.ProxiedPlayer player,
                                             String channelId, String channelName,
                                             String senderName, String content,
                                             Map<String, String> placeholders) {
        return render(new ProxyMessageFormatter(configSupplier.get())
                .formatChatMessage(channelId, channelName, senderName, content, placeholders));
    }

    /**
     * @param message the message body
     * @return the rendered system line
     */
    public BaseComponent[] formatSystemMessage(String message) {
        return render(new ProxyMessageFormatter(configSupplier.get()).formatSystemMessage(message));
    }

    /**
     * @param message the error body
     * @return the rendered error line
     */
    public BaseComponent[] formatError(String message) {
        return render(new ProxyMessageFormatter(configSupplier.get()).formatError(message));
    }

    /**
     * @param message the success body
     * @return the rendered success line
     */
    public BaseComponent[] formatSuccess(String message) {
        return render(new ProxyMessageFormatter(configSupplier.get()).formatSuccess(message));
    }

    /**
     * @param text legacy-coded text
     * @return the rendered components
     */
    public BaseComponent[] parseColors(String text) {
        return render(text);
    }

    /**
     * @param text legacy-coded text
     * @return the text with color markup stripped
     */
    public String stripColors(String text) {
        return MessageFormatService.stripColors(text);
    }

    /**
     * Installs a new config snapshot.
     *
     * @param newConfig the fresh snapshot (null is ignored)
     */
    public void setConfig(NovaChatConfig newConfig) {
        // Config is read through the supplier on every call, so swapping the
        // plugin's snapshot is the full reload semantics.
    }

    /**
     * Renders a legacy-coded string into md_5 components.
     *
     * @param legacyText the text
     * @return the components, never null
     */
    private BaseComponent[] render(String legacyText) {
        return renderer.render(legacyText);
    }
}
