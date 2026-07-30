package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.state.PlayerChannelState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Join command - allows players to join a channel.
 *
 * <p>Delegates the JOIN packet and the optimistic local active-channel update
 * to {@link ChannelCommandService} (Architecture B client-core). The pending-
 * request correlation is preserved automatically because the shared service
 * sends through {@code NetworkClient#sendPacket}, which hooks the tracker for
 * every {@code ChannelActionPacket}. Keeps the Bukkit command shape, permission
 * check, tab completion, and Chinese UX copy.
 *
 * Requirements: 3
 */
public class JoinCommand extends AbstractSubCommand {

    public JoinCommand(NovaChatBukkit plugin) {
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

        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        ChannelCommandService channelCommands = plugin.getChannelCommandService();

        CommandResult result = channelCommands.join(state, channelId, password, player.getName(), player.getWorld().getName());
        if (result.isSuccess()) {
            messageHelper.sendMessage(sender, "正在加入频道 &e" + channelId + "&7...");

            // Optimistically update local active channel so incoming Title/announcement routing works immediately.
            // Backend may still reject; in that case player can retry and/or /nc join again.
            plugin.getChatInterceptor().setPlayerChannel(player, channelId);
        } else {
            errorHandler.sendRequestFailed(sender);
        }

        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
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
