package com.nova.chat.folia.command;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.folia.NovaChatFolia;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Announce command - allows admins to send announcements to channels.
 *
 * <p>Mirrors the Bukkit shell: same permission node, same packet action and
 * extras, same i18n keys. The local {@code novachat.announce} node is only a
 * coarse client-side gate; the backend requires a super-admin session.
 */
public class AnnounceCommand extends AbstractSubCommand {

    public AnnounceCommand(NovaChatFolia plugin) {
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
            messageHelper.sendUsage(sender, getUsage());
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
        if (sender instanceof Player) {
            packet.setPlayerId(((Player) sender).getUniqueId());
        } else {
            packet.setPlayerId(java.util.UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID));
            packet.addExtra("console", "true");
        }
        packet.setTarget(channelId);
        packet.addExtra("type", "ANNOUNCE");
        packet.addExtra("operatorName", sender.getName());
        packet.addExtra("content", content);

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.announce.progress", channelId));
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
