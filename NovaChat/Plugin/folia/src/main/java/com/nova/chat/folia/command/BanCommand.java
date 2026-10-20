package com.nova.chat.folia.command;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.folia.NovaChatFolia;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Ban command - removes a player from a channel and blocks them from rejoining.
 *
 * <p>Mirrors the Bukkit shell: same permission node, same packet action and
 * extras, same i18n keys. Omitting the channel bans across the whole network
 * rather than falling back to the sender's active channel — narrowing a global
 * ban to one channel silently would be worse than requiring the operator to
 * name the channel.
 */
public class BanCommand extends AbstractSubCommand {

    public BanCommand(NovaChatFolia plugin) {
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
        return "/nc ban <玩家> [时长] [频道ID]";
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
            messageHelper.sendUsage(sender, getUsage());
            messageHelper.sendSuggestion(sender, I18n.tr(playerIdOf(sender), "chat.ban.duration_hint"));
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        String targetName = args[0];

        // The channel is optional: leaving it out means a network-wide ban,
        // matching the backend, where a null channel id is the global scope.
        String channelId = null;
        String duration = null;
        if (args.length > 1) {
            if (isDuration(args[1])) {
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
                sendError(sender, ErrorCode.INVALID_DURATION,
                        I18n.tr(playerIdOf(sender), "chat.ban.invalid_duration", duration),
                        I18n.tr(playerIdOf(sender), "chat.ban.duration_hint"));
                return true;
            }
        }

        // No fallback to the sender's active channel: a ban is not inherently
        // per-channel, so an omitted channel is deliberately global.

        Player target = parsePlayer(targetName);
        String targetId = (target != null) ? target.getUniqueId().toString() : null;

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.BAN, channelId);
        if (sender instanceof Player) {
            String playerId = ((Player) sender).getUniqueId().toString();
            packet.addExtra("operatorId", playerId);
            // Correlates the asynchronous response back to this sender; see
            // ChannelResponseTracker (it keys the pending entry on playerId).
            packet.addExtra("playerId", playerId);
        } else {
            packet.addExtra("operatorId", NovaConstants.CONSOLE_SENTINEL_UUID);
            // Sentinel playerId so the outcome is logged rather than dropped.
            packet.addExtra("playerId", NovaConstants.CONSOLE_SENTINEL_UUID);
            packet.addExtra("console", "true");
        }
        packet.addExtra("operatorName", sender.getName());
        if (targetId != null) {
            packet.addExtra("targetId", targetId);
        }
        packet.addExtra("targetName", targetName);
        if (durationSeconds > 0) {
            packet.addExtra("duration", String.valueOf(durationSeconds));
        }

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, I18n.tr(playerIdOf(sender),
                    "chat.ban.progress", targetName,
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
