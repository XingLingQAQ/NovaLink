package com.nova.chat.folia.command;

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
 * Title command - allows admins to send title messages to channel players.
 *
 * <p>Mirrors the Bukkit shell: same permission node, same packet action and
 * extras, same i18n keys. The local {@code novachat.title} node is only a
 * coarse client-side gate; the backend requires a super-admin session.
 */
public class TitleCommand extends AbstractSubCommand {

    public TitleCommand(NovaChatFolia plugin) {
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
        // Console/RCON can send titles too, carrying the all-zeros sentinel UUID
        // for the backend's super-admin gate.
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

        AdminActionPacket packet = new AdminActionPacket();
        packet.setAction(AdminAction.STATUS); // Backend distinguishes via the "type" extra
        if (sender instanceof Player) {
            packet.setPlayerId(((Player) sender).getUniqueId());
        } else {
            packet.setPlayerId(java.util.UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID));
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
        if (args.length == 2) {
            return Collections.singletonList("<标题>");
        }
        return Collections.emptyList();
    }
}
