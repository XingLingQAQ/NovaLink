package com.nova.chat.pnx.command;

import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;

/**
 * Reload sub-command - reloads plugin configuration.
 * 
 * Requirements: 29.1, 29.2
 */
public class ReloadCommand extends AbstractSubCommand {

    public ReloadCommand(NovaChatPNX plugin) {
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
        // Signal intent through shared service (documented no-op on the wire/state),
        // then perform the platform-owned config reload / reconnect.
        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        if (channelCommands != null) {
            channelCommands.reload();
        }
        plugin.reload();
        sendSuccess(sender, I18n.tr("chat.command.reload.success"));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
