package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Mute command - mutes a player in a channel for a bounded duration.
 *
 * <p>Same packet shape as the Bukkit/Folia shells: {@link ChannelAction#MUTE}
 * with {@code operatorId}/{@code operatorName}/{@code targetId}/
 * {@code targetName}/{@code duration} extras, the all-zeros console sentinel
 * plus {@code console=true} for a console sender. A channel is mandatory: an
 * explicit third argument wins, otherwise the sender's active channel is used;
 * console has no active channel so it must name one.
 *
 * <p>An optional trailing reason is forwarded as the {@code reason} extra the
 * backend's mute manager records. Omitting it leaves the backend default.
 */
public class MuteCommand extends AbstractSubCommand {

    public MuteCommand(NovaChatPNX plugin) {
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
        return "/nc mute <玩家> <时间> [频道ID] [原因...]";
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
            sendUsage(sender, getUsage());
            sendSuggestion(sender, I18n.tr(playerIdOf(sender), "chat.mute.duration_hint"));
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

        long durationSeconds = parseDuration(durationStr);
        if (durationSeconds <= 0) {
            sendError(sender, ErrorCode.INVALID_DURATION,
                    I18n.tr(playerIdOf(sender), "chat.mute.invalid_duration", durationStr),
                    I18n.tr(playerIdOf(sender), "chat.mute.duration_hint"));
            return true;
        }

        // The target may be on another sub-server; the name is always sent so
        // the backend can resolve it there.
        String targetId = resolveTargetId(targetName);

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.MUTE, channelId);
        addOperatorExtras(packet, sender);
        if (targetId != null) {
            packet.addExtra("targetId", targetId);
        }
        packet.addExtra("targetName", targetName);
        packet.addExtra("duration", String.valueOf(durationSeconds));
        String reason = joinArgs(args, 3);
        if (reason != null) {
            packet.addExtra("reason", reason);
        }

        if (sendPacket(packet)) {
            sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.mute.progress",
                    targetName, channelId, formatDuration(durationSeconds)));
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
