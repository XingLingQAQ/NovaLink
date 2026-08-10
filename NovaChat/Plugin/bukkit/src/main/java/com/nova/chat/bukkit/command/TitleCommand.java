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
 * Title command - allows admins to send title messages to channel players.
 * 
 * Requirements: 15
 */
public class TitleCommand extends AbstractSubCommand {

    public TitleCommand(NovaChatBukkit plugin) {
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
        // Console/RCON can also send titles (uses the all-zeros sentinel UUID so
        // the backend can route the broadcast). The super-admin auth session is no
        // longer required for TITLE (see AdminActionHandler.handleStatus).
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messageHelper.sendUsage(sender, getUsage());
            messageHelper.sendSuggestion(sender, I18n.tr(playerIdOf(sender), "chat.title.color_hint"));
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        String channelId = args[0];
        String title = args[1];
        String subtitle = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";

        // Create admin action packet for title
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
        packet.addExtra("type", "TITLE");
        packet.addExtra("operatorName", sender.getName());
        packet.addExtra("title", title);
        packet.addExtra("subtitle", subtitle);

        if (sendPacket(packet)) {
            messageHelper.sendMessage(sender, I18n.tr(playerIdOf(sender), "chat.title.progress", channelId));
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
        if (args.length == 2) {
            return Collections.singletonList("<标题>");
        }
        return Collections.emptyList();
    }
}
