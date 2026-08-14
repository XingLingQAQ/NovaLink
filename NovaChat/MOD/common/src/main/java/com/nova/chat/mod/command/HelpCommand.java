package com.nova.chat.mod.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;

import java.util.UUID;

/**
 * Help command — displays available commands in the player's locale.
 */
public class HelpCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        UUID playerId = context.getPlayerId();
        context.sendMessage(I18n.tr(playerId, "chat.command.help.title"));
        context.sendMessage(I18n.tr(playerId, "chat.command.help.line_help"));
        context.sendMessage(I18n.tr(playerId, "chat.command.help.line_join"));
        context.sendMessage(I18n.tr(playerId, "chat.command.help.line_leave"));
        context.sendMessage(I18n.tr(playerId, "chat.command.help.line_list"));
        context.sendMessage(I18n.tr(playerId, "chat.command.help.line_who"));
        context.sendMessage(I18n.tr(playerId, "chat.command.help.line_toggle"));
        context.sendMessage(I18n.tr(playerId, "chat.command.help.line_ignore"));
        context.sendMessage(I18n.tr(playerId, "chat.command.help.line_unignore"));
        context.sendMessage(I18n.tr(playerId, "chat.command.help.line_pm"));
        context.sendMessage(I18n.tr(playerId, "chat.command.help.line_reply"));
        context.sendMessage(I18n.tr(playerId, "chat.command.help.line_msg"));
        if (context.isAdmin()) {
            context.sendMessage(I18n.tr(playerId, "chat.command.help.line_reload"));
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
