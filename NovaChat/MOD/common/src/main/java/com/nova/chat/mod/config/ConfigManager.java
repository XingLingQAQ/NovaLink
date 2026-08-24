package com.nova.chat.mod.config;

import com.nova.chat.common.config.YamlConfigUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Manages loading and saving of mod configuration
 */
public class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigManager.class);
    private static final String CONFIG_FILE = "novachat.yml";
    private static final String LEGACY_CONFIG_FILE = "config/novachat.yml";
    private static final String CONFIG_TEMPLATE = "/novachat.yml";
    private static final Set<String> DYNAMIC_MAPPINGS = Set.of(
            "chat.channel-prefixes", "format.channels");
    private static final Yaml YAML = new Yaml();
    
    private ModConfig config;
    private final Path configPath;
    
    public ConfigManager(Path configDir) {
        this.configPath = configDir.resolve(CONFIG_FILE);
    }
    
    /**
     * Load configuration from file
     * @return the loaded configuration
     */
    public ModConfig loadConfig() {
        try {
            migrateLegacyPath();
            try (InputStream template = ConfigManager.class.getResourceAsStream(CONFIG_TEMPLATE)) {
                if (template == null) {
                    throw new IOException("Bundled configuration template is missing: " + CONFIG_TEMPLATE);
                }
                YamlConfigUpdater.UpdateResult update =
                        YamlConfigUpdater.update(configPath, template, DYNAMIC_MAPPINGS);
                if (update.created()) {
                    LOGGER.info("Created configuration from bundled template: {}", configPath);
                } else if (update.updated()) {
                    LOGGER.info("Added newly introduced configuration fields to {} (backup: {})",
                            configPath, update.backupPath());
                }
            }

            LOGGER.info("Loading configuration from {}", configPath);
            try (FileInputStream input = new FileInputStream(configPath.toFile())) {
                Map<String, Object> data = YAML.load(input);
                ModConfig loaded = parseYamlToConfig(data);
                if (!loaded.validate()) {
                    throw new IOException("Configuration validation failed");
                }
                loaded.toClientConnectionConfig();
                config = loaded;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load configuration {}; keeping the previous runtime values",
                    configPath, e);
            if (config == null) {
                throw new IllegalStateException("NovaChat configuration could not be loaded", e);
            }
        }
        
        return config;
    }
    
    /**
     * Get the current configuration
     * @return the configuration
     */
    public ModConfig getConfig() {
        if (config == null) {
            loadConfig();
        }
        return config;
    }
    
    private void migrateLegacyPath() throws IOException {
        Path legacyPath = configPath.getParent().resolve(LEGACY_CONFIG_FILE);
        if (!Files.exists(configPath) && Files.isRegularFile(legacyPath)) {
            Files.createDirectories(configPath.getParent());
            Files.copy(legacyPath, configPath);
            LOGGER.warn("Copied legacy configuration {} to {}; the legacy file was kept",
                    legacyPath, configPath);
        }
    }
    
    /**
     * Parse YAML data to ModConfig
     * @param data the YAML data
     * @return the parsed configuration
     */
    private ModConfig parseYamlToConfig(Map<String, Object> data) {
        if (data == null) {
            throw new IllegalArgumentException("Configuration root must be a YAML mapping");
        }
        ModConfig config = new ModConfig();

        Map<String, Object> backendData = requireMap(data, "backend");
        ModConfig.BackendConfig backend = new ModConfig.BackendConfig();
        backend.setHost(requireNonBlankString(backendData, "host", "backend.host"));
        backend.setPort(requirePort(backendData, "port", "backend.port"));
        backend.setUsername(requireNonBlankString(backendData, "username", "backend.username"));
        backend.setPassword(requireString(backendData, "password", "backend.password"));
        backend.setReconnectDelay(requirePositiveInt(
                backendData, "reconnect-delay", "backend.reconnect-delay"));
        config.setBackend(backend);

        Map<String, Object> chatData = requireMap(data, "chat");
        ModConfig.ChatConfig chat = new ModConfig.ChatConfig();
        chat.setReplaceVanilla(requireBoolean(
                chatData, "replace_vanilla", "chat.replace_vanilla"));
        chat.setDefaultChannel(requireNonBlankString(
                chatData, "default_channel", "chat.default_channel"));
        chat.setLocale(requireNonBlankString(chatData, "locale", "chat.locale"));
        Map<String, Object> prefixData = requireMap(chatData, "channel-prefixes");
        Map<String, String> prefixes = new HashMap<>();
        for (Map.Entry<String, Object> entry : prefixData.entrySet()) {
            if (!(entry.getValue() instanceof String channelId)) {
                throw new IllegalArgumentException(
                        "Configuration value chat.channel-prefixes." + entry.getKey()
                                + " must be a string");
            }
            if (!entry.getKey().isEmpty() && !channelId.isBlank()) {
                prefixes.put(entry.getKey(), channelId);
            }
        }
        chat.setChannelPrefixes(prefixes);
        config.setChat(chat);

        Map<String, Object> formatData = requireMap(data, "format");
        Map<String, Object> channelData = requireMap(formatData, "channels");
        Map<String, String> channels = new HashMap<>();
        for (Map.Entry<String, Object> entry : channelData.entrySet()) {
            if (!(entry.getValue() instanceof String format)) {
                throw new IllegalArgumentException(
                        "Configuration value format.channels." + entry.getKey()
                                + " must be a string");
            }
            channels.put(entry.getKey(), format);
        }
        config.setFormats(channels);

        config.setDebug(requireBoolean(data, "debug", "debug"));
        
        return config;
    }

    private static Map<String, Object> requireMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException(
                    "Configuration value " + key + " must be a mapping");
        }
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (!(entry.getKey() instanceof String stringKey)) {
                throw new IllegalArgumentException(
                        "Configuration mapping " + key + " contains a non-string key");
            }
            result.put(stringKey, entry.getValue());
        }
        return result;
    }

    private static String requireString(Map<String, Object> parent, String key, String path) {
        Object value = parent.get(key);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " must be a string");
        }
        return stringValue;
    }

    private static int requireInt(Map<String, Object> parent, String key, String path) {
        Object value = parent.get(key);
        if (!(value instanceof Number numberValue)
                || !Double.isFinite(numberValue.doubleValue())
                || numberValue.doubleValue() != Math.rint(numberValue.doubleValue())
                || numberValue.doubleValue() < Integer.MIN_VALUE
                || numberValue.doubleValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " must be an integer");
        }
        return numberValue.intValue();
    }

    private static int requirePositiveInt(Map<String, Object> parent, String key, String path) {
        int value = requireInt(parent, key, path);
        if (value <= 0) {
            throw new IllegalArgumentException("Configuration value " + path + " must be greater than 0");
        }
        return value;
    }

    private static int requirePort(Map<String, Object> parent, String key, String path) {
        int value = requireInt(parent, key, path);
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " must be between 1 and 65535");
        }
        return value;
    }

    private static String requireNonBlankString(
            Map<String, Object> parent, String key, String path) {
        String value = requireString(parent, key, path);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Configuration value " + path + " must not be blank");
        }
        return value;
    }

    private static boolean requireBoolean(Map<String, Object> parent, String key, String path) {
        Object value = parent.get(key);
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(
                    "Configuration value " + path + " must be a boolean");
        }
        return booleanValue;
    }

}
