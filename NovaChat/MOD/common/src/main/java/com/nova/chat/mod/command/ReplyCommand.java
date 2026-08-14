package com.nova.chat.mod.command;

import com.nova.chat.client.command.PrivateMessageCommandService;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.ModServices;

import java.util.List;

/**
 * Reply command — whispers to the most recent private-message partner
 * ({@code /nc r <message...>}).
 *
 * <p>The reply target is tracked by the shared
 * {@link com.nova.chat.client.privatemsg.PrivateMessageService} (updated on
 * both sent and received private messages); validation and receipt copy live
 * in {@link PrivateMessageCommandService}.
 */
public class ReplyCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        ModServices services = context.getServices();
        if (services == null || services.getNetworkClient() == null
                || services.getPrivateMessageService() == null) {
            context.sendMessage("NovaChat not initialized");
            return false;
        }
        List<String> lines = PrivateMessageCommandService.reply(
                services.getPrivateMessageService(),
                packet -> {
                    if (!services.getNetworkClient().isConnected()) {
                        return false;
                    }
                    services.getNetworkClient().sendPacket(packet);
                    return true;
                },
                context.getPlayerId(), context.getPlayerName(),
                services.getConfig() != null ? services.getConfig().getUsername() : null,
                args);
        for (String line : lines) {
            context.sendMessage(line);
        }
        return true;
    }

    @Override
    public String getDescription() {
        return "Reply to your last private message";
    }

    @Override
    public String getUsage() {
        return "/nc r <message>";
    }
}
