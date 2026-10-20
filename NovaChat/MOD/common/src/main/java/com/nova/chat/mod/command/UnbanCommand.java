package com.nova.chat.mod.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.ConsoleCapableCommand;

import java.util.List;

/**
 * Unban command — lifts a ban so the player may rejoin
 * ({@code /nc unban <player|UUID> [channel]}).
 *
 * <p>Mirrors the bukkit/folia shells: same {@code novachat.ban} node, same
 * {@link ChannelAction#UNBAN} packet and extras, same i18n keys. The target is
 * usually offline — that is the point of a ban — so a UUID is accepted, and an
 * unresolved name is still sent as {@code targetName} for the backend to
 * resolve across every sub-server.
 */
public class UnbanCommand extends AbstractModerationCommand implements ConsoleCapableCommand {

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
            return false;
        }
        if (!requireConnection(context)) {
            return false;
        }

        String targetArg = args[0];
        // Omitted channel lifts the network-wide ban, matching /nc ban.
        String channelId = args.length > 1 ? args[1] : null;

        String targetId = resolveTargetId(context.getPlatform(), targetArg);
        if (targetId == null) {
            // The authority resolves online players by name, so a name is still
            // worth sending; only warn about the offline case, which is the one
            // that cannot succeed from this server alone.
            sendHint(context, I18n.tr(context.getPlayerId(),
                    "chat.unban.offline_hint", targetArg));
        }

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.UNBAN, channelId);
        applyOperatorExtras(packet, context);
        if (targetId != null) {
            packet.addExtra("targetId", targetId);
        }
        packet.addExtra("targetName", targetArg);

        if (!sendPacket(context, packet)) {
            sendRequestFailed(context);
            return false;
        }
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.unban.progress",
                targetArg, channelId != null ? channelId : "all channels"));
        return true;
    }

    @Override
    public String getDescription() {
        return "Lift a ban on a player";
    }

    @Override
    public String getUsage() {
        return "/nc unban <player|uuid> [channel]";
    }

    @Override
    public List<String> tabComplete(String[] args) {
        return List.of();
    }
}
