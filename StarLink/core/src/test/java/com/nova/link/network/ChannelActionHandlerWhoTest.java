package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChannelAction#WHO}: online member listing.
 * WHO is a read-only query; tests assert the response extra carries the
 * member names + count and that cross-client boundaries are enforced.
 */
@DisplayName("ChannelActionHandler WHO")
class ChannelActionHandlerWhoTest {

    private ChannelManager channelManager;
    private PlayerStateManager playerStateManager;
    private ChannelActionHandler handler;
    private ClientConnection connection;

    private UUID aliceId;
    private UUID bobId;
    private UUID carolId;
    private UUID requesterId;

    @BeforeEach
    void setUp() throws Exception {
        channelManager = new ChannelManager();
        DatabaseProvider db = new MemoryProvider();
        db.initialize();
        playerStateManager = new PlayerStateManager(db);
        PermissionManager permissionManager = new PermissionManager();
        PrivateChannelManager privateChannelManager = new PrivateChannelManager(channelManager);
        InvitationManager invitationManager = new InvitationManager(db, channelManager);
        MuteManager muteManager = new MuteManager(db, permissionManager, channelManager);

        handler = new ChannelActionHandler(
                channelManager,
                playerStateManager,
                db,
                privateChannelManager,
                invitationManager,
                permissionManager,
                muteManager
        );

        aliceId = UUID.randomUUID();
        bobId = UUID.randomUUID();
        carolId = UUID.randomUUID();
        requesterId = UUID.randomUUID();

        // Seed player names via getOrCreateState so the cache has display names.
        playerStateManager.getOrCreateState(aliceId, "Alice");
        playerStateManager.getOrCreateState(bobId, "Bob");
        // Carol has no cached name -> should fall back to UUID string.

        connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn("Survival");
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("WHO returns sorted member names + count + displayName")
        void returnsMemberList() {
            channelManager.createChannel(ChannelConfig.builder()
                    .id("local")
                    .displayName("Local Chat")
                    .scope(ChannelScope.SERVER)
                    .clientId("Survival")
                    .build());
            channelManager.addMember("local", aliceId);
            channelManager.addMember("local", bobId);
            channelManager.addMember("local", carolId);

            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, "local");
            packet.addExtra("requesterId", requesterId.toString());
            packet.addExtra("requesterName", "Requester");

            ChannelActionResponsePacket response = handler.handle(connection, packet);

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getAction()).isEqualTo(ChannelAction.WHO);
            assertThat(response.getChannelId()).isEqualTo("local");
            assertThat(response.getExtra("memberCount")).isEqualTo("3");
            assertThat(response.getExtra("displayName")).isEqualTo("Local Chat");

            // Alice, Bob are named; Carol falls back to her UUID. Names are sorted
            // case-insensitively, UUID fallbacks appended after named entries.
            String members = response.getExtra("members");
            Set<String> memberSet = new HashSet<>(Arrays.asList(members.split(", ")));
            assertThat(memberSet).contains("Alice", "Bob", carolId.toString());
            // Alice should sort before Bob.
            assertThat(members.indexOf("Alice")).isLessThan(members.indexOf("Bob"));

            // Requester identity is echoed back for client-side routing.
            assertThat(response.getExtra("requesterId")).isEqualTo(requesterId.toString());
            assertThat(response.getExtra("requesterName")).isEqualTo("Requester");
        }

        @Test
        @DisplayName("WHO on an empty channel returns count=0 and empty members")
        void emptyChannel() {
            channelManager.createChannel(ChannelConfig.builder()
                    .id("empty")
                    .displayName("Empty")
                    .scope(ChannelScope.SERVER)
                    .clientId("Survival")
                    .build());

            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, "empty");

            ChannelActionResponsePacket response = handler.handle(connection, packet);

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getExtra("memberCount")).isEqualTo("0");
            assertThat(response.getExtra("members")).isEqualTo("");
        }

        @Test
        @DisplayName("GLOBAL channel WHO works without client boundary check")
        void globalChannel() {
            channelManager.createChannel(ChannelConfig.builder()
                    .id("global")
                    .displayName("Global")
                    .scope(ChannelScope.GLOBAL)
                    .build());
            channelManager.addMember("global", aliceId);
            channelManager.addMember("global", bobId);

            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, "global");

            ChannelActionResponsePacket response = handler.handle(connection, packet);

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getExtra("memberCount")).isEqualTo("2");
            assertThat(response.getExtra("members")).isEqualTo("Alice, Bob");
        }
    }

    @Nested
    @DisplayName("error paths")
    class Errors {

        @Test
        @DisplayName("missing channelId is rejected with NC-400")
        void missingChannelId() {
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, "");

            ChannelActionResponsePacket response = handler.handle(connection, packet);

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-400");
        }

        @Test
        @DisplayName("unknown channel is rejected with NC-404")
        void unknownChannel() {
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, "nope");

            ChannelActionResponsePacket response = handler.handle(connection, packet);

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-404");
        }

        @Test
        @DisplayName("cross-client WHO on a SERVER channel is denied with NC-403")
        void crossClientDenied() {
            channelManager.createChannel(ChannelConfig.builder()
                    .id("other-server")
                    .displayName("Other")
                    .scope(ChannelScope.SERVER)
                    .clientId("Creative")
                    .build());
            channelManager.addMember("other-server", aliceId);

            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, "other-server");

            ChannelActionResponsePacket response = handler.handle(connection, packet);

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-403");
        }

        @Test
        @DisplayName("unauthenticated connection is rejected with NC-401")
        void unauthenticated() {
            ClientConnection unauth = mock(ClientConnection.class);
            when(unauth.isAuthenticated()).thenReturn(false);

            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.WHO, "global");

            ChannelActionResponsePacket response = handler.handle(unauth, packet);

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-401");
        }
    }
}
