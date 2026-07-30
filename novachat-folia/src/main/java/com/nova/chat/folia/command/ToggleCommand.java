package com.nova.chat.folia.command;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.ChatModeDescriptions;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.folia.NovaChatFolia;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Toggle command - toggles chat mode between HYBRID and REPLACE.
 *
 * <p>Delegates the local mode flip to {@link ChannelCommandService#toggle}
 * (Architecture B client-core, no network packet). Keeps the Folia follow-up
 * explanatory lines and Chinese UX copy.
 *
 * Requirements: 2.1
 */
public class ToggleCommand extends AbstractSubCommand {

    public ToggleCommand(NovaChatFolia plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "toggle";
    }

    @Override
    public String getDescription() {
        return "切换聊天模式";
    }

    @Override
    public String getUsage() {
        return "/nc toggle";
    }

    @Override
    public String getPermission() {
        return "novachat.toggle";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        ChannelCommandService channelCommands = plugin.getChannelCommandService();

        CommandResult result = channelCommands.toggle(state);
        if (!result.isSuccess()) {
            messageHelper.sendError(sender, result.getMessage());
            return true;
        }

        ChatMode newMode = state.getChatMode();

        String modeDesc = newMode == ChatMode.REPLACE ? "频道模式" : "混合模式";
        messageHelper.sendSuccess(sender, "聊天模式已切换为: &e" + modeDesc);
        messageHelper.sendRaw(sender, "&7" + ChatModeDescriptions.describe(newMode));

        plugin.debug("Player " + player.getName() + " toggled chat mode to: " + newMode);

        return true;
    }
}
