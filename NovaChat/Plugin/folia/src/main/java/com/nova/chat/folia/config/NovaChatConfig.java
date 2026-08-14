package com.nova.chat.folia.config;

import com.nova.chat.client.network.ClientConnectionConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration wrapper for NovaChat Folia plugin.
 * Parses and provides access to config.yml settings.
 * 
 * Requirements: 2.1
 */
public class NovaChatConfig {

    // Backend connection settings
    private final String backendHost;
    private final int backendPort;
    private final String username;
    private final String password;
    private final int reconnectDelay;

    // Folia specific settings
    private final boolean asyncProcessing;
    private final int networkTimeout;

    // Chat settings
    private final boolean replaceVanilla;
    private final String defaultChannel;
    private final String locale;
    private final Map<String, String> channelPrefixes;

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

        // Folia settings
        this.asyncProcessing = config.getBoolean("folia.async-processing", true);
        this.networkTimeout = config.getInt("folia.network-timeout", 5000);

        // Chat settings
        this.replaceVanilla = config.getBoolean("chat.replace_vanilla", false);
        this.defaultChannel = config.getString("chat.default_channel", "local");
        this.locale = config.getString("chat.locale", "zh_CN");

        // Channel prefixes (prefix -> channel id; empty map = feature disabled)
        this.channelPrefixes = new HashMap<>();
        ConfigurationSection prefixSection = config.getConfigurationSection("chat.channel-prefixes");
        if (prefixSection != null) {
            for (String key : prefixSection.getKeys(false)) {
                String channelId = prefixSection.getString(key);
                if (key != null && !key.isEmpty() && channelId != null && !channelId.isEmpty()) {
                    channelPrefixes.put(key, channelId);
                }
            }
        }

        // Format settings
        this.prefix = config.getString("format.prefix", "&8[&bNovaChat&8]&r ");
        this.errorFormat = config.getString("format.error", "&c错误: {message}");
        this.successFormat = config.getString("format.success", "&a成功: {message}");
        this.defaultFormat = config.getString("format.default", "&7[{channel_color}{channel_name}] {player}&f: {message}");

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

    public boolean isAsyncProcessing() {
        return asyncProcessing;
    }

    public int getNetworkTimeout() {
        return networkTimeout;
    }

    public boolean isReplaceVanilla() {
        return replaceVanilla;
    }

    public String getDefaultChannel() {
        return defaultChannel;
    }

    /**
     * @return the configured default locale tag (e.g. {@code "zh_CN"}),
     *         parsed by {@link com.nova.chat.client.i18n.LocaleResolver} at startup
     */
    public String getLocale() {
        return locale;
    }

    /**
     * @return the {@code chat.channel-prefixes} map (message prefix → channel id);
     *         empty when the feature is disabled
     */
    public Map<String, String> getChannelPrefixes() {
        return channelPrefixes;
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
     * callers that want the configured value. Network timeout is mapped to
     * {@code connectTimeoutMs}.
     */
    public ClientConnectionConfig toClientConnectionConfig() {
        return ClientConnectionConfig.builder()
                .host(backendHost)
                .port(backendPort)
                .username(username)
                .password(password)
                .connectTimeoutMs(networkTimeout)
                .build();
    }
}
