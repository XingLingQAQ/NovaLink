package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Mute command - allows admins to mute players in channels.
 * 
 * Requirements: 13
 */
public class MuteCommand extends AbstractSubCommand {

    public MuteCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "mute";
    }

    @Override
    public String getDescription() {
        return "禁言玩家";
    }

    @Override
    public String getUsage() {
        return "/nc mute <玩家> <时间> [频道ID]";
    }

    @Override
    public String getPermission() {
        return "novachat.mute";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messageHelper.sendUsage(sender, getUsage());
            messageHelper.sendSuggestion(sender, I18n.tr(playerIdOf(sender), "chat.mute.duration_hint"));
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        String targetName = args[0];
        String durationStr = args[1];
        String channelId = null;

        if (args.length > 2) {
            channelId = args[2];
        } else if (sender instanceof Player) {
            // Use current channel
            PlayerChannelState state = getPlayerState((Player) sender);
            if (state != null && state.getActiveChannel() != null) {
                channelId = state.getActiveChannel();
            }
        }

        if (channelId == null) {
            errorHandler.sendError(sender, com.nova.chat.client.error.ErrorCode.BAD_REQUEST,
                I18n.tr(playerIdOf(sender), "chat.command.specify_channel"));
            return true;
        }

        // Parse duration
        long durationSeconds = parseDuration(durationStr);
        if (durationSeconds <= 0) {
            errorHandler.sendError(sender, com.nova.chat.client.error.ErrorCode.INVALID_DURATION,
                I18n.tr(playerIdOf(sender), "chat.mute.invalid_duration", durationStr),
                I18n.tr(playerIdOf(sender), "chat.mute.duration_hint"));
            return true;
        }

        // Check if target player exists locally; if not, still send the packet
        // with the target name so the backend can resolve across all servers.
        Player target = parsePlayer(targetName);
        String targetId = (target != null) ? target.getUniqueId().toString() : null;

        // Create and send mute packet
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.MUTE, channelId);
        if (sender instanceof Player) {
            packet.addExtra("operatorId", ((Player) sender).getUniqueId().toString());
        } else {
            // Console/RCON: use a well-known console UUID so the backend can
            // grant SUPER_ADMIN permission for console-originated moderation.
            packet.addExtra("operatorId", "00000000-0000-0000-0000-000000000000");
            packet.addExtra("console", "true");
        }
        packet.addExtra("operatorName", sender.getName());
        if (targetId != null) {
            packet.addExtra("targetId", targetId);
        }
        packet.addExtra("targetName", targetName);
        packet.addExtra("duration", String.valueOf(durationSeconds));

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender,
                    I18n.tr(playerIdOf(sender), "chat.mute.progress", targetName, channelId, formatDuration(durationSeconds)));
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
            return Arrays.asList("30s", "10m", "1h", "1d");
        }
        if (args.length == 3) {
            List<String> known = getKnownChannelIds(args[2]);
            if (!known.isEmpty()) {
                return known;
            }
            return Arrays.asList("global", "local");
        }
        return Collections.emptyList();
    }
}
