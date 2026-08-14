package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.IgnoreCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.nukkit.NovaChatNukkit;

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

    public IgnoreCommand(NovaChatNukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "ignore";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.ignore");
    }

    @Override
    public String getUsage() {
        return "/nc ignore <player|list>";
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

        List<String> lines = IgnoreCommandService.ignore(
                plugin.getIgnoreListService(), player.getUniqueId(), player.getName(), args);
        for (String line : lines) {
            messageHelper.sendRawMessage(sender, line);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> completions = new ArrayList<>();
            if (IgnoreCommandService.LIST_ARG.startsWith(prefix)) {
                completions.add(IgnoreCommandService.LIST_ARG);
            }
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
