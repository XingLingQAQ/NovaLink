package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Accept command - allows players to accept channel invitations.
 * 
 * Requirements: 8
 */
public class AcceptCommand extends AbstractSubCommand {

    public AcceptCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "accept";
    }

    @Override
    public String getDescription() {
        return "接受频道邀请";
    }

    @Override
    public String getUsage() {
        return "/nc accept <邀请码>";
    }

    @Override
    public String getPermission() {
        return "novachat.accept";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
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

        Player player = (Player) sender;
        String inviteCode = args[0].toUpperCase();

        // Validate invite code format (6 alphanumeric characters)
        if (!inviteCode.matches("^[A-Z0-9]{6}$")) {
            errorHandler.sendError(sender, com.nova.chat.bukkit.error.ErrorCode.INVALID_FORMAT, 
                "无效的邀请码格式", "邀请码应为6位字母数字组合");
            return true;
        }

        // Create and send accept packet
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.ACCEPT, inviteCode);
        packet.addExtra("playerId", player.getUniqueId().toString());
        packet.addExtra("playerName", player.getName());
        packet.addExtra("world", player.getWorld().getName());

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, "正在验证邀请码 &e" + inviteCode + "&7...");
        } else {
            errorHandler.sendRequestFailed(sender);
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("<邀请码>");
        }
        return Collections.emptyList();
    }
}
