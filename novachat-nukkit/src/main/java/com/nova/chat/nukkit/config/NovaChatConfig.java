package com.nova.chat.nukkit.config;

import cn.nukkit.utils.Config;
import com.nova.chat.client.network.ClientConnectionConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration wrapper for NovaChat Nukkit plugin.
 * Parses and provides access to config.yml settings.
 * 
 * Adapted from Bukkit version for Nukkit API.
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
    private final String locale;

    // Format settings
    private final String prefix;
    private final String errorFormat;
    private final String successFormat;
    private final String defaultFormat;
    private final Map<String, String> channelFormats;

    // Debug mode
    private final boolean debug;

    /**
     * Creates a new configuration from the given Nukkit Config.
     *
     * @param config the Nukkit configuration
     */
    public NovaChatConfig(Config config) {
        // Backend settings
        this.backendHost = config.getString("backend.host", "127.0.0.1");
        this.backendPort = config.getInt("backend.port", 8888);
        this.username = config.getString("backend.username", "");
        this.password = config.getString("backend.password", "");
        this.reconnectDelay = config.getInt("backend.reconnect-delay", 5);

        // Chat settings
        this.replaceVanilla = config.getBoolean("chat.replace_vanilla", false);
        this.defaultChannel = config.getString("chat.default_channel", "local");
        this.locale = config.getString("chat.locale", "zh_CN");

        // Format settings
        this.prefix = config.getString("format.prefix", "§8[§bNovaChat§8]§r ");
        this.errorFormat = config.getString("format.error", "§c错误: {message}");
        this.successFormat = config.getString("format.success", "§a成功: {message}");
        this.defaultFormat = config.getString("format.default", "§7[{channel_color}{channel_name}] {player}§f: {message}");

        // Channel formats
        this.channelFormats = new HashMap<>();
        Map<String, Object> channelsSection = config.getSection("format.channels").getAllMap();
        if (channelsSection != null) {
            for (Map.Entry<String, Object> entry : channelsSection.entrySet()) {
                if (entry.getValue() instanceof String) {
                    channelFormats.put(entry.getKey(), (String) entry.getValue());
                }
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

    /**
     * Gets the configured default locale code (e.g. {@code "zh_CN"},
     * {@code "en_US"}). Used at startup to seed {@link com.nova.chat.client.i18n.I18n}.
     *
     * @return the configured locale code, defaulting to {@code "zh_CN"}
     */
    public String getLocale() {
        return locale;
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

    /**
     * Maps platform config to the shared {@link ClientConnectionConfig}.
     *
     * <p>Historical reconnect math used a fixed 1s initial / 30s cap / 10 attempts
     * (not {@code backend.reconnect-delay}); that behaviour is preserved here.
     * {@code reconnect-delay} remains available via {@link #getReconnectDelay()} for
     * callers that want the configured value.
     */
    public ClientConnectionConfig toClientConnectionConfig() {
        return ClientConnectionConfig.builder()
                .host(backendHost)
                .port(backendPort)
                .username(username)
                .password(password)
                .build();
    }
}
