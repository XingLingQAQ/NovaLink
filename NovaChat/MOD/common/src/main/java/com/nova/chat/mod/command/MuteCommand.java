package com.nova.chat.mod.command;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.ConsoleCapableCommand;

import java.util.List;

/**
 * Mute command — silences a player in a channel for a duration
 * ({@code /nc mute <player> <duration> [channel]}).
 *
 * <p>Mirrors the bukkit/folia shells: same {@code novachat.mute} node, same
 * {@link ChannelAction#MUTE} packet and extras ({@code operatorId} /
 * {@code console} / {@code operatorName} / {@code targetId} / {@code targetName}
 * / {@code duration}), same i18n keys. An omitted channel falls back to the
 * sender's active channel; console has no active channel, so it must name one.
 */
public class MuteCommand extends AbstractModerationCommand implements ConsoleCapableCommand {

    @Override
    public String getPermissionNode() {
        return "novachat.mute";
    }

    @Override
    public boolean execute(String[] args, CommandContext context) {
        if (!requireAdmin(context)) {
            return false;
        }
        if (args.length < 2) {
            sendUsage(context);
            sendHint(context, I18n.tr(context.getPlayerId(), "chat.mute.duration_hint"));
            return false;
        }
        if (!requireConnection(context)) {
            return false;
        }

        String targetName = args[0];
        String durationStr = args[1];
        String channelId = args.length > 2 ? args[2] : activeChannel(context);

        if (channelId == null) {
            sendError(context, ErrorCode.BAD_REQUEST,
                    I18n.tr(context.getPlayerId(), "chat.command.specify_channel"));
            return false;
        }

        long durationSeconds = parseDuration(durationStr);
        if (durationSeconds <= 0) {
            sendError(context, ErrorCode.INVALID_DURATION,
                    I18n.tr(context.getPlayerId(), "chat.mute.invalid_duration", durationStr),
                    I18n.tr(context.getPlayerId(), "chat.mute.duration_hint"));
            return false;
        }

        // A target that is not local still travels by name so the backend can
        // resolve it across every sub-server, exactly like the references.
        String targetId = resolveTargetId(context.getPlatform(), targetName);

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.MUTE, channelId);
        applyOperatorExtras(packet, context);
        if (targetId != null) {
            packet.addExtra("targetId", targetId);
        }
        packet.addExtra("targetName", targetName);
        packet.addExtra("duration", String.valueOf(durationSeconds));

        if (!sendPacket(context, packet)) {
            sendRequestFailed(context);
            return false;
        }
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.mute.progress",
                targetName, channelId, formatDuration(durationSeconds)));
        return true;
    }

    @Override
    public String getDescription() {
        return "Mute a player in a channel";
    }

    @Override
    public String getUsage() {
        return "/nc mute <player> <duration> [channel]";
    }

    @Override
    public List<String> tabComplete(String[] args) {
        return List.of();
    }
}
