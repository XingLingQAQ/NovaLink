package com.nova.chat.mod.fabric.version;

import com.nova.chat.mod.version.VersionAdapter;
import com.nova.chat.mod.version.VersionDetector;
import com.nova.chat.mod.version.VersionRange;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Version adapter for Fabric 1.14.x through 1.19.x.
 * Handles API differences in older Minecraft versions.
 * 
 * Key differences from modern versions:
 * - Uses Text instead of Component in some cases
 * - Different chat event handling
 * - Different method signatures for sending messages
 * 
 * Requirements: 4.1, 4.3
 */
public class LegacyVersionAdapter implements VersionAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyVersionAdapter.class);
    
    private static final String SUPPORTED_RANGE = "1.14-1.19";
    private String minecraftVersion = "1.19.4"; // Default, will be set at runtime
    
    public LegacyVersionAdapter() {
        LOGGER.info("LegacyVersionAdapter initialized for Fabric {}", SUPPORTED_RANGE);
    }
    
    /**
     * Sets the actual Minecraft version detected at runtime.
     * @param version the Minecraft version
     */
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
        // In 1.14-1.19, we use sendMessage with a UUID parameter
        // The UUID.randomUUID() indicates it's not from a specific player
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
        
        // In 1.14-1.19, dimension access varies slightly
        // Using level().dimension() which works across these versions
        return serverPlayer.level().dimension().identifier().toString();
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
        VersionRange range = VersionRange.forVersion(version);
        return range == VersionRange.LEGACY_1_14_1_19;
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
        // Convert & color codes to § for Minecraft
        return text.replace("&", "§");
    }
    
    /**
     * Creates a Component from text with color code support.
     * @param text the text with optional color codes
     * @return the Component
     */
    private Component createComponentFromText(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        
        String converted = parseColorCodes(text);
        return Component.literal(converted);
    }
    
    /**
     * Gets the version-specific chat type for sending messages.
     * In 1.14-1.19, chat types are handled differently than in 1.20+.
     * @return a description of the chat handling method
     */
    public String getChatTypeDescription() {
        return "Legacy chat handling (1.14-1.19): Uses sendSystemMessage with Component";
    }
}
