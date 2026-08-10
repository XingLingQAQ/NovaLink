package com.nova.chat.mod.command;

import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.ChatModeDescriptions;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.ModServices;

/**
 * Toggle command — toggles chat mode (HYBRID ↔ REPLACE) on local state via the
 * shared {@code ChannelCommandService}. No network packet.
 */
public class ToggleCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        ModServices services = context.getServices();
        if (services == null) {
            context.sendMessage("NovaChat not initialized");
            return false;
        }
        PlayerChannelState state = services.getChatInterceptor().getOrCreateState(
                context.getPlayerId(), context.getPlayerName());
        CommandResult result = services.getChannelCommandService().toggle(state);
        if (!result.isSuccess()) {
            context.sendMessage(result.getMessage());
            return false;
        }
        ChatMode newMode = state.getChatMode();
        String modeText = ChatModeDescriptions.modeName(newMode);
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.command.toggle.switched", modeText));
        context.sendMessage(ChatModeDescriptions.describe(newMode));
        return true;
    }

    @Override
    public String getDescription() {
        return "Toggle chat mode on/off";
    }

    @Override
    public String getUsage() {
        return "/nc toggle";
    }
}
