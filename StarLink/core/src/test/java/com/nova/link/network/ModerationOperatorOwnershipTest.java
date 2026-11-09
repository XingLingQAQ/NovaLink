package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.ban.BanManager;
import com.nova.link.channel.Channel;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.console.ConsoleSentinel;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.mute.MuteManager;
import com.nova.link.notification.NotificationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ModerationOperatorOwnershipTest {
    private MemoryProvider db;
    private ChannelManager channels;
    private PlayerStateManager players;
    private MuteManager mutes;
    private BanManager bans;
    private ChannelActionHandler handler;
    private NotificationStore notifications;
    private UUID operator;
    private UUID target;
    private ClientConnection oldClient;
    private ClientConnection newClient;
    private Channel channel;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();
        channels = new ChannelManager();
        players = new PlayerStateManager(db);
        var permissions = new PermissionManager();
        operator = UUID.randomUUID();
        target = UUID.randomUUID();
        permissions.registerSuperAdmin(new SuperAdminCredentials(operator, "test-hash"));
        permissions.authenticateSuperAdmin(operator, "test-hash");
        mutes = new MuteManager(db, permissions, channels);
        bans = new BanManager(db, permissions, channels, players);
        handler = new ChannelActionHandler(channels, players, db, new PrivateChannelManager(channels),
                new InvitationManager(db, channels), permissions, mutes, bans);
        notifications = mock(NotificationStore.class);
        handler.setNotificationStore(notifications);
        channel = channels.createChannel(ChannelConfig.builder().id("managed")
                .scope(ChannelScope.PRIVATE).clientId("old").ownerId(operator).build());
        channels.createChannel(ChannelConfig.builder().id("global").scope(ChannelScope.GLOBAL).build());
        var targetState = players.getOrCreateState(target, "Target");
        targetState.setClientId("target-client");
        targetState.addJoinedChannel(channel.getId());
        targetState.setActiveChannel(channel.getId());
        channel.addMember(target);
        oldClient = connection("old");
        newClient = connection("new");
        joinOperator(oldClient);
        joinOperator(newClient);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @ParameterizedTest
    @EnumSource(value = ChannelAction.class, names = {"KICK", "MUTE", "UNMUTE", "BAN", "UNBAN", "DELETE"})
    void anotherClientsOrOfflineOperatorsAuthorityCannotBeBorrowed(ChannelAction action) {
        var request = request(action, channel.getId(), operator);
        prepareRemoval(action, channel.getId());
        boolean member = channel.isMember(target);
        boolean muted = mutes.isMuted(target, channel.getId());
        boolean banned = bans.isBanned(target, channel.getId());

        assertThat(handler.handle(oldClient, request).getErrorCode()).isEqualTo("NC-403");
        assertUnchanged(member, muted, banned);

        players.removeFromCache(operator, true);
        assertThat(handler.handle(newClient, request).getErrorCode()).isEqualTo("NC-403");
        assertUnchanged(member, muted, banned);
        assertThat(players.getPlayerState(operator)).isNull();

        joinOperator(newClient);
        assertThat(handler.handle(newClient, request).isSuccess()).isTrue();
        switch (action) {
            case KICK, BAN -> assertThat(channel.isMember(target)).isFalse();
            case MUTE -> assertThat(mutes.isMuted(target, channel.getId())).isTrue();
            case UNMUTE -> assertThat(mutes.isMuted(target, channel.getId())).isFalse();
            case UNBAN -> assertThat(bans.isBanned(target, channel.getId())).isFalse();
            case DELETE -> assertThat(channels.getChannel(channel.getId())).isNull();
            default -> throw new AssertionError(action);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ChannelAction.class, names = {"BAN", "UNBAN"})
    void networkWideBansUseTheSameOperatorIdentityCheck(ChannelAction action) {
        prepareRemoval(action, null);
        boolean before = bans.isBanned(target, null);
        var request = request(action, null, operator);

        assertThat(handler.handle(oldClient, request).getErrorCode()).isEqualTo("NC-403");
        assertThat(bans.isBanned(target, null)).isEqualTo(before);
        assertThat(handler.handle(newClient, request).isSuccess()).isTrue();
        assertThat(bans.isBanned(target, null)).isEqualTo(action == ChannelAction.BAN);
    }

    @Test
    void authenticatedClientConsoleKeepsItsExistingModerationAuthority() {
        var request = request(ChannelAction.MUTE, channel.getId(), ConsoleSentinel.CONSOLE_SENTINEL);
        request.addExtra("console", "true");

        assertThat(handler.handle(oldClient, request).isSuccess()).isTrue();
        assertThat(mutes.isMuted(target, channel.getId())).isTrue();
    }

    @Test
    void anOperatorCanBanItselfThroughTheTargetMutationBoundary() {
        var request = request(ChannelAction.BAN, null, operator);
        request.addExtra("targetId", operator.toString());

        assertThat(handler.handle(newClient, request).isSuccess()).isTrue();
        assertThat(bans.isBanned(operator, null)).isTrue();
        assertThat(players.getPlayerState(operator).getJoinedChannels()).isEmpty();
    }

    private void assertUnchanged(boolean member, boolean muted, boolean banned) {
        assertThat(channels.getChannel(channel.getId())).isSameAs(channel);
        assertThat(channel.isMember(target)).isEqualTo(member);
        assertThat(mutes.isMuted(target, channel.getId())).isEqualTo(muted);
        assertThat(bans.isBanned(target, channel.getId())).isEqualTo(banned);
        verifyNoInteractions(notifications);
    }

    private void prepareRemoval(ChannelAction action, String channelId) {
        if (action == ChannelAction.UNMUTE) {
            assertThat(mutes.mutePlayer(operator, target, channelId, 0, "test", "new").isSuccess()).isTrue();
        } else if (action == ChannelAction.UNBAN) {
            assertThat(bans.banPlayer(operator, target, channelId, 0, "test", "new").isSuccess()).isTrue();
        }
    }

    private void joinOperator(ClientConnection connection) {
        var join = new ChannelActionPacket(ChannelAction.JOIN, "global");
        join.addExtra("playerId", operator.toString());
        join.addExtra("playerName", "Operator");
        assertThat(handler.handle(connection, join).isSuccess()).isTrue();
    }

    private ChannelActionPacket request(ChannelAction action, String channelId, UUID actor) {
        var request = new ChannelActionPacket(action, channelId);
        request.addExtra("operatorId", actor.toString());
        request.addExtra("targetId", target.toString());
        return request;
    }

    private static ClientConnection connection(String id) {
        var connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn(id);
        return connection;
    }
}
