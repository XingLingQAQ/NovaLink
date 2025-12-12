package com.nova.chat.mod.forge.version;

import com.nova.chat.mod.version.VersionDetector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Version adapter for Forge 1.20 through 1.21.x.
 * These are the latest Forge versions with modern APIs.
 * 
 * Key characteristics:
 * - Uses modern Forge event system (MinecraftForge.EVENT_BUS)
 * - Modern registry system with ResourceLocations
 * - Component API for text handling
 * - Data components for item data (1.20.5+)
 * - Full chat signing support
 * 
 * Requirements: 7.1, 7.3
 */
public class Forge1_20_1_21Adapter implements ForgeVersionAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(Forge1_20_1_21Adapter.class);
    
    private static final String SUPPORTED_RANGE = "1.20-1.21.x";
    private String minecraftVersion = "1.20.4";
    
    public Forge1_20_1_21Adapter() {
        LOGGER.info("Forge1_20_1_21Adapter initialized for Forge {}", SUPPORTED_RANGE);
    }
    
    @Override
    public void setMinecraftVersion(String version) {
        this.minecraftVersion = version;
    }
    
    @Override
    public String getMinecraftVersion() {
        return minecraftVersion;
    }
    
    @Override
    public String getSupportedVersionRange() {
        return SUPPORTED_RANGE;
    }
    
    @Override
    public void sendChatMessage(Object player, String message) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            LOGGER.warn("Invalid player object type: {}", player.getClass().getName());
            return;
        }
        
        Component component = createComponentFromText(message);
        serverPlayer.sendSystemMessage(component);
    }
    
    @Override
    public void sendSystemMessage(Object player, String message) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            LOGGER.warn("Invalid player object type: {}", player.getClass().getName());
            return;
        }
        
        Component component = createComponentFromText(message);
        serverPlayer.sendSystemMessage(component);
    }
    
    @Override
    public void broadcastMessage(Object server, String message) {
        if (!(server instanceof MinecraftServer minecraftServer)) {
            LOGGER.warn("Invalid server object type: {}", server.getClass().getName());
            return;
        }
        
        Component component = createComponentFromText(message);
        minecraftServer.getPlayerList().broadcastSystemMessage(component, false);
    }
    
    @Override
    public String getPlayerDimension(Object player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return null;
        }
        return serverPlayer.level().dimension().location().toString();
    }
    
    @Override
    public String getPlayerDisplayName(Object player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return null;
        }
        return serverPlayer.getDisplayName().getString();
    }
    
    @Override
    public UUID getPlayerUUID(Object player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return null;
        }
        return serverPlayer.getUUID();
    }
    
    @Override
    public boolean supportsVersion(String version) {
        int[] parsed = VersionDetector.parseVersion(version);
        int major = parsed[0];
        int minor = parsed[1];
        
        // Supports 1.20.x through 1.21.x
        return major == 1 && (minor == 20 || minor == 21);
    }
    
    @Override
    public Object createTextComponent(String text) {
        return createComponentFromText(text);
    }
    
    @Override
    public String parseColorCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace("&", "§");
    }
    
    private Component createComponentFromText(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        String converted = parseColorCodes(text);
        return Component.literal(converted);
    }
    
    @Override
    public String getForgeApiVersion() {
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        int minor = parsed[1];
        int patch = parsed[2];
        
        if (minor == 21) {
            return "Forge API 1.21.x (Latest, data components)";
        } else if (patch >= 5) {
            return "Forge API 1.20.5-1.20.6 (Data components)";
        } else {
            return "Forge API 1.20.0-1.20.4 (NBT-based items)";
        }
    }
    
    @Override
    public boolean usesLegacyEvents() {
        return false; // Modern MinecraftForge.EVENT_BUS
    }
    
    @Override
    public boolean usesModernRegistries() {
        return true; // ResourceLocation-based registries
    }
    
    @Override
    public boolean supportsChatSigning() {
        return true; // All 1.20+ versions support chat signing
    }
    
    @Override
    public String getChatTypeDescription() {
        return "Forge 1.20-1.21 chat handling: Uses Component API with sendSystemMessage";
    }
    
    @Override
    public boolean usesFlattening() {
        return true; // All 1.13+ versions use flattening
    }
    
    @Override
    public boolean usesDataComponents() {
        // Data components were introduced in 1.20.5
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        int minor = parsed[1];
        int patch = parsed[2];
        return minor >= 21 || (minor == 20 && patch >= 5);
    }
    
    /**
     * Gets the specific Forge version range for API compatibility.
     * @return the specific version range
     */
    public String getSpecificVersionRange() {
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        int minor = parsed[1];
        int patch = parsed[2];
        
        if (minor == 21) {
            return "1.21.x";
        } else if (patch >= 5) {
            return "1.20.5-1.20.6";
        } else {
            return "1.20.0-1.20.4";
        }
    }
    
    /**
     * Checks if the current version is 1.20.0-1.20.4.
     * These versions share similar Forge API before data components.
     * @return true if in 1.20.0-1.20.4 range
     */
    public boolean is1_20_0_to_1_20_4() {
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        return parsed[1] == 20 && parsed[2] <= 4;
    }
    
    /**
     * Checks if the current version is 1.20.5-1.20.6.
     * These versions have data components.
     * @return true if in 1.20.5-1.20.6 range
     */
    public boolean is1_20_5_to_1_20_6() {
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        return parsed[1] == 20 && parsed[2] >= 5;
    }
    
    /**
     * Checks if the current version is 1.21.x.
     * @return true if 1.21.x
     */
    public boolean is1_21() {
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        return parsed[1] == 21;
    }
}
