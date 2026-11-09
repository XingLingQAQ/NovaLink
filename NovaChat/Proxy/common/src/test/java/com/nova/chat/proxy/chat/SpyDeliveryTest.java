package com.nova.chat.proxy.chat;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.SpyMessagePacket;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.*;

class SpyDeliveryTest {
    @Test
    void monitoringIgnoresActiveChannelButRespectsPlayerAndBackendScope() {
        var platform = mock(ProxyPlatform.class);
        var config = mock(ProxyConfigView.class);
        when(config.getDefaultChannel()).thenReturn("global");
        var admin = mock(ProxyPlayer.class);
        UUID adminId = UUID.randomUUID();
        when(admin.getUniqueId()).thenReturn(adminId);
        when(admin.getCurrentServer()).thenReturn("lobby");
        when(platform.getPlayer(adminId)).thenReturn(admin);
        var client = mock(AbstractPlatformNetworkClient.class);
        var core = new ProxyChatCore(platform, config, null, null, new KnownChannelRegistry(), () -> client);
        var packet = new SpyMessagePacket(adminId,
                new ChatMessagePacket(UUID.randomUUID(), "Sender", "remote", "staff", "observed"));

        core.handleInbound(client, Set.of("other"), packet);
        verify(platform, never()).sendMessage(any(), anyString());
        core.handleInbound(client, Set.of("lobby"), packet);
        verify(platform).sendMessage(admin, "[SPY] [remote/staff] Sender: observed");
        when(platform.getPlayer(adminId)).thenReturn(null);
        core.handleInbound(client, null, packet);
        verify(platform, times(1)).sendMessage(any(), anyString());
    }
}
