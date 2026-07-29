package com.nova.chat.mod.quilt.version;

import com.nova.chat.mod.version.VersionAdapter;
import com.nova.chat.mod.version.VersionRange;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Version adapter for Quilt 1.20.x through 1.21.x and calendar-line 26.x+.
 * Uses modern Component API and chat event systems via Quilted Fabric API.
 *
 * Key features in modern versions:
 * - Component API for text handling
 * - Modern chat event system with signed messages
 * - Updated method signatures for message sending
 *
 * Requirements: 6.1, 6.3
 */
public class QuiltModernVersionAdapter implements VersionAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(QuiltModernVersionAdapter.class);

    private static final String SUPPORTED_RANGE = "1.20-1.21/26.x";
    private String minecraftVersion = "1.21.11"; // Default, will be set at runtime
    
    public QuiltModernVersionAdapter() {
        LOGGER.info("QuiltModernVersionAdapter initialized for Quilt {}", SUPPORTED_RANGE);
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
        // In 1.20+, sendSystemMessage is the standard way to send messages
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
        // In 1.20+, broadcastSystemMessage takes a Component and a boolean for overlay
        minecraftServer.getPlayerList().broadcastSystemMessage(component, false);
    }
    
    @Override
    public String getPlayerDimension(Object player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return null;
        }
        
        // In 1.20+, level().dimension() returns the dimension key
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
        VersionRange range = VersionRange.forVersion(version);
        return range == VersionRange.MODERN_1_20_PLUS;
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
     * Uses the modern Component API available in 1.20+.
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
     * In 1.20+, chat types are registered and use the modern system.
     * @return a description of the chat handling method
     */
    public String getChatTypeDescription() {
        return "Modern chat handling (1.20-1.21): Uses Component API with sendSystemMessage";
    }
    
    /**
     * Checks if the current version supports the new chat signing system.
     * Chat signing was introduced in 1.19.1 and refined in 1.20.
     * @return true if chat signing is supported
     */
    public boolean supportsChatSigning() {
        return true; // All 1.20+ versions support chat signing
    }
    
    /**
     * Checks if the current version supports Quilted Fabric API features.
     * @return true if Quilted Fabric API is available
     */
    public boolean supportsQuiltedFabricApi() {
        return true; // All Quilt versions support Quilted Fabric API
    }
}
