package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.command.PrivateMessageCommandService;
import com.nova.chat.client.i18n.I18n;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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

    public MsgCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "msg";
    }

    @Override
    public String getDescription() {
        return "向全网任意玩家发送私聊";
    }

    @Override
    public String getUsage() {
        return "/nc msg <玩家名> <消息>";
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
            messageHelper.sendError(sender, I18n.tr(playerIdOf(sender), "chat.command.player_only"));
            return true;
        }
        Player player = (Player) sender;

        List<String> lines = PrivateMessageCommandService.msg(
                this::sendPacket,
                player.getUniqueId(), player.getName(),
                plugin.getNovaChatConfig().getUsername(), args);
        for (String line : lines) {
            messageHelper.sendRaw(sender, line);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return getOnlinePlayerNames(args[0]);
        }
        return null;
    }
}
