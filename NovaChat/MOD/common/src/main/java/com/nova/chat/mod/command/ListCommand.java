package com.nova.chat.mod.command;

import com.nova.chat.client.command.ListCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.ModServices;

import java.util.Set;

/**
 * List command — shows channels the backend advertised via ConfigSync, marking
 * those the player has joined (UX-DESIGN §2.2).
 */
public class ListCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        ModServices services = context.getServices();
        if (services == null) {
            context.sendMessage("NovaChat not initialized");
            return false;
        }
        PlayerChannelState state = services.getChatInterceptor().getState(context.getPlayerId());
        Set<String> joined = state != null ? state.getJoinedChannels() : Set.of();
        java.util.List<String> lines = ListCommandService.formatChannelList(
                services.getKnownChannelRegistry(), joined);
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.command.list.title"));
        for (String line : lines) {
            context.sendMessage(line);
        }
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.command.list.tail"));
        return true;
    }

    @Override
    public String getDescription() {
        return "List available chat channels";
    }

    @Override
    public String getUsage() {
        return "/nc list";
    }
}
