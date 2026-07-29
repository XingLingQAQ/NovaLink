package com.nova.chat.mod.forge.version;

import com.nova.chat.mod.version.VersionDetector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Version adapter for Forge 1.13 through 1.19.4.
 * These versions use post-flattening APIs but pre-data-component item system.
 * 
 * Key characteristics:
 * - Uses modern Forge event system (MinecraftForge.EVENT_BUS)
 * - Post-flattening block/item IDs (string-based ResourceLocations)
 * - Uses Component API (ITextComponent in earlier versions)
 * - NBT-based item data
 * - Chat signing introduced in 1.19.1
 * 
 * Requirements: 7.1, 7.3
 */
public class Forge1_13_1_19Adapter implements ForgeVersionAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(Forge1_13_1_19Adapter.class);
    
    private static final String SUPPORTED_RANGE = "1.13-1.19.4";
    private String minecraftVersion = "1.19.4";
    
    public Forge1_13_1_19Adapter() {
        LOGGER.info("Forge1_13_1_19Adapter initialized for Forge {}", SUPPORTED_RANGE);
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
        // In 1.13-1.19, sendSystemMessage is available
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
        // In 1.13-1.19, dimension is accessed via level().dimension()
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
        
        // Supports 1.13.x through 1.19.x
        return major == 1 && minor >= 13 && minor <= 19;
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
        return "Forge API 1.13-1.19.4 (Post-flattening, NBT-based items)";
    }
    
    @Override
    public boolean usesLegacyEvents() {
        return false; // 1.13+ uses modern MinecraftForge.EVENT_BUS
    }
    
    @Override
    public boolean usesModernRegistries() {
        return true; // Post-flattening uses ResourceLocation-based registries
    }
    
    @Override
    public boolean supportsChatSigning() {
        // Chat signing was introduced in 1.19.1
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        int minor = parsed[1];
        int patch = parsed[2];
        return minor == 19 && patch >= 1;
    }
    
    @Override
    public String getChatTypeDescription() {
        return "Forge 1.13-1.19 chat handling: Uses Component API with sendSystemMessage";
    }
    
    @Override
    public boolean usesFlattening() {
        return true; // Flattening was introduced in 1.13
    }
    
    @Override
    public boolean usesDataComponents() {
        return false; // Data components were introduced in 1.20.5
    }
    
    /**
     * Gets the specific Forge version range for API compatibility.
     * Different 1.13-1.19 versions have slightly different APIs.
     * @return the specific version range
     */
    public String getSpecificVersionRange() {
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        int minor = parsed[1];
        
        if (minor <= 15) {
            return "1.13-1.15.2";
        } else if (minor <= 17) {
            return "1.16-1.17.1";
        } else {
            return "1.18-1.19.4";
        }
    }
}
