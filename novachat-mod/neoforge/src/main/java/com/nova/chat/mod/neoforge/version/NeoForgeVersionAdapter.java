package com.nova.chat.mod.neoforge.version;

import com.nova.chat.mod.version.VersionAdapter;

/**
 * Base interface for NeoForge-specific version adapters.
 * Extends the common VersionAdapter with NeoForge-specific functionality.
 * 
 * Requirements: 5.1, 5.3
 */
public interface NeoForgeVersionAdapter extends VersionAdapter {
    
    /**
     * Gets the NeoForge-specific API version description.
     * @return the API version description
     */
    String getNeoForgeApiVersion();
    
    /**
     * Checks if the current version uses the new data component system.
     * Data components were introduced in 1.20.5.
     * @return true if data components are used
     */
    boolean usesDataComponents();
    
    /**
     * Checks if the current version supports chat signing.
     * @return true if chat signing is supported
     */
    boolean supportsChatSigning();
    
    /**
     * Gets the chat type description for this version.
     * @return the chat type description
     */
    String getChatTypeDescription();
    
    /**
     * Sets the Minecraft version for this adapter.
     * @param version the Minecraft version
     */
    void setMinecraftVersion(String version);
}
