package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Create command - allows players to create private channels.
 * 
 * Requirements: 7
 */
public class CreateCommand extends AbstractSubCommand {

    public CreateCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "create";
    }

    @Override
    public String getDescription() {
        return "创建一个私有频道";
    }

    @Override
    public String getUsage() {
        return "/nc create <名称> [密码]";
    }

    @Override
    public String getPermission() {
        return "novachat.create";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            messageHelper.sendUsage(sender, getUsage());
            messageHelper.sendSuggestion(sender, I18n.tr(playerIdOf(sender), "chat.create.suggestion"));
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        Player player = (Player) sender;
        String channelName = args[0];
        String password = args.length > 1 ? args[1] : "";

        // Validate channel name
        if (channelName.length() < 2 || channelName.length() > 16) {
            errorHandler.sendError(sender, com.nova.chat.client.error.ErrorCode.INVALID_FORMAT,
                I18n.tr(player.getUniqueId(), "chat.create.name_length"));
            return true;
        }

        if (!channelName.matches("^[a-zA-Z0-9_\\u4e00-\\u9fa5]+$")) {
            errorHandler.sendError(sender, com.nova.chat.client.error.ErrorCode.INVALID_FORMAT,
                I18n.tr(player.getUniqueId(), "chat.create.name_chars"));
            return true;
        }

        // Create and send create packet
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.CREATE, channelName, password);
        packet.addExtra("playerId", player.getUniqueId().toString());
        packet.addExtra("playerName", player.getName());
        packet.addExtra("displayName", channelName);

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, I18n.tr(player.getUniqueId(), "chat.create.progress", channelName));
            if (password.isEmpty()) {
                messageHelper.sendMessage(sender, I18n.tr(player.getUniqueId(), "chat.create.random_password"));
            }
        } else {
            errorHandler.sendRequestFailed(sender);
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Collections.singletonList("<频道名称>");
        }
        return Collections.emptyList();
    }
}
