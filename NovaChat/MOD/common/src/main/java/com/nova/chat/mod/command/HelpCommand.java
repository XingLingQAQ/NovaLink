package com.nova.chat.mod.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;

import java.util.UUID;

/**
 * Help command — displays available commands in the player's locale.
 *
 * <p>Lines come from {@link ModCommandCatalog}, the same list that drives
 * handler registration, so a command cannot be registered and silently left out
 * of help (the gap that hid the moderation commands). Admin-gated entries
 * render only for a sender {@link CommandContext#isAdmin()} accepts — the same
 * coarse gate the brigadier literals use. Hidden commands (no help key) are
 * skipped by the catalog and never revealed here.
 */
public class HelpCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        UUID playerId = context.getPlayerId();
        context.sendMessage(I18n.tr(playerId, "chat.command.help.title"));
        for (ModCommandCatalog.HelpLine line : ModCommandCatalog.helpLines()) {
            if (line.adminOnly() && !context.isAdmin()) {
                continue;
            }
            context.sendMessage(I18n.tr(playerId, line.i18nKey()));
        }
        return true;
    }

    @Override
    public String getDescription() {
        return "Display help information";
    }

    @Override
    public String getUsage() {
        return "/nc help";
    }
}
