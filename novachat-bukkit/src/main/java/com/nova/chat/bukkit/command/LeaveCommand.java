package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.state.PlayerChannelState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Leave command - allows players to leave a channel.
 *
 * <p>Delegates the LEAVE packet to {@link ChannelCommandService} (Architecture B
 * client-core). The pending-request correlation is preserved automatically
 * because the shared service sends through {@code NetworkClient#sendPacket},
 * which hooks the tracker for every {@code ChannelActionPacket}. After a
 * successful leave of the active channel, the Bukkit default-channel reset is
 * re-applied on top of the service's membership update to keep the original
 * "leave current → default" UX. Keeps the Bukkit command shape, permission
 * check, tab completion, and Chinese UX copy.
 *
 * Requirements: 3
 */
public class LeaveCommand extends AbstractSubCommand {

    public LeaveCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "leave";
    }

    @Override
    public String getDescription() {
        return "离开当前或指定频道";
    }

    @Override
    public String getUsage() {
        return "/nc leave [频道ID]";
    }

    @Override
    public String getPermission() {
        return "novachat.leave";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!checkConnection(sender)) {
            return true;
        }

        Player player = (Player) sender;
        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);

        String channelId;
        boolean leavingCurrent;
        if (args.length > 0) {
            channelId = args[0];
            leavingCurrent = false;
        } else {
            // Leave current channel
            if (state.getActiveChannel() == null) {
                errorHandler.sendError(sender, com.nova.chat.bukkit.error.ErrorCode.NOT_IN_CHANNEL);
                return true;
            }
            channelId = state.getActiveChannel();
            leavingCurrent = true;
        }

        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.leave(state, channelId, player.getName());
        if (result.isSuccess()) {
            messageHelper.sendMessage(sender, "正在离开频道 &e" + channelId + "&7...");

            // Optimistically adjust local active channel if leaving the current one.
            // The service's leaveChannel() falls back to the next joined channel (or null);
            // Bukkit UX lands on the configured default instead, so override after success.
            if (leavingCurrent) {
                plugin.getChatInterceptor().setPlayerChannel(player, plugin.getNovaChatConfig().getDefaultChannel());
            }
        } else {
            // Distinguish "not in a channel" (service short-circuit) from a send failure.
            String message = result.getMessage();
            if (message != null && message.contains("Not in a channel")) {
                errorHandler.sendError(sender, com.nova.chat.bukkit.error.ErrorCode.NOT_IN_CHANNEL);
            } else {
                errorHandler.sendRequestFailed(sender);
            }
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            // UX-DESIGN §2.3: leave <Tab> completes channels the player has joined.
            if (!(sender instanceof Player)) {
                return Collections.emptyList();
            }
            Player player = (Player) sender;
            PlayerChannelState state = plugin.getChatInterceptor().getPlayerState(player.getUniqueId());
            if (state == null) {
                return Collections.emptyList();
            }
            String prefix = args[0] == null ? "" : args[0].toLowerCase();
            return state.getJoinedChannels().stream()
                    .filter(id -> id != null && id.toLowerCase().startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(java.util.stream.Collectors.toList());
        }
        return Collections.emptyList();
    }
}
