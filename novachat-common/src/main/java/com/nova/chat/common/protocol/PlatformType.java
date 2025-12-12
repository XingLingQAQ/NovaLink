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
    
    /** MultiPaper server */
    MULTIPAPER(12),
    
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

    public static PlatformType fromId(int id) {
        for (PlatformType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown platform type ID: " + id);
    }
}
