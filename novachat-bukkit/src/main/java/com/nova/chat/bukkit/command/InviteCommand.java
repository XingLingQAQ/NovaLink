package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Invite command - allows channel admins to invite players to a channel.
 * 
 * Requirements: 8
 */
public class InviteCommand extends AbstractSubCommand {

    public InviteCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "invite";
    }

    @Override
    public String getDescription() {
        return "邀请玩家加入频道";
    }

    @Override
    public String getUsage() {
        return "/nc invite <玩家> [频道ID]";
    }

    @Override
    public String getPermission() {
        return "novachat.invite";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            messageHelper.sendUsage(sender, getUsage());
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        Player player = (Player) sender;
        String targetName = args[0];
        String channelId;

        if (args.length > 1) {
            channelId = args[1];
        } else {
            // Use current channel
            PlayerChannelState state = getPlayerState(player);
            if (state == null || state.getActiveChannel() == null) {
                errorHandler.sendError(sender, com.nova.chat.bukkit.error.ErrorCode.NOT_IN_CHANNEL, 
                    "请指定频道ID或先加入一个频道");
                return true;
            }
            channelId = state.getActiveChannel();
        }

        // Check if target player exists
        Player target = parsePlayer(targetName);
        if (target == null) {
            errorHandler.sendPlayerNotFound(sender, targetName);
            return true;
        }

        // Create and send invite packet
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.INVITE, channelId);
        packet.addExtra("playerId", player.getUniqueId().toString());
        packet.addExtra("playerName", player.getName());
        packet.addExtra("targetId", target.getUniqueId().toString());
        packet.addExtra("targetName", target.getName());

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, "正在邀请 &e" + targetName + " &7加入频道 &e" + channelId + "&7...");
        } else {
            errorHandler.sendRequestFailed(sender);
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return getOnlinePlayerNames(args[0]);
        }
        if (args.length == 2) {
            List<String> known = getKnownChannelIds(args[1]);
            if (!known.isEmpty()) {
                return known;
            }
            return Arrays.asList("global", "local");
        }
        return Collections.emptyList();
    }
}
