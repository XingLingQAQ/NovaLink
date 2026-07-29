package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.TextFormat;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;
import java.util.Map;

/**
 * Help sub-command - displays available commands.
 * 
 * Requirements: 29.1, 29.2
 */
public class HelpCommand extends AbstractSubCommand {

    private final NovaChatCommand parentCommand;

    public HelpCommand(NovaChatPNX plugin, NovaChatCommand parentCommand) {
        super(plugin);
        this.parentCommand = parentCommand;
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
        sender.sendMessage(TextFormat.GOLD + "=== NovaChat 帮助 ===");
        
        Map<String, SubCommand> subCommands = parentCommand.getSubCommands();
        
        for (Map.Entry<String, SubCommand> entry : subCommands.entrySet()) {
            SubCommand cmd = entry.getValue();
            
            // Skip hidden commands
            if (cmd.isHidden()) {
                continue;
            }
            
            // Skip commands the sender doesn't have permission for
            if (!cmd.hasPermission(sender)) {
                continue;
            }
            
            // Check if it's an admin command
            String permission = cmd.getPermission();
            boolean isAdmin = permission != null && permission.contains("admin");
            
            if (isAdmin) {
                sender.sendMessage(TextFormat.RED + cmd.getUsage() + TextFormat.WHITE + " - " + cmd.getDescription());
            } else {
                sender.sendMessage(TextFormat.YELLOW + cmd.getUsage() + TextFormat.WHITE + " - " + cmd.getDescription());
            }
        }
        
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
