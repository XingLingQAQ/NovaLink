package com.nova.chat.mod.command;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.ConsoleCapableCommand;

import java.util.List;
import java.util.UUID;

/**
 * Announce command — broadcasts an announcement to a channel
 * ({@code /nc announce <channel> <content...>}).
 *
 * <p>Mirrors the bukkit/folia shells: same {@code novachat.announce} node, and
 * an {@link AdminActionPacket} carrying {@link AdminAction#STATUS} with the
 * operation distinguished by the {@code type} extra ({@code ANNOUNCE}), plus
 * {@code operatorName}/{@code content}; console senders carry the all-zero
 * sentinel UUID and the {@code console} extra.
 *
 * <p><b>Requires a backend super-admin session.</b> The backend gates every
 * STATUS action on {@code PermissionManager#hasSuperAdminSession} and answers
 * NC-403 otherwise. On mod servers that session can only be obtained through
 * {@code /nc auth <password>}, which this module now also provides. Without it
 * this command sends, then fails — the failure is reported back to the sender.
 */
public class AnnounceCommand extends AbstractModerationCommand implements ConsoleCapableCommand {

    @Override
    public String getPermissionNode() {
        return "novachat.announce";
    }

    @Override
    public boolean execute(String[] args, CommandContext context) {
        if (!requireAdmin(context)) {
            return false;
        }
        if (args.length < 2) {
            sendUsage(context);
            return false;
        }
        if (!requireConnection(context)) {
            return false;
        }

        String channelId = args[0];
        String content = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        if (content.isEmpty()) {
            sendError(context, ErrorCode.BAD_REQUEST,
                    I18n.tr(context.getPlayerId(), "chat.announce.empty"));
            return false;
        }

        AdminActionPacket packet = new AdminActionPacket();
        packet.setAction(AdminAction.STATUS); // Backend distinguishes via the "type" extra
        if (isConsole(context)) {
            packet.setPlayerId(UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID));
            packet.addExtra("console", "true");
        } else {
            packet.setPlayerId(context.getPlayerId());
        }
        packet.setTarget(channelId);
        packet.addExtra("type", "ANNOUNCE");
        packet.addExtra("operatorName", context.getPlayerName());
        packet.addExtra("content", content);

        if (!sendPacket(context, packet)) {
            sendRequestFailed(context);
            return false;
        }
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.announce.progress", channelId));
        return true;
    }

    @Override
    public String getDescription() {
        return "Send an announcement to a channel (super admin)";
    }

    @Override
    public String getUsage() {
        return "/nc announce <channel> <content>";
    }

    @Override
    public List<String> tabComplete(String[] args) {
        return List.of();
    }
}
