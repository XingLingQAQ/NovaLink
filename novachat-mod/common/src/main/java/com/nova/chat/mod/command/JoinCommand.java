package com.nova.chat.mod.command;

import com.nova.chat.mod.chat.PlayerChatState;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;

/**
 * Join command - join a chat channel
 */
public class JoinCommand implements CommandHandler {
    
    @Override
    public boolean execute(String[] args, CommandContext context) {
        if (args.length < 1) {
            context.sendMessage("Usage: /nc join <channel>");
            return false;
        }
        
        String channelId = args[0];
        context.sendMessage("Joining channel: " + channelId);
        
        // In a real implementation, this would communicate with the backend
        // For now, just acknowledge the command
        return true;
    }
    
    @Override
    public String getDescription() {
        return "Join a chat channel";
    }
    
    @Override
    public String getUsage() {
        return "/nc join <channel>";
    }
}
