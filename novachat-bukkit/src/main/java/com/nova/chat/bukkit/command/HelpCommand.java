package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import org.bukkit.command.CommandSender;

import java.util.Map;

/**
 * Help command - displays available commands based on player permissions.
 * 
 * Requirements: 26.1-26.4
 */
public class HelpCommand extends AbstractSubCommand {

    private final NovaChatCommand mainCommand;

    public HelpCommand(NovaChatBukkit plugin, NovaChatCommand mainCommand) {
        super(plugin);
        this.mainCommand = mainCommand;
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return "显示可用命令列表";
    }

    @Override
    public String getUsage() {
        return "/nc help";
    }

    @Override
    public String getPermission() {
        return "novachat.help";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        messageHelper.sendHeader(sender, "NovaChat 帮助");
        
        Map<String, SubCommand> subCommands = mainCommand.getSubCommands();
        
        for (Map.Entry<String, SubCommand> entry : subCommands.entrySet()) {
            SubCommand cmd = entry.getValue();
            
            // Skip hidden commands
            if (cmd.isHidden()) {
                continue;
            }
            
            // Only show commands the player has permission for
            if (cmd.hasPermission(sender)) {
                messageHelper.sendCommandHelp(sender, cmd.getUsage(), cmd.getDescription());
            }
        }
        
        messageHelper.sendFooter(sender);
        return true;
    }
}
