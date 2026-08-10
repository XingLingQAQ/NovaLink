package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.command.ListCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * List command - shows channels the backend advertised via ConfigSync, marking
 * those the player has already joined (UX-DESIGN §2.2).
 *
 * <p>Local-only: reads the shared {@code KnownChannelRegistry} (filled by the
 * bukkit NetworkClient's ConfigSync handler) plus the player's joined channels
 * via {@link ListCommandService#formatChannelList}. No backend packet, no
 * permission requirement — any player can discover channels.
 */
public class ListCommand extends AbstractSubCommand {

    public ListCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "list";
    }

    @Override
    public String getDescription() {
        return "列出可用频道";
    }

    @Override
    public String getUsage() {
        return "/nc list";
    }

    @Override
    public String getPermission() {
        return null; // No permission required (UX-DESIGN §2.2)
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

        PlayerChannelState state = plugin.getChatInterceptor().getPlayerState(player.getUniqueId());
        java.util.Set<String> joined = state != null ? state.getJoinedChannels() : java.util.Set.of();

        List<String> lines = ListCommandService.formatChannelList(
                plugin.getKnownChannelRegistry(), joined);

        messageHelper.sendHeader(sender, I18n.tr(player.getUniqueId(), "chat.command.list.title"));
        for (String line : lines) {
            messageHelper.sendRaw(sender, line);
        }
        messageHelper.sendFooter(sender);
        return true;
    }
}
