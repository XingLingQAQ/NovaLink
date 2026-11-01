package com.nova.chat.client.channel;

import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;

import java.util.ArrayList;
import java.util.function.Consumer;

/** Re-requests an online player's memberships through the normal backend checks. */
public final class ChannelMembershipRestorer {
    private ChannelMembershipRestorer() { }

    public static void restore(PlayerChannelState state, String playerName, PlatformType platform,
                               String world, String server, Consumer<ChannelActionPacket> sender) {
        if (state == null) {
            return;
        }
        String active = state.getActiveChannel();
        var channels = new ArrayList<>(state.getJoinedChannels());
        if (channels.remove(active)) {
            channels.add(active);
        }
        for (String channel : channels) {
            if (!state.getJoinedChannels().contains(channel)) {
                continue;
            }
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, channel);
            packet.addExtra("playerId", state.getPlayerId().toString());
            packet.addExtra("playerName", playerName);
            packet.addExtra("platform", platform.name());
            if (active != null) { packet.addExtra("previousChannel", active); }
            if (world != null) { packet.addExtra("world", world); }
            if (server != null) { packet.addExtra("server", server); }
            sender.accept(packet);
        }
    }
}
