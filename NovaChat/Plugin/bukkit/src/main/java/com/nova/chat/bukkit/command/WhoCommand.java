package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * {@code /nc who [频道]} — lists the online members of a channel
 * (UX-DESIGN §8.2).
 *
 * <p>Sends a {@link ChannelAction#WHO} request to the backend and shows an
 * interim {@code chat.who.fetching} prompt. The asynchronous
 * {@link com.nova.chat.common.protocol.packets.ChannelActionResponsePacket}
 * is rendered by {@code NetworkClient.handleChannelActionResponse}, which
 * calls {@link WhoCommandService#formatMemberList} and sends the result to
 * the requesting player. No permission requirement.
 */
public class WhoCommand extends AbstractSubCommand {

    public WhoCommand(NovaChatBukkit plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "who";
    }

    @Override
    public String getDescription() {
        return "查看频道在线成员";
    }

    @Override
    public String getUsage() {
        return "/nc who [频道]";
    }

    @Override
    public String getPermission() {
        return null; // No permission required (UX-DESIGN §8.2)
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!WhoCommandService.isMemberListingSupported()) {
            messageHelper.sendMessage(sender, WhoCommandService.getUnavailablePrompt());
            return true;
        }
        if (!checkConnection(sender)) {
            return true;
        }

        UUID requesterId = playerIdOf(sender);
        String channelId = resolveChannelId(sender, args, requesterId);
        if (channelId == null) {
            return true;
        }

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, channelId);
        packet.addExtra("playerId", requesterId != null ? requesterId.toString() : "");
        if (sender instanceof Player) {
            packet.addExtra("requesterName", sender.getName());
        }
        if (requesterId != null) {
            packet.addExtra("requesterId", requesterId.toString());
        }

        if (!sendPacket(packet)) {
            messageHelper.sendMessage(sender, I18n.tr(requesterId, "chat.network.not_connected"));
            return true;
        }
        messageHelper.sendMessage(sender, WhoCommandService.getFetchingPrompt(channelId));
        return true;
    }

    /**
     * Resolves the target channel id: explicit arg → player's active channel →
     * {@code null} with a prompt.
     */
    private String resolveChannelId(CommandSender sender, String[] args, UUID requesterId) {
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            return args[0];
        }
        if (sender instanceof Player) {
            PlayerChannelState state = getPlayerState((Player) sender);
            String active = state != null ? state.getActiveChannel() : null;
            if (active != null && !active.isBlank()) {
                return active;
            }
        }
        messageHelper.sendMessage(sender, I18n.tr(requesterId, "chat.who.no_channel"));
        return null;
    }
}
