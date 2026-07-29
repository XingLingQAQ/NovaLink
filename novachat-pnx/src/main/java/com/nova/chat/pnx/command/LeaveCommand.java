package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;

/**
 * Leave sub-command - leaves current channel and returns to default.
 * 
 * Requirements: 29.1, 29.2
 */
public class LeaveCommand extends AbstractSubCommand {

    public LeaveCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "leave";
    }

    @Override
    public String getDescription() {
        return "离开当前频道";
    }

    @Override
    public String getUsage() {
        return "/nc leave";
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

        String currentChannel = plugin.getChatInterceptor().getPlayerChannel(player);
        String defaultChannel = plugin.getNovaChatConfig().getDefaultChannel();

        if (currentChannel.equals(defaultChannel)) {
            sendError(sender, "你已经在默认频道中");
            return true;
        }

        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player).getChannelState();
        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.leave(state, currentChannel, player.getName());

        if (result.isSuccess()) {
            // Preserve prior PNX leave UX: always land on the configured default.
            if (!defaultChannel.equals(state.getActiveChannel())) {
                state.setActiveChannel(defaultChannel);
            }
            sendSuccess(sender, "已返回默认频道: " + defaultChannel);
            plugin.debug("Player " + player.getName() + " left channel: " + currentChannel);
        } else {
            // Actionable error: NC-433 not-in-channel vs NC-503 network failure (via ErrorCode).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            sendError(sender, ErrorMessageFormatter.format(code));
            plugin.debug("Player " + player.getName() + " failed to leave channel "
                    + currentChannel + ": " + result.getMessage());
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
