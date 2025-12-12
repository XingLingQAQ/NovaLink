package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.chat.PlayerChatState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Leave command - allows players to leave a channel.
 * 
 * Requirements: 3
 */
public class LeaveCommand extends AbstractSubCommand {

    public LeaveCommand(NovaChatBukkit plugin) {
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
        String channelId;
        boolean leavingCurrent = false;

        if (args.length > 0) {
            channelId = args[0];
        } else {
            // Leave current channel
            PlayerChatState state = getPlayerState(player);
            if (state == null || state.getActiveChannel() == null) {
                errorHandler.sendError(sender, com.nova.chat.bukkit.error.ErrorCode.NOT_IN_CHANNEL);
                return true;
            }
            channelId = state.getActiveChannel();
            leavingCurrent = true;
        }

        // Create and send leave packet
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.LEAVE, channelId);
        packet.addExtra("playerId", player.getUniqueId().toString());
        packet.addExtra("playerName", player.getName());

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, "正在离开频道 &e" + channelId + "&7...");

            // Optimistically adjust local active channel if leaving the current one.
            if (leavingCurrent) {
                plugin.getChatInterceptor().setPlayerChannel(player, plugin.getNovaChatConfig().getDefaultChannel());
            }
        } else {
            errorHandler.sendRequestFailed(sender);
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> known = getKnownChannelIds(args[0]);
            if (!known.isEmpty()) {
                return known;
            }
            return Arrays.asList("global", "local");
        }
        return Collections.emptyList();
    }
}
