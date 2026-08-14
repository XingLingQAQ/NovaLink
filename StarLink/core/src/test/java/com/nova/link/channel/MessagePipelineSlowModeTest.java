package com.nova.link.channel;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.console.ConsoleSentinel;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Slow-mode stage tests for {@link MessagePipeline}: interval enforcement with
 * remaining-seconds reporting, admin/console exemption, per-channel isolation
 * and tracker cleanup. The {@link SlowModeTracker} clock is injected so no
 * test sleeps.
 */
@DisplayName("MessagePipeline slow mode")
class MessagePipelineSlowModeTest {

    private ChannelManager channelManager;
    private ServerNetworkHandler networkHandler;
    private MessagePipeline pipeline;
    private PermissionManager permissionManager;
    private SlowModeTracker tracker;
    private AtomicLong clock;

    private UUID senderId;
    private UUID superAdminId;
    private ClientConnection conn;

    @BeforeEach
    void setUp() {
        channelManager = new ChannelManager();
        networkHandler = mock(ServerNetworkHandler.class);

        // One authenticated connection so GLOBAL fan-out delivers.
        conn = mock(ClientConnection.class);
        when(conn.getClientId()).thenReturn("Survival");
        when(conn.isAuthenticated()).thenReturn(true);
        when(conn.isActive()).thenReturn(true);
        when(conn.sendPacket(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(networkHandler.getConnections()).thenReturn(Set.of(conn));

        clock = new AtomicLong(1_000_000L);
        tracker = new SlowModeTracker(clock::get);

        permissionManager = new PermissionManager();
        superAdminId = UUID.randomUUID();
        permissionManager.registerSuperAdmin(new SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        pipeline = new MessagePipeline(channelManager, networkHandler, tracker);
        pipeline.setPermissionManager(permissionManager);

        senderId = UUID.randomUUID();

        channelManager.createChannel(ChannelConfig.builder()
                .id("slow")
                .displayName("Slow")
                .scope(ChannelScope.GLOBAL)
                .slowModeSeconds(5)
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("fast")
                .displayName("Fast")
                .scope(ChannelScope.GLOBAL)
                .build());
    }

    private ChatMessagePacket msg(UUID sender, String channelId) {
        return new ChatMessagePacket(sender, "Steve", "Survival", channelId, "hello");
    }

    @Test
    @DisplayName("second message within the window drops with SLOW_MODE and remaining seconds")
    void slowModeDropsWithRemainingSeconds() {
        MessagePipelineResult first = pipeline.process(msg(senderId, "slow"));
        assertThat(first.isDelivered()).isTrue();

        clock.addAndGet(1_000); // +1s of the 5s window
        MessagePipelineResult second = pipeline.process(msg(senderId, "slow"));

        assertThat(second.isDelivered()).isFalse();
        assertThat(second.getDropReason()).isEqualTo(MessagePipelineResult.DropReason.SLOW_MODE);
        assertThat(second.getSlowModeRemainingSeconds()).isEqualTo(4);
        assertThat(second.getChannel().getId()).isEqualTo("slow");
    }

    @Test
    @DisplayName("after the window elapses the sender can post again")
    void windowElapsesAllowsNextMessage() {
        assertThat(pipeline.process(msg(senderId, "slow")).isDelivered()).isTrue();

        clock.addAndGet(5_000);
        assertThat(pipeline.process(msg(senderId, "slow")).isDelivered()).isTrue();
    }

    @Test
    @DisplayName("slow_mode=0 channels are unaffected")
    void disabledChannelUnaffected() {
        assertThat(pipeline.process(msg(senderId, "fast")).isDelivered()).isTrue();
        assertThat(pipeline.process(msg(senderId, "fast")).isDelivered()).isTrue();
        assertThat(pipeline.process(msg(senderId, "fast")).isDelivered()).isTrue();
    }

    @Test
    @DisplayName("channels are tracked independently")
    void perChannelIsolation() {
        channelManager.createChannel(ChannelConfig.builder()
                .id("slow2")
                .displayName("Slow2")
                .scope(ChannelScope.GLOBAL)
                .slowModeSeconds(5)
                .build());

        assertThat(pipeline.process(msg(senderId, "slow")).isDelivered()).isTrue();
        // Same sender, different channel: its own window.
        assertThat(pipeline.process(msg(senderId, "slow2")).isDelivered()).isTrue();
        // But the first channel is still locked.
        assertThat(pipeline.process(msg(senderId, "slow")).getDropReason())
                .isEqualTo(MessagePipelineResult.DropReason.SLOW_MODE);
    }

    @Test
    @DisplayName("players are tracked independently")
    void perPlayerIsolation() {
        UUID other = UUID.randomUUID();
        assertThat(pipeline.process(msg(senderId, "slow")).isDelivered()).isTrue();
        assertThat(pipeline.process(msg(other, "slow")).isDelivered()).isTrue();
    }

    @Test
    @DisplayName("authenticated super admin (>= CHANNEL_ADMIN) is exempt")
    void adminExempt() {
        assertThat(pipeline.process(msg(superAdminId, "slow")).isDelivered()).isTrue();
        assertThat(pipeline.process(msg(superAdminId, "slow")).isDelivered()).isTrue();
        assertThat(pipeline.process(msg(superAdminId, "slow")).isDelivered()).isTrue();
    }

    @Test
    @DisplayName("console sentinel is exempt")
    void consoleExempt() {
        assertThat(pipeline.process(msg(ConsoleSentinel.CONSOLE_SENTINEL, "slow")).isDelivered()).isTrue();
        assertThat(pipeline.process(msg(ConsoleSentinel.CONSOLE_SENTINEL, "slow")).isDelivered()).isTrue();
    }

    @Test
    @DisplayName("channel owner (channel admin) is exempt on their channel")
    void channelOwnerExempt() {
        channelManager.createChannel(ChannelConfig.builder()
                .id("owned")
                .displayName("Owned")
                .scope(ChannelScope.SERVER)
                .clientId("Survival")
                .ownerId(senderId)
                .slowModeSeconds(5)
                .build());
        permissionManager.grantChannelAdmin("owned", senderId);
        when(networkHandler.findByClientId("Survival")).thenReturn(conn);

        assertThat(pipeline.process(msg(senderId, "owned")).isDelivered()).isTrue();
        assertThat(pipeline.process(msg(senderId, "owned")).isDelivered()).isTrue();
    }

    @Test
    @DisplayName("tracker cleanup evicts expired entries (bounded map)")
    void trackerCleanup() {
        assertThat(tracker.tryAcquire(senderId, "slow", 5)).isZero();
        assertThat(tracker.size()).isEqualTo(1);

        // Window still open → entry retained.
        tracker.cleanupExpired();
        assertThat(tracker.size()).isEqualTo(1);

        clock.addAndGet(5_000);
        tracker.cleanupExpired();
        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("tryAcquire rounds remaining seconds up")
    void remainingSecondsRoundUp() {
        assertThat(tracker.tryAcquire(senderId, "slow", 5)).isZero();
        clock.addAndGet(4_500); // 500ms remaining → reported as 1s
        assertThat(tracker.tryAcquire(senderId, "slow", 5)).isEqualTo(1);
    }
}
