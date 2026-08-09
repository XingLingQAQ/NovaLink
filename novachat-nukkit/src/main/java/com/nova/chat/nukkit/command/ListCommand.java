package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.ListCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.nukkit.NovaChatNukkit;

import java.util.List;

/**
 * List command - shows channels the backend advertised via ConfigSync, marking
 * those the player has already joined (UX-DESIGN §2.2).
 *
 * <p>Local-only: reads the shared {@code KnownChannelRegistry} plus the player's
 * joined channels via {@link ListCommandService#formatChannelList}. No backend
 * packet, no permission requirement.
 */
public class ListCommand extends AbstractSubCommand {

    public ListCommand(NovaChatNukkit plugin) {
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

        PlayerChannelState state = plugin.getChatInterceptor().getState(player.getUniqueId());
        java.util.Set<String> joined = state != null ? state.getJoinedChannels() : java.util.Set.of();

        List<String> lines = ListCommandService.formatChannelList(
                plugin.getKnownChannelRegistry(), joined);

        // Use the message helper's header/footer via raw messages for the list body.
        messageHelper.sendRawMessage(sender, I18n.tr(player.getUniqueId(), "chat.command.list.title"));
        for (String line : lines) {
            messageHelper.sendRawMessage(sender, line);
        }
        messageHelper.sendRawMessage(sender, I18n.tr(player.getUniqueId(), "chat.command.list.tail"));
        return true;
    }
}
