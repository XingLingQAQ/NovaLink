package com.nova.chat.common.protocol;

/**
 * Enum representing the platform type of a NovaChat client.
 */
public enum PlatformType {
    /** Bukkit/Spigot/Paper server */
    BUKKIT(0),
    
    /** Velocity proxy */
    VELOCITY(1),
    
    /** BungeeCord proxy */
    BUNGEECORD(2),
    
    /** Nukkit (Bedrock) server */
    NUKKIT(3),
    
    /** LeviLamina (BDS) server */
    LEVILAMINA(4),
    
    /** Fabric mod */
    FABRIC(5),
    
    /** NeoForge mod */
    NEOFORGE(6),
    
    /** Quilt mod */
    QUILT(7),
    
    /** Forge mod */
    FORGE(8),
    
    /** PocketMine-MP (Bedrock) server */
    POCKETMINE(9),
    
    /** Endstone (Bedrock) server */
    ENDSTONE(10),
    
    /** PowerNukkitX (Bedrock) server */
    POWERNUKKITX(11),

    /** Folia server */
    FOLIA(13),
    
    /** Sponge server */
    SPONGE(14);

    private final int id;

    PlatformType(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /**
     * Resolves a platform type by its numeric wire ID.
     *
     * <p>The ID is first normalized with {@code id & 0xFF} so that signed-byte
     * wire values (e.g. {@code -1} becoming {@code 255}) decode correctly for
     * platform IDs above 127.
     *
     * @param id the wire ID (may be a signed-byte value)
     * @return the matching platform type
     * @throws IllegalArgumentException if no platform type matches the normalized ID
     */
    public static PlatformType fromId(int id) {
        // Normalize signed-byte wire values (e.g. -1 -> 255) for future platform IDs
        int normalized = id & 0xFF;
        for (PlatformType type : values()) {
            if (type.id == normalized) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown platform type ID: " + id);
    }

    /**
     * Returns true if the given id maps to a known platform.
     */
    public static boolean isKnown(int id) {
        int normalized = id & 0xFF;
        for (PlatformType type : values()) {
            if (type.id == normalized) {
                return true;
            }
        }
        return false;
    }
}
