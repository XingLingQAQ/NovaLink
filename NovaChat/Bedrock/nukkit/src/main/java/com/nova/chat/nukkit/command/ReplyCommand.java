package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.PrivateMessageCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.network.NetworkClient;

import java.util.List;

/**
 * Reply command — whispers to the most recent private-message partner
 * ({@code /nc r <message...>}).
 *
 * <p>The reply target is tracked by the shared
 * {@link com.nova.chat.client.privatemsg.PrivateMessageService} (updated on
 * both sent and received private messages); validation and receipt copy live
 * in {@link PrivateMessageCommandService}.
 */
public class ReplyCommand extends AbstractSubCommand {

    public ReplyCommand(NovaChatNukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "r";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.reply");
    }

    @Override
    public String getUsage() {
        return "/nc r <message>";
    }

    @Override
    public String getPermission() {
        return null; // No permission required
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sendError(sender, I18n.tr("chat.command.player_only"));
            return true;
        }
        Player player = (Player) sender;

        List<String> lines = PrivateMessageCommandService.reply(
                plugin.getPrivateMessageService(),
                this::sendPrivateMessagePacket,
                player.getUniqueId(), player.getName(),
                plugin.getNovaChatConfig().getUsername(), args);
        for (String line : lines) {
            messageHelper.sendRawMessage(sender, line);
        }
        return true;
    }

    /** Transmits a private-message packet when the backend link is up. */
    private boolean sendPrivateMessagePacket(PrivateMessagePacket packet) {
        NetworkClient client = plugin.getNetworkClient();
        if (client == null || !client.isConnected()) {
            return false;
        }
        client.sendPacket(packet);
        return true;
    }
}
