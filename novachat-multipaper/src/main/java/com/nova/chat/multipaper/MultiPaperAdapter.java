package com.nova.chat.multipaper;

import com.nova.chat.multipaper.chat.PlayerChatState;
import com.nova.chat.multipaper.config.NovaChatConfig;

import java.util.UUID;

/**
 * Adapter for MultiPaper-specific functionality.
 * Detects MultiPaper environment and provides cross-instance state synchronization.
 * 
 * Requirements: 1.2
 */
public class MultiPaperAdapter {
    
    private final NovaChatMultiPaper plugin;
    private NovaChatConfig config;
    
    /** Whether running on MultiPaper */
    private boolean isMultiPaper;
    
    /** The instance ID for this server */
    private String instanceId;
    
    /** Whether cross-instance sync is enabled */
    private boolean syncEnabled;
    
    /**
     * Creates a new MultiPaperAdapter.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public MultiPaperAdapter(NovaChatMultiPaper plugin, NovaChatConfig config) {
        this.plugin = plugin;
        this.config = config;
        
        // Detect MultiPaper environment
        detectMultiPaper();
        
        // Load configuration
        loadConfig();
    }
    
    /**
     * Detects if running on MultiPaper.
     * Checks for MultiPaper-specific classes and methods.
     */
    private void detectMultiPaper() {
        try {
            // Try to load MultiPaper class
            Class.forName("puregero.multipaper.MultiPaper");
            isMultiPaper = true;
            plugin.debug("MultiPaper detected via class check");
        } catch (ClassNotFoundException e) {
            // Try alternative detection via server brand
            try {
                String serverBrand = plugin.getServer().getName();
                isMultiPaper = serverBrand != null && serverBrand.toLowerCase().contains("multipaper");
                if (isMultiPaper) {
                    plugin.debug("MultiPaper detected via server brand: " + serverBrand);
                }
            } catch (Exception ex) {
                isMultiPaper = false;
            }
        }
    }
    
    /**
     * Loads configuration settings.
     */
    private void loadConfig() {
        syncEnabled = config.isMultiPaperSyncEnabled();
        
        // Get instance ID from config or auto-detect
        String configInstanceId = config.getMultiPaperInstanceId();
        if (configInstanceId != null && !configInstanceId.isEmpty()) {
            instanceId = configInstanceId;
        } else {
            instanceId = detectInstanceId();
        }
    }
    
    /**
     * Detects the instance ID automatically.
     * Uses MultiPaper API if available, otherwise generates a unique ID.
     *
     * @return the detected instance ID
     */
    private String detectInstanceId() {
        if (isMultiPaper) {
            try {
                // Try to get instance ID from MultiPaper API
                Class<?> multiPaperClass = Class.forName("puregero.multipaper.MultiPaper");
                Object localServer = multiPaperClass.getMethod("getLocalServer").invoke(null);
                if (localServer != null) {
                    String name = (String) localServer.getClass().getMethod("getName").invoke(localServer);
                    if (name != null && !name.isEmpty()) {
                        plugin.debug("Got instance ID from MultiPaper API: " + name);
                        return name;
                    }
                }
            } catch (Exception e) {
                plugin.debug("Failed to get instance ID from MultiPaper API: " + e.getMessage());
            }
        }
        
        // Fallback: generate unique ID based on server properties
        String serverName = plugin.getServer().getName();
        int port = plugin.getServer().getPort();
        return serverName + "-" + port;
    }
    
    /**
     * Reloads the adapter configuration.
     *
     * @param config the new configuration
     */
    public void reload(NovaChatConfig config) {
        this.config = config;
        loadConfig();
    }
    
    /**
     * Checks if running on MultiPaper.
     *
     * @return true if running on MultiPaper
     */
    public boolean isMultiPaper() {
        return isMultiPaper;
    }
    
    /**
     * Gets the instance ID.
     *
     * @return the instance ID
     */
    public String getInstanceId() {
        return instanceId;
    }
    
    /**
     * Checks if cross-instance sync is enabled.
     *
     * @return true if sync is enabled
     */
    public boolean isSyncEnabled() {
        return syncEnabled && isMultiPaper;
    }
    
    /**
     * Synchronizes player state across MultiPaper instances.
     * This is called when a player's chat state changes.
     *
     * Requirements: 1.3
     *
     * @param playerId the player's UUID
     * @param state the player's chat state
     */
    public void syncPlayerState(UUID playerId, PlayerChatState state) {
        if (!isSyncEnabled()) {
            return;
        }
        
        try {
            // Use MultiPaper's external server data API for state sync
            Class<?> multiPaperClass = Class.forName("puregero.multipaper.MultiPaper");
            
            // Serialize state to string
            String stateData = serializeState(state);
            String key = "novachat:state:" + playerId.toString();
            
            // Store in MultiPaper's shared data
            // This uses MultiPaper's built-in cross-instance data sharing
            Object externalPlayer = multiPaperClass.getMethod("getExternalPlayer", UUID.class).invoke(null, playerId);
            if (externalPlayer != null) {
                externalPlayer.getClass().getMethod("setData", String.class, String.class)
                    .invoke(externalPlayer, key, stateData);
                plugin.debug("Synced player state for " + playerId + " to MultiPaper");
            }
        } catch (Exception e) {
            plugin.debug("Failed to sync player state: " + e.getMessage());
        }
    }
    
    /**
     * Retrieves player state from MultiPaper shared data.
     *
     * @param playerId the player's UUID
     * @return the player's chat state, or null if not found
     */
    public PlayerChatState getSharedPlayerState(UUID playerId) {
        if (!isSyncEnabled()) {
            return null;
        }
        
        try {
            Class<?> multiPaperClass = Class.forName("puregero.multipaper.MultiPaper");
            String key = "novachat:state:" + playerId.toString();
            
            Object externalPlayer = multiPaperClass.getMethod("getExternalPlayer", UUID.class).invoke(null, playerId);
            if (externalPlayer != null) {
                String stateData = (String) externalPlayer.getClass().getMethod("getData", String.class)
                    .invoke(externalPlayer, key);
                if (stateData != null && !stateData.isEmpty()) {
                    return deserializeState(playerId, stateData);
                }
            }
        } catch (Exception e) {
            plugin.debug("Failed to get shared player state: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Serializes a player chat state to a string.
     *
     * @param state the state to serialize
     * @return the serialized state
     */
    private String serializeState(PlayerChatState state) {
        // Simple format: channel|mode|modeOverridden
        return state.getActiveChannel() + "|" + 
               state.getChatMode().name() + "|" + 
               state.isModeOverridden();
    }
    
    /**
     * Deserializes a player chat state from a string.
     *
     * @param playerId the player's UUID
     * @param data the serialized state
     * @return the deserialized state
     */
    private PlayerChatState deserializeState(UUID playerId, String data) {
        try {
            String[] parts = data.split("\\|");
            if (parts.length >= 3) {
                String channel = parts[0];
                com.nova.chat.multipaper.chat.ChatMode mode = 
                    com.nova.chat.multipaper.chat.ChatMode.valueOf(parts[1]);
                boolean modeOverridden = Boolean.parseBoolean(parts[2]);
                
                PlayerChatState state = new PlayerChatState(playerId, channel, mode);
                state.setModeOverridden(modeOverridden);
                return state;
            }
        } catch (Exception e) {
            plugin.debug("Failed to deserialize player state: " + e.getMessage());
        }
        return null;
    }
}
