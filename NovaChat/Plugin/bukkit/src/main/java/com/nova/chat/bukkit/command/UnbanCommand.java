package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Unban command - lifts a ban so the player may rejoin.
 *
 * <p>Differs from every other moderation command in one respect that shapes the
 * whole shell: the target is usually offline. A ban exists precisely to keep
 * someone out while they are away, so looking the target up among online
 * players would make this command useless exactly when it is needed.
 *
 * <p>It therefore accepts a UUID as well as a name. A name is passed through
 * for the authority to resolve, but when the target is offline and only a name
 * is known the command says so instead of failing silently - the operator's
 * next step is to use the UUID from the backend records.
 */
public class UnbanCommand extends AbstractSubCommand {

    public UnbanCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "unban";
    }

    @Override
    public String getDescription() {
        return "解除封禁";
    }

    @Override
    public String getUsage() {
        return "/nc unban <玩家|UUID> [频道ID]";
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
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        String targetArg = args[0];
        // Omitted channel lifts the network-wide ban, matching /nc ban.
        String channelId = args.length > 1 ? args[1] : null;

        String targetId = null;
        UUID parsed = parseUuid(targetArg);
        if (parsed != null) {
            targetId = parsed.toString();
        } else {
            Player target = parsePlayer(targetArg);
            if (target != null) {
                targetId = target.getUniqueId().toString();
            }
        }

        if (targetId == null) {
            // The authority resolves online players by name, so a name is still
            // worth sending: the target may be on another sub-server. Only warn
            // about the offline case, which is the one that cannot succeed.
            messageHelper.sendSuggestion(sender,
                    I18n.tr(playerIdOf(sender), "chat.unban.offline_hint", targetArg));
        }

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.UNBAN, channelId);
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
        packet.addExtra("targetName", targetArg);

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, I18n.tr(playerIdOf(sender),
                    "chat.unban.progress", targetArg,
                    channelId != null ? channelId : "all channels"));
        } else {
            errorHandler.sendRequestFailed(sender);
        }

        return true;
    }

    /**
     * @param value a command argument
     * @return the parsed UUID, or null when it is not one
     */
    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
