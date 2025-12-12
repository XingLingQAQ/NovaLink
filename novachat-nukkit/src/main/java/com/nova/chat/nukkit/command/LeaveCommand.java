package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.chat.PlayerChatState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;

/**
 * Leave command - leaves the current channel.
 * 
 * Requirements: 3
 */
public class LeaveCommand extends AbstractSubCommand {

    public LeaveCommand(NovaChatNukkit plugin) {
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
        return null; // No permission required
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = getPlayer(sender);

        // Check if connected to backend
        if (!plugin.getNetworkClient().isAuthenticated()) {
            sendError(sender, "未连接到聊天服务器");
            return true;
        }

        // Get current channel
        PlayerChatState state = plugin.getChatInterceptor().getOrCreateState(player);
        String currentChannel = state.getActiveChannel();
        String defaultChannel = plugin.getNovaChatConfig().getDefaultChannel();

        if (currentChannel.equals(defaultChannel)) {
            sendError(sender, "你已经在默认频道中");
            return true;
        }

        // Send leave request to backend
        ChannelActionPacket packet = new ChannelActionPacket(
            ChannelAction.LEAVE,
            currentChannel,
            null
        );
        packet.addExtra("player_uuid", player.getUniqueId().toString());
        packet.addExtra("player_name", player.getName());

        plugin.getNetworkClient().sendPacket(packet);
        
        // Update local state to default channel
        state.setActiveChannel(defaultChannel);
        
        sendSuccess(sender, "已离开频道 " + currentChannel + "，已切换到默认频道");
        
        return true;
    }
}
