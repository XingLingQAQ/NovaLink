package com.nova.chat.multipaper.command;

import com.nova.chat.multipaper.NovaChatMultiPaper;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Join command - allows players to join a channel.
 */
public class JoinCommand extends AbstractSubCommand {

    public JoinCommand(NovaChatMultiPaper plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "join";
    }

    @Override
    public String getDescription() {
        return "加入一个频道";
    }

    @Override
    public String getUsage() {
        return "/nc join <频道ID> [密码]";
    }

    @Override
    public String getPermission() {
        return "novachat.join";
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
        String channelId = args[0];
        String password = args.length > 1 ? args[1] : "";

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, channelId, password);
        packet.addExtra("playerId", player.getUniqueId().toString());
        packet.addExtra("playerName", player.getName());
        packet.addExtra("world", player.getWorld().getName());

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, "正在加入频道 &e" + channelId + "&7...");
        } else {
            messageHelper.sendError(sender, "发送请求失败");
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("global", "local");
        }
        return Collections.emptyList();
    }
}
