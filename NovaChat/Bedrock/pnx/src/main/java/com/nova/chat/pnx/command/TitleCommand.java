package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;
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
 * Title command - sends a title to the players of a channel.
 *
 * <p>Same packet shape as the Bukkit/Folia shells: {@link AdminActionPacket}
 * with {@link AdminAction#STATUS} plus a {@code type=TITLE} extra carrying
 * {@code title}/{@code subtitle}. The backend gates STATUS subtypes on a
 * super-admin session and answers NC-403 otherwise; the response is rendered
 * by {@link com.nova.chat.pnx.network.NetworkClient#handleAdminActionResponse}
 * as the "run /nc auth" guidance.
 */
public class TitleCommand extends AbstractSubCommand {

    public TitleCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "title";
    }

    @Override
    public String getDescription() {
        return "向频道玩家发送Title消息";
    }

    @Override
    public String getUsage() {
        return "/nc title <频道ID> <标题> [副标题]";
    }

    @Override
    public String getPermission() {
        return "novachat.title";
    }

    @Override
    public boolean isPlayerOnly() {
        // Console/RCON can send titles too, carrying the all-zeros sentinel UUID
        // for the backend's super-admin gate.
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender, getUsage());
            sendSuggestion(sender, I18n.tr(playerIdOf(sender), "chat.title.color_hint"));
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        String channelId = args[0];
        String title = args[1];
        String subtitle = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "";

        AdminActionPacket packet = new AdminActionPacket();
        packet.setAction(AdminAction.STATUS); // Backend distinguishes via the "type" extra
        if (isPlayer(sender)) {
            packet.setPlayerId(getPlayer(sender).getUniqueId());
        } else {
            packet.setPlayerId(UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID));
            packet.addExtra("console", "true");
        }
        packet.setTarget(channelId);
        packet.addExtra("type", "TITLE");
        packet.addExtra("operatorName", sender.getName());
        packet.addExtra("title", title);
        packet.addExtra("subtitle", subtitle);

        if (sendPacket(packet)) {
            sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.title.progress", channelId));
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
        if (args.length == 2) {
            return Collections.singletonList("<标题>");
        }
        return Collections.emptyList();
    }
}
