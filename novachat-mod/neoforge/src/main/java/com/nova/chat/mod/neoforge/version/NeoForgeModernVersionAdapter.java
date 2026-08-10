package com.nova.chat.mod.neoforge.version;

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
 * Version adapter for NeoForge 1.20.2 through 1.21.x and calendar-line 26.x+.
 * Uses modern Component API and NeoForge event systems.
 *
 * NeoForge was created as a fork of Forge starting from 1.20.2,
 * so this adapter only needs to handle modern versions.
 *
 * Key features in NeoForge versions:
 * - Component API for text handling
 * - Modern chat event system with signed messages
 * - NeoForge-specific event bus
 * - Updated method signatures for message sending
 *
 * Requirements: 5.1, 5.3
 */
public class NeoForgeModernVersionAdapter implements VersionAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgeModernVersionAdapter.class);

    private static final String SUPPORTED_RANGE = "1.20.2-1.21/26.x";
    private String minecraftVersion = "1.21.11"; // Default, will be set at runtime
    
    public NeoForgeModernVersionAdapter() {
        LOGGER.info("NeoForgeModernVersionAdapter initialized for NeoForge {}", SUPPORTED_RANGE);
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
        // In NeoForge 1.20.2+, sendSystemMessage is the standard way to send messages
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
        // In NeoForge 1.20.2+, broadcastSystemMessage takes a Component and a boolean for overlay
        minecraftServer.getPlayerList().broadcastSystemMessage(component, false);
    }
    
    @Override
    public String getPlayerDimension(Object player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return null;
        }
        
        // In NeoForge 1.20.2+, level().dimension() returns the dimension key
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
        int[] parsed = VersionDetector.parseVersion(version);
        int major = parsed[0];
        int minor = parsed[1];
        int patch = parsed[2];
        
        // NeoForge supports 1.20.2 through 1.21.x
        if (major != 1) return false;
        
        if (minor == 20) {
            return patch >= 2; // 1.20.2+
        } else if (minor == 21) {
            return true; // All 1.21.x versions
        }
        
        return false;
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
     * Uses the modern Component API available in NeoForge 1.20.2+.
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
     * In NeoForge 1.20.2+, chat types are registered and use the modern system.
     * @return a description of the chat handling method
     */
    public String getChatTypeDescription() {
        return "NeoForge modern chat handling (1.20.2-1.21): Uses Component API with sendSystemMessage";
    }
    
    /**
     * Checks if the current version supports the new chat signing system.
     * All NeoForge versions support chat signing.
     * @return true if chat signing is supported
     */
    public boolean supportsChatSigning() {
        return true; // All NeoForge versions support chat signing
    }
    
    /**
     * Gets the NeoForge-specific API version description.
     * Different NeoForge versions may have slightly different APIs.
     * @return the API version description
     */
    public String getNeoForgeApiVersion() {
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        int minor = parsed[1];
        int patch = parsed[2];
        
        if (minor == 20) {
            if (patch >= 2 && patch <= 4) {
                return "NeoForge API 1.20.2-1.20.4";
            } else if (patch >= 5) {
                return "NeoForge API 1.20.5-1.20.6 (with data components)";
            }
        } else if (minor == 21) {
            return "NeoForge API 1.21.x (latest)";
        }
        
        return "NeoForge API (unknown version)";
    }
    
    /**
     * Checks if the current version uses the new data component system.
     * Data components were introduced in 1.20.5.
     * @return true if data components are used
     */
    public boolean usesDataComponents() {
        int[] parsed = VersionDetector.parseVersion(minecraftVersion);
        int minor = parsed[1];
        int patch = parsed[2];
        
        // Data components introduced in 1.20.5
        return minor > 20 || (minor == 20 && patch >= 5);
    }
}
