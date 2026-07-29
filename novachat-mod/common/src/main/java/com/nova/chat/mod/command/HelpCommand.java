package com.nova.chat.mod.command;

import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.CommandManager;

/**
 * Help command - displays available commands
 */
public class HelpCommand implements CommandHandler {
    private final CommandManager commandManager;
    
    public HelpCommand(CommandManager commandManager) {
        this.commandManager = commandManager;
    }
    
    @Override
    public boolean execute(String[] args, CommandContext context) {
        context.sendMessage("=== NovaChat Commands ===");
        context.sendMessage("/nc help - Show this help message");
        context.sendMessage("/nc join <channel> - Join a channel");
        context.sendMessage("/nc leave - Leave current channel");
        context.sendMessage("/nc toggle - Toggle chat on/off");
        if (context.isAdmin()) {
            context.sendMessage("/nc reload - Reload configuration");
            context.sendMessage("/nc debug - Show debug information");
        }
        return true;
    }
    
    @Override
    public String getDescription() {
        return "Display help information";
    }
    
    @Override
    public String getUsage() {
        return "/nc help";
    }
}
