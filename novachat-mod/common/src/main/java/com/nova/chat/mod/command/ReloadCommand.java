package com.nova.chat.mod.command;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.ModServices;

/**
 * Reload command — admin-only. Signals reload intent through the shared
 * {@code ChannelCommandService} (documented no-op) then triggers a platform
 * reload via the attached services holder.
 */
public class ReloadCommand implements CommandHandler {

    @Override
    public boolean execute(String[] args, CommandContext context) {
        if (!context.isAdmin()) {
            context.sendMessage(ErrorMessageFormatter.format(ErrorCode.FORBIDDEN));
            return false;
        }
        ModServices services = context.getServices();
        if (services == null) {
            context.sendMessage("NovaChat not initialized");
            return false;
        }
        services.getChannelCommandService().reload();
        // Platform-owned reload (config + reconnect) is triggered by the loader
        // bootstrap via its own reload hook; the common layer only signals intent.
        context.sendMessage(I18n.tr(context.getPlayerId(), "chat.command.reload.success"));
        return true;
    }

    @Override
    public String getDescription() {
        return "Reload NovaChat configuration (admin)";
    }

    @Override
    public String getUsage() {
        return "/nc reload";
    }
}
