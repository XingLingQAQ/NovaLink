package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;

/**
 * Channel sub-command - opens the channel selection form GUI.
 * 
 * Requirements: 28.8, 29.1, 29.2
 */
public class ChannelCommand extends AbstractSubCommand {

    public ChannelCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "channel";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.channel");
    }

    @Override
    public String getUsage() {
        return "/nc channel";
    }

    @Override
    public String getPermission() {
        return null;
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = getPlayer(sender);

        if (plugin.getChannelFormManager() == null) {
            sendError(sender, I18n.tr(player.getUniqueId(), "chat.action.form_unavailable"));
            return true;
        }

        plugin.getChannelFormManager().showChannelSelectionForm(player);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
