package com.nova.chat.pnx.config;

import cn.nukkit.utils.Config;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.pnx.NovaChatPNX;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration manager for NovaChat-PNX plugin.
 */
@Getter
public class NovaChatConfig {

    private final NovaChatPNX plugin;

    // Backend settings
    private String backendHost;
    private int backendPort;
    private String backendUsername;
    private String backendPassword;
    private int reconnectDelay;

    // Chat settings
    private boolean replaceVanilla;
    private String defaultChannel;

    // Format settings
    private String formatPrefix;
    private String formatError;
    private String formatSuccess;
    private String formatDefault;
    private Map<String, String> channelFormats;

    // World routing settings
    private boolean worldRoutingEnabled;
    private Map<String, String> worldMappings;

    // Debug mode
    private boolean debug;

    public NovaChatConfig(NovaChatPNX plugin) {
        this.plugin = plugin;
        this.channelFormats = new HashMap<>();
        this.worldMappings = new HashMap<>();
    }

    /**
     * Load configuration from config.yml
     */
    public void load() {
        Config config = plugin.getConfig();

        // Backend settings
        backendHost = config.getString("backend.host", "127.0.0.1");
        backendPort = config.getInt("backend.port", 8888);
        backendUsername = config.getString("backend.username", "PNX_Server");
        backendPassword = config.getString("backend.password", "");
        reconnectDelay = config.getInt("backend.reconnect-delay", 5);

        // Chat settings
        replaceVanilla = config.getBoolean("chat.replace_vanilla", false);
        defaultChannel = config.getString("chat.default_channel", "local");

        // Format settings
        formatPrefix = config.getString("format.prefix", "§8[§bNovaChat§8]§r ");
        formatError = config.getString("format.error", "§c错误: {message}");
        formatSuccess = config.getString("format.success", "§a成功: {message}");
        formatDefault = config.getString("format.default", "§7[{channel_color}{channel_name}] {player}§f: {message}");

        // Load channel formats
        channelFormats.clear();
        if (config.exists("format.channels")) {
            var channelsSection = config.getSection("format.channels");
            for (String key : channelsSection.getKeys(false)) {
                channelFormats.put(key, channelsSection.getString(key));
            }
        }

        // World routing settings
        worldRoutingEnabled = config.getBoolean("world-routing.enabled", true);
        worldMappings.clear();
        if (config.exists("world-routing.mappings")) {
            var mappingsSection = config.getSection("world-routing.mappings");
            for (String key : mappingsSection.getKeys(false)) {
                worldMappings.put(key, mappingsSection.getString(key));
            }
        }

        // Debug mode
        debug = config.getBoolean("debug", false);

        if (debug) {
            plugin.getLogger().info("Configuration loaded:");
            plugin.getLogger().info("  Backend: " + backendHost + ":" + backendPort);
            plugin.getLogger().info("  Username: " + backendUsername);
            plugin.getLogger().info("  Default channel: " + defaultChannel);
            plugin.getLogger().info("  World routing: " + worldRoutingEnabled);
        }
    }

    /**
     * Get the format for a specific channel.
     *
     * @param channelId The channel ID
     * @return The format string, or the default format if not configured
     */
    public String getChannelFormat(String channelId) {
        return channelFormats.getOrDefault(channelId, formatDefault);
    }

    /**
     * Get the channel ID for a specific world.
     *
     * @param worldName The world name
     * @return The channel ID, or null if not mapped
     */
    public String getWorldChannel(String worldName) {
        return worldMappings.get(worldName);
    }

    /**
     * Maps platform config to the shared {@link ClientConnectionConfig}.
     *
     * <p>Historical reconnect math used a fixed 1s initial / 30s cap / 10 attempts
     * (not {@code backend.reconnect-delay}); that behaviour is preserved here.
     * {@code reconnectDelay} remains available via the getter for callers that want
     * the configured value.
     */
    public ClientConnectionConfig toClientConnectionConfig() {
        return ClientConnectionConfig.builder()
                .host(backendHost)
                .port(backendPort)
                .username(backendUsername)
                .password(backendPassword)
                .build();
    }
}
