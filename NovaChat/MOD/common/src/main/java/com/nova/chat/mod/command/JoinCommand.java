package com.nova.chat.mod.command;

import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.ModServices;

import java.util.ArrayList;
import java.util.List;
/**
 * Join command — joins a chat channel via the shared {@code ChannelCommandService}.
 */
public class JoinCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        if (args.length < 1) {
            context.sendMessage(I18n.tr(context.getPlayerId(), "chat.command.usage.join"));
            return false;
        }
        ModServices services = context.getServices();
        if (services == null) {
            context.sendMessage("NovaChat not initialized");
            return false;
        }
        String channelId = args[0];
        String password = args.length > 1 ? args[1] : "";
        PlayerChannelState state = services.getChatInterceptor().getOrCreateState(
                context.getPlayerId(), context.getPlayerName());
        String world = context.getPlatform().getCurrentWorld(context.getPlayerId());
        CommandResult result = services.getChannelCommandService()
                .join(state, channelId, password, context.getPlayerName(), world);
        if (result.isSuccess()) {
            context.sendMessage(PlayerMessages.joining(context.getPlayerId(), channelId));
            return true;
        }
        String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
        context.sendMessage(ErrorMessageFormatter.format(code));
        return false;
    }

    @Override
    public String getDescription() {
        return "Join a chat channel";
    }

    @Override
    public String getUsage() {
        return "/nc join <channel>";
    }

    @Override
    public List<String> tabComplete(String[] args) {
        // Completion resolved against the known-channel registry in the registrar.
        return new ArrayList<>();
    }
}
