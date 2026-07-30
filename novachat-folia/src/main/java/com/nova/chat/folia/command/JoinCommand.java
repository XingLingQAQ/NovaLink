package com.nova.chat.folia.command;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.chat.PlayerChatState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Join command - allows players to join a channel.
 *
 * <p>Delegates the JOIN packet and the optimistic local active-channel update
 * to {@link ChannelCommandService} (Architecture B client-core). Keeps the
 * Folia command shape, permissions, tab completion, and Chinese UX copy.
 *
 * Requirements: 2.1
 */
public class JoinCommand extends AbstractSubCommand {

    public JoinCommand(NovaChatFolia plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "join";
    }

    @Override
    public String getDescription() {
        return "加入一个频道";
    }

    @Override
    public String getUsage() {
        return "/nc join <频道ID> [密码]";
    }

    @Override
    public String getPermission() {
        return "novachat.join";
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            messageHelper.sendUsage(sender, getUsage());
            return true;
        }

        if (!checkConnection(sender)) {
            return true;
        }

        Player player = (Player) sender;
        String channelId = args[0];
        String password = args.length > 1 ? args[1] : "";

        PlayerChatState foliaState = plugin.getChatInterceptor().getOrCreateState(player);
        PlayerChannelState state = foliaState.getChannelState();
        ChannelCommandService channelCommands = plugin.getChannelCommandService();

        CommandResult result = channelCommands.join(state, channelId, password, player.getName());
        if (result.isSuccess()) {
            // Keep the Folia active-channel mirror in sync with the shared state.
            foliaState.setActiveChannel(state.getActiveChannel());
            messageHelper.sendMessage(sender, "正在加入频道 &e" + channelId + "&7...");
            plugin.debug("Player " + player.getName() + " joined channel: " + channelId);
        } else {
            // Actionable error via shared ErrorCode system (NC-503 network failure here).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            messageHelper.sendError(sender, ErrorMessageFormatter.format(code));
            plugin.debug("Player " + player.getName() + " failed to join channel " + channelId
                    + ": " + result.getMessage());
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        // UX-DESIGN §2.3: join <Tab> completes from the shared KnownChannelRegistry,
        // falling back to global/local when the backend has not pushed a roster yet.
        if (args.length == 1) {
            List<String> known = getKnownChannelIds(args[0]);
            if (!known.isEmpty()) {
                return known;
            }
            return Arrays.asList("global", "local");
        }
        return Collections.emptyList();
    }
}
