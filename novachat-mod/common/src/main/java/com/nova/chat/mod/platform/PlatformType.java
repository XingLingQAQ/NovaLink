package com.nova.chat.mod.platform;

/**
 * Enum representing different Minecraft mod loaders
 */
public enum PlatformType {
    FABRIC("Fabric"),
    NEOFORGE("NeoForge"),
    QUILT("Quilt"),
    FORGE("Forge");
    
    private final String displayName;
    
    PlatformType(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}
