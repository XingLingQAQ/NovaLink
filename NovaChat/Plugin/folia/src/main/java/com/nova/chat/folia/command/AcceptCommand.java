package com.nova.chat.folia.command;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.folia.NovaChatFolia;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Accept command - allows players to accept channel invitations.
 *
 * <p>Mirrors the Bukkit shell: same permission node, same packet action and
 * extras, same i18n keys and the same 6-character invite-code validation.
 */
public class AcceptCommand extends AbstractSubCommand {

    public AcceptCommand(NovaChatFolia plugin) {
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

        if (!inviteCode.matches("^[A-Z0-9]{6}$")) {
            sendError(sender, ErrorCode.INVALID_FORMAT,
                    I18n.tr(player.getUniqueId(), "chat.accept.invalid_format"),
                    I18n.tr(player.getUniqueId(), "chat.accept.invalid_hint"));
            return true;
        }

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.ACCEPT, inviteCode);
        packet.addExtra("playerId", player.getUniqueId().toString());
        packet.addExtra("playerName", player.getName());
        packet.addExtra("world", player.getWorld().getName());

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, I18n.tr(player.getUniqueId(), "chat.accept.progress", inviteCode));
        } else {
            sendRequestFailed(sender);
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
