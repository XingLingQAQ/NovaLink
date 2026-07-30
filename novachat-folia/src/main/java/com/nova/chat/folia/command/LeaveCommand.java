package com.nova.chat.folia.command;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.folia.NovaChatFolia;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * Leave command - allows players to leave a channel.
 *
 * <p>Delegates the LEAVE packet and the local membership update to
 * {@link ChannelCommandService} (Architecture B client-core). Keeps the Folia
 * command shape, permissions, tab completion, and Chinese UX copy.
 *
 * Requirements: 2.1
 */
public class LeaveCommand extends AbstractSubCommand {

    public LeaveCommand(NovaChatFolia plugin) {
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
        if (args.length > 0) {
            channelId = args[0];
        } else if (state.getActiveChannel() != null && !state.getActiveChannel().isBlank()) {
            channelId = state.getActiveChannel();
        } else {
            messageHelper.sendError(sender, "请指定要离开的频道");
            return true;
        }

        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.leave(state, channelId, player.getName());
        if (result.isSuccess()) {
            // The shared service optimistically updates local joined membership and,
            // when the left channel was the active one, falls the active channel back
            // to the next joined channel (or null). This matches the shared behavior
            // used by the other platforms; Folia region threads read it via volatile.
            messageHelper.sendMessage(sender, "正在离开频道 &e" + channelId + "&7...");
            plugin.debug("Player " + player.getName() + " left channel: " + channelId);
        } else {
            // Actionable error: NC-433 not-in-channel vs NC-503 network failure (via ErrorCode).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            messageHelper.sendError(sender, ErrorMessageFormatter.format(code));
            plugin.debug("Player " + player.getName() + " failed to leave channel "
                    + channelId + ": " + result.getMessage());
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
        PlayerChannelState state = getPlayerState(player);
        if (state == null) {
            return Collections.emptyList();
        }
        String prefix = args[0] == null ? "" : args[0].toLowerCase();
        return state.getJoinedChannels().stream()
                .filter(id -> id != null && id.toLowerCase().startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(java.util.stream.Collectors.toList());
    }
}
