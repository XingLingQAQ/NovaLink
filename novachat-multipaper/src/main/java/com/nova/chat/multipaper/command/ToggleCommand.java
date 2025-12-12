package com.nova.chat.multipaper.command;

import com.nova.chat.multipaper.NovaChatMultiPaper;
import com.nova.chat.multipaper.chat.ChatMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Toggle command - toggles chat mode between HYBRID and REPLACE.
 */
public class ToggleCommand extends AbstractSubCommand {

    public ToggleCommand(NovaChatMultiPaper plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "toggle";
    }

    @Override
    public String getDescription() {
        return "切换聊天模式";
    }

    @Override
    public String getUsage() {
        return "/nc toggle";
    }

    @Override
    public String getPermission() {
        return "novachat.toggle";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        ChatMode newMode = plugin.getChatInterceptor().togglePlayerMode(player);
        
        String modeDesc = newMode == ChatMode.REPLACE ? "频道模式" : "混合模式";
        messageHelper.sendSuccess(sender, "聊天模式已切换为: &e" + modeDesc);
        
        if (newMode == ChatMode.REPLACE) {
            messageHelper.sendRaw(sender, "&7所有聊天消息将发送到当前频道");
        } else {
            messageHelper.sendRaw(sender, "&7原版聊天已启用，使用命令发送频道消息");
        }
        
        return true;
    }
}
