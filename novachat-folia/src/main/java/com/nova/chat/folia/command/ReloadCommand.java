package com.nova.chat.folia.command;

import com.nova.chat.folia.NovaChatFolia;
import org.bukkit.command.CommandSender;

/**
 * Reload command - reloads plugin configuration.
 * 
 * Requirements: 2.1
 */
public class ReloadCommand extends AbstractSubCommand {

    public ReloadCommand(NovaChatFolia plugin) {
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
        return "novachat.reload";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        plugin.reload();
        messageHelper.sendSuccess(sender, "配置已重新加载");
        return true;
    }
}
