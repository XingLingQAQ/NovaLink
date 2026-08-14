package com.nova.chat.pnx.command;

import cn.nukkit.Player;
import cn.nukkit.command.CommandSender;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.network.NetworkClient;

import java.util.Collections;
import java.util.List;

/**
 * {@code /nc who [频道]} — lists the online members of a channel
 * (UX-DESIGN §8.2).
 *
 * <p>Sends a {@link ChannelAction#WHO} request to the backend and shows an
 * interim {@code chat.who.fetching} prompt. The asynchronous response is
 * rendered by the {@code NetworkClient}'s {@code ChannelResponseDispatcher}
 * adapter, which calls {@link WhoCommandService#formatMemberList} and sends
 * the result to the requesting player. No permission requirement.
 *
 * Requirements: 29.1, 29.2
 */
public class WhoCommand extends AbstractSubCommand {

    public WhoCommand(NovaChatPNX plugin) {
        super(plugin);
    }

    @Override
    public String getName() {
        return "who";
    }

    @Override
    public String getDescription() {
        return I18n.tr("chat.command.desc.who");
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
            sendMessage(sender, WhoCommandService.getUnavailablePrompt());
            return true;
        }
        NetworkClient client = plugin.getNetworkClient();
        if (client == null || !client.isConnected() || !client.isAuthenticated()) {
            sendError(sender, I18n.tr("chat.network.not_connected"));
            return true;
        }

        Player player = getPlayer(sender);
        java.util.UUID requesterId = player != null ? player.getUniqueId() : null;
        String channelId = resolveChannelId(sender, args, player, requesterId);
        if (channelId == null) {
            return true;
        }

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, channelId);
        packet.addExtra("playerId", requesterId != null ? requesterId.toString() : "");
        if (player != null) {
            packet.addExtra("requesterName", player.getName());
        }
        if (requesterId != null) {
            packet.addExtra("requesterId", requesterId.toString());
        }

        client.sendPacket(packet);
        sendMessage(sender, WhoCommandService.getFetchingPrompt(channelId));
        return true;
    }

    private String resolveChannelId(CommandSender sender, String[] args, Player player, java.util.UUID requesterId) {
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            return args[0];
        }
        if (player != null) {
            String active = plugin.getChatInterceptor().getOrCreateState(player).getActiveChannel();
            if (active != null && !active.isBlank()) {
                return active;
            }
        }
        sendMessage(sender, I18n.tr(requesterId, "chat.who.no_channel"));
        return null;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> known = getKnownChannelIds(args[0]);
            if (!known.isEmpty()) {
                return known;
            }
            return java.util.Arrays.asList("global", "local");
        }
        return Collections.emptyList();
    }
}
