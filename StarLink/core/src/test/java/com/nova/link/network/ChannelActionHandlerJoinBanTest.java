package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.BanInfo;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.mute.MuteManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JOIN must refuse a player who is banned from the target channel, and must
 * stop refusing once that ban expires.
 *
 * <p>The defect these guard: {@code handleJoin} had no ban check at all, so a
 * banned player could rejoin freely and was only silenced by
 * {@code MessagePipeline} stage 4b. {@code BanInfo} and the lite authority both
 * say a ban keeps the player out, so the backend was the outlier.
 *
 * <p>The ban table is driven through the real {@link BanManager} (backed by an
 * in-memory provider) rather than a mock, so these assertions exercise the same
 * {@code isBanned} path production uses — including its global fallback and its
 * expired-entry eviction on read.
 */
@DisplayName("ChannelActionHandler JOIN vs active ban")
class ChannelActionHandlerJoinBanTest {

    private static final String CLIENT_ID = "Survival";
    private static final String CHANNEL = "global";

    private MemoryProvider databaseProvider;
    private ChannelManager channelManager;
    private PermissionManager permissionManager;
    private BanManager banManager;
    private ChannelActionHandler handler;
    private ClientConnection connection;

    private UUID superAdminId;
    private UUID playerId;

    @BeforeEach
    void setUp() throws Exception {
        databaseProvider = new MemoryProvider();
        databaseProvider.initialize();
        channelManager = new ChannelManager();
        permissionManager = new PermissionManager();

        superAdminId = UUID.randomUUID();
        playerId = UUID.randomUUID();
        permissionManager.registerSuperAdmin(new SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        MuteManager muteManager = new MuteManager(databaseProvider, permissionManager, channelManager);
        banManager = new BanManager(databaseProvider, permissionManager, channelManager);

        handler = new ChannelActionHandler(
                channelManager,
                new PlayerStateManager(databaseProvider),
                databaseProvider,
                new PrivateChannelManager(channelManager),
                new InvitationManager(databaseProvider, channelManager),
                permissionManager,
                muteManager,
                banManager
        );

        channelManager.createChannel(ChannelConfig.builder()
                .id(CHANNEL)
                .displayName("Global")
                .scope(ChannelScope.GLOBAL)
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("second")
                .displayName("Second")
                .scope(ChannelScope.GLOBAL)
                .build());

        connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn(CLIENT_ID);
    }

    @AfterEach
    void tearDown() {
        databaseProvider.shutdown();
    }

    private ChannelActionResponsePacket join(String channelId) {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, channelId);
        packet.addExtra("playerId", playerId.toString());
        packet.addExtra("playerName", "Steve");
        return handler.handle(connection, packet);
    }

    @Test
    @DisplayName("a banned player's join is refused with NC-403 and adds no membership")
    void bannedJoinRefused() {
        banManager.banPlayer(superAdminId, playerId, CHANNEL, 60_000, "spam", CLIENT_ID);

        ChannelActionResponsePacket response = join(CHANNEL);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-403");
        assertThat(response.getAction()).isEqualTo(ChannelAction.JOIN);
        assertThat(channelManager.getChannel(CHANNEL).isMember(playerId)).isFalse();
    }

    @Test
    @DisplayName("an unbanned player can join")
    void unbannedPlayerCanJoin() {
        banManager.banPlayer(superAdminId, playerId, CHANNEL, 60_000, "spam", CLIENT_ID);
        banManager.unbanPlayer(superAdminId, playerId, CHANNEL, CLIENT_ID);

        ChannelActionResponsePacket response = join(CHANNEL);

        assertThat(response.isSuccess()).isTrue();
        assertThat(channelManager.getChannel(CHANNEL).isMember(playerId)).isTrue();
    }

    @Test
    @DisplayName("a player who was never banned can join")
    void neverBannedPlayerCanJoin() {
        ChannelActionResponsePacket response = join(CHANNEL);

        assertThat(response.isSuccess()).isTrue();
        assertThat(channelManager.getChannel(CHANNEL).isMember(playerId)).isTrue();
    }

    @Test
    @DisplayName("an expired ban does not block a join")
    void expiredBanDoesNotBlock() throws Exception {
        // Seed a ban whose deadline is already in the past. Written straight to
        // the provider (and loaded by a fresh manager) because banPlayer always
        // computes a future deadline; this is the state a restart or a long
        // offline period produces, which is exactly what must not block.
        long past = System.currentTimeMillis() - 60_000;
        databaseProvider.saveBan(playerId, new BanInfo(CHANNEL, past, "old", superAdminId));

        BanManager restarted = new BanManager(databaseProvider, permissionManager, channelManager);
        restarted.loadAllBans();
        handler = newHandlerWith(restarted);

        ChannelActionResponsePacket response = join(CHANNEL);

        assertThat(response.isSuccess()).isTrue();
        assertThat(channelManager.getChannel(CHANNEL).isMember(playerId)).isTrue();
    }

    @Test
    @DisplayName("a global ban refuses a join to every channel")
    void globalBanRefusesEveryChannel() {
        banManager.banPlayer(superAdminId, playerId, null, 0L, "perm", CLIENT_ID);

        ChannelActionResponsePacket first = join(CHANNEL);
        ChannelActionResponsePacket second = join("second");

        assertThat(first.isSuccess()).isFalse();
        assertThat(first.getErrorCode()).isEqualTo("NC-403");
        assertThat(second.isSuccess()).isFalse();
        assertThat(second.getErrorCode()).isEqualTo("NC-403");
        assertThat(channelManager.getChannel(CHANNEL).isMember(playerId)).isFalse();
        assertThat(channelManager.getChannel("second").isMember(playerId)).isFalse();
    }

    @Test
    @DisplayName("a channel ban does not block a different channel")
    void channelBanIsScoped() {
        banManager.banPlayer(superAdminId, playerId, CHANNEL, 60_000, "spam", CLIENT_ID);

        assertThat(join(CHANNEL).isSuccess()).isFalse();
        assertThat(join("second").isSuccess()).isTrue();
        assertThat(channelManager.getChannel("second").isMember(playerId)).isTrue();
    }

    @Test
    @DisplayName("a banned player cannot use an invitation to re-enter, and the invite is not consumed")
    void bannedPlayerCannotAcceptInvite() throws Exception {
        InvitationManager invitations = new InvitationManager(databaseProvider, channelManager);
        ChannelActionHandler inviteHandler = new ChannelActionHandler(
                channelManager,
                new PlayerStateManager(databaseProvider),
                databaseProvider,
                new PrivateChannelManager(channelManager),
                invitations,
                permissionManager,
                new MuteManager(databaseProvider, permissionManager, channelManager),
                banManager);

        String code = invitations.createInvitation(CHANNEL, superAdminId).getCode();
        banManager.banPlayer(superAdminId, playerId, CHANNEL, 60_000, "spam", CLIENT_ID);

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.ACCEPT, code);
        packet.addExtra("playerId", playerId.toString());
        packet.addExtra("playerName", "Steve");
        ChannelActionResponsePacket response = inviteHandler.handle(connection, packet);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-403");
        assertThat(channelManager.getChannel(CHANNEL).isMember(playerId)).isFalse();
        // A refusal must not burn the invite: it is still valid for a player
        // who is not banned.
        assertThat(invitations.validateInvitation(code).isSuccess()).isTrue();
    }

    private ChannelActionHandler newHandlerWith(BanManager bans) {
        return new ChannelActionHandler(
                channelManager,
                new PlayerStateManager(databaseProvider),
                databaseProvider,
                new PrivateChannelManager(channelManager),
                new InvitationManager(databaseProvider, channelManager),
                permissionManager,
                new MuteManager(databaseProvider, permissionManager, channelManager),
                bans
        );
    }
}
