package com.nova.chat.sponge.config;

import com.nova.chat.client.network.ClientConnectionConfig;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration wrapper for NovaChat Sponge plugin.
 * Parses and provides access to config.yml settings using Configurate.
 * 
 * Requirements: 3.1
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
     * Creates a new configuration from the given ConfigurationNode.
     *
     * @param rootNode the root configuration node (can be null for defaults)
     */
    public NovaChatConfig(CommentedConfigurationNode rootNode) {
        if (rootNode == null) {
            // Use defaults
            this.backendHost = "127.0.0.1";
            this.backendPort = 8888;
            this.username = "";
            this.password = "";
            this.reconnectDelay = 5;
            this.replaceVanilla = false;
            this.defaultChannel = "local";
            this.locale = "zh_CN";
            this.prefix = "&8[&bNovaChat&8]&r ";
            this.errorFormat = "&c错误: {message}";
            this.successFormat = "&a成功: {message}";
            this.defaultFormat = "&7[{channel_color}{channel_name}] {player}&f: {message}";
            this.channelFormats = new HashMap<>();
            this.debug = false;
            return;
        }

        // Backend settings
        CommentedConfigurationNode backendNode = rootNode.node("backend");
        this.backendHost = backendNode.node("host").getString("127.0.0.1");
        this.backendPort = backendNode.node("port").getInt(8888);
        this.username = backendNode.node("username").getString("");
        this.password = backendNode.node("password").getString("");
        this.reconnectDelay = backendNode.node("reconnect-delay").getInt(5);

        // Chat settings
        CommentedConfigurationNode chatNode = rootNode.node("chat");
        this.replaceVanilla = chatNode.node("replace_vanilla").getBoolean(false);
        this.defaultChannel = chatNode.node("default_channel").getString("local");
        this.locale = chatNode.node("locale").getString("zh_CN");

        // Format settings
        CommentedConfigurationNode formatNode = rootNode.node("format");
        this.prefix = formatNode.node("prefix").getString("&8[&bNovaChat&8]&r ");
        this.errorFormat = formatNode.node("error").getString("&c错误: {message}");
        this.successFormat = formatNode.node("success").getString("&a成功: {message}");
        this.defaultFormat = formatNode.node("default").getString("&7[{channel_color}{channel_name}] {player}&f: {message}");

        // Channel formats
        this.channelFormats = new HashMap<>();
        CommentedConfigurationNode channelsNode = formatNode.node("channels");
        if (!channelsNode.virtual()) {
            for (Map.Entry<Object, CommentedConfigurationNode> entry : channelsNode.childrenMap().entrySet()) {
                String key = entry.getKey().toString();
                String value = entry.getValue().getString();
                if (value != null) {
                    channelFormats.put(key, value);
                }
            }
        }

        // Debug mode
        this.debug = rootNode.node("debug").getBoolean(false);
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
     * @return the configured default locale tag (e.g. {@code "zh_CN"}), used
     *         to seed {@link com.nova.chat.client.i18n.I18n#setDefaultLocale}
     *         at startup. Never null; defaults to {@code "zh_CN"}.
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
