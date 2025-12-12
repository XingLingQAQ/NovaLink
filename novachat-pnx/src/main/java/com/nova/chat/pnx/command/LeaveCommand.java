package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;

/**
 * Leave sub-command - leaves current channel and returns to default.
 * 
 * Requirements: 29.1, 29.2
 */
public class LeaveCommand extends AbstractSubCommand {

    public LeaveCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "leave";
    }

    @Override
    public String getDescription() {
        return "离开当前频道";
    }

    @Override
    public String getUsage() {
        return "/nc leave";
    }

    @Override
    public String getPermission() {
        return null;
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = getPlayer(sender);
        
        String currentChannel = plugin.getChatInterceptor().getPlayerChannel(player);
        String defaultChannel = plugin.getNovaChatConfig().getDefaultChannel();
        
        if (currentChannel.equals(defaultChannel)) {
            sendError(sender, "你已经在默认频道中");
            return true;
        }
        
        plugin.getChatInterceptor().setPlayerChannel(player, defaultChannel);
        sendSuccess(sender, "已返回默认频道: " + defaultChannel);

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
