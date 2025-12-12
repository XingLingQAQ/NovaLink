package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;

/**
 * Join sub-command - joins a channel.
 * 
 * Requirements: 29.1, 29.2
 */
public class JoinCommand extends AbstractSubCommand {

    public JoinCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "join";
    }

    @Override
    public String getDescription() {
        return "加入频道";
    }

    @Override
    public String getUsage() {
        return "/nc join <频道>";
    }

    @Override
    public String getPermission() {
        return null; // Permission checked per-channel
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = getPlayer(sender);
        
        if (args.length < 1) {
            sendError(sender, "用法: /nc join <频道>");
            return true;
        }

        String channelId = args[0];

        // Check permission for specific channel
        if (!player.hasPermission("novachat.channel." + channelId) && 
            !player.hasPermission("novachat.channel.*")) {
            sendError(sender, "你没有权限加入此频道");
            return true;
        }

        plugin.getChatInterceptor().setPlayerChannel(player, channelId);
        sendSuccess(sender, "已加入频道: " + channelId);

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            // Return common channel names
            return List.of("global", "local", "pvp", "resource");
        }
        return List.of();
    }
}
