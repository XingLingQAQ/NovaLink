package com.nova.chat.nukkit.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.nukkit.NovaChatNukkit;

/**
 * Reload command - reloads plugin configuration.
 *
 * <p>{@link ChannelCommandService#reload()} is intentionally a no-op on the wire;
 * the platform still owns config reload / reconnect.
 *
 * Requirements: 18.1
 */
public class ReloadCommand extends AbstractSubCommand {

    public ReloadCommand(NovaChatNukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.reload");
    }

    @Override
    public String getUsage() {
        return "/nc reload";
    }

    @Override
    public String getPermission() {
        return "novachat.admin";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        try {
            // Signal intent through shared service (documented no-op), then do platform reload.
            ChannelCommandService channelCommands = plugin.getChannelCommandService();
            if (channelCommands != null) {
                channelCommands.reload();
            }
            plugin.reload();
            sendSuccess(sender, I18n.tr("chat.command.reload.success"));
        } catch (Exception e) {
            sendError(sender, I18n.tr("chat.action.reload_error", e.getMessage()));
            plugin.getLogger().error("Error reloading config", e);
        }

        return true;
    }
}
