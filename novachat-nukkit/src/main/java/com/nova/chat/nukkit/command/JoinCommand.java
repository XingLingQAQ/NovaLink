package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.chat.PlayerChatState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;

/**
 * Join command - joins a channel.
 * 
 * Requirements: 3
 */
public class JoinCommand extends AbstractSubCommand {

    public JoinCommand(NovaChatNukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "join";
    }

    @Override
    public String getDescription() {
        return "加入一个频道";
    }

    @Override
    public String getUsage() {
        return "/nc join <频道ID> [密码]";
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
        if (args.length < 1) {
            sendError(sender, "用法: " + getUsage());
            return true;
        }

        Player player = getPlayer(sender);
        String channelId = args[0];
        String password = args.length > 1 ? args[1] : null;

        // Check if connected to backend
        if (!plugin.getNetworkClient().isAuthenticated()) {
            sendError(sender, "未连接到聊天服务器");
            return true;
        }

        // Send join request to backend
        ChannelActionPacket packet = new ChannelActionPacket(
            ChannelAction.JOIN,
            channelId,
            password
        );
        packet.addExtra("player_uuid", player.getUniqueId().toString());
        packet.addExtra("player_name", player.getName());

        plugin.getNetworkClient().sendPacket(packet);
        
        // Update local state (will be confirmed by backend response)
        PlayerChatState state = plugin.getChatInterceptor().getOrCreateState(player);
        state.setActiveChannel(channelId);
        
        sendSuccess(sender, "正在加入频道 " + channelId + "...");
        
        return true;
    }
}
