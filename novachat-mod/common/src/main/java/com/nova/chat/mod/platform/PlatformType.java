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

    /**
     * Maps this mod-loader platform type onto the shared protocol
     * {@code com.nova.chat.common.protocol.PlatformType} used in the backend
     * handshake and packet extras.
     *
     * @return the corresponding common protocol platform type
     */
    public com.nova.chat.common.protocol.PlatformType toCommon() {
        switch (this) {
            case FABRIC:
                return com.nova.chat.common.protocol.PlatformType.FABRIC;
            case NEOFORGE:
                return com.nova.chat.common.protocol.PlatformType.NEOFORGE;
            case QUILT:
                return com.nova.chat.common.protocol.PlatformType.QUILT;
            case FORGE:
                return com.nova.chat.common.protocol.PlatformType.FORGE;
            default:
                return com.nova.chat.common.protocol.PlatformType.FABRIC;
        }
    }
}
