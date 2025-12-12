package com.nova.chat.bungee.config;

import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration wrapper for NovaChat BungeeCord plugin.
 * Parses and provides access to config.yml settings.
 */
public class NovaChatConfig {

    // Backend connection settings
    private String backendHost;
    private int backendPort;
    private String username;
    private String password;
    private int reconnectDelay;

    // Chat settings
    private boolean replaceVanilla;
    private String defaultChannel;

    // Format settings
    private String prefix;
    private String errorFormat;
    private String successFormat;
    private String defaultFormat;
    private Map<String, String> channelFormats;

    // Debug mode
    private boolean debug;

    /**
     * Creates a new configuration from the given data folder.
     *
     * @param dataFolder the plugin data folder
     */
    public NovaChatConfig(File dataFolder) {
        this.channelFormats = new HashMap<>();
        loadConfig(dataFolder);
    }

    /**
     * Loads configuration from file, creating default if not exists.
     */
    private void loadConfig(File dataFolder) {
        File configFile = new File(dataFolder, "config.yml");
        
        // Create default config if not exists
        if (!configFile.exists()) {
            try {
                if (!dataFolder.exists()) {
                    dataFolder.mkdirs();
                }
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile.toPath());
                    } else {
                        createDefaultConfig(configFile);
                    }
                }
            } catch (IOException e) {
                setDefaults();
                return;
            }
        }

        // Parse YAML config
        try {
            Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class)
                .load(configFile);
            parseConfig(config);
        } catch (Exception e) {
            setDefaults();
        }
    }

    /**
     * Parses the YAML configuration.
     */
    private void parseConfig(Configuration config) {
        // Backend settings
        Configuration backend = config.getSection("backend");
        if (backend != null) {
            this.backendHost = backend.getString("host", "127.0.0.1");
            this.backendPort = backend.getInt("port", 8888);
            this.username = backend.getString("username", "");
            this.password = backend.getString("password", "");
            this.reconnectDelay = backend.getInt("reconnect-delay", 5);
        } else {
            this.backendHost = "127.0.0.1";
            this.backendPort = 8888;
            this.username = "";
            this.password = "";
            this.reconnectDelay = 5;
        }

        // Chat settings
        Configuration chat = config.getSection("chat");
        if (chat != null) {
            this.replaceVanilla = chat.getBoolean("replace_vanilla", false);
            this.defaultChannel = chat.getString("default_channel", "local");
        } else {
            this.replaceVanilla = false;
            this.defaultChannel = "local";
        }

        // Format settings
        Configuration format = config.getSection("format");
        if (format != null) {
            this.prefix = format.getString("prefix", "&8[&bNovaChat&8]&r ");
            this.errorFormat = format.getString("error", "&c错误: {message}");
            this.successFormat = format.getString("success", "&a成功: {message}");
            this.defaultFormat = format.getString("default", "&7[{channel_name}] {player}&f: {message}");
            
            Configuration channels = format.getSection("channels");
            if (channels != null) {
                for (String key : channels.getKeys()) {
                    channelFormats.put(key, channels.getString(key));
                }
            }
        } else {
            this.prefix = "&8[&bNovaChat&8]&r ";
            this.errorFormat = "&c错误: {message}";
            this.successFormat = "&a成功: {message}";
            this.defaultFormat = "&7[{channel_name}] {player}&f: {message}";
        }

        // Debug mode
        this.debug = config.getBoolean("debug", false);
    }

    /**
     * Sets default values.
     */
    private void setDefaults() {
        this.backendHost = "127.0.0.1";
        this.backendPort = 8888;
        this.username = "";
        this.password = "";
        this.reconnectDelay = 5;
        this.replaceVanilla = false;
        this.defaultChannel = "local";
        this.prefix = "&8[&bNovaChat&8]&r ";
        this.errorFormat = "&c错误: {message}";
        this.successFormat = "&a成功: {message}";
        this.defaultFormat = "&7[{channel_name}] {player}&f: {message}";
        this.debug = false;
    }

    /**
     * Creates a default configuration file.
     */
    private void createDefaultConfig(File configFile) throws IOException {
        String defaultConfig = """
            # ==========================================
            # NovaChat BungeeCord 配置文件
            # ==========================================
            
            # 后端连接设置
            backend:
              host: "127.0.0.1"
              port: 8888
              username: ""
              password: ""
              reconnect-delay: 5
            
            # 聊天设置
            chat:
              replace_vanilla: false
              default_channel: "local"
            
            # 消息格式
            format:
              prefix: "&8[&bNovaChat&8]&r "
              error: "&c错误: {message}"
              success: "&a成功: {message}"
              default: "&7[{channel_name}] {player}&f: {message}"
              channels:
                global: "&c[全服] &7{player}&f: {message}"
                local: "&e[本地] &7{player}&f: {message}"
            
            # 调试模式
            debug: false
            """;
        Files.writeString(configFile.toPath(), defaultConfig);
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
