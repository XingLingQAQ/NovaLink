package com.nova.chat.nukkit.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.nukkit.NovaChatNukkit;

import java.util.Map;

/**
 * Help command - displays available commands.
 * 
 * Requirements: 26.1-26.4
 */
public class HelpCommand extends AbstractSubCommand {

    private final NovaChatCommand mainCommand;

    public HelpCommand(NovaChatNukkit plugin, NovaChatCommand mainCommand) {
        super(plugin);
        this.mainCommand = mainCommand;
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return "显示帮助信息";
    }

    @Override
    public String getUsage() {
        return "/nc help";
    }

    @Override
    public String getPermission() {
        return null; // No permission required
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        messageHelper.sendRawMessage(sender, "&b&l========== NovaChat 帮助 ==========");
        
        for (Map.Entry<String, SubCommand> entry : mainCommand.getSubCommands().entrySet()) {
            SubCommand cmd = entry.getValue();
            
            // Skip hidden commands
            if (cmd.isHidden()) {
                continue;
            }
            
            // Skip commands the sender doesn't have permission for
            if (!cmd.hasPermission(sender)) {
                continue;
            }
            
            String permission = cmd.getPermission();
            String permDisplay = permission != null ? " &8(" + permission + ")" : "";
            
            messageHelper.sendRawMessage(sender, 
                "&e" + cmd.getUsage() + " &7- " + cmd.getDescription() + permDisplay);
        }
        
        messageHelper.sendRawMessage(sender, "&b&l=====================================");
        
        return true;
    }
}
