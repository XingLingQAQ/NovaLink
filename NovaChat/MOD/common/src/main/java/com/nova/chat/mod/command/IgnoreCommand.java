package com.nova.chat.mod.command;

import com.nova.chat.client.command.IgnoreCommandService;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.ModServices;

import java.util.List;

/**
 * Ignore command — blocks another player's chat / mention / item-display
 * output for the invoking player ({@code /nc ignore <player>},
 * {@code /nc ignore list}).
 *
 * <p>Local-only: validation, service calls and receipt copy live in the
 * shared {@link IgnoreCommandService}; this handler forwards arguments and
 * sends the returned lines. No backend packet.
 */
public class IgnoreCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        ModServices services = context.getServices();
        if (services == null || services.getIgnoreListService() == null) {
            context.sendMessage("NovaChat not initialized");
            return false;
        }
        List<String> lines = IgnoreCommandService.ignore(
                services.getIgnoreListService(),
                context.getPlayerId(), context.getPlayerName(), args);
        for (String line : lines) {
            context.sendMessage(line);
        }
        return true;
    }

    @Override
    public String getDescription() {
        return "Ignore a player or list ignored players";
    }

    @Override
    public String getUsage() {
        return "/nc ignore <player|list>";
    }

    @Override
    public List<String> tabComplete(String[] args) {
        // Online-player completion is resolved in the loader registrar (the
        // common layer has no player directory); "list" is always offered.
        if (args.length <= 1) {
            String prefix = args.length == 1 ? args[0].toLowerCase() : "";
            if (IgnoreCommandService.LIST_ARG.startsWith(prefix)) {
                return List.of(IgnoreCommandService.LIST_ARG);
            }
        }
        return List.of();
    }
}
