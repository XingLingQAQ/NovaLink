package com.nova.chat.bukkit.chat;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.format.ColorRenderer;
import com.nova.chat.client.format.FormatTemplateEngine;
import com.nova.chat.client.format.MessageFormatService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Handles message formatting including color codes and PlaceholderAPI variables.
 *
 * <p>String assembly is delegated to {@link MessageFormatService}; the Bukkit
 * {@link ColorRenderer} converts the legacy-coded string (hex expanded to
 * {@code §x§r…}) to a plain {@link String} via {@link ChatColor#translateAlternateColorCodes}.
 * PlaceholderAPI substitution and the platform-specific
 * {@code display_name}/{@code world}/{@code server} extras remain here.
 *
 * Requirements: 10.1-10.6
 */
public class MessageFormatter {

    private final NovaChatBukkit plugin;
    private final NovaChatConfig config;
    private final ColorRenderer<String> renderer;

    private boolean placeholderApiAvailable = false;

    private static final Pattern LEGACY_PATTERN = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    public MessageFormatter(NovaChatBukkit plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();
        this.renderer = text -> {
            String converted = MessageFormatService.convertHexToSection(text);
            return ChatColor.translateAlternateColorCodes('&', converted);
        };
        checkPlaceholderApi();
    }

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

    public String formatChatMessage(Player player, String channelId, String channelName,
                                    String senderName, String message) {
        String format = config.getChannelFormat(channelId);
        Map<String, String> extras = platformExtras(player, senderName);
        String result = FormatTemplateEngine.apply(
                format, senderName, channelId,
                channelName != null ? channelName : channelId,
                message, extras);
        result = applyPlaceholderApi(player, result);
        return renderer.render(result);
    }

    public String formatChatMessage(Player player, String channelId, String channelName,
                                    String senderName, String message,
                                    Map<String, String> placeholders) {
        String format = config.getChannelFormat(channelId);
        Map<String, String> values = new LinkedHashMap<>();
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isEmpty()) {
                    continue;
                }
                values.put(entry.getKey(), entry.getValue());
            }
        }
        values.put(FormatTemplateEngine.KEY_PLAYER, senderName);
        values.put(FormatTemplateEngine.KEY_CHANNEL, channelId);
        values.put(FormatTemplateEngine.KEY_CHANNEL_NAME,
                channelName != null ? channelName : channelId);
        values.put(FormatTemplateEngine.KEY_MESSAGE, message);
        values.putAll(platformExtras(player, senderName));

        String result = FormatTemplateEngine.apply(format, values);
        result = applyPlaceholderApi(player, result);
        return renderer.render(result);
    }

    public String formatSystemMessage(String type, String message) {
        String result = MessageFormatService.buildTypedSystem(
                config.getPrefix(), config.getErrorFormat(), config.getSuccessFormat(), type, message);
        return renderer.render(result);
    }

    private Map<String, String> platformExtras(Player player, String senderName) {
        Map<String, String> extras = new LinkedHashMap<>(4);
        extras.put("display_name", player != null ? player.getDisplayName() : senderName);
        extras.put("world", player != null ? player.getWorld().getName() : "");
        extras.put("server", plugin.getServer().getName());
        return extras;
    }

    public String translateColorCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return renderer.render(text);
    }

    public String stripColors(String text) {
        if (text == null) {
            return null;
        }
        String stripped = MessageFormatService.stripColors(text);
        return ChatColor.stripColor(stripped);
    }

    private String applyPlaceholderApi(Player player, String result) {
        if (placeholderApiAvailable && player != null) {
            try {
                return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, result);
            } catch (Exception e) {
                plugin.debug("Failed to set PlaceholderAPI placeholders: " + e.getMessage());
            }
        }
        return result;
    }

    public boolean isPlaceholderApiAvailable() {
        return placeholderApiAvailable;
    }

    public void reload() {
        checkPlaceholderApi();
    }
}
