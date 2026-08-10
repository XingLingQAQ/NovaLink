package com.nova.chat.mod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **Feature: novachat-platform-expansion, Property 12: Mod Configuration Parsing Round-Trip**
 * 
 * Property: For any valid mod configuration, serializing to JSON and parsing back 
 * should produce an equivalent configuration object.
 * 
 * **Validates: Requirements 6.1**
 */
class ModConfigParsingRoundTripPropertyTest {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    @Property
    @Report(Reporting.GENERATED)
    void configurationRoundTripPreservesBackendSettings(
            @ForAll @StringLength(min = 1, max = 100) String host,
            @ForAll @IntRange(min = 1, max = 65535) int port,
            @ForAll @StringLength(min = 1, max = 50) String username,
            @ForAll @StringLength(min = 1, max = 100) String password,
            @ForAll @IntRange(min = 1, max = 300) int reconnectDelay) {
        
        // Create original config
        ModConfig original = new ModConfig();
        ModConfig.BackendConfig backend = new ModConfig.BackendConfig();
        backend.setHost(host);
        backend.setPort(port);
        backend.setUsername(username);
        backend.setPassword(password);
        backend.setReconnectDelay(reconnectDelay);
        original.setBackend(backend);
        
        // Serialize and deserialize
        ModConfig restored = roundTripConfig(original);
        
        // Verify backend settings are preserved
        assertThat(restored.getBackend().getHost()).isEqualTo(host);
        assertThat(restored.getBackend().getPort()).isEqualTo(port);
        assertThat(restored.getBackend().getUsername()).isEqualTo(username);
        assertThat(restored.getBackend().getPassword()).isEqualTo(password);
        assertThat(restored.getBackend().getReconnectDelay()).isEqualTo(reconnectDelay);
    }
    
    @Property
    @Report(Reporting.GENERATED)
    void configurationRoundTripPreservesChatSettings(
            @ForAll boolean replaceVanilla,
            @ForAll @StringLength(min = 1, max = 50) String defaultChannel) {

        // Create original config
        ModConfig original = new ModConfig();
        ModConfig.ChatConfig chat = new ModConfig.ChatConfig();
        chat.setReplaceVanilla(replaceVanilla);
        chat.setDefaultChannel(defaultChannel);
        original.setChat(chat);

        // Serialize and deserialize
        ModConfig restored = roundTripConfig(original);

        // Verify chat settings are preserved
        assertThat(restored.getChat().isReplaceVanilla()).isEqualTo(replaceVanilla);
        assertThat(restored.getChat().getDefaultChannel()).isEqualTo(defaultChannel);
    }

    @Property
    @Report(Reporting.GENERATED)
    void configurationRoundTripPreservesLocale(
            @ForAll("validLocale") String locale) {

        // Create original config with the given locale
        ModConfig original = new ModConfig();
        original.getChat().setLocale(locale);

        // Serialize and deserialize
        ModConfig restored = roundTripConfig(original);

        // Verify locale is preserved
        assertThat(restored.getChat().getLocale()).isEqualTo(locale);
    }

    @Provide
    Arbitrary<String> validLocale() {
        return Arbitraries.of("zh_CN", "en_US", "ja_JP", "de_DE", "fr_FR", "ko_KR");
    }
    
    @Property
    @Report(Reporting.GENERATED)
    void configurationRoundTripPreservesFormatTemplates(
            @ForAll @StringLength(min = 1, max = 50) String channelName,
            @ForAll @StringLength(min = 1, max = 200) String template) {
        
        // Create original config
        ModConfig original = new ModConfig();
        Map<String, String> formats = new HashMap<>();
        formats.put(channelName, template);
        original.setFormats(formats);
        
        // Serialize and deserialize
        ModConfig restored = roundTripConfig(original);
        
        // Verify format templates are preserved
        assertThat(restored.getFormats()).containsEntry(channelName, template);
    }
    
    @Property
    @Report(Reporting.GENERATED)
    void configurationRoundTripPreservesDebugFlag(
            @ForAll boolean debug) {
        
        // Create original config
        ModConfig original = new ModConfig();
        original.setDebug(debug);
        
        // Serialize and deserialize
        ModConfig restored = roundTripConfig(original);
        
        // Verify debug flag is preserved
        assertThat(restored.isDebug()).isEqualTo(debug);
    }
    
    @Property
    @Report(Reporting.GENERATED)
    void configurationRoundTripPreservesCompleteConfiguration(
            @ForAll @StringLength(min = 1, max = 100) String host,
            @ForAll @IntRange(min = 1, max = 65535) int port,
            @ForAll @StringLength(min = 1, max = 50) String username,
            @ForAll @StringLength(min = 1, max = 100) String password,
            @ForAll @IntRange(min = 1, max = 300) int reconnectDelay,
            @ForAll boolean replaceVanilla,
            @ForAll @StringLength(min = 1, max = 50) String defaultChannel,
            @ForAll boolean debug) {
        
        // Create complete original config
        ModConfig original = new ModConfig();
        
        ModConfig.BackendConfig backend = new ModConfig.BackendConfig();
        backend.setHost(host);
        backend.setPort(port);
        backend.setUsername(username);
        backend.setPassword(password);
        backend.setReconnectDelay(reconnectDelay);
        original.setBackend(backend);
        
        ModConfig.ChatConfig chat = new ModConfig.ChatConfig();
        chat.setReplaceVanilla(replaceVanilla);
        chat.setDefaultChannel(defaultChannel);
        original.setChat(chat);
        
        original.setDebug(debug);
        
        // Serialize and deserialize
        ModConfig restored = roundTripConfig(original);
        
        // Verify all settings are preserved
        assertThat(restored.getBackend().getHost()).isEqualTo(host);
        assertThat(restored.getBackend().getPort()).isEqualTo(port);
        assertThat(restored.getBackend().getUsername()).isEqualTo(username);
        assertThat(restored.getBackend().getPassword()).isEqualTo(password);
        assertThat(restored.getBackend().getReconnectDelay()).isEqualTo(reconnectDelay);
        assertThat(restored.getChat().isReplaceVanilla()).isEqualTo(replaceVanilla);
        assertThat(restored.getChat().getDefaultChannel()).isEqualTo(defaultChannel);
        assertThat(restored.isDebug()).isEqualTo(debug);
    }
    
    /**
     * Helper method to perform round-trip serialization/deserialization
     */
    @SuppressWarnings("unchecked")
    private ModConfig roundTripConfig(ModConfig original) {
        // Serialize to JSON
        Map<String, Object> data = configToMap(original);
        String json = GSON.toJson(data);
        
        // Deserialize from JSON
        Map<String, Object> parsed = GSON.fromJson(json, Map.class);
        return parseMapToConfig(parsed);
    }
    
    /**
     * Convert ModConfig to Map for JSON serialization
     */
    private Map<String, Object> configToMap(ModConfig config) {
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
        data.put("chat", chat);
        
        // Format
        Map<String, Object> format = new HashMap<>();
        format.put("channels", config.getFormats());
        data.put("format", format);
        
        data.put("debug", config.isDebug());
        
        return data;
    }
    
    /**
     * Parse Map (from JSON) to ModConfig
     */
    @SuppressWarnings("unchecked")
    private ModConfig parseMapToConfig(Map<String, Object> data) {
        if (data == null) {
            return new ModConfig();
        }
        
        ModConfig config = new ModConfig();
        
        try {
            if (data.containsKey("backend")) {
                Object backendObj = data.get("backend");
                if (backendObj instanceof Map) {
                    Map<String, Object> backendData = (Map<String, Object>) backendObj;
                    ModConfig.BackendConfig backend = new ModConfig.BackendConfig();
                    Object hostObj = backendData.get("host");
                    backend.setHost(hostObj != null ? String.valueOf(hostObj) : "127.0.0.1");
                    Object portObj = backendData.get("port");
                    backend.setPort(portObj instanceof Number ? ((Number) portObj).intValue() : 8888);
                    Object usernameObj = backendData.get("username");
                    backend.setUsername(usernameObj != null ? String.valueOf(usernameObj) : "ModServer");
                    Object passwordObj = backendData.get("password");
                    backend.setPassword(passwordObj != null ? String.valueOf(passwordObj) : "password");
                    Object reconnectObj = backendData.get("reconnect-delay");
                    backend.setReconnectDelay(reconnectObj instanceof Number ? ((Number) reconnectObj).intValue() : 5);
                    config.setBackend(backend);
                }
            }
            
            if (data.containsKey("chat")) {
                Object chatObj = data.get("chat");
                if (chatObj instanceof Map) {
                    Map<String, Object> chatData = (Map<String, Object>) chatObj;
                    ModConfig.ChatConfig chat = new ModConfig.ChatConfig();
                    Object replaceObj = chatData.get("replace_vanilla");
                    chat.setReplaceVanilla(replaceObj instanceof Boolean ? (Boolean) replaceObj : false);
                    Object channelObj = chatData.get("default_channel");
                    chat.setDefaultChannel(channelObj != null ? String.valueOf(channelObj) : "local");
                    Object localeObj = chatData.get("locale");
                    chat.setLocale(localeObj != null ? String.valueOf(localeObj) : "zh_CN");
                    config.setChat(chat);
                }
            }
            
            if (data.containsKey("format")) {
                Object formatObj = data.get("format");
                if (formatObj instanceof Map) {
                    Map<String, Object> formatData = (Map<String, Object>) formatObj;
                    if (formatData.containsKey("channels")) {
                        Object channelsObj = formatData.get("channels");
                        if (channelsObj instanceof Map) {
                            Map<String, String> channels = new HashMap<>();
                            for (Map.Entry<?, ?> entry : ((Map<?, ?>) channelsObj).entrySet()) {
                                channels.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                            }
                            config.setFormats(channels);
                        }
                    }
                }
            }
            
            Object debugObj = data.get("debug");
            if (debugObj instanceof Boolean) {
                config.setDebug((Boolean) debugObj);
            }
        } catch (Exception e) {
            // If parsing fails, return default config
            return new ModConfig();
        }
        
        return config;
    }
}
