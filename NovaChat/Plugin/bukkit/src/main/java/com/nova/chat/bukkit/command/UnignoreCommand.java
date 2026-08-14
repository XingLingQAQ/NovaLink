package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.command.IgnoreCommandService;
import com.nova.chat.client.i18n.I18n;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Unignore command - removes a player from the invoker's ignore list
 * ({@code /nc unignore <player>}).
 *
 * <p>Local-only: shared logic in {@link IgnoreCommandService}, this shell
 * forwards arguments and sends the returned lines.
 */
public class UnignoreCommand extends AbstractSubCommand {

    public UnignoreCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "unignore";
    }

    @Override
    public String getDescription() {
        return "解除屏蔽玩家";
    }

    @Override
    public String getUsage() {
        return "/nc unignore <玩家名>";
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

        List<String> lines = IgnoreCommandService.unignore(
                plugin.getIgnoreListService(), player.getUniqueId(), args);
        for (String line : lines) {
            messageHelper.sendRaw(sender, line);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && sender instanceof Player) {
            // Complete from the invoker's own ignore list (normalized names).
            String prefix = args[0].toLowerCase();
            return plugin.getIgnoreListService()
                    .listIgnored(((Player) sender).getUniqueId()).stream()
                    .filter(name -> name.startsWith(prefix))
                    .collect(java.util.stream.Collectors.toList());
        }
        return null;
    }
}
