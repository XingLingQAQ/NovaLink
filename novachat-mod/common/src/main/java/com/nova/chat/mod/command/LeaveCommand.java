package com.nova.chat.mod.command;

import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;

/**
 * Leave command - leave the current chat channel
 */
public class LeaveCommand implements CommandHandler {
    
    @Override
    public boolean execute(String[] args, CommandContext context) {
        context.sendMessage("Leaving current channel");
        
        // In a real implementation, this would communicate with the backend
        // For now, just acknowledge the command
        return true;
    }
    
    @Override
    public String getDescription() {
        return "Leave the current chat channel";
    }
    
    @Override
    public String getUsage() {
        return "/nc leave";
    }
}
