package com.nova.chat.pnx.command;

import org.powernukkitx.command.CommandSender;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Unban command - lifts a ban so the player may rejoin.
 *
 * <p>Same packet shape as the Bukkit/Folia shells: {@link ChannelAction#UNBAN}
 * with the operator/target extras. The target is usually offline, which is the
 * point of a ban, so the command accepts a UUID as well as a name; a name is
 * still sent for the backend to resolve across every sub-server. An omitted
 * channel lifts the network-wide ban, matching {@code /nc ban}.
 */
public class UnbanCommand extends AbstractSubCommand {

    public UnbanCommand(NovaChatPNX plugin) {
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
            sendUsage(sender, getUsage());
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        String targetArg = args[0];
        String channelId = args.length > 1 ? args[1] : null;

        String targetId = parseUuid(targetArg);
        if (targetId == null) {
            targetId = resolveTargetId(targetArg);
        }
        if (targetId == null) {
            // The backend resolves online players by name, so a name is still
            // worth sending: the target may be on another sub-server. Warn only
            // about the case that cannot succeed locally.
            sendSuggestion(sender,
                    I18n.tr(playerIdOf(sender), "chat.unban.offline_hint", targetArg));
        }

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.UNBAN, channelId);
        addOperatorExtras(packet, sender);
        if (targetId != null) {
            packet.addExtra("targetId", targetId);
        }
        packet.addExtra("targetName", targetArg);

        if (sendPacket(packet)) {
            sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.unban.progress", targetArg,
                    channelId != null ? channelId : "all channels"));
        } else {
            sendRequestFailed(sender);
        }

        return true;
    }

    /**
     * @param value a command argument
     * @return the UUID string when it parses, otherwise null
     */
    private static String parseUuid(String value) {
        try {
            return UUID.fromString(value).toString();
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
