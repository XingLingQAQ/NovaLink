package com.nova.chat.folia.command;

import com.nova.chat.client.command.ListCommandService;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.folia.NovaChatFolia;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * List command - shows channels the backend advertised via ConfigSync, marking
 * those the player has already joined (UX-DESIGN §2.2).
 *
 * <p>Local-only: reads the shared {@code KnownChannelRegistry} plus the player's
 * joined channels via {@link ListCommandService#formatChannelList}. No backend
 * packet, no permission requirement.
 *
 * Requirements: 2.1
 */
public class ListCommand extends AbstractSubCommand {

    public ListCommand(NovaChatFolia plugin) {
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
            messageHelper.sendError(sender, "此命令只能由玩家执行");
            return true;
        }
        Player player = (Player) sender;

        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        java.util.Set<String> joined = state != null ? state.getJoinedChannels() : java.util.Set.of();

        List<String> lines = ListCommandService.formatChannelList(
                plugin.getKnownChannelRegistry(), joined);

        messageHelper.sendHeader(sender, "NovaChat 频道列表");
        for (String line : lines) {
            messageHelper.sendRaw(sender, line);
        }
        messageHelper.sendFooter(sender);
        return true;
    }
}
