package com.nova.link.channel;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.ServerNetworkHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that MessageRouter enforces mute status and sensitive-word filtering
 * on the chat path (Requirements 12.x / 13.2).
 */
@DisplayName("MessageRouter mute + filter enforcement")
class MessageRouterMuteFilterTest {

    private ChannelManager channelManager;
    private ServerNetworkHandler networkHandler;
    private MessageRouter router;
    private MuteManager muteManager;
    private SensitiveWordFilter filter;
    private PermissionManager permissionManager;

    private UUID superAdminId;
    private UUID senderId;

    @BeforeEach
    void setUp() {
        channelManager = new ChannelManager();
        networkHandler = mock(ServerNetworkHandler.class);
        when(networkHandler.getConnections()).thenReturn(Set.of());

        permissionManager = new PermissionManager();
        superAdminId = UUID.randomUUID();
        senderId = UUID.randomUUID();
        permissionManager.registerSuperAdmin(new SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        muteManager = new MuteManager(null, permissionManager, channelManager);
        filter = new SensitiveWordFilter();
        filter.clearAll();
        filter.addWord("spam");

        router = new MessageRouter(channelManager, networkHandler);
        router.setMuteManager(muteManager);
        router.setSensitiveWordFilter(filter);

        channelManager.createChannel(ChannelConfig.builder()
                .id("global")
                .displayName("Global")
                .scope(ChannelScope.GLOBAL)
                .build());
    }

    @Test
    @DisplayName("muted sender messages are dropped")
    void mutedSenderDropped() {
        muteManager.mutePlayer(superAdminId, senderId, "global", 60_000, "test", "client-1");

        ChatMessagePacket msg = new ChatMessagePacket(senderId, "MutedSteve", "s1", "global", "hello");
        Set<String> recipients = router.routeMessage(msg);
        assertThat(recipients).isEmpty();
        verify(networkHandler, never()).findByClientId(any());
    }

    @Test
    @DisplayName("sensitive words are replaced before routing")
    void sensitiveWordsFiltered() {
        ChatMessagePacket msg = new ChatMessagePacket(senderId, "Steve", "s1", "global", "please no spam here");
        // No recipients because network has zero connections, but content must still be filtered.
        router.routeMessage(msg);
        assertThat(msg.getContent()).isEqualTo("please no *** here");
    }

    @Test
    @DisplayName("non-muted clean message is not altered")
    void cleanMessageUnchanged() {
        ChatMessagePacket msg = new ChatMessagePacket(senderId, "Steve", "s1", "global", "hello world");
        router.routeMessage(msg);
        assertThat(msg.getContent()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("routeToChannel also enforces mute")
    void routeToChannelEnforcesMute() {
        muteManager.mutePlayer(superAdminId, senderId, "global", 60_000, "test", "client-1");
        Channel channel = channelManager.getChannel("global");
        ChatMessagePacket msg = new ChatMessagePacket(senderId, "MutedSteve", "s1", "global", "hello");
        assertThat(router.routeToChannel(channel, msg)).isEmpty();
    }
}
