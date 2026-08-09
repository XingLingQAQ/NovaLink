package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.chat.ChatInterceptor;

import java.util.List;

/**
 * Toggle sub-command - toggles chat on/off for the player.
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

        ChatInterceptor.PlayerChatState state = plugin.getChatInterceptor().getOrCreateState(player);
        boolean newState = !state.isChatEnabled();
        state.setChatEnabled(newState);

        if (newState) {
            sendSuccess(sender, PlayerMessages.chatOn(player.getUniqueId()));
        } else {
            sendSuccess(sender, PlayerMessages.chatOff(player.getUniqueId()));
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
