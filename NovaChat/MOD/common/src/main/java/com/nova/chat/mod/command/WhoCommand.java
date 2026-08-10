package com.nova.chat.mod.command;

import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.ModServices;

import java.util.UUID;

/**
 * Who command — sends a {@link ChannelAction#WHO} request to the backend and
 * shows an interim {@code chat.who.fetching} prompt. The asynchronous response
 * is rendered by {@code ChatInterceptor}'s {@code ChannelResponseDispatcher}
 * adapter (UX-DESIGN §8.2).
 */
public class WhoCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        if (!WhoCommandService.isMemberListingSupported()) {
            context.sendMessage(WhoCommandService.getUnavailablePrompt());
            return true;
        }
        ModServices services = context.getServices();
        if (services == null) {
            context.sendMessage("NovaChat not initialized");
            return false;
        }
        if (services.getNetworkClient() == null
                || !services.getNetworkClient().isConnected()
                || !services.getNetworkClient().isAuthenticated()) {
            context.sendMessage(I18n.tr(context.getPlayerId(), "chat.network.not_connected"));
            return false;
        }

        UUID requesterId = context.getPlayerId();
        String channelId = null;
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            channelId = args[0];
        } else {
            PlayerChannelState state = services.getChatInterceptor().getOrCreateState(
                    requesterId, context.getPlayerName());
            String active = state.getActiveChannel();
            if (active != null && !active.isBlank()) {
                channelId = active;
            }
        }
        if (channelId == null || channelId.isBlank()) {
            context.sendMessage(I18n.tr(requesterId, "chat.who.no_channel"));
            return false;
        }

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, channelId);
        if (requesterId != null) {
            packet.addExtra("playerId", requesterId.toString());
            packet.addExtra("requesterId", requesterId.toString());
        }
        if (context.getPlayerName() != null && !context.getPlayerName().isBlank()) {
            packet.addExtra("requesterName", context.getPlayerName());
        }
        services.getNetworkClient().sendPacket(packet);
        context.sendMessage(WhoCommandService.getFetchingPrompt(channelId));
        return true;
    }

    @Override
    public String getDescription() {
        return "Show members of a channel";
    }

    @Override
    public String getUsage() {
        return "/nc who [channel]";
    }
}
