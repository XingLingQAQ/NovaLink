package com.nova.chat.folia.command;

import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.chat.PlayerChatState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Leave command - allows players to leave a channel.
 * 
 * Requirements: 2.1
 */
public class LeaveCommand extends AbstractSubCommand {

    public LeaveCommand(NovaChatFolia plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "leave";
    }

    @Override
    public String getDescription() {
        return "离开当前或指定频道";
    }

    @Override
    public String getUsage() {
        return "/nc leave [频道ID]";
    }

    @Override
    public String getPermission() {
        return "novachat.leave";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!checkConnection(sender)) {
            return true;
        }

        Player player = (Player) sender;
        PlayerChatState state = getPlayerState(player);
        
        String channelId;
        if (args.length > 0) {
            channelId = args[0];
        } else if (state != null) {
            channelId = state.getActiveChannel();
        } else {
            messageHelper.sendError(sender, "请指定要离开的频道");
            return true;
        }

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.LEAVE, channelId, "");
        packet.addExtra("playerId", player.getUniqueId().toString());
        packet.addExtra("playerName", player.getName());

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, "正在离开频道 &e" + channelId + "&7...");
        } else {
            messageHelper.sendError(sender, "发送请求失败");
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
