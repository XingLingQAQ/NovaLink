package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.List;

/**
 * Join sub-command - joins a channel.
 * 
 * Requirements: 29.1, 29.2
 */
public class JoinCommand extends AbstractSubCommand {

    public JoinCommand(NovaChatPNX plugin) {
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
        return "/nc join <频道>";
    }

    @Override
    public String getPermission() {
        return null; // Permission checked per-channel
    }

    @Override
    public boolean isPlayerOnly() {
        return true;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = getPlayer(sender);

        if (args.length < 1) {
            sendError(sender, I18n.tr(player.getUniqueId(), "chat.command.usage.join"));
            return true;
        }

        String channelId = args[0];

        // Check permission for specific channel
        if (!player.hasPermission("novachat.channel." + channelId) &&
            !player.hasPermission("novachat.channel.*")) {
            sendError(sender, I18n.tr(player.getUniqueId(), "chat.action.no_permission_join"));
            return true;
        }

        PlayerChannelState state = plugin.getChatInterceptor().getOrCreateState(player).getChannelState();
        ChannelCommandService channelCommands = plugin.getChannelCommandService();
        CommandResult result = channelCommands.join(state, channelId, null, player.getName(), player.getLevel().getName());

        if (result.isSuccess()) {
            // §7: optimistic "joining…" receipt; the async ChannelActionResponsePacket
            // handler confirms with "已加入频道 X" once the backend accepts, or
            // surfaces an actionable error if it rejects.
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
            return java.util.Arrays.asList("global", "local");
        }
        return List.of();
    }
}
