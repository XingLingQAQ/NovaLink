package com.nova.link.channel;

import com.nova.link.database.PlayerStateManager;

import java.util.ArrayList;
import java.util.Objects;

public final class ChannelStateCleanup {

    private ChannelStateCleanup() {}

    public static void clearMemberships(Channel channel, PlayerStateManager players) {
        synchronized (channel) {
            String id = channel.getId();
            for (var state : players.getAllPlayerStates()) {
                if (state.hasJoinedChannel(id) || Objects.equals(state.getActiveChannel(), id)) {
                    players.leaveChannel(state.getPlayerId(), id);
                }
            }
            for (var member : new ArrayList<>(channel.getMembers())) {
                channel.removeMember(member);
            }
        }
    }
}
