package com.nova.chat.nukkit.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.nukkit.NovaChatNukkit;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Kick command - removes a player from a channel.
 *
 * <p>Same packet shape as the Bukkit/Folia shells: {@link ChannelAction#KICK}
 * with the operator/target extras. A kick is inherently per-channel, so an
 * omitted channel falls back to the sender's active channel; console must name
 * one.
 */
public class KickCommand extends AbstractSubCommand {

    public KickCommand(NovaChatNukkit plugin) {
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
            sendUsage(sender, getUsage());
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        String targetName = args[0];
        String channelId = null;

        if (args.length > 1) {
            channelId = args[1];
        } else if (isPlayer(sender)) {
            PlayerChannelState state = getPlayerState(getPlayer(sender));
            if (state != null && state.getActiveChannel() != null) {
                channelId = state.getActiveChannel();
            }
        }

        if (channelId == null) {
            sendError(sender, ErrorCode.BAD_REQUEST,
                    I18n.tr(playerIdOf(sender), "chat.command.specify_channel"));
            return true;
        }

        String targetId = resolveTargetId(targetName);

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.KICK, channelId);
        addOperatorExtras(packet, sender);
        if (targetId != null) {
            packet.addExtra("targetId", targetId);
        }
        packet.addExtra("targetName", targetName);

        if (sendPacket(packet)) {
            sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.kick.progress", targetName, channelId));
        } else {
            sendRequestFailed(sender);
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
