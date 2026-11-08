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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedProtocolCreationLeavesNoRuntimeStateWithEitherManagerMode(boolean wired) throws Exception {
        var provider = new CountingProvider();
        provider.initialize();
        var channels = new ChannelManager(wired ? provider : null);
        var privateChannels = new PrivateChannelManager(channels);
        var players = new PlayerStateManager(provider);
        UUID owner = UUID.randomUUID();
        provider.failSave = true;
        try {
            var packet = new ChannelActionPacket(ChannelAction.CREATE, "Unsaved");
            packet.addExtra("playerId", owner.toString());
            var response = handler(channels, privateChannels, provider, players).handle(connection(), packet);
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-510");
            assertThat(channels.getAllChannels()).isEmpty();
            assertThat(channels.getChannelsByClient("primary")).isEmpty();
            assertThat(privateChannels.getGeneratedIdCount()).isZero();
            assertThat(players.getPlayerState(owner)).isNull();
            assertThat(provider.loadChannel(provider.lastSaved.getId())).isEmpty();
            assertThat(channels.isPersistenceEnabled()).isEqualTo(wired);
        } finally {
            provider.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedProtocolDeletionKeepsChannelStateAndInvitesWithEitherManagerMode(boolean wired) throws Exception {
        var provider = new CountingProvider();
        provider.initialize();
        var channels = new ChannelManager(wired ? provider : null);
        var privateChannels = new PrivateChannelManager(channels);
        var players = new PlayerStateManager(provider);
        var permissions = new PermissionManager();
        var invitations = new InvitationManager(provider, channels);
        UUID owner = UUID.randomUUID();
        Channel channel = privateChannels.createPrivateChannel("Retained", "primary", owner, "secret").getChannel();
        if (!wired) provider.saveChannel(channel);
        var invitation = invitations.createInvitation(channel.getId(), owner);
        var state = players.getOrCreateState(owner, "Owner");
        players.joinChannel(owner, channel.getId());
        players.setActiveChannel(owner, channel.getId());
        permissions.grantChannelAdmin(channel.getId(), owner);
        provider.failDelete = true;
        try {
            var endpoint = new ChannelActionHandler(channels, players, provider, privateChannels, invitations, permissions);
            var packet = new ChannelActionPacket(ChannelAction.DELETE, channel.getId());
            packet.addExtra("operatorId", owner.toString());
            var response = endpoint.handle(connection(), packet);
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-510");
            assertThat(channels.getChannel(channel.getId())).isSameAs(channel);
            assertThat(channel.isMember(owner)).isTrue();
            assertThat(state.hasJoinedChannel(channel.getId())).isTrue();
            assertThat(state.getActiveChannel()).isEqualTo(channel.getId());
            assertThat(permissions.isChannelAdmin(channel.getId(), owner)).isTrue();
            assertThat(privateChannels.wasIdGenerated(channel.getId())).isTrue();
            assertThat(provider.loadChannel(channel.getId())).isPresent();
            assertThat(invitations.validateInvitation(invitation.getCode()).isSuccess()).isTrue();
            assertThat(channels.isPersistenceEnabled()).isEqualTo(wired);
        } finally {
            provider.shutdown();
        }
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
        boolean failSave;
        boolean failDelete;
        Channel lastSaved;

        @Override
        public void saveChannel(Channel channel) throws DatabaseException {
            saves++;
            lastSaved = channel;
            if (failSave) throw new DatabaseException("injected save failure");
            super.saveChannel(channel);
        }

        @Override
        public void deleteChannel(String channelId) throws DatabaseException {
            deletes++;
            if (failDelete) throw new DatabaseException("injected delete failure");
            super.deleteChannel(channelId);
        }
    }
}
