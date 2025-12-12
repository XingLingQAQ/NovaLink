package com.nova.chat.nukkit.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.nukkit.NovaChatNukkit;

/**
 * Reload command - reloads plugin configuration.
 * 
 * Requirements: 18.1
 */
public class ReloadCommand extends AbstractSubCommand {

    public ReloadCommand(NovaChatNukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public String getDescription() {
        return "重新加载配置";
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
        try {
            plugin.reload();
            sendSuccess(sender, "配置已重新加载");
        } catch (Exception e) {
            sendError(sender, "重新加载配置时出错: " + e.getMessage());
            plugin.getLogger().error("Error reloading config", e);
        }
        
        return true;
    }
}
