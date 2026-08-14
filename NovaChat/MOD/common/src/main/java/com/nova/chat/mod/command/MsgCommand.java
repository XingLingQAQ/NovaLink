package com.nova.chat.mod.command;

import com.nova.chat.client.command.PrivateMessageCommandService;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.ModServices;

import java.util.List;

/**
 * Private message command — whispers to a player anywhere on the network
 * ({@code /nc msg <player> <message...>}).
 *
 * <p>Validation, packet construction and receipt copy live in the shared
 * {@link PrivateMessageCommandService}; this handler forwards arguments and
 * sends the returned lines. The success confirmation is rendered from the
 * backend echo (see {@code ChatInterceptor#handlePrivateMessage}).
 */
public class MsgCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        ModServices services = context.getServices();
        if (services == null || services.getNetworkClient() == null) {
            context.sendMessage("NovaChat not initialized");
            return false;
        }
        List<String> lines = PrivateMessageCommandService.msg(
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
        return "Whisper to any player on the network";
    }

    @Override
    public String getUsage() {
        return "/nc msg <player> <message>";
    }

    @Override
    public List<String> tabComplete(String[] args) {
        // Online-player completion is resolved in the loader registrar (the
        // common layer has no player directory), mirroring IgnoreCommand.
        return List.of();
    }
}
