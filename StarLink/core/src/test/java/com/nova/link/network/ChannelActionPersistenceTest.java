package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChannelActionPersistenceTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void protocolCreationPersistsOnceWithEitherManagerMode(boolean wired) throws Exception {
        var provider = new CountingProvider();
        provider.initialize();
        try {
            var channels = new ChannelManager(wired ? provider : null);
            var privateChannels = new PrivateChannelManager(channels);
            var players = new PlayerStateManager(provider);
            var handler = handler(channels, privateChannels, provider, players);
            var packet = new ChannelActionPacket(ChannelAction.CREATE, "Private");
            packet.addExtra("playerId", UUID.randomUUID().toString());

            var response = handler.handle(connection(), packet);

            assertThat(response.isSuccess()).isTrue();
            assertThat(provider.saves).isEqualTo(1);
            assertThat(provider.loadChannel(response.getChannelId())).isPresent();
        } finally {
            provider.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void protocolDeletionPersistsOnceWithEitherManagerMode(boolean wired) throws Exception {
        var provider = new CountingProvider();
        provider.initialize();
        try {
            var channels = new ChannelManager(wired ? provider : null);
            var privateChannels = new PrivateChannelManager(channels);
            var players = new PlayerStateManager(provider);
            var handler = handler(channels, privateChannels, provider, players);
            UUID owner = UUID.randomUUID();
            Channel channel = privateChannels.createPrivateChannel("Private", "primary", owner, "secret")
                    .getChannel();
            if (!wired) provider.saveChannel(channel);
            var state = players.getOrCreateState(owner, "Owner");
            players.joinChannel(owner, channel.getId());
            players.setActiveChannel(owner, channel.getId());
            var packet = new ChannelActionPacket(ChannelAction.DELETE, channel.getId());
            packet.addExtra("operatorId", owner.toString());

            var response = handler.handle(connection(), packet);

            assertThat(response.isSuccess()).isTrue();
            assertThat(provider.deletes).isEqualTo(1);
            assertThat(provider.loadChannel(channel.getId())).isEmpty();
            assertThat(state.hasJoinedChannel(channel.getId())).isFalse();
            assertThat(state.getActiveChannel()).isNull();
            assertThat(channel.getMembers()).isEmpty();
        } finally {
            provider.shutdown();
        }
    }

    private static ChannelActionHandler handler(ChannelManager channels, PrivateChannelManager privateChannels,
                                                MemoryProvider provider, PlayerStateManager players) {
        return new ChannelActionHandler(channels, players, provider,
                privateChannels, new InvitationManager(provider, channels), new PermissionManager());
    }

    private static ClientConnection connection() {
        var connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn("primary");
        return connection;
    }

    private static class CountingProvider extends MemoryProvider {
        int saves;
        int deletes;

        @Override
        public void saveChannel(Channel channel) throws DatabaseException {
            saves++;
            super.saveChannel(channel);
        }

        @Override
        public void deleteChannel(String channelId) throws DatabaseException {
            deletes++;
            super.deleteChannel(channelId);
        }
    }
}
