package com.nova.chat.mod.forge.version;

import com.nova.chat.mod.version.VersionAdapter;

/**
 * Base interface for Forge-specific version adapters.
 * Extends the common VersionAdapter with Forge-specific functionality.
 * 
 * Forge supports a wide range of Minecraft versions from 1.7.10 to 1.21.x,
 * requiring different adapters for different API generations.
 * 
 * Requirements: 7.1, 7.3
 */
public interface ForgeVersionAdapter extends VersionAdapter {
    
    /**
     * Gets the Forge-specific API version description.
     * @return the API version description
     */
    String getForgeApiVersion();
    
    /**
     * Checks if the current version uses the legacy Forge event system.
     * Legacy events were used in 1.7-1.12.
     * @return true if legacy events are used
     */
    boolean usesLegacyEvents();
    
    /**
     * Checks if the current version uses the modern registry system.
     * Modern registries were introduced in 1.13+.
     * @return true if modern registries are used
     */
    boolean usesModernRegistries();
    
    /**
     * Checks if the current version supports chat signing.
     * Chat signing was introduced in 1.19.1.
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
    
    /**
     * Checks if the current version uses the flattening (1.13+).
     * The flattening changed block/item IDs significantly.
     * @return true if the version uses flattening
     */
    boolean usesFlattening();
    
    /**
     * Checks if the current version uses data components (1.20.5+).
     * Data components replaced NBT for item data.
     * @return true if data components are used
     */
    boolean usesDataComponents();
}
