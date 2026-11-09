package com.nova.link.channel;

import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.console.BackendContext;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.i18n.I18n;
import com.nova.link.network.AdminActionHandler;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TitleRoutingTest {
    private MemoryProvider db;
    private ChannelManager channels;
    private PlayerStateManager players;
    private ServerNetworkHandler network;
    private MessageRouter router;
    private AdminActionHandler admin;
    private ConsoleCommandHandler console;
    private UUID operator;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryProvider();
        db.initialize();
        channels = new ChannelManager();
        players = new PlayerStateManager(db);
        network = mock(ServerNetworkHandler.class);
        router = new MessageRouter(channels, network);
        router.getPipeline().setPlayerStateManager(players);
        var permissions = new PermissionManager();
        operator = UUID.randomUUID();
        permissions.registerSuperAdmin(new SuperAdminCredentials(operator, "test-hash"));
        permissions.authenticateSuperAdmin(operator, "test-hash");
        admin = new AdminActionHandler(permissions);
        admin.setChannelManager(channels);
        admin.setNetworkHandler(network);
        admin.setMessageRouter(router);
        var context = mock(BackendContext.class);
        when(context.getChannelManager()).thenReturn(channels);
        when(context.getNetworkHandler()).thenReturn(network);
        when(context.getMessageRouter()).thenReturn(router);
        console = new ConsoleCommandHandler(context);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privateTitlesReachCurrentMemberClientsWithoutSendingToAnEmptyBindingClient(boolean fromConsole) {
        var channel = channels.createChannel(ChannelConfig.builder().id("private-title")
                .scope(ChannelScope.PRIVATE).clientId("binding").build());
        var binding = connection("binding");
        var left = connection("left");
        var right = connection("right");
        var outsider = connection("outsider");
        member(channel, "left");
        member(channel, "left");
        member(channel, "right");
        member(channel, "offline");

        send(fromConsole, channel.getId());

        verify(left).sendPacket(any(TitlePacket.class));
        verify(right).sendPacket(any(TitlePacket.class));
        verify(binding, never()).sendPacket(any());
        verify(outsider, never()).sendPacket(any());
        verify(network, never()).broadcastAuthenticated(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void globalTitlesRespectChannelPermissionAndActiveConnections(boolean fromConsole) {
        channels.createChannel(ChannelConfig.builder().id("staff-title")
                .scope(ChannelScope.GLOBAL).permission("staff").build());
        var allowed = connection("allowed");
        var denied = connection("denied");
        var inactive = connection("inactive");
        var unauthenticated = connection("unauthenticated");
        when(inactive.isActive()).thenReturn(false);
        when(unauthenticated.isAuthenticated()).thenReturn(false);
        when(network.getConnections()).thenReturn(Set.of(allowed, denied, inactive, unauthenticated));
        router.setPermissionChecker((client, node) -> !client.equals("denied"));

        send(fromConsole, "staff-title");

        verify(allowed).sendPacket(any(TitlePacket.class));
        verify(denied, never()).sendPacket(any());
        verify(inactive, never()).sendPacket(any());
        verify(unauthenticated, never()).sendPacket(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disconnectedServerTitleReportsNoConnectedRecipient(boolean fromConsole) {
        channels.createChannel(ChannelConfig.builder().id("offline-title")
                .scope(ChannelScope.SERVER).clientId("offline").build());

        if (fromConsole) {
            assertThat(console.dispatch("title offline-title Hello World"))
                    .isEqualTo(I18n.tr("console.title.client_not_connected", "offline-title") + "\n");
        } else {
            var result = admin.handle(request("offline-title"));
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorCode()).isEqualTo("NC-503");
        }
    }

    @Test
    void standaloneAdminTitleStillWorksWithOnlyChannelAndNetworkWired() {
        channels.createChannel(ChannelConfig.builder().id("server-title")
                .scope(ChannelScope.SERVER).clientId("server").build());
        var target = connection("server");
        admin.setMessageRouter(null);
        var request = request("server-title");
        request.addExtra("fadeIn", "3");
        request.addExtra("stay", "40");
        request.addExtra("fadeOut", "8");

        assertThat(admin.handle(request).isSuccess()).isTrue();

        var captured = org.mockito.ArgumentCaptor.forClass(TitlePacket.class);
        verify(target).sendPacket(captured.capture());
        var title = captured.getValue();
        assertThat(title.getRequestId()).isEqualTo(request.getRequestId());
        assertThat(title.getSenderId()).isEqualTo(operator);
        assertThat(title.getTitle()).isEqualTo("Hello");
        assertThat(title.getSubtitle()).isEqualTo("World");
        assertThat(title.getFadeIn()).isEqualTo(3);
        assertThat(title.getStay()).isEqualTo(40);
        assertThat(title.getFadeOut()).isEqualTo(8);
    }

    private void send(boolean fromConsole, String channelId) {
        if (fromConsole) {
            console.dispatch("title " + channelId + " Hello World");
        } else {
            assertThat(admin.handle(request(channelId)).isSuccess()).isTrue();
        }
    }

    private AdminActionPacket request(String channelId) {
        var request = new AdminActionPacket();
        request.setAction(AdminAction.STATUS);
        request.setPlayerId(operator);
        request.setTarget(channelId);
        request.addExtra("type", "TITLE");
        request.addExtra("title", "Hello");
        request.addExtra("subtitle", "World");
        return request;
    }

    private void member(Channel channel, String clientId) {
        var id = UUID.randomUUID();
        var state = players.getOrCreateState(id, "Member");
        state.setClientId(clientId);
        players.joinChannel(id, channel.getId());
        channel.addMember(id);
    }

    private ClientConnection connection(String clientId) {
        var connection = mock(ClientConnection.class);
        when(connection.getClientId()).thenReturn(clientId);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.isActive()).thenReturn(true);
        when(network.findByClientId(clientId)).thenReturn(connection);
        return connection;
    }
}
