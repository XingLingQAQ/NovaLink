package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.ListCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;

/**
 * List sub-command - shows channels the backend advertised via ConfigSync,
 * marking those the player has already joined (UX-DESIGN §2.2).
 *
 * <p>Local-only: reads the shared {@code KnownChannelRegistry} plus the player's
 * joined channels via {@link ListCommandService#formatChannelList}. No backend
 * packet, no permission requirement.
 *
 * Requirements: 29.1, 29.2
 */
public class ListCommand extends AbstractSubCommand {

    public ListCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "list";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.list");
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
            sendError(sender, I18n.tr("chat.command.player_only"));
            return true;
        }
        Player player = (Player) sender;

        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        java.util.Set<String> joined = state != null ? state.getJoinedChannels() : java.util.Set.of();

        List<String> lines = ListCommandService.formatChannelList(
                plugin.getKnownChannelRegistry(), joined);

        sendMessage(sender, I18n.tr(player.getUniqueId(), "chat.command.list.title"));
        for (String line : lines) {
            sendMessage(sender, line);
        }
        sendMessage(sender, I18n.tr(player.getUniqueId(), "chat.command.list.tail"));
        return true;
    }
}
