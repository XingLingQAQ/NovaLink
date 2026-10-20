package com.nova.chat.mod.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.ConsoleCapableCommand;

import java.util.List;
import java.util.UUID;

/**
 * Title command — sends a title/subtitle to a channel's players
 * ({@code /nc title <channel> <title> [subtitle...]}).
 *
 * <p>Mirrors the bukkit/folia shells: same {@code novachat.title} node, and an
 * {@link AdminActionPacket} carrying {@link AdminAction#STATUS} with the
 * operation distinguished by the {@code type} extra ({@code TITLE}), plus
 * {@code operatorName}/{@code title}/{@code subtitle}; console senders carry
 * the all-zero sentinel UUID and the {@code console} extra. A missing subtitle
 * is sent as an empty string, matching the references.
 *
 * <p><b>Requires a backend super-admin session.</b> The backend gates every
 * STATUS action on {@code PermissionManager#hasSuperAdminSession} and answers
 * NC-403 otherwise. On mod servers that session can only be obtained through
 * {@code /nc auth <password>}, which this module now also provides. Without it
 * this command sends, then fails — the failure is reported back to the sender.
 */
public class TitleCommand extends AbstractModerationCommand implements ConsoleCapableCommand {

    @Override
    public String getPermissionNode() {
        return "novachat.title";
    }

    @Override
    public boolean execute(String[] args, CommandContext context) {
        if (!requireAdmin(context)) {
            return false;
        }
        if (args.length < 2) {
            sendUsage(context);
            sendHint(context, I18n.tr(context.getPlayerId(), "chat.title.color_hint"));
            return false;
        }
        if (!requireConnection(context)) {
            return false;
        }

        String channelId = args[0];
        String title = args[1];
        String subtitle = args.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                : "";

        AdminActionPacket packet = new AdminActionPacket();
        packet.setAction(AdminAction.STATUS); // Backend distinguishes via the "type" extra
        if (isConsole(context)) {
            packet.setPlayerId(UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID));
            packet.addExtra("console", "true");
        } else {
            packet.setPlayerId(context.getPlayerId());
        }
        packet.setTarget(channelId);
        packet.addExtra("type", "TITLE");
        packet.addExtra("operatorName", context.getPlayerName());
        packet.addExtra("title", title);
        packet.addExtra("subtitle", subtitle);

        if (!sendPacket(context, packet)) {
            sendRequestFailed(context);
            return false;
        }
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.title.progress", channelId));
        return true;
    }

    @Override
    public String getDescription() {
        return "Send a title to a channel (super admin)";
    }

    @Override
    public String getUsage() {
        return "/nc title <channel> <title> [subtitle]";
    }

    @Override
    public List<String> tabComplete(String[] args) {
        return List.of();
    }
}
