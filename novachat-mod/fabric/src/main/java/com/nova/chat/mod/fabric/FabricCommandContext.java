package com.nova.chat.mod.fabric;

import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.Platform;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * Fabric-specific command context that provides proper message sending
 */
public class FabricCommandContext extends CommandContext {
    private final CommandSourceStack source;
    
    public FabricCommandContext(UUID playerId, String playerName, Platform platform, boolean isAdmin, CommandSourceStack source) {
        super(playerId, playerName, platform, isAdmin);
        this.source = source;
    }
    
    @Override
    public void sendMessage(String message) {
        if (source != null) {
            // Convert color codes and send
            String converted = message.replace("&", "§");
            source.sendSuccess(() -> Component.literal(converted), false);
        }
    }
    
    /**
     * Send an error message to the player
     * @param message the error message
     */
    public void sendError(String message) {
        if (source != null) {
            String converted = message.replace("&", "§");
            source.sendFailure(Component.literal(converted));
        }
    }
    
    /**
     * Get the command source stack
     * @return the command source stack
     */
    public CommandSourceStack getSource() {
        return source;
    }
}
