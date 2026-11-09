package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.MessageRouter;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.Invitation;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivateChannelInvitationAccessTest {
    private MemoryProvider db;
    private ChannelManager channels;
    private PlayerStateManager players;
    private InvitationManager invitations;
    private PermissionManager permissions;
    private ChannelActionHandler handler;
    private MessageRouter router;
    private Channel channel;
    private ClientConnection primary;
    private ClientConnection secondary;
    private ClientConnection unrelated;
    private UUID owner;
    private UUID invitee;
    private UUID outsider;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();
        channels = new ChannelManager();
        players = new PlayerStateManager(db);
        invitations = new InvitationManager(db, channels);
        permissions = new PermissionManager();
        PrivateChannelManager privateChannels = new PrivateChannelManager(channels);
        owner = UUID.randomUUID();
        invitee = UUID.randomUUID();
        outsider = UUID.randomUUID();
        channel = privateChannels.createPrivateChannel("Private", "primary", owner, "secret").getChannel();
        permissions.grantChannelAdmin(channel.getId(), owner);
        handler = new ChannelActionHandler(channels, players, db, privateChannels, invitations, permissions);
        primary = connection("primary");
        secondary = connection("secondary");
        unrelated = connection("proxy-relay-with-no-members");
        state(owner, "Owner", "primary").addJoinedChannel(channel.getId());
        state(invitee, "Invitee", "secondary");
        state(outsider, "Outsider", "primary");
        ServerNetworkHandler network = mock(ServerNetworkHandler.class);
        when(network.findByClientId("primary")).thenReturn(primary);
        when(network.findByClientId("secondary")).thenReturn(secondary);
        when(network.findByClientId("proxy-relay-with-no-members")).thenReturn(unrelated);
        router = new MessageRouter(channels, network);
        router.getPipeline().setPlayerStateManager(players);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @Test
    void targetedInviteAllowsCrossServerMembershipAndBidirectionalDelivery() {
        String code = invite();
        assertThat(action(secondary, invitee, ChannelAction.ACCEPT, code).isSuccess()).isTrue();
        ChannelActionResponsePacket who = action(secondary, invitee, ChannelAction.WHO, channel.getId());
        assertThat(who.isSuccess()).isTrue();
        assertThat(who.getExtra("members")).isEqualTo("Invitee, Owner");
        assertThat(who.getExtra("memberCount")).isEqualTo("2");
        assertThat(route(owner, "primary")).containsExactlyInAnyOrder("primary", "secondary");
        assertThat(route(invitee, "secondary")).containsExactlyInAnyOrder("primary", "secondary");
        verify(unrelated, never()).sendPacket(any());
        assertThat(channel.isMember(outsider)).isFalse();
    }

    @Test
    void wrongRecipientCannotConsumeTargetedInviteOnEitherClient() throws Exception {
        String code = invite();
        assertThat(action(primary, outsider, ChannelAction.ACCEPT, code).getErrorCode()).isEqualTo("NC-403");
        assertThat(action(secondary, outsider, ChannelAction.ACCEPT, code).getErrorCode()).isEqualTo("NC-403");
        assertThat(db.loadInvitation(code).orElseThrow().getUsedCount()).isZero();
        assertThat(channel.isMember(outsider)).isFalse();
        assertThat(action(secondary, invitee, ChannelAction.ACCEPT, code).isSuccess()).isTrue();
        assertThat(action(secondary, invitee, ChannelAction.ACCEPT, code).getErrorCode()).isEqualTo("NC-411");
    }

    @Test
    void targetedBindingSurvivesReloadingTheManager() throws Exception {
        String code = invite();
        InvitationManager reloaded = new InvitationManager(db, channels);
        assertThat(reloaded.acceptInvitation(code, outsider, "secondary").getErrorCode()).isEqualTo("NC-403");
        assertThat(reloaded.acceptInvitation(code, invitee, "secondary").isSuccess()).isTrue();
    }

    @Test
    void directJoinCannotBypassInvitationEvenWithThePassword() {
        ChannelActionPacket join = packet(invitee, ChannelAction.JOIN, channel.getId());
        join.setPassword("secret");
        assertThat(handler.handle(secondary, join).getErrorCode()).isEqualTo("NC-403");
        assertThat(action(primary, outsider, ChannelAction.JOIN, channel.getId()).getErrorCode()).isEqualTo("NC-434");
        assertThat(action(secondary, invitee, ChannelAction.WHO, channel.getId()).getErrorCode()).isEqualTo("NC-403");
        assertThat(route(invitee, "secondary")).isEmpty();
    }

    @Test
    void acceptedMemberCanRestoreAcrossLinksButLeaveRevokesAccess() {
        assertThat(action(secondary, invitee, ChannelAction.ACCEPT, invite()).isSuccess()).isTrue();
        assertThat(action(secondary, invitee, ChannelAction.JOIN, channel.getId()).isSuccess()).isTrue();
        assertThat(action(secondary, invitee, ChannelAction.LEAVE, channel.getId()).isSuccess()).isTrue();
        assertThat(action(secondary, invitee, ChannelAction.JOIN, channel.getId()).getErrorCode()).isEqualTo("NC-403");
        assertThat(action(secondary, invitee, ChannelAction.WHO, channel.getId()).getErrorCode()).isEqualTo("NC-403");
        assertThat(route(invitee, "secondary")).isEmpty();
        assertThat(route(owner, "primary")).containsExactly("primary");
    }

    @Test
    void invalidExpiredAndRevokedCodesNeverGrantMembership() throws Exception {
        assertThat(action(secondary, invitee, ChannelAction.ACCEPT, "NOPE00").getErrorCode()).isEqualTo("NC-404");
        String revoked = invite();
        invitations.revokeInvitation(revoked, owner);
        assertThat(action(secondary, invitee, ChannelAction.ACCEPT, revoked).getErrorCode()).isEqualTo("NC-410");
        String expired = invite();
        Invitation saved = db.loadInvitation(expired).orElseThrow();
        db.saveInvitation(new Invitation(saved.getCode(), saved.getChannelId(), saved.getInviterId(),
                System.currentTimeMillis() - 1, saved.getCreatedAt(), false, null, 0,
                1, 0, null, invitee));
        assertThat(action(secondary, invitee, ChannelAction.ACCEPT, expired).getErrorCode()).isEqualTo("NC-410");
        assertThat(channel.isMember(invitee)).isFalse();
        assertThat(db.loadInvitation(expired).orElseThrow().getUsedCount()).isZero();
    }

    @Test
    void legacyUnboundPrivateInviteCannotAuthorizeAnotherClient() throws Exception {
        String code = invitations.createInvitation(channel.getId(), owner).getCode();
        assertThat(action(secondary, invitee, ChannelAction.ACCEPT, code).getErrorCode()).isEqualTo("NC-403");
        assertThat(invitations.validateInvitation(code).isSuccess()).isTrue();
    }

    @Test
    void unresolvedOrConflictingTargetNeverProducesAnInvite() {
        ChannelActionPacket unknown = packet(owner, ChannelAction.INVITE, channel.getId());
        unknown.addExtra("targetName", "MissingPlayer");
        assertThat(handler.handle(primary, unknown).getErrorCode()).isEqualTo("NC-404");
        unknown.addExtra("targetId", invitee.toString());
        assertThat(handler.handle(primary, unknown).getErrorCode()).isEqualTo("NC-400");
    }

    @Test
    void serverChannelsRemainBoundToTheirOwningClient() {
        Channel server = channels.createChannel(ChannelConfig.builder().id("server").scope(ChannelScope.SERVER).clientId("primary").build());
        server.addMember(invitee);
        players.getPlayerState(invitee).addJoinedChannel(server.getId());
        assertThat(action(secondary, invitee, ChannelAction.JOIN, server.getId()).getErrorCode()).isEqualTo("NC-403");
        assertThat(action(secondary, invitee, ChannelAction.WHO, server.getId()).getErrorCode()).isEqualTo("NC-403");
        assertThat(action(secondary, invitee, ChannelAction.LEAVE, server.getId()).getErrorCode()).isEqualTo("NC-403");
        assertThat(router.routeMessage(new ChatMessagePacket(invitee, "Invitee", "secondary", server.getId(), "blocked"))).isEmpty();
    }

    @Test
    void memberCannotSendThroughAnUnrelatedClientIdentity() {
        assertThat(action(secondary, invitee, ChannelAction.ACCEPT, invite()).isSuccess()).isTrue();
        assertThat(route(invitee, "proxy-relay-with-no-members")).isEmpty();
        assertThat(route(outsider, "primary")).isEmpty();
    }

    @Test
    void anotherClientCannotReadPrivateMembersByClaimingAnExistingMembersIdentity() {
        assertThat(action(secondary, invitee, ChannelAction.ACCEPT, invite()).isSuccess()).isTrue();

        var response = action(unrelated, invitee, ChannelAction.WHO, channel.getId());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-403");
        assertThat(response.getExtra("members")).isNull();
        assertThat(response.getExtra("memberCount")).isNull();
        assertThat(action(secondary, invitee, ChannelAction.WHO, channel.getId()).isSuccess()).isTrue();
        assertThat(players.getPlayerState(invitee).getClientId()).isEqualTo("secondary");
        assertThat(channel.isMember(invitee)).isTrue();
    }

    @Test
    void aTransferredMemberCanReadPrivateMembersOnlyFromItsCurrentClient() {
        assertThat(action(secondary, invitee, ChannelAction.ACCEPT, invite()).isSuccess()).isTrue();
        assertThat(action(unrelated, invitee, ChannelAction.JOIN, channel.getId()).isSuccess()).isTrue();

        var response = action(secondary, invitee, ChannelAction.WHO, channel.getId());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-403");
        assertThat(response.getExtra("members")).isNull();
        assertThat(response.getExtra("memberCount")).isNull();
        var current = action(unrelated, invitee, ChannelAction.WHO, channel.getId());
        assertThat(current.isSuccess()).isTrue();
        assertThat(current.getExtra("members")).isEqualTo("Invitee, Owner");
        assertThat(current.getExtra("memberCount")).isEqualTo("2");
    }

    @Test
    void onlyTheInvitersCurrentClientCanCreateInvitations() {
        var request = packet(owner, ChannelAction.INVITE, channel.getId());
        request.addExtra("targetId", invitee.toString());

        assertThat(handler.handle(unrelated, request).getErrorCode()).isEqualTo("NC-403");
        assertThat(db.getInvitationCount()).isZero();
        assertThat(handler.handle(primary, request).isSuccess()).isTrue();
        assertThat(db.getInvitationCount()).isEqualTo(1);

        assertThat(action(secondary, owner, ChannelAction.JOIN, channel.getId()).isSuccess()).isTrue();
        assertThat(handler.handle(primary, request).getErrorCode()).isEqualTo("NC-403");
        assertThat(db.getInvitationCount()).isEqualTo(1);
        assertThat(handler.handle(secondary, request).isSuccess()).isTrue();
        assertThat(db.getInvitationCount()).isEqualTo(2);

        var logout = packet(owner, ChannelAction.LEAVE, channel.getId());
        logout.addExtra("logout", "true");
        assertThat(handler.handle(secondary, logout).isSuccess()).isTrue();
        assertThat(handler.handle(secondary, request).getErrorCode()).isEqualTo("NC-403");
        assertThat(db.getInvitationCount()).isEqualTo(2);
        assertThat(players.getPlayerState(owner)).isNull();
    }

    @Test
    void anInvitationQueuedBeforeMigrationChecksOwnershipInsideThePlayerBoundary() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var armed = new AtomicBoolean(true);
        var bans = new BanManager(db, permissions, channels, players) {
            @Override
            public <T> T withAdmission(UUID id, Supplier<T> operation) {
                if (armed.compareAndSet(true, false)) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Invitation gate timed out");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                }
                return super.withAdmission(id, operation);
            }
        };
        handler = new ChannelActionHandler(channels, players, db, new PrivateChannelManager(channels),
                invitations, permissions, null, bans);
        var request = packet(owner, ChannelAction.INVITE, channel.getId());
        request.addExtra("targetId", invitee.toString());
        var result = new FutureTask<>(() -> handler.handle(primary, request));
        var inviting = new Thread(result, "player-invite");
        try {
            inviting.start();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(action(secondary, owner, ChannelAction.JOIN, channel.getId()).isSuccess()).isTrue();
            release.countDown();

            var response = result.get(5, TimeUnit.SECONDS);
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getErrorCode()).isEqualTo("NC-403");
            assertThat(db.getInvitationCount()).isZero();
            assertThat(players.getPlayerState(owner).getClientId()).isEqualTo("secondary");
            assertThat(channel.isMember(owner)).isTrue();
        } finally {
            release.countDown();
            inviting.join(5_000);
            if (inviting.isAlive()) {
                inviting.interrupt();
                inviting.join(5_000);
            }
            assertThat(inviting.isAlive()).isFalse();
        }
    }

    private String invite() {
        ChannelActionPacket packet = packet(owner, ChannelAction.INVITE, channel.getId());
        packet.addExtra("targetName", "Invitee");
        ChannelActionResponsePacket response = handler.handle(primary, packet);
        assertThat(response.isSuccess()).isTrue();
        return response.getExtra("code");
    }

    private java.util.Set<String> route(UUID player, String client) {
        return router.routeMessage(new ChatMessagePacket(player, "Sender", client, channel.getId(), "message"));
    }

    private ChannelActionResponsePacket action(ClientConnection connection, UUID player, ChannelAction action, String id) {
        return handler.handle(connection, packet(player, action, id));
    }

    private ChannelActionPacket packet(UUID player, ChannelAction action, String id) {
        ChannelActionPacket packet = new ChannelActionPacket(action, id);
        packet.addExtra("playerId", player.toString());
        packet.addExtra("playerName", players.getPlayerState(player).getPlayerName());
        packet.addExtra("requesterId", player.toString());
        return packet;
    }

    private PlayerState state(UUID player, String name, String client) {
        PlayerState state = players.getOrCreateState(player, name);
        state.setClientId(client);
        return state;
    }

    private ClientConnection connection(String client) {
        ClientConnection connection = mock(ClientConnection.class);
        when(connection.getClientId()).thenReturn(client);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.isActive()).thenReturn(true);
        when(connection.sendPacket(any())).thenReturn(CompletableFuture.completedFuture(null));
        return connection;
    }
}
