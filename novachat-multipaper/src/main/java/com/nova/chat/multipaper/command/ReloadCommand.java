package com.nova.chat.multipaper.command;

import com.nova.chat.multipaper.NovaChatMultiPaper;
import org.bukkit.command.CommandSender;

/**
 * Reload command - reloads plugin configuration.
 */
public class ReloadCommand extends AbstractSubCommand {

    public ReloadCommand(NovaChatMultiPaper plugin) {
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
