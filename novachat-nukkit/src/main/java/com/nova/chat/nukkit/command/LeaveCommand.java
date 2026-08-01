package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.nukkit.NovaChatNukkit;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

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
            sendSuccess(sender, PlayerMessages.leaving(currentChannel));
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
        // UX-DESIGN §2.3: leave <Tab> completes channels the player has joined.
        if (args.length != 1 || !(sender instanceof Player)) {
            return Collections.emptyList();
        }
        Player player = (Player) sender;
        PlayerChannelState state = plugin.getChatInterceptor().getState(player.getUniqueId());
        if (state == null) {
            return Collections.emptyList();
        }
        String prefix = args[0] == null ? "" : args[0].toLowerCase();
        return state.getJoinedChannels().stream()
                .filter(id -> id != null && id.toLowerCase().startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }
}
