package com.nova.chat.velocity.platform;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.engine.ProxyServerSwitchCore;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VelocityChannelReconnectTest {
    private final UUID playerId = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final VelocityProxyPlayer handle = new VelocityProxyPlayer(player);
    private final ProxyPlatform platform = mock(ProxyPlatform.class);
    private final ProxyConfigView config = mock(ProxyConfigView.class);
    private final RecordingClient client = new RecordingClient();
    private ProxyChatCore chat;
    private ProxyServerSwitchCore connections;

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getUsername()).thenReturn("RecoverJava");
        doCallRealMethod().when(player).hasPermission("novachat.use");
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(true);
        when(platform.getPlayer(playerId)).thenReturn(handle);
        when(platform.getPlatformType()).thenReturn(PlatformType.VELOCITY);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(platform).execute(any(Runnable.class));
        chat = new ProxyChatCore(platform, config, new IgnoreListService(), null,
                new KnownChannelRegistry(), () -> client);
        connections = new ProxyServerSwitchCore(platform, chat);
    }

    @Test
    void undefinedBasicPermissionRestoresConfirmedChannel() {
        when(player.getPermissionValue("novachat.use")).thenReturn(Tristate.UNDEFINED);
        assertRestoresLocal();
    }

    @Test
    void grantedBasicPermissionRestoresConfirmedChannel() {
        when(player.getPermissionValue("novachat.use")).thenReturn(Tristate.TRUE);
        assertRestoresLocal();
    }

    private void assertRestoresLocal() {
        disconnectFromLocal();

        connections.onServerConnected(handle, "bukkit");
        ChannelActionPacket join = lastJoin();
        assertThat(join.getChannelId()).isEqualTo("local");
        assertThat(chat.getState(playerId).getActiveChannel()).isNull();
        respond(join);
        assertThat(chat.getState(playerId).getActiveChannel()).isEqualTo("local");
        assertThat(chat.getState(playerId).getJoinedChannels()).contains("local");
    }

    @Test
    void explicitDenialFallsBackToDefault() {
        when(player.getPermissionValue("novachat.use")).thenReturn(Tristate.FALSE);
        disconnectFromLocal();

        connections.onServerConnected(handle, "bukkit");
        assertThat(lastJoin().getChannelId()).isEqualTo("global");
        assertThat(chat.getState(playerId).getActiveChannel()).isEqualTo("global");
    }

    @Test
    void revocationWhileRestoreIsPendingCannotRestoreLocal() {
        when(player.getPermissionValue("novachat.use")).thenReturn(Tristate.TRUE, Tristate.FALSE);
        disconnectFromLocal();

        connections.onServerConnected(handle, "bukkit");
        ChannelActionPacket join = lastJoin();
        assertThat(join.getChannelId()).isEqualTo("local");
        respond(join);
        assertThat(lastJoin().getChannelId()).isEqualTo("global");
        assertThat(chat.getState(playerId).getActiveChannel()).isNull();
    }

    @Test
    void otherPermissionsKeepVelocitySemantics() {
        when(player.hasPermission("novachat.admin")).thenReturn(false);
        assertThat(handle.hasPermission("novachat.admin")).isFalse();
        when(player.hasPermission("novachat.admin")).thenReturn(true);
        assertThat(handle.hasPermission("novachat.admin")).isTrue();
    }

    private void disconnectFromLocal() {
        chat.recordServer(handle, "bukkit");
        chat.getState(playerId).setActiveChannel("local");
        chat.onPlayerDisconnect(playerId);
        client.wire.clear();
    }

    private ChannelActionPacket lastJoin() {
        return client.wire.stream().filter(packet -> packet.getAction() == ChannelAction.JOIN)
                .reduce((first, second) -> second).orElseThrow();
    }

    private void respond(ChannelActionPacket request) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(true, ChannelAction.JOIN,
                request.getChannelId(), "", "ok");
        response.setRequestId(request.getRequestId());
        chat.handleChannelActionResponse(response);
    }

    private static final class RecordingClient extends AbstractPlatformNetworkClient {
        final List<ChannelActionPacket> wire = new ArrayList<>();
        final ChannelResponseTracker tracker = new ChannelResponseTracker();

        @Override public boolean isAuthenticated() { return true; }
        @Override public ChannelResponseTracker getChannelResponseTracker() { return tracker; }
        @Override public void sendPacket(Packet packet) {
            if (packet instanceof ChannelActionPacket action) {
                tracker.track(action);
                wire.add(action);
            }
        }
    }
}
