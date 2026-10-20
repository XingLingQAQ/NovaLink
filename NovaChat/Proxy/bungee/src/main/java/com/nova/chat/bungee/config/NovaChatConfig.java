package com.nova.chat.bungee.config;

import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.common.config.YamlConfigUpdater;
import com.nova.chat.proxy.config.ConfigSource;
import com.nova.chat.proxy.config.LiteChannelConfig;
import com.nova.chat.proxy.config.LiteStorageConfig;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.config.ProxyMode;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

/**
 * Configuration wrapper for NovaChat BungeeCord plugin.
 *
 * <p>YAML reading and template installation/upgrade stay here (platform-owned
 * per the proxy layering); the schema and its validation live in the shared
 * {@link ProxyConfig}, fed through {@link BungeeConfigSource} so BungeeCord and
 * Velocity accept and reject exactly the same documents.
 */
public class NovaChatConfig implements ProxyConfigView {

    /**
     * Mappings whose existing children are entirely user-owned, so the template
     * updater must not add the template's entries back into them.
     *
     * <p>{@code lite.channels} is a dynamic collection: the template ships an
     * example channel and an operator deleting it must not have it restored on
     * the next start.
     */
    private static final Set<String> DYNAMIC_CONFIG_MAPPINGS = Set.of(
            "chat.channel-prefixes", "format.channels", "world-routing.mappings",
            "lite.channels",
            // User-owned collection, same reasoning as lite.channels: the
            // template ships it empty and a deleted identity must not be
            // restored.
            "backend.identities");

    /** Shared immutable value model produced by {@link #parseConfig(Configuration)}. */
    private ProxyConfig model = new ProxyConfig.Builder().build();

    /**
     * @return the parsed configuration model, for values not exposed through
     *         {@link ProxyConfigView}
     */
    public ProxyConfig getModel() {
        return model;
    }

    private YamlConfigUpdater.UpdateResult updateResult;

    /**
     * Creates a new configuration from the given data folder.
     *
     * @param dataFolder the plugin data folder
     */
    public NovaChatConfig(File dataFolder) {
        loadConfig(dataFolder);
    }

    /**
     * Loads configuration from file, creating default if not exists.
     */
    private void loadConfig(File dataFolder) {
        File configFile = new File(dataFolder, "config.yml");
        try (InputStream template = getClass().getResourceAsStream("/config.yml")) {
            if (template == null) {
                throw new IOException("Bundled config.yml template is missing");
            }
            updateResult = YamlConfigUpdater.update(configFile.toPath(), template,
                    DYNAMIC_CONFIG_MAPPINGS);
            Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class)
                .load(configFile);
            parseConfig(config);
        } catch (Exception e) {
            // Keep the validation text in the wrapper message: the plugin only
            // logs the top-level message, and "Failed to install..." alone gives
            // an operator no clue which key is wrong.
            throw new IllegalStateException(
                    "Failed to install, upgrade, or load " + configFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses the YAML configuration into the shared value model.
     */
    private void parseConfig(Configuration config) {
        try {
            this.model = ProxyConfig.from(new BungeeConfigSource(config));
        } catch (ConfigSource.ConfigException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    // Getters

    public YamlConfigUpdater.UpdateResult getUpdateResult() {
        return updateResult;
    }

    @Override
    public ProxyMode getMode() {
        return model.getMode();
    }

    @Override
    public String getClientId() {
        return model.getClientId();
    }

    /**
     * @return the {@code lite.server-id}, or null in relay mode
     */
    public String getServerId() {
        return model.getServerId();
    }

    /**
     * @return the declared {@code lite.channels}; empty in relay mode
     */
    @Override
    public Map<String, LiteChannelConfig> getLiteChannels() {
        return model.getLiteChannels();
    }

    /**
     * @return the parsed {@code lite.storage}, or null in relay mode
     */
    public LiteStorageConfig getLiteStorage() {
        return model.getLiteStorage();
    }

    @Override
    public String getBackendHost() {
        return model.getBackendHost();
    }

    @Override
    public int getBackendPort() {
        return model.getBackendPort();
    }

    @Override
    public String getUsername() {
        return model.getUsername();
    }

    @Override
    public String getPassword() {
        return model.getPassword();
    }

    @Override
    public int getReconnectDelay() {
        return model.getReconnectDelay();
    }

    /**
     * @return the relay per-server backend identities
     *         ({@code backend.identities}); empty when the proxy uses the
     *         single {@code backend.username} connection
     */
    public Map<String, String> getBackendIdentities() {
        return model.getBackendIdentities();
    }

    @Override
    public boolean isReplaceVanilla() {
        return model.isReplaceVanilla();
    }

    @Override
    public String getDefaultChannel() {
        return model.getDefaultChannel();
    }

    /**
     * Gets the configured default locale string (e.g. {@code "zh_CN"},
     * {@code "en_US"}). Used to seed {@link com.nova.chat.client.i18n.I18n} at
     * startup; per-player client locales still override this.
     *
     * @return the locale string read from {@code chat.locale}
     */
    @Override
    public String getLocale() {
        return model.getLocale();
    }


    @Override
    public String getPrefix() {
        return model.getPrefix();
    }

    @Override
    public String getErrorFormat() {
        return model.getErrorFormat();
    }

    @Override
    public String getSuccessFormat() {
        return model.getSuccessFormat();
    }

    @Override
    public String getDefaultFormat() {
        return model.getDefaultFormat();
    }

    @Override
    public Map<String, String> getChannelFormats() {
        return model.getChannelFormats();
    }

    /**
     * Gets the configured channel-prefix routing map
     * ({@code chat.channel-prefixes}: prefix string → channel ID). Empty map
     * (the default) disables prefix routing.
     *
     * @return the prefix→channelId map, never null
     */
    @Override
    public Map<String, String> getChannelPrefixes() {
        return model.getChannelPrefixes();
    }

    /**
     * Gets the format for a specific channel.
     *
     * @param channelId the channel ID
     * @return the format string, or default format if not configured
     */
    @Override
    public String getChannelFormat(String channelId) {
        return model.getChannelFormat(channelId);
    }

    @Override
    public boolean isDebug() {
        return model.isDebug();
    }

    /**
     * Maps platform config to the shared {@link ClientConnectionConfig}.
     *
     * <p>The initial reconnect delay comes from {@code backend.reconnect-delay}.
     *
     * @throws IllegalStateException in lite mode, where no backend is configured
     */
    @Override
    public ClientConnectionConfig toClientConnectionConfig() {
        return model.toClientConnectionConfig();
    }
}
