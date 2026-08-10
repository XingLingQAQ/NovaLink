package com.nova.chat.mod.neoforge;

import com.nova.chat.mod.platform.ChatHandler;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.Platform;
import com.nova.chat.mod.platform.PlatformType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

/**
 * NeoForge platform implementation
 * Uses NeoForge event bus for chat interception and Component API for message rendering
 * 
 * Requirements: 3.2, 3.3
 * - Uses NeoForge event bus to register listeners
 * - Uses ServerChatEvent to intercept messages
 * - Uses Component API to render rich text messages
 */
public class NeoForgePlatform implements Platform {
    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgePlatform.class);
    
    private MinecraftServer server;
    private ChatHandler chatHandler;
    private CommandManager commandManager;
    private boolean replaceVanillaChat = false;
    
    public NeoForgePlatform() {
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
        
        // Register this platform as an event listener on NeoForge event bus
        NeoForge.EVENT_BUS.register(this);
        
        LOGGER.info("NeoForge chat listener registered");
    }
    
    /**
     * Handle ServerChatEvent from NeoForge event bus
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
        LOGGER.info("Command manager set for NeoForge platform");
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
            return player.level().dimension().identifier().toString();
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
    public Collection<UUID> getOnlinePlayerIds() {
        if (server == null) {
            return java.util.Collections.emptyList();
        }
        java.util.List<UUID> ids = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ids.add(player.getUUID());
        }
        return ids;
    }

    @Override
    public void runAsync(Runnable task) {
        if (server == null) {
            new Thread(task, "NovaChat-mod-async").start();
            return;
        }
        server.execute(task);
    }

    @Override
    public void runLater(Runnable task, long delaySeconds) {
        long delayMs = Math.max(0L, delaySeconds) * 1000L;
        if (server == null) {
            new Thread(task, "NovaChat-mod-delayed").start();
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            server.execute(task);
        }, "NovaChat-mod-delayed").start();
    }

    @Override
    public void logInfo(String message) {
        LOGGER.info(message);
    }

    @Override
    public void logWarn(String message) {
        LOGGER.warn(message);
    }

    @Override
    public void logDebug(String message) {
        LOGGER.debug(message);
    }

    @Override
    public void logError(String message) {
        LOGGER.error(message);
    }

    @Override
    public void logError(String message, Throwable cause) {
        if (cause == null) {
            LOGGER.error(message);
        } else {
            LOGGER.error(message, cause);
        }
    }

    @Override
    public String getServerVersion() {
        if (server == null) {
            return "";
        }
        try {
            return server.getServerVersion();
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public PlatformType getPlatformType() {
        return PlatformType.NEOFORGE;
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
