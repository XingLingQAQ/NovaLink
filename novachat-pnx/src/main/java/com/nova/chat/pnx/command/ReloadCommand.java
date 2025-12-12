package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;

/**
 * Reload sub-command - reloads plugin configuration.
 * 
 * Requirements: 29.1, 29.2
 */
public class ReloadCommand extends AbstractSubCommand {

    public ReloadCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public String getDescription() {
        return "重载配置";
    }

    @Override
    public String getUsage() {
        return "/nc reload";
    }

    @Override
    public String getPermission() {
        return "novachat.admin";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        plugin.reload();
        sendSuccess(sender, "配置已重载");
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
