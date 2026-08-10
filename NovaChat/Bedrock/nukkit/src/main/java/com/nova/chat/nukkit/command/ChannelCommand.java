package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.form.ChannelFormManager;

/**
 * Channel command - opens the channel selection GUI using Nukkit's Form API.
 * 
 * This command is specific to Nukkit/Bedrock and provides a GUI-based
 * channel selection experience for Bedrock players.
 * 
 * Requirements: 23.4
 */
public class ChannelCommand extends AbstractSubCommand {

    public ChannelCommand(NovaChatNukkit plugin) {
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
        return null; // No permission required
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = getPlayer(sender);

        // Check if connected to backend
        if (!plugin.getNetworkClient().isAuthenticated()) {
            sendError(sender, I18n.tr(player.getUniqueId(), "chat.network.not_connected"));
            return true;
        }

        // Open the channel selection form
        ChannelFormManager formManager = plugin.getFormManager();
        formManager.showChannelSelectionForm(player);

        return true;
    }
}
