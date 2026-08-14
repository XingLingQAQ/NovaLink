package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.IgnoreCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.nukkit.NovaChatNukkit;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Unignore command - removes a player from the invoker's ignore list
 * ({@code /nc unignore <player>}).
 *
 * <p>Local-only: shared logic in {@link IgnoreCommandService}, this shell
 * forwards arguments and sends the returned lines.
 */
public class UnignoreCommand extends AbstractSubCommand {

    public UnignoreCommand(NovaChatNukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "unignore";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.unignore");
    }

    @Override
    public String getUsage() {
        return "/nc unignore <player>";
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

        List<String> lines = IgnoreCommandService.unignore(
                plugin.getIgnoreListService(), player.getUniqueId(), args);
        for (String line : lines) {
            messageHelper.sendRawMessage(sender, line);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && sender instanceof Player
                && plugin.getIgnoreListService() != null) {
            // Complete from the invoker's own ignore list (normalized names).
            String prefix = args[0].toLowerCase();
            return plugin.getIgnoreListService()
                    .listIgnored(((Player) sender).getUniqueId()).stream()
                    .filter(name -> name.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        return null;
    }
}
