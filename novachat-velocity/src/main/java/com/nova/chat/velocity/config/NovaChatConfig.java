package com.nova.chat.velocity.config;

import com.moandjiezana.toml.Toml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration wrapper for NovaChat Velocity plugin.
 * Parses and provides access to config.toml settings.
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
    public NovaChatConfig(Path dataFolder) {
        this.channelFormats = new HashMap<>();
        loadConfig(dataFolder);
    }

    /**
     * Loads configuration from file, creating default if not exists.
     */
    private void loadConfig(Path dataFolder) {
        Path configPath = dataFolder.resolve("config.toml");
        
        // Create default config if not exists
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(dataFolder);
                try (InputStream in = getClass().getResourceAsStream("/config.toml")) {
                    if (in != null) {
                        Files.copy(in, configPath);
                    } else {
                        createDefaultConfig(configPath);
                    }
                }
            } catch (IOException e) {
                setDefaults();
                return;
            }
        }

        // Parse TOML config
        try {
            Toml toml = new Toml().read(configPath.toFile());
            parseConfig(toml);
        } catch (Exception e) {
            setDefaults();
        }
    }

    /**
     * Parses the TOML configuration.
     */
    private void parseConfig(Toml toml) {
        // Backend settings
        Toml backend = toml.getTable("backend");
        if (backend != null) {
            this.backendHost = backend.getString("host", "127.0.0.1");
            this.backendPort = backend.getLong("port", 8888L).intValue();
            this.username = backend.getString("username", "");
            this.password = backend.getString("password", "");
            this.reconnectDelay = backend.getLong("reconnect-delay", 5L).intValue();
        } else {
            this.backendHost = "127.0.0.1";
            this.backendPort = 8888;
            this.username = "";
            this.password = "";
            this.reconnectDelay = 5;
        }

        // Chat settings
        Toml chat = toml.getTable("chat");
        if (chat != null) {
            this.replaceVanilla = chat.getBoolean("replace_vanilla", false);
            this.defaultChannel = chat.getString("default_channel", "local");
        } else {
            this.replaceVanilla = false;
            this.defaultChannel = "local";
        }

        // Format settings
        Toml format = toml.getTable("format");
        if (format != null) {
            this.prefix = format.getString("prefix", "&8[&bNovaChat&8]&r ");
            this.errorFormat = format.getString("error", "&c错误: {message}");
            this.successFormat = format.getString("success", "&a成功: {message}");
            this.defaultFormat = format.getString("default", "&7[{channel_name}] {player}&f: {message}");
            
            Toml channels = format.getTable("channels");
            if (channels != null) {
                for (Map.Entry<String, Object> entry : channels.entrySet()) {
                    if (entry.getValue() instanceof String) {
                        channelFormats.put(entry.getKey(), (String) entry.getValue());
                    }
                }
            }
        } else {
            this.prefix = "&8[&bNovaChat&8]&r ";
            this.errorFormat = "&c错误: {message}";
            this.successFormat = "&a成功: {message}";
            this.defaultFormat = "&7[{channel_name}] {player}&f: {message}";
        }

        // Debug mode
        this.debug = toml.getBoolean("debug", false);
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
    private void createDefaultConfig(Path configPath) throws IOException {
        String defaultConfig = """
            # NovaChat Velocity Configuration
            
            [backend]
            host = "127.0.0.1"
            port = 8888
            username = ""
            password = ""
            reconnect-delay = 5
            
            [chat]
            replace_vanilla = false
            default_channel = "local"
            
            [format]
            prefix = "&8[&bNovaChat&8]&r "
            error = "&c错误: {message}"
            success = "&a成功: {message}"
            default = "&7[{channel_name}] {player}&f: {message}"
            
            [format.channels]
            global = "&c[全服] &7{player}&f: {message}"
            local = "&e[本地] &7{player}&f: {message}"
            
            debug = false
            """;
        Files.writeString(configPath, defaultConfig);
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
