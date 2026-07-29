package com.nova.chat.mod.neoforge.version;

import com.nova.chat.mod.version.VersionDetector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Version adapter for NeoForge 1.21.x and calendar-line 26.x+.
 * This is the latest NeoForge version with the most recent API changes.
 *
 * Key characteristics:
 * - Uses Data Components for item data
 * - Latest NeoForge event system
 * - Updated registry system
 * - Potential future API changes
 *
 * Requirements: 5.1, 5.3
 */
public class NeoForge1_21Adapter implements NeoForgeVersionAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForge1_21Adapter.class);

    private static final String SUPPORTED_RANGE = "1.21.x/26.x";
    private String minecraftVersion = "1.21.11";
    
    public NeoForge1_21Adapter() {
        LOGGER.info("NeoForge1_21Adapter initialized for NeoForge {}", SUPPORTED_RANGE);
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
        
        // Supports all 1.21.x versions
        return major == 1 && minor == 21;
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
    public String getNeoForgeApiVersion() {
        return "NeoForge API 1.21.x (Latest)";
    }
    
    @Override
    public boolean usesDataComponents() {
        return true; // 1.21.x uses data components
    }
    
    @Override
    public boolean supportsChatSigning() {
        return true;
    }
    
    @Override
    public String getChatTypeDescription() {
        return "NeoForge 1.21.x chat handling: Uses latest Component API with data components";
    }
}
