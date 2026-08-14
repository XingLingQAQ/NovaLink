package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.PrivateMessageCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.network.NetworkClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Private message command — whispers to a player anywhere on the network
 * ({@code /nc msg <player> <message...>}).
 *
 * <p>Validation, packet construction and receipt copy live in the shared
 * {@link PrivateMessageCommandService}; this shell forwards arguments and
 * sends the returned lines. The success confirmation is rendered from the
 * backend echo (see {@code NetworkClient#handlePrivateMessage}).
 */
public class MsgCommand extends AbstractSubCommand {

    public MsgCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "msg";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.msg");
    }

    @Override
    public String getUsage() {
        return "/nc msg <player> <message>";
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

        List<String> lines = PrivateMessageCommandService.msg(
                this::sendPrivateMessagePacket,
                player.getUniqueId(), player.getName(),
                plugin.getNovaChatConfig().getBackendUsername(), args);
        for (String line : lines) {
            sendMessage(sender, line);
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

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> completions = new ArrayList<>();
            for (Player online : plugin.getServer().getOnlinePlayers().values()) {
                if (online.getName().toLowerCase().startsWith(prefix)) {
                    completions.add(online.getName());
                }
            }
            return completions;
        }
        return null;
    }
}
