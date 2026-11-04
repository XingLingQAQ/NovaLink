package com.nova.chat.velocity.chat;

import com.nova.chat.channel.RenderCoordinationProtocol;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.config.ProxyConfig;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;
import com.nova.chat.velocity.platform.VelocityProxyPlayer;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenderCoordinationConnectTest {
    @Test
    void postConnectNegotiatesBeforeAnyEligibleMessageEvenWhenConnectedHadNoCurrentServer() {
        var plugin = mock(NovaChatVelocity.class);
        var config = mock(NovaChatConfig.class);
        var platform = mock(ProxyPlatform.class);
        var player = mock(Player.class);
        var connection = mock(ServerConnection.class);
        var server = mock(RegisteredServer.class);
        UUID id = UUID.randomUUID();
        when(plugin.getConfig()).thenReturn(config);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getCurrentServer()).thenReturn(Optional.empty());
        var info = new ServerInfo("bukkit", new InetSocketAddress("127.0.0.1", 25565));
        when(server.getServerInfo()).thenReturn(info);
        when(connection.getServerInfo()).thenReturn(info);
        var handle = new VelocityProxyPlayer(player);
        when(platform.getPlayer(id)).thenReturn(handle);
        var core = new ProxyChatCore(platform, new ProxyConfig.Builder().defaultChannel("global")
                .renderCoordinationSecret("bukkit", "render-connect-test-secret-0123456789").build(),
                null, null, null, () -> null);
        when(plugin.getChatCore()).thenReturn(core);
        var listener = new ChatListener(plugin);
        var connected = mock(ServerConnectedEvent.class);
        when(connected.getPlayer()).thenReturn(player);
        when(connected.getServer()).thenReturn(server);
        listener.onServerConnected(connected);
        verify(platform, never()).sendPlayerPluginMessage(any(), any(), anyString(), any());
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        when(platform.getPlayerConnectionIdentity(id)).thenReturn(connection);
        var post = mock(ServerPostConnectEvent.class);
        when(post.getPlayer()).thenReturn(player);
        listener.onServerPostConnect(post);
        verify(platform).sendPlayerPluginMessage(eq(id), same(connection),
                eq(RenderCoordinationProtocol.CHANNEL_ID), any(byte[].class));
    }
}
