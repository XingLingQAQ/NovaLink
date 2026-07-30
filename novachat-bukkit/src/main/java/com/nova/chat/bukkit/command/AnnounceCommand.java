package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
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
        // Backend requires a player UUID (super admin session is UUID-based).
        return true;
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
                "公告内容不能为空");
            return true;
        }

        // Create admin action packet for announcement
        AdminActionPacket packet = new AdminActionPacket();
        packet.setAction(AdminAction.STATUS); // Reuse STATUS for now, backend will handle
        packet.setPlayerId(((Player) sender).getUniqueId());
        packet.setTarget(channelId);
        packet.addExtra("type", "ANNOUNCE");
        packet.addExtra("operatorName", sender.getName());
        packet.addExtra("content", content);

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, "正在发送公告到频道 &e" + channelId + "&7...");
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
