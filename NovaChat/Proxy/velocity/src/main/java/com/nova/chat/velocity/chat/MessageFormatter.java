package com.nova.chat.velocity.chat;

import com.nova.chat.client.format.MessageFormatService;
import com.nova.chat.proxy.chat.ProxyMessageFormatter;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.nova.chat.velocity.platform.VelocityProxyPlatform;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Velocity-side message formatter shell.
 *
 * <p>String assembly is owned by the shared {@link ProxyMessageFormatter} (which
 * wraps the client-core {@link MessageFormatService}); this class only renders
 * the produced legacy-coded strings into Adventure {@link Component}s through
 * the same {@link VelocityProxyPlatform} renderer the shared core uses.
 *
 * <p>Requirements: 10.1-10.6
 */
public class MessageFormatter {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.builder()
                    .character('&')
                    .hexColors()
                    .build();

    private final Supplier<? extends ProxyConfigView> configSupplier;
    private final ProxyMessageFormatter delegate;

    /**
     * @param plugin the plugin instance
     */
    public MessageFormatter(NovaChatVelocity plugin) {
        this(plugin, plugin::getConfig);
    }

    /**
     * @param plugin the plugin instance (retained for API compatibility)
     * @param configSupplier supplies the current configuration snapshot
     */
    public MessageFormatter(NovaChatVelocity plugin, Supplier<? extends ProxyConfigView> configSupplier) {
        this.configSupplier = configSupplier;
        this.delegate = new ProxyMessageFormatter(this.configSupplier.get());
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
     * @return the rendered component
     */
    public Component formatChatMessage(com.velocitypowered.api.proxy.Player player,
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
    public Component formatSystemMessage(String message) {
        return render(new ProxyMessageFormatter(configSupplier.get()).formatSystemMessage(message));
    }

    /**
     * @param message the error body
     * @return the rendered error line
     */
    public Component formatError(String message) {
        return render(new ProxyMessageFormatter(configSupplier.get()).formatError(message));
    }

    /**
     * @param message the success body
     * @return the rendered success line
     */
    public Component formatSuccess(String message) {
        return render(new ProxyMessageFormatter(configSupplier.get()).formatSuccess(message));
    }

    /**
     * @param text legacy-coded text
     * @return the rendered component
     */
    public Component parseColors(String text) {
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
     * Renders a legacy-coded string exactly like the shared platform renderer.
     *
     * @param legacyText the text
     * @return the component, never null
     */
    private static Component render(String legacyText) {
        return LEGACY_SERIALIZER.deserialize(
                MessageFormatService.convertHexToAmpersand(legacyText != null ? legacyText : ""));
    }
}
