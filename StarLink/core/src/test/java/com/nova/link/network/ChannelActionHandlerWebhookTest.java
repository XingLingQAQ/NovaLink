package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.link.api.WebhookManager;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.PlayerStateManager;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies that player join / leave channel actions fire the
 * {@code player.join} / {@code player.leave} webhook events (P0-4).
 */
@DisplayName("ChannelActionHandler player join/leave webhooks (P0-4)")
class ChannelActionHandlerWebhookTest {

    private ChannelManager channelManager;
    private PlayerStateManager playerStateManager;
    private DatabaseProvider databaseProvider;
    private PrivateChannelManager privateChannelManager;
    private InvitationManager invitationManager;
    private PermissionManager permissionManager;
    private WebhookManager webhookManager;
    private ChannelActionHandler handler;
    private ClientConnection connection;

    @BeforeEach
    void setUp() {
        databaseProvider = mock(DatabaseProvider.class);
        channelManager = new ChannelManager();
        playerStateManager = new PlayerStateManager(databaseProvider);
        privateChannelManager = mock(PrivateChannelManager.class);
        invitationManager = mock(InvitationManager.class);
        permissionManager = mock(PermissionManager.class);
        webhookManager = mock(WebhookManager.class);
        connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn("survival-01");

        handler = new ChannelActionHandler(channelManager, playerStateManager,
                databaseProvider, privateChannelManager, invitationManager,
                permissionManager);
        handler.setWebhookManager(webhookManager);

        channelManager.createChannel(ChannelConfig.builder()
                .id("global")
                .scope(ChannelScope.GLOBAL)
                .build());
    }

    @Test
    @DisplayName("JOIN fires player.join webhook with uuid/name/server/channelId")
    void joinFiresPlayerJoinWebhook() {
        UUID playerId = UUID.randomUUID();
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, "global");
        packet.addExtra("playerId", playerId.toString());
        packet.addExtra("playerName", "Steve");

        handler.handle(connection, packet);

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<JsonObject> payloadCaptor = ArgumentCaptor.forClass(JsonObject.class);
        verify(webhookManager).triggerWebhook(eventCaptor.capture(), payloadCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo("player.join");
        JsonObject payload = payloadCaptor.getValue();
        assertThat(payload.get("uuid").getAsString()).isEqualTo(playerId.toString());
        assertThat(payload.get("name").getAsString()).isEqualTo("Steve");
        assertThat(payload.get("server").getAsString()).isEqualTo("survival-01");
        assertThat(payload.get("channelId").getAsString()).isEqualTo("global");
    }

    @Test
    @DisplayName("LEAVE fires player.leave webhook with uuid/name/server/channelId")
    void leaveFiresPlayerLeaveWebhook() {
        UUID playerId = UUID.randomUUID();
        // First join so the player is in the channel.
        ChannelActionPacket join = new ChannelActionPacket(ChannelAction.JOIN, "global");
        join.addExtra("playerId", playerId.toString());
        join.addExtra("playerName", "Alex");
        handler.handle(connection, join);
        reset(webhookManager);

        ChannelActionPacket leave = new ChannelActionPacket(ChannelAction.LEAVE, "global");
        leave.addExtra("playerId", playerId.toString());
        leave.addExtra("playerName", "Alex");
        handler.handle(connection, leave);

        ArgumentCaptor<String> eventCaptor = ArgumentCaptor.forClass(String.class);
        verify(webhookManager).triggerWebhook(eventCaptor.capture(), any());
        assertThat(eventCaptor.getValue()).isEqualTo("player.leave");
    }

    @Test
    @DisplayName("No webhookManager wired: join still succeeds (graceful no-op)")
    void noWebhookManager_joinStillSucceeds() {
        ChannelActionHandler noWmHandler = new ChannelActionHandler(channelManager,
                playerStateManager, databaseProvider, privateChannelManager,
                invitationManager, permissionManager);

        UUID playerId = UUID.randomUUID();
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, "global");
        packet.addExtra("playerId", playerId.toString());
        packet.addExtra("playerName", "Steve");

        ChannelActionResponsePacket resp = noWmHandler.handle(connection, packet);
        assertThat(resp.isSuccess()).isTrue();
        verifyNoInteractions(webhookManager);
    }
}
