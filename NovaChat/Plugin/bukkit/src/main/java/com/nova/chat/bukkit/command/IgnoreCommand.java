package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.command.IgnoreCommandService;
import com.nova.chat.client.i18n.I18n;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Ignore command - blocks another player's chat / mention / item-display
 * output for the invoking player ({@code /nc ignore <player>},
 * {@code /nc ignore list}).
 *
 * <p>Local-only: validation, service calls and receipt copy live in the
 * shared {@link IgnoreCommandService}; this shell forwards arguments and
 * sends the returned lines. No backend packet.
 */
public class IgnoreCommand extends AbstractSubCommand {

    public IgnoreCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "ignore";
    }

    @Override
    public String getDescription() {
        return "屏蔽玩家或查看屏蔽列表";
    }

    @Override
    public String getUsage() {
        return "/nc ignore <玩家名|list>";
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

        List<String> lines = IgnoreCommandService.ignore(
                plugin.getIgnoreListService(), player.getUniqueId(), player.getName(), args);
        for (String line : lines) {
            messageHelper.sendRaw(sender, line);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String prefix = args[0].toLowerCase();
            if (IgnoreCommandService.LIST_ARG.startsWith(prefix)) {
                completions.add(IgnoreCommandService.LIST_ARG);
            }
            completions.addAll(getOnlinePlayerNames(args[0]));
            return completions;
        }
        return null;
    }
}
