package com.nova.chat.mod.command;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.ConsoleCapableCommand;

import java.util.List;

/**
 * Kick command — removes a player from a channel
 * ({@code /nc kick <player> [channel]}).
 *
 * <p>Mirrors the bukkit/folia shells: same {@code novachat.kick} node, same
 * {@link ChannelAction#KICK} packet and extras, same i18n keys. A kick is
 * inherently per-channel, so an omitted channel falls back to the sender's
 * active channel.
 */
public class KickCommand extends AbstractModerationCommand implements ConsoleCapableCommand {

    @Override
    public String getPermissionNode() {
        return "novachat.kick";
    }

    @Override
    public boolean execute(String[] args, CommandContext context) {
        if (!requireAdmin(context)) {
            return false;
        }
        if (args.length < 1) {
            sendUsage(context);
            return false;
        }
        if (!requireConnection(context)) {
            return false;
        }

        String targetName = args[0];
        String channelId = args.length > 1 ? args[1] : activeChannel(context);
        if (channelId == null) {
            sendError(context, ErrorCode.BAD_REQUEST,
                    I18n.tr(context.getPlayerId(), "chat.command.specify_channel"));
            return false;
        }

        String targetId = resolveTargetId(context.getPlatform(), targetName);

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.KICK, channelId);
        applyOperatorExtras(packet, context);
        if (targetId != null) {
            packet.addExtra("targetId", targetId);
        }
        packet.addExtra("targetName", targetName);

        if (!sendPacket(context, packet)) {
            sendRequestFailed(context);
            return false;
        }
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.kick.progress",
                targetName, channelId));
        return true;
    }

    @Override
    public String getDescription() {
        return "Kick a player from a channel";
    }

    @Override
    public String getUsage() {
        return "/nc kick <player> [channel]";
    }

    @Override
    public List<String> tabComplete(String[] args) {
        return List.of();
    }
}
