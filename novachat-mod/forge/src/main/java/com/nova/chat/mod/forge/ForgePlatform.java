package com.nova.chat.mod.forge;

import com.nova.chat.mod.platform.ChatHandler;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.Platform;
import com.nova.chat.mod.platform.PlatformType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Forge platform implementation
 * Uses MinecraftForge.EVENT_BUS for chat interception and Component API for message rendering
 * 
 * Requirements: 5.2, 5.3
 * - Uses MinecraftForge.EVENT_BUS to register listeners
 * - Uses ServerChatEvent to intercept messages
 * - Uses Forge's Component API to render rich text messages
 */
public class ForgePlatform implements Platform {
    private static final Logger LOGGER = LoggerFactory.getLogger(ForgePlatform.class);
    
    private MinecraftServer server;
    private ChatHandler chatHandler;
    private CommandManager commandManager;
    private boolean replaceVanillaChat = false;
    
    public ForgePlatform() {
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
        
        // Register this platform as an event listener on MinecraftForge event bus
        MinecraftForge.EVENT_BUS.register(this);
        
        LOGGER.info("Forge chat listener registered");
    }
    
    /**
     * Handle ServerChatEvent from MinecraftForge event bus
     * This method intercepts player chat messages
     * @param event the chat event
     */
    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (chatHandler != null) {
            ServerPlayer player = event.getPlayer();
            UUID playerId = player.getUUID();
            String playerName = player.getName().getString();
            String content = event.getMessage().getString();
            
            LOGGER.debug("Chat intercepted from {}: {}", playerName, content);
            chatHandler.onPlayerChat(playerId, playerName, content);
            
            // If replaceVanillaChat is true, cancel the vanilla chat message
            // The message will be handled by our chat handler instead
            if (replaceVanillaChat) {
                event.setCanceled(true);
            }
        }
    }
    
    @Override
    public void registerCommands(CommandManager manager) {
        this.commandManager = manager;
        // Commands are registered separately via RegisterCommandsEvent
        LOGGER.info("Command manager set for Forge platform");
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
        return PlatformType.FORGE;
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
