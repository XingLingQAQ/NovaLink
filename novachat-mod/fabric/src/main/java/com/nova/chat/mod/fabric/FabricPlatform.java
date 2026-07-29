package com.nova.chat.mod.fabric;

import com.nova.chat.mod.platform.ChatHandler;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.Platform;
import com.nova.chat.mod.platform.PlatformType;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Fabric platform implementation
 * Uses ServerMessageEvents for chat interception and Text API for message rendering
 * 
 * Requirements: 2.2, 2.4, 2.5
 */
public class FabricPlatform implements Platform {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricPlatform.class);
    
    private MinecraftServer server;
    private ChatHandler chatHandler;
    private CommandManager commandManager;
    private boolean replaceVanillaChat = false;
    
    public FabricPlatform() {
    }
    
    /**
     * Set the Minecraft server instance
     * @param server the server instance
     */
    public void setServer(MinecraftServer server) {
        this.server = server;
    }
    
    /**
     * Set whether to replace vanilla chat
     * @param replace true to replace vanilla chat
     */
    public void setReplaceVanillaChat(boolean replace) {
        this.replaceVanillaChat = replace;
    }
    
    @Override
    public void registerChatListener(ChatHandler handler) {
        this.chatHandler = handler;
        
        // Register chat event listener using Fabric API's ServerMessageEvents
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (chatHandler != null && sender != null) {
                UUID playerId = sender.getUUID();
                String playerName = sender.getName().getString();
                String content = message.signedContent();
                
                LOGGER.debug("Chat intercepted from {}: {}", playerName, content);
                chatHandler.onPlayerChat(playerId, playerName, content);
            }
        });
        
        // Also register for allow chat event to potentially cancel vanilla chat
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            // If replaceVanillaChat is true, cancel the vanilla chat message
            // The message will be handled by our chat handler instead
            return !replaceVanillaChat;
        });
        
        LOGGER.info("Fabric chat listener registered");
    }
    
    @Override
    public void registerCommands(CommandManager manager) {
        this.commandManager = manager;
        // Commands are registered separately via CommandRegistrationCallback
        LOGGER.info("Command manager set for Fabric platform");
    }

    
    @Override
    public void sendMessage(UUID playerId, Object message) {
        if (server == null) {
            LOGGER.warn("Server not initialized, cannot send message");
            return;
        }
        
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            Component component;
            if (message instanceof Component) {
                component = (Component) message;
            } else if (message instanceof String) {
                component = Component.literal((String) message);
            } else if (message == null) {
                LOGGER.warn("Null message provided");
                return;
            } else {
                component = Component.literal(message.toString());
            }
            player.sendSystemMessage(component);
        }
    }
    
    @Override
    public void broadcastMessage(Object message) {
        if (server == null) {
            LOGGER.warn("Server not initialized, cannot broadcast message");
            return;
        }
        
        Component component;
        if (message instanceof Component) {
            component = (Component) message;
        } else if (message instanceof String) {
            component = Component.literal((String) message);
        } else if (message == null) {
            LOGGER.warn("Null message provided");
            return;
        } else {
            component = Component.literal(message.toString());
        }
        
        server.getPlayerList().broadcastSystemMessage(component, false);
    }
    
    @Override
    public String getCurrentWorld(UUID playerId) {
        if (server == null) {
            return null;
        }
        
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            return player.level().dimension().location().toString();
        }
        return null;
    }
    
    @Override
    public String getPlayerName(UUID playerId) {
        if (server == null) {
            return null;
        }
        
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            return player.getName().getString();
        }
        return null;
    }
    
    @Override
    public boolean isPlayerOnline(UUID playerId) {
        if (server == null) {
            return false;
        }
        
        return server.getPlayerList().getPlayer(playerId) != null;
    }
    
    @Override
    public PlatformType getPlatformType() {
        return PlatformType.FABRIC;
    }
    
    /**
     * Get the command manager
     * @return the command manager
     */
    public CommandManager getCommandManager() {
        return commandManager;
    }
    
    /**
     * Send a formatted message to a player using color codes
     * @param playerId the player UUID
     * @param formattedMessage the message with color codes (e.g., &c for red)
     */
    public void sendFormattedMessage(UUID playerId, String formattedMessage) {
        Component component = parseColorCodes(formattedMessage);
        sendMessage(playerId, component);
    }
    
    /**
     * Broadcast a formatted message to all players using color codes
     * @param formattedMessage the message with color codes
     */
    public void broadcastFormattedMessage(String formattedMessage) {
        Component component = parseColorCodes(formattedMessage);
        broadcastMessage(component);
    }
    
    /**
     * Parse color codes in a message and convert to Component
     * Supports & color codes (e.g., &c for red, &l for bold)
     * @param message the message with color codes
     * @return the parsed Component
     */
    private Component parseColorCodes(String message) {
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }
        
        // Convert & color codes to § for Minecraft
        String converted = message.replace("&", "§");
        return Component.literal(converted);
    }
}
