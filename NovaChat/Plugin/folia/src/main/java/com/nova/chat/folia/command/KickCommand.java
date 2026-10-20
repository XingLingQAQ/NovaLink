package com.nova.chat.folia.command;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
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
 * Kick command - allows admins to kick players from channels.
 *
 * <p>Mirrors the Bukkit shell: same permission node, same packet action and
 * extras, same i18n keys. A kick is per-channel, so an omitted channel falls
 * back to the sender's active channel.
 */
public class KickCommand extends AbstractSubCommand {

    public KickCommand(NovaChatFolia plugin) {
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
            sendError(sender, ErrorCode.BAD_REQUEST,
                    I18n.tr(playerIdOf(sender), "chat.command.specify_channel"));
            return true;
        }

        // Check if target player exists locally; if not, still send the packet
        // with the target name so the backend can resolve across all servers.
        Player target = parsePlayer(targetName);
        String targetId = (target != null) ? target.getUniqueId().toString() : null;

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.KICK, channelId);
        if (sender instanceof Player) {
            String playerId = ((Player) sender).getUniqueId().toString();
            packet.addExtra("operatorId", playerId);
            // Correlates the asynchronous response back to this sender;
            // ChannelResponseTracker keys its pending entry on this extra.
            packet.addExtra("playerId", playerId);
        } else {
            // Console/RCON: use a well-known console UUID so the backend can
            // grant SUPER_ADMIN permission for console-originated moderation.
            // playerId mirrors operatorId so the outcome is logged.
            packet.addExtra("operatorId", NovaConstants.CONSOLE_SENTINEL_UUID);
            packet.addExtra("playerId", NovaConstants.CONSOLE_SENTINEL_UUID);
            packet.addExtra("console", "true");
        }
        packet.addExtra("operatorName", sender.getName());
        if (targetId != null) {
            packet.addExtra("targetId", targetId);
        }
        packet.addExtra("targetName", targetName);

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.kick.progress", targetName, channelId));
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
