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
 * Kick command - allows admins to kick players from channels.
 * 
 * Requirements: 16
 */
public class KickCommand extends AbstractSubCommand {

    public KickCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "kick";
    }

    @Override
    public String getDescription() {
        return "将玩家踢出频道";
    }

    @Override
    public String getUsage() {
        return "/nc kick <玩家> [频道ID]";
    }

    @Override
    public String getPermission() {
        return "novachat.kick";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
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

        String targetName = args[0];
        String channelId = null;

        if (args.length > 1) {
            channelId = args[1];
        } else if (sender instanceof Player) {
            // Use current channel
            PlayerChannelState state = getPlayerState((Player) sender);
            if (state != null && state.getActiveChannel() != null) {
                channelId = state.getActiveChannel();
            }
        }

        if (channelId == null) {
            errorHandler.sendError(sender, com.nova.chat.bukkit.error.ErrorCode.BAD_REQUEST, 
                "请指定频道ID");
            return true;
        }

        // Check if target player exists
        Player target = parsePlayer(targetName);
        if (target == null) {
            errorHandler.sendPlayerNotFound(sender, targetName);
            return true;
        }

        // Create and send kick packet
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.KICK, channelId);
        if (sender instanceof Player) {
            packet.addExtra("operatorId", ((Player) sender).getUniqueId().toString());
        }
        packet.addExtra("operatorName", sender.getName());
        packet.addExtra("targetId", target.getUniqueId().toString());
        packet.addExtra("targetName", target.getName());

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, "正在将 &e" + targetName + " &7踢出频道 &e" + channelId + "&7...");
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
