package com.nova.chat.nukkit.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.nukkit.NovaChatNukkit;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Join command - joins a channel.
 *
 * <p>Delegates JOIN packet + optimistic local active channel to
 * {@link ChannelCommandService}. Platform keeps Chinese UX copy.
 *
 * Requirements: 3
 */
public class JoinCommand extends AbstractSubCommand {

    public JoinCommand(NovaChatNukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "join";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.join");
    }

    @Override
    public String getUsage() {
        return "/nc join <频道ID> [密码]";
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
        if (args.length < 1) {
            sendError(sender, I18n.tr("chat.command.usage.join"));
            return true;
        }

        Player player = getPlayer(sender);
        String channelId = args[0];
        String password = args.length > 1 ? args[1] : null;

        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player);
        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.join(state, channelId, password, player.getName(), player.getLevel().getName());

        if (result.isSuccess()) {
            // Match previous Nukkit UX (in-progress join rather than service English text).
            sendSuccess(sender, PlayerMessages.joining(player.getUniqueId(), channelId));
            plugin.debug("Player " + player.getName() + " joined channel: " + channelId);
        } else {
            // Actionable error via shared ErrorCode system (NC-503 network failure here).
            String code = result.getErrorCode() != null ? result.getErrorCode() : "NC-503";
            sendError(sender, ErrorMessageFormatter.format(code));
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
