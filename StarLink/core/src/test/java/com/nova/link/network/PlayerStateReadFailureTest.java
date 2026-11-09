package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.DatabaseException;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerStateReadFailureTest {
    @ParameterizedTest
    @EnumSource(value = ChannelAction.class, names = {"JOIN", "CREATE", "ACCEPT", "LEAVE"})
    void failedPlayerReadLeavesAdmissionUnchangedAndCanBeRetried(ChannelAction action) throws Exception {
        UUID player = UUID.randomUUID();
        var db = new MemoryProvider() {
            boolean fail;

            @Override
            public Optional<PlayerState> loadPlayerState(UUID id) throws DatabaseException {
                if (fail && id.equals(player)) {
                    throw new DatabaseException("injected player read failure");
                }
                return super.loadPlayerState(id);
            }
        };
        db.initialize();
        var channels = new ChannelManager(db);
        var privateChannels = new PrivateChannelManager(channels);
        var invitations = new InvitationManager(db, channels);
        var permissions = new PermissionManager();
        var players = new PlayerStateManager(db);
        var handler = new ChannelActionHandler(channels, players, db, privateChannels, invitations, permissions);
        var connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn("client");
        try {
            var stored = new PlayerState(player, "Player");
            stored.setDmEnabled(false);
            stored.addJoinedChannel("stored");
            db.savePlayerState(stored);
            var global = channels.createChannel(ChannelConfig.builder()
                    .id("global").scope(ChannelScope.GLOBAL).build());
            UUID owner = UUID.randomUUID();
            var privateChannel = privateChannels.createPrivateChannel("Private", "client", owner, "password");
            var invitation = invitations.createInvitation(privateChannel.getChannelId(), owner, player);
            int channelCount = channels.getAllChannels().size();
            if (action == ChannelAction.LEAVE) {
                global.addMember(player);
            }
            String target = switch (action) {
                case ACCEPT -> invitation.getCode();
                case CREATE -> "New private";
                default -> "global";
            };
            var request = new ChannelActionPacket(action, target);
            request.addExtra("playerId", player.toString());
            request.addExtra("playerName", "Player");
            db.fail = true;

            var response = handler.handle(connection, request);

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-510");
            assertThat(players.getCachedState(player)).isEmpty();
            assertThat(players.getDirtyCount()).isZero();
            assertThat(channels.getAllChannels()).hasSize(channelCount);
            assertThat(global.isMember(player)).isEqualTo(action == ChannelAction.LEAVE);
            assertThat(privateChannel.getChannel().isMember(player)).isFalse();
            assertThat(permissions.isChannelAdmin(privateChannel.getChannelId(), player)).isFalse();
            assertThat(db.loadInvitation(invitation.getCode()).orElseThrow().getUsedCount()).isZero();

            db.fail = false;
            assertThat(handler.handle(connection, request).isSuccess()).isTrue();
            assertThat(players.getPlayerState(player).isDmEnabled()).isFalse();
            assertThat(players.getPlayerState(player).getJoinedChannels()).contains("stored");
            assertThat(players.saveAllDirty()).isEqualTo(1);
            assertThat(db.loadPlayerState(player).orElseThrow().isDmEnabled()).isFalse();
            assertThat(db.loadPlayerState(player).orElseThrow().getJoinedChannels()).contains("stored");
        } finally {
            db.shutdown();
        }
    }
}
