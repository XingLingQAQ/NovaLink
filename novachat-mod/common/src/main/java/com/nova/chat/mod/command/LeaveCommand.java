package com.nova.chat.mod.command;

import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.ModServices;

/**
 * Leave command — leaves a chat channel via the shared {@code ChannelCommandService}.
 */
public class LeaveCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        ModServices services = context.getServices();
        if (services == null) {
            context.sendMessage("NovaChat not initialized");
            return false;
        }
        PlayerChannelState state = services.getChatInterceptor().getOrCreateState(
                context.getPlayerId(), context.getPlayerName());
        String requested = args.length > 0 ? args[0] : null;
        String leavingChannel = (requested != null && !requested.isBlank())
                ? requested
                : state.getActiveChannel();
        if (leavingChannel == null || leavingChannel.isBlank()) {
            context.sendMessage(ErrorMessageFormatter.format(ErrorCode.NOT_IN_CHANNEL));
            return false;
        }
        CommandResult result = services.getChannelCommandService()
                .leave(state, leavingChannel, context.getPlayerName());
        if (result.isSuccess()) {
            // Prefer the configured default only when still a confirmed local membership.
            String defaultChannel = services.getConfig().getChat().getDefaultChannel();
            state.setActiveChannelIfJoined(defaultChannel);
            context.sendMessage(PlayerMessages.leaving(context.getPlayerId(), leavingChannel));
            return true;
        }
        String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
        context.sendMessage(ErrorMessageFormatter.format(code));
        return false;
    }

    @Override
    public String getDescription() {
        return "Leave the current chat channel";
    }

    @Override
    public String getUsage() {
        return "/nc leave [channel]";
    }
}
