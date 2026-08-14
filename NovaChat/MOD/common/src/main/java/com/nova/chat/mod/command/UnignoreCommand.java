package com.nova.chat.mod.command;

import com.nova.chat.client.command.IgnoreCommandService;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.ModServices;

import java.util.List;

/**
 * Unignore command — removes a player from the invoker's ignore list
 * ({@code /nc unignore <player>}).
 *
 * <p>Local-only: shared logic in {@link IgnoreCommandService}, this handler
 * forwards arguments and sends the returned lines.
 */
public class UnignoreCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        ModServices services = context.getServices();
        if (services == null || services.getIgnoreListService() == null) {
            context.sendMessage("NovaChat not initialized");
            return false;
        }
        List<String> lines = IgnoreCommandService.unignore(
                services.getIgnoreListService(), context.getPlayerId(), args);
        for (String line : lines) {
            context.sendMessage(line);
        }
        return true;
    }

    @Override
    public String getDescription() {
        return "Stop ignoring a player";
    }

    @Override
    public String getUsage() {
        return "/nc unignore <player>";
    }
}
