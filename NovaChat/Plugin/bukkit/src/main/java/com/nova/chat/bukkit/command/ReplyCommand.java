package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.command.PrivateMessageCommandService;
import com.nova.chat.client.i18n.I18n;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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

    public ReplyCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "r";
    }

    @Override
    public String getDescription() {
        return "回复最近一次私聊";
    }

    @Override
    public String getUsage() {
        return "/nc r <消息>";
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

        List<String> lines = PrivateMessageCommandService.reply(
                plugin.getPrivateMessageService(),
                this::sendPacket,
                player.getUniqueId(), player.getName(),
                plugin.getNovaChatConfig().getUsername(), args);
        for (String line : lines) {
            messageHelper.sendRaw(sender, line);
        }
        return true;
    }
}
