package com.nova.chat.mod.forge.version;

import com.nova.chat.mod.version.VersionDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Version adapter for Forge 1.7.10 through 1.12.2.
 * These are legacy versions before the "flattening" in 1.13.
 * 
 * Key characteristics:
 * - Uses legacy Forge event system (FML events)
 * - Pre-flattening block/item IDs (numeric IDs with metadata)
 * - Uses IChatComponent instead of Component
 * - Different server/player API structure
 * 
 * Note: This adapter provides stub implementations since the actual
 * Minecraft classes differ significantly in these versions. The real
 * implementation would need version-specific compilation.
 * 
 * Requirements: 7.1, 7.3
 */
public class Forge1_7_1_12Adapter implements ForgeVersionAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(Forge1_7_1_12Adapter.class);
    
    private static final String SUPPORTED_RANGE = "1.7.10-1.12.2";
    private String minecraftVersion = "1.12.2";
    
    public Forge1_7_1_12Adapter() {
        LOGGER.info("Forge1_7_1_12Adapter initialized for Forge {}", SUPPORTED_RANGE);
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
        // In 1.7-1.12, we would use:
        // EntityPlayerMP.addChatMessage(IChatComponent)
        // This is a stub - actual implementation requires version-specific compilation
        LOGGER.debug("Sending chat message to player: {}", message);
        sendMessageLegacy(player, message);
    }
    
    @Override
    public void sendSystemMessage(Object player, String message) {
        // In 1.7-1.12, system messages use the same API as chat messages
        LOGGER.debug("Sending system message to player: {}", message);
        sendMessageLegacy(player, message);
    }
    
    /**
     * Legacy message sending implementation.
     * In actual use, this would call EntityPlayerMP.addChatMessage()
     */
    private void sendMessageLegacy(Object player, String message) {
        try {
            // Use reflection to call the legacy API
            // EntityPlayerMP.addChatMessage(new ChatComponentText(message))
            Class<?> playerClass = player.getClass();
            
            // Try to find addChatMessage or sendMessage method
            java.lang.reflect.Method sendMethod = null;
            for (java.lang.reflect.Method method : playerClass.getMethods()) {
                String name = method.getName();
                if (name.equals("addChatMessage") || name.equals("sendMessage")) {
                    if (method.getParameterCount() == 1) {
                        sendMethod = method;
                        break;
                    }
                }
            }
            
            if (sendMethod != null) {
                // Create IChatComponent/ChatComponentText
                Object chatComponent = createLegacyChatComponent(message);
                if (chatComponent != null) {
                    sendMethod.invoke(player, chatComponent);
                }
            } else {
                LOGGER.warn("Could not find message sending method for legacy Forge");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to send legacy message: {}", e.getMessage());
        }
    }
    
    /**
     * Creates a legacy chat component using reflection.
     */
    private Object createLegacyChatComponent(String message) {
        try {
            // Try ChatComponentText (1.7.10-1.8)
            Class<?> chatClass = Class.forName("net.minecraft.util.ChatComponentText");
            return chatClass.getConstructor(String.class).newInstance(parseColorCodes(message));
        } catch (ClassNotFoundException e1) {
            try {
                // Try TextComponentString (1.9-1.12)
                Class<?> textClass = Class.forName("net.minecraft.util.text.TextComponentString");
                return textClass.getConstructor(String.class).newInstance(parseColorCodes(message));
            } catch (Exception e2) {
                LOGGER.debug("Could not create legacy chat component: {}", e2.getMessage());
            }
        } catch (Exception e) {
            LOGGER.debug("Could not create legacy chat component: {}", e.getMessage());
        }
        return null;
    }
    
    @Override
    public void broadcastMessage(Object server, String message) {
        // In 1.7-1.12: MinecraftServer.getConfigurationManager().sendChatMsg()
        // or iterate through players and send individually
        LOGGER.debug("Broadcasting message: {}", message);
        try {
            java.lang.reflect.Method getPlayerList = null;
            for (java.lang.reflect.Method method : server.getClass().getMethods()) {
                String name = method.getName();
                if (name.equals("getConfigurationManager") || name.equals("getPlayerList")) {
                    getPlayerList = method;
                    break;
                }
            }
            
            if (getPlayerList != null) {
                Object playerList = getPlayerList.invoke(server);
                // Send to all players
                java.lang.reflect.Method sendAll = null;
                for (java.lang.reflect.Method method : playerList.getClass().getMethods()) {
                    if (method.getName().equals("sendChatMsg") || method.getName().equals("sendMessage")) {
                        sendAll = method;
                        break;
                    }
                }
                
                if (sendAll != null) {
                    Object chatComponent = createLegacyChatComponent(message);
                    if (chatComponent != null) {
                        sendAll.invoke(playerList, chatComponent);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to broadcast legacy message: {}", e.getMessage());
        }
    }
    
    @Override
    public String getPlayerDimension(Object player) {
        try {
            // In 1.7-1.12: EntityPlayerMP.dimension (int) or EntityPlayerMP.worldObj.provider.getDimensionId()
            java.lang.reflect.Field dimensionField = player.getClass().getField("dimension");
            int dimension = dimensionField.getInt(player);
            return String.valueOf(dimension);
        } catch (Exception e) {
            LOGGER.debug("Could not get player dimension: {}", e.getMessage());
        }
        return "0"; // Overworld
    }
    
    @Override
    public String getPlayerDisplayName(Object player) {
        try {
            // In 1.7-1.12: EntityPlayerMP.getDisplayName() or getName()
            java.lang.reflect.Method getDisplayName = player.getClass().getMethod("getDisplayName");
            Object result = getDisplayName.invoke(player);
            if (result instanceof String) {
                return (String) result;
            }
            // If it returns IChatComponent, get the string
            return result.toString();
        } catch (Exception e) {
            try {
                java.lang.reflect.Method getName = player.getClass().getMethod("getName");
                return (String) getName.invoke(player);
            } catch (Exception e2) {
                LOGGER.debug("Could not get player display name: {}", e2.getMessage());
            }
        }
        return null;
    }
    
    @Override
    public UUID getPlayerUUID(Object player) {
        try {
            // In 1.7-1.12: EntityPlayerMP.getUniqueID() or getGameProfile().getId()
            java.lang.reflect.Method getUUID = player.getClass().getMethod("getUniqueID");
            return (UUID) getUUID.invoke(player);
        } catch (Exception e) {
            try {
                java.lang.reflect.Method getGameProfile = player.getClass().getMethod("getGameProfile");
                Object profile = getGameProfile.invoke(player);
                java.lang.reflect.Method getId = profile.getClass().getMethod("getId");
                return (UUID) getId.invoke(profile);
            } catch (Exception e2) {
                LOGGER.debug("Could not get player UUID: {}", e2.getMessage());
            }
        }
        return null;
    }
    
    @Override
    public boolean supportsVersion(String version) {
        int[] parsed = VersionDetector.parseVersion(version);
        int major = parsed[0];
        int minor = parsed[1];
        int patch = parsed[2];
        
        // Supports 1.7.10 through 1.12.2
        if (major != 1) return false;
        if (minor < 7 || minor > 12) return false;
        if (minor == 7 && patch < 10) return false;
        return true;
    }
    
    @Override
    public Object createTextComponent(String text) {
        return createLegacyChatComponent(text);
    }
    
    @Override
    public String parseColorCodes(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace("&", "§");
    }
    
    @Override
    public String getForgeApiVersion() {
        return "Forge API 1.7.10-1.12.2 (Legacy, pre-flattening)";
    }
    
    @Override
    public boolean usesLegacyEvents() {
        return true; // 1.7-1.12 uses legacy FML event system
    }
    
    @Override
    public boolean usesModernRegistries() {
        return false; // Pre-flattening uses numeric IDs
    }
    
    @Override
    public boolean supportsChatSigning() {
        return false; // Chat signing was introduced in 1.19.1
    }
    
    @Override
    public String getChatTypeDescription() {
        return "Legacy Forge 1.7-1.12 chat handling: Uses IChatComponent/TextComponentString with addChatMessage";
    }
    
    @Override
    public boolean usesFlattening() {
        return false; // Flattening was introduced in 1.13
    }
    
    @Override
    public boolean usesDataComponents() {
        return false; // Data components were introduced in 1.20.5
    }
}
