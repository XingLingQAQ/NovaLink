package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Announce command - sends an announcement to a channel.
 *
 * <p>Same packet shape as the Bukkit/Folia shells: {@link AdminActionPacket}
 * with {@link AdminAction#STATUS} plus a {@code type=ANNOUNCE} extra. The
 * backend gates STATUS subtypes on a super-admin session and answers NC-403
 * otherwise; the response is rendered by
 * {@link com.nova.chat.pnx.network.NetworkClient#handleAdminActionResponse}
 * as the "run /nc auth" guidance. The local {@code novachat.announce} node is
 * only a coarse client-side gate.
 */
public class AnnounceCommand extends AbstractSubCommand {

    public AnnounceCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "announce";
    }

    @Override
    public String getDescription() {
        return "向频道发送公告";
    }

    @Override
    public String getUsage() {
        return "/nc announce <频道ID> <内容>";
    }

    @Override
    public String getPermission() {
        return "novachat.announce";
    }

    @Override
    public boolean isPlayerOnly() {
        // Console/RCON can announce too, carrying the all-zeros sentinel UUID
        // for the backend's super-admin gate.
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender, getUsage());
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        String channelId = args[0];
        String content = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        if (content.isEmpty()) {
            sendError(sender, ErrorCode.BAD_REQUEST,
                    I18n.tr(playerIdOf(sender), "chat.announce.empty"));
            return true;
        }

        AdminActionPacket packet = new AdminActionPacket();
        packet.setAction(AdminAction.STATUS); // Backend distinguishes via the "type" extra
        if (isPlayer(sender)) {
            packet.setPlayerId(getPlayer(sender).getUniqueId());
        } else {
            packet.setPlayerId(UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID));
            packet.addExtra("console", "true");
        }
        packet.setTarget(channelId);
        packet.addExtra("type", "ANNOUNCE");
        packet.addExtra("operatorName", sender.getName());
        packet.addExtra("content", content);

        if (sendPacket(packet)) {
            sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.announce.progress", channelId));
        } else {
            sendRequestFailed(sender);
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> known = getKnownChannelIds(args[0]);
            if (!known.isEmpty()) {
                return known;
            }
            return Arrays.asList("global", "local");
        }
        return Collections.emptyList();
    }
}
