package com.nova.link.channel;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.auth.ClientPermissionRegistry;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests for MessageRouter ↔ MessagePipeline interaction:
 * TCP path enforces boundary once via process(); REST path skips boundary;
 * mute/filter run once; GLOBAL permission checker still applied.
 */
@DisplayName("MessageRouter boundary + pipeline interaction")
class MessageRouterBoundaryPipelineTest {

    private ChannelManager channelManager;
    private ServerNetworkHandler networkHandler;
    private MessageRouter router;
    private UUID senderId;

    @BeforeEach
    void setUp() {
        channelManager = new ChannelManager();
        networkHandler = mock(ServerNetworkHandler.class);
        when(networkHandler.getConnections()).thenReturn(Set.of());

        router = new MessageRouter(channelManager, networkHandler);
        senderId = UUID.randomUUID();

        channelManager.createChannel(ChannelConfig.builder()
                .id("global")
                .displayName("Global")
                .scope(ChannelScope.GLOBAL)
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("staff")
                .displayName("Staff")
                .scope(ChannelScope.GLOBAL)
                .permission("novachat.channel.staff")
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("local")
                .displayName("Local")
                .scope(ChannelScope.SERVER)
                .clientId("Survival")
                .build());
    }

    private ClientConnection mockConn(String clientId) {
        ClientConnection c = mock(ClientConnection.class);
        when(c.getClientId()).thenReturn(clientId);
        when(c.isAuthenticated()).thenReturn(true);
        when(c.isActive()).thenReturn(true);
        when(c.sendPacket(any())).thenReturn(CompletableFuture.completedFuture(null));
        return c;
    }

    @Test
    @DisplayName("routeMessage (TCP) drops cross-client SERVER inject")
    void routeMessageEnforcesBoundary() {
        ChatMessagePacket msg = new ChatMessagePacket(
                senderId, "Steve", "OtherServer", "local", "inject");
        assertThat(router.routeMessage(msg)).isEmpty();
        verify(networkHandler, never()).findByClientId(any());
    }

    @Test
    @DisplayName("routeMessage (TCP) delivers SERVER when client matches")
    void routeMessageAllowsOwner() {
        ClientConnection owner = mockConn("Survival");
        when(networkHandler.findByClientId("Survival")).thenReturn(owner);

        ChatMessagePacket msg = new ChatMessagePacket(
                senderId, "Steve", "Survival", "local", "hello");
        assertThat(router.routeMessage(msg)).containsExactly("Survival");
        verify(owner).sendPacket(any());
    }

    @Test
    @DisplayName("REST routeMessage(channelId,...) skips boundary (trusted)")
    void restRouteSkipsBoundary() {
        ClientConnection owner = mockConn("Survival");
        when(networkHandler.findByClientId("Survival")).thenReturn(owner);

        // Trusted REST helper stamps clientId from the channel itself; still delivers
        // even if caller only provides channelId (no client ownership check needed).
        Set<String> recipients = router.routeMessage(
                "local", senderId, "API", "announce", null);
        assertThat(recipients).containsExactly("Survival");
        verify(owner).sendPacket(any());
    }

    @Test
    @DisplayName("routeToChannel does not re-check boundary")
    void routeToChannelTrusted() {
        ClientConnection owner = mockConn("Survival");
        when(networkHandler.findByClientId("Survival")).thenReturn(owner);

        Channel channel = channelManager.getChannel("local");
        // Wrong clientId on packet — trusted path still delivers to channel owner
        ChatMessagePacket msg = new ChatMessagePacket(
                senderId, "Steve", "OtherServer", "local", "hello");
        assertThat(router.routeToChannel(channel, msg)).containsExactly("Survival");
    }

    @Test
    @DisplayName("filter runs exactly once through routeMessage")
    void filterOnce() {
        SensitiveWordFilter filter = new SensitiveWordFilter();
        filter.clearAll();
        filter.addWord("spam");
        router.setSensitiveWordFilter(filter);

        ClientConnection c = mockConn("A");
        when(networkHandler.getConnections()).thenReturn(Set.of(c));

        ChatMessagePacket msg = new ChatMessagePacket(
                senderId, "Steve", "A", "global", "please no spam here");
        router.routeMessage(msg);
        // One replacement pass: "spam" → "***", not double-masked
        assertThat(msg.getContent()).isEqualTo("please no *** here");
    }

    @Test
    @DisplayName("GLOBAL permission checker still applied via router pipeline")
    void globalPermissionViaRegistry() {
        ClientPermissionRegistry registry = new ClientPermissionRegistry();
        registry.grant("StaffNode", "novachat.channel.staff");
        // Survival has no staff permission
        router.setPermissionChecker(registry.asChecker());

        ClientConnection staff = mockConn("StaffNode");
        ClientConnection survival = mockConn("Survival");
        when(networkHandler.getConnections()).thenReturn(Set.of(staff, survival));

        ChatMessagePacket msg = new ChatMessagePacket(
                senderId, "Steve", "StaffNode", "staff", "secret");
        Set<String> recipients = router.routeMessage(msg);
        assertThat(recipients).containsExactly("StaffNode");
        verify(staff).sendPacket(any());
        verify(survival, never()).sendPacket(any());
    }

    @Test
    @DisplayName("concurrent routeMessage calls keep independent boundary enforcement")
    void concurrentBoundarySafe() throws Exception {
        ClientConnection owner = mockConn("Survival");
        when(networkHandler.findByClientId("Survival")).thenReturn(owner);

        AtomicInteger drops = new AtomicInteger();
        AtomicInteger delivers = new AtomicInteger();

        Runnable inject = () -> {
            ChatMessagePacket bad = new ChatMessagePacket(
                    senderId, "Eve", "Hacker", "local", "inject");
            if (router.routeMessage(bad).isEmpty()) {
                drops.incrementAndGet();
            }
        };
        Runnable legit = () -> {
            ChatMessagePacket good = new ChatMessagePacket(
                    senderId, "Steve", "Survival", "local", "hello");
            if (!router.routeMessage(good).isEmpty()) {
                delivers.incrementAndGet();
            }
        };

        Thread[] threads = new Thread[20];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(i % 2 == 0 ? inject : legit);
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }

        // 10 inject attempts all dropped; 10 legit all delivered
        assertThat(drops.get()).isEqualTo(10);
        assertThat(delivers.get()).isEqualTo(10);
        // Owner only receives the 10 legitimate messages
        verify(owner, times(10)).sendPacket(any());
    }
}
