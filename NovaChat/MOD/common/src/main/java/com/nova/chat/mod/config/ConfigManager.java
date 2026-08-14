package com.nova.chat.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages loading and saving of mod configuration
 */
public class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigManager.class);
    private static final String CONFIG_FILE = "config/novachat.yml";
    private static final Yaml YAML = new Yaml();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
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
            if (Files.exists(configPath)) {
                LOGGER.info("Loading configuration from {}", configPath);
                try (FileInputStream fis = new FileInputStream(configPath.toFile())) {
                    Map<String, Object> data = YAML.load(fis);
                    config = parseYamlToConfig(data);
                    
                    if (!config.validate()) {
                        LOGGER.warn("Configuration validation failed, using defaults");
                        config = createDefaultConfig();
                    }
                }
            } else {
                LOGGER.info("Configuration file not found, creating default");
                config = createDefaultConfig();
                saveConfig();
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load configuration", e);
            config = createDefaultConfig();
        }
        
        return config;
    }
    
    /**
     * Save configuration to file
     */
    public void saveConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            
            Map<String, Object> data = configToYaml(config);
            try (FileWriter fw = new FileWriter(configPath.toFile())) {
                YAML.dump(data, fw);
                LOGGER.info("Configuration saved to {}", configPath);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save configuration", e);
        }
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
    
    /**
     * Set the configuration
     * @param config the new configuration
     */
    public void setConfig(ModConfig config) {
        this.config = config;
    }
    
    /**
     * Create default configuration
     * @return the default configuration
     */
    private ModConfig createDefaultConfig() {
        ModConfig config = new ModConfig();
        
        // Backend config
        ModConfig.BackendConfig backend = new ModConfig.BackendConfig();
        backend.setHost("127.0.0.1");
        backend.setPort(8888);
        backend.setUsername("ModServer");
        backend.setPassword("your-password");
        backend.setReconnectDelay(5);
        config.setBackend(backend);
        
        // Chat config
        ModConfig.ChatConfig chat = new ModConfig.ChatConfig();
        chat.setReplaceVanilla(false);
        chat.setDefaultChannel("local");
        chat.setLocale("zh_CN");
        config.setChat(chat);
        
        // Format templates
        Map<String, String> formats = new HashMap<>();
        formats.put("global", "&c[全服] &7{player}&f: {message}");
        formats.put("local", "&e[本地] &7{player}&f: {message}");
        config.setFormats(formats);
        
        config.setDebug(false);
        
        return config;
    }
    
    /**
     * Parse YAML data to ModConfig
     * @param data the YAML data
     * @return the parsed configuration
     */
    @SuppressWarnings("unchecked")
    private ModConfig parseYamlToConfig(Map<String, Object> data) {
        ModConfig config = new ModConfig();
        
        if (data.containsKey("backend")) {
            Map<String, Object> backendData = (Map<String, Object>) data.get("backend");
            ModConfig.BackendConfig backend = new ModConfig.BackendConfig();
            backend.setHost((String) backendData.getOrDefault("host", "127.0.0.1"));
            backend.setPort(((Number) backendData.getOrDefault("port", 8888)).intValue());
            backend.setUsername((String) backendData.getOrDefault("username", "ModServer"));
            backend.setPassword((String) backendData.getOrDefault("password", "password"));
            backend.setReconnectDelay(((Number) backendData.getOrDefault("reconnect-delay", 5)).intValue());
            config.setBackend(backend);
        }
        
        if (data.containsKey("chat")) {
            Map<String, Object> chatData = (Map<String, Object>) data.get("chat");
            ModConfig.ChatConfig chat = new ModConfig.ChatConfig();
            chat.setReplaceVanilla((Boolean) chatData.getOrDefault("replace_vanilla", false));
            chat.setDefaultChannel((String) chatData.getOrDefault("default_channel", "local"));
            chat.setLocale((String) chatData.getOrDefault("locale", "zh_CN"));
            // Channel-prefix routing map (e.g. "!": global); empty = disabled.
            Object prefixData = chatData.get("channel-prefixes");
            if (prefixData instanceof Map) {
                Map<String, String> prefixes = new HashMap<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) prefixData).entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        String prefix = String.valueOf(entry.getKey());
                        String channelId = String.valueOf(entry.getValue());
                        if (!prefix.isEmpty() && !channelId.isBlank()) {
                            prefixes.put(prefix, channelId);
                        }
                    }
                }
                chat.setChannelPrefixes(prefixes);
            }
            config.setChat(chat);
        }
        
        if (data.containsKey("format")) {
            Map<String, Object> formatData = (Map<String, Object>) data.get("format");
            if (formatData.containsKey("channels")) {
                Map<String, String> channels = (Map<String, String>) formatData.get("channels");
                config.setFormats(new HashMap<>(channels));
            }
        }
        
        config.setDebug((Boolean) data.getOrDefault("debug", false));
        
        return config;
    }
    
    /**
     * Convert ModConfig to YAML data
     * @param config the configuration
     * @return the YAML data
     */
    private Map<String, Object> configToYaml(ModConfig config) {
        Map<String, Object> data = new HashMap<>();
        
        // Backend
        Map<String, Object> backend = new HashMap<>();
        backend.put("host", config.getBackend().getHost());
        backend.put("port", config.getBackend().getPort());
        backend.put("username", config.getBackend().getUsername());
        backend.put("password", config.getBackend().getPassword());
        backend.put("reconnect-delay", config.getBackend().getReconnectDelay());
        data.put("backend", backend);
        
        // Chat
        Map<String, Object> chat = new HashMap<>();
        chat.put("replace_vanilla", config.getChat().isReplaceVanilla());
        chat.put("default_channel", config.getChat().getDefaultChannel());
        chat.put("locale", config.getChat().getLocale());
        chat.put("channel-prefixes", config.getChat().getChannelPrefixes());
        data.put("chat", chat);
        
        // Format
        Map<String, Object> format = new HashMap<>();
        format.put("channels", config.getFormats());
        data.put("format", format);
        
        data.put("debug", config.isDebug());
        
        return data;
    }
}
