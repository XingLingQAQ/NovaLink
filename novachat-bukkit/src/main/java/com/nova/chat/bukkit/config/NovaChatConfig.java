package com.nova.chat.bukkit.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration wrapper for NovaChat Bukkit plugin.
 * Parses and provides access to config.yml settings.
 */
public class NovaChatConfig {

    // Backend connection settings
    private final String backendHost;
    private final int backendPort;
    private final String username;
    private final String password;
    private final int reconnectDelay;

    // Chat settings
    private final boolean replaceVanilla;
    private final String defaultChannel;

    // Format settings
    private final String prefix;
    private final String errorFormat;
    private final String successFormat;
    private final String defaultFormat;
    private final Map<String, String> channelFormats;

    // Debug mode
    private final boolean debug;

    /**
     * Creates a new configuration from the given FileConfiguration.
     *
     * @param config the Bukkit configuration
     */
    public NovaChatConfig(FileConfiguration config) {
        // Backend settings
        this.backendHost = config.getString("backend.host", "127.0.0.1");
        this.backendPort = config.getInt("backend.port", 8888);
        this.username = config.getString("backend.username", "");
        this.password = config.getString("backend.password", "");
        this.reconnectDelay = config.getInt("backend.reconnect-delay", 5);

        // Chat settings
        this.replaceVanilla = config.getBoolean("chat.replace_vanilla", false);
        this.defaultChannel = config.getString("chat.default_channel", "local");

        // Format settings
        this.prefix = config.getString("format.prefix", "&8[&bNovaChat&8]&r ");
        this.errorFormat = config.getString("format.error", "&c错误: {message}");
        this.successFormat = config.getString("format.success", "&a成功: {message}");
        this.defaultFormat = config.getString("format.default", "&7[{channel_name}] {player}&f: {message}");

        // Channel formats
        this.channelFormats = new HashMap<>();
        ConfigurationSection channelsSection = config.getConfigurationSection("format.channels");
        if (channelsSection != null) {
            for (String key : channelsSection.getKeys(false)) {
                channelFormats.put(key, channelsSection.getString(key));
            }
        }

        // Debug mode
        this.debug = config.getBoolean("debug", false);
    }

    // Getters

    public String getBackendHost() {
        return backendHost;
    }

    public int getBackendPort() {
        return backendPort;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getReconnectDelay() {
        return reconnectDelay;
    }

    public boolean isReplaceVanilla() {
        return replaceVanilla;
    }

    public String getDefaultChannel() {
        return defaultChannel;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getErrorFormat() {
        return errorFormat;
    }

    public String getSuccessFormat() {
        return successFormat;
    }

    public String getDefaultFormat() {
        return defaultFormat;
    }

    public Map<String, String> getChannelFormats() {
        return channelFormats;
    }

    /**
     * Gets the format for a specific channel.
     *
     * @param channelId the channel ID
     * @return the format string, or default format if not configured
     */
    public String getChannelFormat(String channelId) {
        return channelFormats.getOrDefault(channelId, defaultFormat);
    }

    public boolean isDebug() {
        return debug;
    }
}
