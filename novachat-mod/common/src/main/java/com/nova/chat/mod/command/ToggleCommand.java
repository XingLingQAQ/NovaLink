package com.nova.chat.mod.command;

import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;

/**
 * Toggle command - toggle chat on/off
 */
public class ToggleCommand implements CommandHandler {
    
    @Override
    public boolean execute(String[] args, CommandContext context) {
        context.sendMessage("Chat toggled");
        
        // In a real implementation, this would toggle the player's chat state
        // For now, just acknowledge the command
        return true;
    }
    
    @Override
    public String getDescription() {
        return "Toggle chat on/off";
    }
    
    @Override
    public String getUsage() {
        return "/nc toggle";
    }
}
