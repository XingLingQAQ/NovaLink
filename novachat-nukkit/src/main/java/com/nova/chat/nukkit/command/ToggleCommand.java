package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.chat.ChatMode;

/**
 * Toggle command - toggles chat mode between HYBRID and REPLACE.
 * 
 * Requirements: 11.3
 */
public class ToggleCommand extends AbstractSubCommand {

    public ToggleCommand(NovaChatNukkit plugin) {
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
        return null; // No permission required
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = getPlayer(sender);
        
        ChatMode newMode = plugin.getChatInterceptor().togglePlayerMode(player);
        
        String modeDescription = newMode == ChatMode.REPLACE 
            ? "频道模式 (所有聊天发送到频道)" 
            : "混合模式 (原版聊天正常工作)";
        
        sendSuccess(sender, "聊天模式已切换为: &e" + modeDescription);
        
        return true;
    }
}
