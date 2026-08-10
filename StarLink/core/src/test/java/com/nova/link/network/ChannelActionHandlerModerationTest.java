package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.mute.MuteManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for KICK/MUTE/UNMUTE/DELETE channel actions.
 * Ensures unsupported actions no longer return a fake success.
 */
@DisplayName("ChannelActionHandler moderation")
class ChannelActionHandlerModerationTest {

    private ChannelManager channelManager;
    private PermissionManager permissionManager;
    private MuteManager muteManager;
    private ChannelActionHandler handler;
    private ClientConnection connection;

    private UUID superAdminId;
    private UUID targetId;
    private UUID playerId;

    @BeforeEach
    void setUp() throws Exception {
        channelManager = new ChannelManager();
        permissionManager = new PermissionManager();
        DatabaseProvider db = new MemoryProvider();
        db.initialize();
        PlayerStateManager playerStateManager = new PlayerStateManager(db);
        PrivateChannelManager privateChannelManager = new PrivateChannelManager(channelManager);
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        muteManager = new MuteManager(db, permissionManager, channelManager);

        handler = new ChannelActionHandler(
                channelManager,
                playerStateManager,
                db,
                privateChannelManager,
                invitationManager,
                permissionManager,
                muteManager
        );

        superAdminId = UUID.randomUUID();
        targetId = UUID.randomUUID();
        playerId = UUID.randomUUID();

        permissionManager.registerSuperAdmin(new SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        channelManager.createChannel(ChannelConfig.builder()
                .id("local")
                .displayName("Local")
                .scope(ChannelScope.SERVER)
                .clientId("Survival")
                .build());
        channelManager.addMember("local", targetId);
        channelManager.addMember("local", playerId);

        connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn("Survival");
    }

    @Nested
    @DisplayName("MUTE")
    class Mute {

        @Test
        @DisplayName("super admin can mute a player")
        void muteSuccess() {
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.MUTE, "local");
            packet.addExtra("operatorId", superAdminId.toString());
            packet.addExtra("targetId", targetId.toString());
            packet.addExtra("duration", "60");

            ChannelActionResponsePacket response = handler.handle(connection, packet);
            assertThat(response.isSuccess()).isTrue();
            assertThat(muteManager.isMuted(targetId, "local")).isTrue();
        }

        @Test
        @DisplayName("regular player cannot mute")
        void muteForbidden() {
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.MUTE, "local");
            packet.addExtra("operatorId", playerId.toString());
            packet.addExtra("targetId", targetId.toString());
            packet.addExtra("duration", "60");

            ChannelActionResponsePacket response = handler.handle(connection, packet);
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-403");
            assertThat(muteManager.isMuted(targetId, "local")).isFalse();
        }
    }

    @Nested
    @DisplayName("UNMUTE")
    class Unmute {

        @Test
        @DisplayName("super admin can unmute")
        void unmuteSuccess() {
            muteManager.mutePlayer(superAdminId, targetId, "local", 60_000, "test", "Survival");

            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.UNMUTE, "local");
            packet.addExtra("operatorId", superAdminId.toString());
            packet.addExtra("targetId", targetId.toString());

            ChannelActionResponsePacket response = handler.handle(connection, packet);
            assertThat(response.isSuccess()).isTrue();
            assertThat(muteManager.isMuted(targetId, "local")).isFalse();
        }
    }

    @Nested
    @DisplayName("KICK")
    class Kick {

        @Test
        @DisplayName("super admin can kick a member")
        void kickSuccess() {
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.KICK, "local");
            packet.addExtra("operatorId", superAdminId.toString());
            packet.addExtra("targetId", targetId.toString());

            ChannelActionResponsePacket response = handler.handle(connection, packet);
            assertThat(response.isSuccess()).isTrue();
            assertThat(channelManager.getChannel("local").isMember(targetId)).isFalse();
        }

        @Test
        @DisplayName("kick of non-member fails")
        void kickNonMember() {
            UUID stranger = UUID.randomUUID();
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.KICK, "local");
            packet.addExtra("operatorId", superAdminId.toString());
            packet.addExtra("targetId", stranger.toString());

            ChannelActionResponsePacket response = handler.handle(connection, packet);
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-433");
        }
    }

    @Nested
    @DisplayName("DELETE")
    class Delete {

        @Test
        @DisplayName("owner can delete private channel")
        void deletePrivate() {
            PrivateChannelManager pcm = new PrivateChannelManager(channelManager);
            // rebuild handler with same managers is unnecessary; use existing channelManager
            var created = pcm.createPrivateChannel("Party", "Survival", superAdminId, "secret1");
            String channelId = created.getChannelId();
            permissionManager.grantChannelAdmin(channelId, superAdminId);

            // re-create handler with same private manager reference for tracking cleanup
            // (delete uses privateChannelManager.removeTrackedId; tracking is local to pcm)
            // Use a handler that shares this pcm:
            ChannelActionHandler localHandler = new ChannelActionHandler(
                    channelManager,
                    new PlayerStateManager(new MemoryProvider()),
                    new MemoryProvider(),
                    pcm,
                    new InvitationManager(new MemoryProvider(), channelManager),
                    permissionManager,
                    muteManager
            );

            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.DELETE, channelId);
            packet.addExtra("operatorId", superAdminId.toString());

            ChannelActionResponsePacket response = localHandler.handle(connection, packet);
            assertThat(response.isSuccess()).isTrue();
            assertThat(channelManager.getChannel(channelId)).isNull();
        }

        @Test
        @DisplayName("server-scoped channel cannot be deleted via protocol")
        void deleteServerChannelRejected() {
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.DELETE, "local");
            packet.addExtra("operatorId", superAdminId.toString());

            ChannelActionResponsePacket response = handler.handle(connection, packet);
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-403");
            assertThat(channelManager.getChannel("local")).isNotNull();
        }
    }

    @Test
    @DisplayName("unauthenticated connection is rejected")
    void unauthenticated() {
        ClientConnection unauth = mock(ClientConnection.class);
        when(unauth.isAuthenticated()).thenReturn(false);

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.MUTE, "local");
        packet.addExtra("operatorId", superAdminId.toString());
        packet.addExtra("targetId", targetId.toString());

        ChannelActionResponsePacket response = handler.handle(unauth, packet);
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-401");
    }
}
