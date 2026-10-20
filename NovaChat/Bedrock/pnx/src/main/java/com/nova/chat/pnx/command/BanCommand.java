package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Ban command - bans a player, optionally scoped to a channel and/or a
 * duration.
 *
 * <p>Same packet shape as the Bukkit/Folia shells: {@link ChannelAction#BAN}
 * with the operator/target extras. Omitting the channel means a network-wide
 * ban; unlike kick, a ban does <em>not</em> fall back to the sender's active
 * channel, because silently narrowing a global ban to one channel would be a
 * worse failure than making the operator name it. The duration and channel
 * arguments may come in either order, decided by {@link #isDuration}.
 */
public class BanCommand extends AbstractSubCommand {

    public BanCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "ban";
    }

    @Override
    public String getDescription() {
        return "封禁玩家";
    }

    @Override
    public String getUsage() {
        return "/nc ban <玩家> [时长] [频道ID] [原因...]";
    }

    @Override
    public String getPermission() {
        return "novachat.ban";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sendUsage(sender, getUsage());
            sendSuggestion(sender, I18n.tr(playerIdOf(sender), "chat.ban.duration_hint"));
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        String targetName = args[0];
        String channelId = null;
        String duration = null;
        int reasonStart = 1;

        // The second argument decides the shape; the third is taken as the
        // other slot without re-testing it, matching the Bukkit/Folia shells.
        // So "/nc ban Bob banana global" is an invalid duration, not a channel
        // named "banana".
        if (args.length > 1) {
            if (isDuration(args[1])) {
                duration = args[1];
                reasonStart = 2;
                if (args.length > 2) {
                    channelId = args[2];
                    reasonStart = 3;
                }
            } else {
                channelId = args[1];
                reasonStart = 2;
                if (args.length > 2) {
                    duration = args[2];
                    reasonStart = 3;
                }
            }
        }

        long durationSeconds = 0L;
        if (duration != null) {
            durationSeconds = parseDuration(duration);
            if (durationSeconds <= 0) {
                sendError(sender, ErrorCode.INVALID_DURATION,
                        I18n.tr(playerIdOf(sender), "chat.ban.invalid_duration", duration),
                        I18n.tr(playerIdOf(sender), "chat.ban.duration_hint"));
                return true;
            }
        }

        String targetId = resolveTargetId(targetName);

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.BAN, channelId);
        addOperatorExtras(packet, sender);
        if (targetId != null) {
            packet.addExtra("targetId", targetId);
        }
        packet.addExtra("targetName", targetName);
        if (durationSeconds > 0) {
            packet.addExtra("duration", String.valueOf(durationSeconds));
        }
        String reason = joinArgs(args, reasonStart);
        if (reason != null) {
            packet.addExtra("reason", reason);
        }

        if (sendPacket(packet)) {
            sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.ban.progress", targetName,
                    channelId != null ? channelId : "all channels"));
        } else {
            sendRequestFailed(sender);
        }

        return true;
    }

    /**
     * @param value a command argument
     * @return whether it looks like a duration rather than a channel id
     */
    private static boolean isDuration(String value) {
        return value.matches("\\d+[smhdw]?");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return getOnlinePlayerNames(args[0]);
        }
        if (args.length == 2) {
            return Arrays.asList("30m", "1h", "1d", "permanent");
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
