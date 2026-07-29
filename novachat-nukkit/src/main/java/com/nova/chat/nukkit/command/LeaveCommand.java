package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.nukkit.NovaChatNukkit;

/**
 * Leave command - leaves the current channel.
 *
 * <p>Uses {@link ChannelCommandService#leave} for the LEAVE packet and membership
 * update. After a successful leave, restores the configured default channel so
 * Nukkit leave UX stays "leave → default".
 *
 * Requirements: 3
 */
public class LeaveCommand extends AbstractSubCommand {

    public LeaveCommand(NovaChatNukkit plugin) {
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
        return null; // No permission required
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = getPlayer(sender);

        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        String currentChannel = state.getActiveChannel();
        String defaultChannel = plugin.getNovaChatConfig().getDefaultChannel();

        if (currentChannel == null || currentChannel.isBlank()) {
            sendError(sender, "你当前不在任何频道中");
            return true;
        }

        // Preserve prior Nukkit guard: cannot leave the default channel via /leave.
        if (currentChannel.equals(defaultChannel)) {
            sendError(sender, "你已经在默认频道中");
            return true;
        }

        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.leave(state, currentChannel, player.getName());

        if (result.isSuccess()) {
            // Preserve prior Nukkit leave UX: always land on the configured default.
            if (!defaultChannel.equals(state.getActiveChannel())) {
                state.setActiveChannel(defaultChannel);
            }
            sendSuccess(sender, "已离开频道 " + currentChannel + "，已切换到默认频道");
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
}
