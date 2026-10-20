package com.nova.chat.mod.command;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.ConsoleCapableCommand;

import java.util.List;

/**
 * Ban command — removes a player from a channel and blocks a rejoin
 * ({@code /nc ban <player> [duration] [channel]}).
 *
 * <p>Mirrors the bukkit/folia shells: same {@code novachat.ban} node, same
 * {@link ChannelAction#BAN} packet and extras, same i18n keys. The channel is
 * optional and a missing one means a network-wide ban: silently narrowing a
 * global ban to the sender's active channel would be worse than requiring the
 * operator to name the channel, so there is deliberately no fallback here
 * (unlike {@code kick}/{@code mute}). The duration and channel tokens may be
 * given in either order.
 */
public class BanCommand extends AbstractModerationCommand implements ConsoleCapableCommand {

    @Override
    public String getPermissionNode() {
        return "novachat.ban";
    }

    @Override
    public boolean execute(String[] args, CommandContext context) {
        if (!requireAdmin(context)) {
            return false;
        }
        if (args.length < 1) {
            sendUsage(context);
            sendHint(context, I18n.tr(context.getPlayerId(), "chat.ban.duration_hint"));
            return false;
        }
        if (!requireConnection(context)) {
            return false;
        }

        String targetName = args[0];

        String channelId = null;
        String duration = null;
        if (args.length > 1) {
            if (isDurationToken(args[1])) {
                duration = args[1];
                if (args.length > 2) {
                    channelId = args[2];
                }
            } else {
                channelId = args[1];
                if (args.length > 2) {
                    duration = args[2];
                }
            }
        }

        long durationSeconds = 0L;
        if (duration != null) {
            durationSeconds = parseDuration(duration);
            if (durationSeconds <= 0) {
                sendError(context, ErrorCode.INVALID_DURATION,
                        I18n.tr(context.getPlayerId(), "chat.ban.invalid_duration", duration),
                        I18n.tr(context.getPlayerId(), "chat.ban.duration_hint"));
                return false;
            }
        }

        String targetId = resolveTargetId(context.getPlatform(), targetName);

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.BAN, channelId);
        applyOperatorExtras(packet, context);
        if (targetId != null) {
            packet.addExtra("targetId", targetId);
        }
        packet.addExtra("targetName", targetName);
        if (durationSeconds > 0) {
            packet.addExtra("duration", String.valueOf(durationSeconds));
        }

        if (!sendPacket(context, packet)) {
            sendRequestFailed(context);
            return false;
        }
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.ban.progress",
                targetName, channelId != null ? channelId : "all channels"));
        return true;
    }

    @Override
    public String getDescription() {
        return "Ban a player from a channel or the whole network";
    }

    @Override
    public String getUsage() {
        return "/nc ban <player> [duration] [channel]";
    }

    @Override
    public List<String> tabComplete(String[] args) {
        return List.of();
    }
}
