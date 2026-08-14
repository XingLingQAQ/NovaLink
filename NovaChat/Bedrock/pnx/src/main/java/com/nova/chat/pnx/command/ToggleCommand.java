package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.ChatModeDescriptions;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;

/**
 * Toggle sub-command - toggles chat mode between HYBRID and REPLACE for the player.
 *
 * <p>DUP-7 migration: delegates the local mode flip to
 * {@link ChannelCommandService#toggle} (no network packet), matching the
 * other six platforms. Previously this flipped a PNX-only forwarding flag;
 * it now toggles the shared {@link ChatMode} so PNX is consistent with the
 * rest of the fleet.
 *
 * Requirements: 29.1, 29.2
 */
public class ToggleCommand extends AbstractSubCommand {

    public ToggleCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "toggle";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.toggle");
    }

    @Override
    public String getUsage() {
        return "/nc toggle";
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

        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.toggle(state);

        if (!result.isSuccess()) {
            sendError(sender, result.getMessage());
            return true;
        }

        ChatMode newMode = state.getChatMode();

        sendSuccess(sender, I18n.tr(player.getUniqueId(), "chat.command.toggle.switched",
                ChatModeDescriptions.modeName(newMode)));
        sendMessage(sender, ChatModeDescriptions.describe(newMode));
        plugin.debug("Player " + player.getName() + " toggled chat mode to: " + newMode);

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
