package com.nova.chat.proxy.chat;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;
import com.nova.chat.proxy.relay.BackendRouter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProxyMembershipRecoveryTest {
    @Test
    void restoresOnlyOnlinePlayersOnTheAuthenticatedServerLink() {
        var platform = mock(ProxyPlatform.class);
        var config = mock(ProxyConfigView.class);
        var router = mock(BackendRouter.class);
        var first = mock(AbstractPlatformNetworkClient.class);
        var second = mock(AbstractPlatformNetworkClient.class);
        when(config.getDefaultChannel()).thenReturn("global");
        when(platform.getPlatformType()).thenReturn(PlatformType.VELOCITY);
        when(first.isAuthenticated()).thenReturn(true);
        when(router.clientFor("first")).thenReturn(first);
        when(router.clientFor("second")).thenReturn(second);
        var alice = player("Alice", "first");
        var bob = player("Bob", "second");
        var gone = player("Gone", "first");
        doReturn(List.of(alice, bob)).when(platform).getOnlinePlayers();
        var core = new ProxyChatCore(platform, config, new IgnoreListService(),
                new PrivateMessageService(), new KnownChannelRegistry(), () -> first);
        core.setBackendRouter(router);
        for (var player : List.of(alice, bob, gone)) { core.getOrCreateState(player); }
        core.restoreOnlineMemberships(first);
        var packets = ArgumentCaptor.forClass(ChannelActionPacket.class);
        verify(first).sendPacket(packets.capture());
        assertThat(packets.getValue().getExtra("playerId")).isEqualTo(alice.getUniqueId().toString());
        assertThat(packets.getValue().getExtra("server")).isEqualTo("first");
        verify(second, never()).sendPacket(any());
        when(first.isAuthenticated()).thenReturn(false);
        core.restoreOnlineMemberships(first);
        verify(first, times(1)).sendPacket(any());
    }

    private ProxyPlayer player(String name, String server) {
        var player = mock(ProxyPlayer.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.getCurrentServer()).thenReturn(server);
        return player;
    }
}
