package com.nova.chat.client.channel;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelMembershipRestorerTest {
    @Test
    void restoresOnlyCurrentMembershipsWithActiveChannelLast() {
        var playerId = UUID.randomUUID();
        var state = new PlayerChannelState(playerId, "global", ChatMode.REPLACE);
        state.joinChannel("local");
        state.joinChannel("left");
        state.leaveChannel("left");
        var sent = new ArrayList<ChannelActionPacket>();
        ChannelMembershipRestorer.restore(state, "Alice", PlatformType.BUKKIT,
                "world", "bukkit2", sent::add);
        assertThat(sent).extracting(ChannelActionPacket::getChannelId).containsExactly("local", "global");
        assertThat(sent).allSatisfy(packet -> {
            assertThat(packet.getAction()).isEqualTo(ChannelAction.JOIN);
            assertThat(packet.getExtra("playerId")).isEqualTo(playerId.toString());
            assertThat(packet.getExtra("playerName")).isEqualTo("Alice");
            assertThat(packet.getExtra("world")).isEqualTo("world");
            assertThat(packet.getExtra("server")).isEqualTo("bukkit2");
            assertThat(packet.getExtra("platform")).isEqualTo("BUKKIT");
        });
        assertThat(state.getActiveChannel()).isEqualTo("global");
    }

    @Test
    void missingOrEmptyStateNeverManufacturesMembership() {
        var sent = new ArrayList<ChannelActionPacket>();
        ChannelMembershipRestorer.restore(null, "Alice", PlatformType.BUKKIT, null, null, sent::add);
        var state = new PlayerChannelState(UUID.randomUUID(), "global", ChatMode.REPLACE);
        state.leaveChannel("global");
        ChannelMembershipRestorer.restore(state, "Alice", PlatformType.BUKKIT, null, null, sent::add);
        assertThat(sent).isEmpty();
    }
}
