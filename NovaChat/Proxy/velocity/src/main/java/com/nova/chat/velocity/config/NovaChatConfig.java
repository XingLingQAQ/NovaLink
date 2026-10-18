package com.nova.chat.velocity.config;

import com.moandjiezana.toml.Toml;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.proxy.config.ConfigSource;
import com.nova.chat.proxy.config.LiteChannelConfig;
import com.nova.chat.proxy.config.LiteStorageConfig;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.config.ProxyMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Configuration wrapper for NovaChat Velocity plugin.
 *
 * <p>TOML reading and template installation/upgrade stay here (platform-owned
 * per the proxy layering); the schema and its validation live in the shared
 * {@link ProxyConfig}, fed through {@link TomlConfigSource} so Velocity and
 * BungeeCord accept and reject exactly the same documents.
 */
public class NovaChatConfig implements ProxyConfigView {

    /**
     * Tables whose existing children are entirely user-owned, so the template
     * updater must not add the template's entries back into them.
     *
     * <p>{@code lite.channels} is a dynamic collection: the template ships an
     * example channel and an operator deleting it must not have it restored on
     * the next start. The table itself is still appended when missing entirely
     * (a table header is not an assignment), which is why lite channels cannot
     * be declared with {@code [[array-of-tables]]}: the updater's header pattern
     * does not match those, so they would be re-appended wholesale.
     */
    private static final Set<String> DYNAMIC_CONFIG_TABLES = Set.of(
            "chat.channel-prefixes", "format.channels", "world-routing.mappings",
            "lite.channels");

    /** Shared immutable value model produced by {@link #parseConfig(Toml)}. */
    private ProxyConfig model = new ProxyConfig.Builder().build();

    private TomlConfigUpdater.UpdateResult updateResult;

    /**
     * Creates a new configuration from the given data folder.
     *
     * @param dataFolder the plugin data folder
     */
    public NovaChatConfig(Path dataFolder) {
        loadConfig(dataFolder);
    }

    /**
     * Loads configuration from file, creating default if not exists.
     */
    private void loadConfig(Path dataFolder) {
        Path configPath = dataFolder.resolve("config.toml");
        try (InputStream template = getClass().getResourceAsStream("/config.toml")) {
            if (template == null) {
                throw new IOException("Bundled config.toml template is missing");
            }
            updateResult = TomlConfigUpdater.update(configPath, template,
                    DYNAMIC_CONFIG_TABLES);
            Toml toml = new Toml().read(configPath.toFile());
            parseConfig(toml);
        } catch (Exception e) {
            // Keep the validation text in the wrapper message: the plugin only
            // logs the top-level message, and "Failed to install..." alone gives
            // an operator no clue which key is wrong.
            throw new IllegalStateException(
                    "Failed to install, upgrade, or load " + configPath + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parses the TOML configuration into the shared value model.
     */
    private void parseConfig(Toml toml) {
        try {
            this.model = ProxyConfig.from(new TomlConfigSource(toml));
        } catch (ConfigSource.ConfigException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    // Getters

    public boolean wasConfigCreated() {
        return updateResult != null && updateResult.created();
    }

    public boolean wasConfigUpdated() {
        return updateResult != null && updateResult.updated();
    }

    public Path getConfigBackupPath() {
        return updateResult != null ? updateResult.backupPath() : null;
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

    /**
     * @return the {@code chat.channel-prefixes} map (message prefix → channel id);
     *         empty when the feature is disabled
     */
    @Override
    public Map<String, String> getChannelPrefixes() {
        return model.getChannelPrefixes();
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
