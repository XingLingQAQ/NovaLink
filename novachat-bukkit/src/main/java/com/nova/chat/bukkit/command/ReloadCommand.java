package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/**
 * Reload command - allows admins to reload the plugin configuration.
 * 
 * Requirements: 18
 */
public class ReloadCommand extends AbstractSubCommand {

    public ReloadCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public String getDescription() {
        return "重新加载配置文件";
    }

    @Override
    public String getUsage() {
        return "/nc reload";
    }

    @Override
    public String getPermission() {
        return "novachat.reload";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        messageHelper.sendMessage(sender, "正在重新加载配置...");
        
        try {
            plugin.reload();
            messageHelper.sendSuccess(sender, "配置已重新加载");
        } catch (Exception e) {
            messageHelper.sendError(sender, "重新加载失败: " + e.getMessage());
            plugin.getLogger().severe("Failed to reload configuration: " + e.getMessage());
            e.printStackTrace();
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
