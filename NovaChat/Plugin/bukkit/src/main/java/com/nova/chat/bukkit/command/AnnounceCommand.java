package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Announce command - allows admins to send announcements to channels.
 * 
 * Requirements: 14
 */
public class AnnounceCommand extends AbstractSubCommand {

    public AnnounceCommand(NovaChatBukkit plugin) {
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
        // Console/RCON can also announce (uses the all-zeros sentinel UUID so the
        // backend can route the broadcast). The backend gates STATUS/ANNOUNCE
        // behind permissionManager.hasSuperAdminSession(playerId) and returns
        // NC-403 when absent (see AdminActionHandler.handleStatus), so the
        // sender must first run /nc auth <password> to establish a super-admin
        // session. The local novachat.announce permission (default: op) is only
        // a coarse client-side gate; the real authorization gate is the backend
        // super-admin session.
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messageHelper.sendUsage(sender, getUsage());
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        String channelId = args[0];
        String content = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        if (content.isEmpty()) {
            errorHandler.sendError(sender, com.nova.chat.client.error.ErrorCode.BAD_REQUEST,
                I18n.tr(playerIdOf(sender), "chat.announce.empty"));
            return true;
        }

        // Create admin action packet for announcement
        AdminActionPacket packet = new AdminActionPacket();
        packet.setAction(AdminAction.STATUS); // Reuse STATUS for now, backend will handle
        if (sender instanceof Player) {
            packet.setPlayerId(((Player) sender).getUniqueId());
        } else {
            // Console/RCON: use the well-known console sentinel UUID.
            packet.setPlayerId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"));
            packet.addExtra("console", "true");
        }
        packet.setTarget(channelId);
        packet.addExtra("type", "ANNOUNCE");
        packet.addExtra("operatorName", sender.getName());
        packet.addExtra("content", content);

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.announce.progress", channelId));
        } else {
            errorHandler.sendRequestFailed(sender);
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
