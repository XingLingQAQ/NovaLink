package com.nova.chat.folia.command;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.chat.PlayerChatState;
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
        PlayerChatState foliaState = plugin.getChatInterceptor().getOrCreateState(player);
        PlayerChannelState state = foliaState.getChannelState();
        ChannelCommandService channelCommands = plugin.getChannelCommandService();

        CommandResult result = channelCommands.toggle(state);
        if (!result.isSuccess()) {
            messageHelper.sendError(sender, result.getMessage());
            return true;
        }

        // The shared service mutated the underlying PlayerChannelState; refresh the
        // Folia-side volatile mirrors so chat handling on region threads sees the
        // new mode immediately.
        foliaState.setChatMode(state.getChatMode());
        foliaState.setModeOverridden(state.isModeOverridden());
        ChatMode newMode = foliaState.getChatMode();

        String modeDesc = newMode == ChatMode.REPLACE ? "频道模式" : "混合模式";
        messageHelper.sendSuccess(sender, "聊天模式已切换为: &e" + modeDesc);

        if (newMode == ChatMode.REPLACE) {
            messageHelper.sendRaw(sender, "&7所有聊天消息将发送到当前频道");
        } else {
            messageHelper.sendRaw(sender, "&7原版聊天已启用，使用命令发送频道消息");
        }

        plugin.debug("Player " + player.getName() + " toggled chat mode to: " + newMode);

        return true;
    }
}
