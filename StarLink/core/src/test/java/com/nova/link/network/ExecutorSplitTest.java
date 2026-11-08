package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-001: verifies the two-executor split in {@link ServerNetworkHandler}.
 *
 * <p>Acceptance criteria (audit OPS-001):
 * <ul>
 *   <li>queue-saturated control packets get an explicit failure response, not a silent drop;</li>
 *   <li>queue-saturated chat packets increment a per-type drop metric and do not crash;</li>
 *   <li>{@code getDropCounts()} returns the incremented counts and the total matches their sum;</li>
 *   <li>shutdown cancels pending tasks without leaking.</li>
 * </ul>
 *
 * <p>Uses a tiny queue + a blocking latch to saturate the executors. Real
 * {@link ClientConnection} + {@link EmbeddedChannel} (as in
 * {@code ServerNetworkHandlerGenerationTest}) so the generation state machine
 * is exercised for real and outbound failure packets can be read from the
 * channel's outbound queue.
 */
@DisplayName("OPS-001 executor split")
class ExecutorSplitTest {

    /** Mirrors {@link ServerNetworkHandler}'s control-plane queue capacity. */
    private static final int CONTROL_QUEUE_CAPACITY = 1024;
    /** Mirrors {@link ServerNetworkHandler}'s message-plane queue capacity. */
    private static final int MESSAGE_QUEUE_CAPACITY = 10000;

    private static ClientConnection connection(ServerNetworkHandler handler) {
        ClientConnection connection = new ClientConnection(new EmbeddedChannel());
        handler.onClientConnected(connection);
        return connection;
    }

    /**
     * Saturates the control executor (1 thread, queue full) with a blocking
     * task, then submits a ChannelActionPacket and asserts an explicit
     * ChannelActionResponsePacket failure (NC-500) is written back rather than
     * the packet being silently dropped.
     */
    @Test
    @DisplayName("control packet under saturation sends explicit failure (not silent drop)")
    void controlPacketUnderSaturationSendsExplicitFailure() throws Exception {
        // 1 thread: once the blocker holds the single control thread AND the
        // queue is full, the next submit is rejected.
        ServerNetworkHandler handler = new ServerNetworkHandler(1, true);
        ClientConnection conn = connection(handler);
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        try {
            // KeepAlivePacket (0x07) is control-plane (not chat, not pre-auth).
            handler.registerHandler(KeepAlivePacket.class, (connection, packet) -> {
                blockerEntered.countDown();
                await(releaseBlocker);
            });
            handler.registerHandler(ChannelActionPacket.class, (connection, packet) -> {
                // Should never run under saturation; the assertion is on the
                // outbound failure response below.
            });

            // Fill the thread + the queue (capacity CONTROL_QUEUE_CAPACITY).
            handler.handlePacket(conn, new KeepAlivePacket(1L));
            assertThat(blockerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < CONTROL_QUEUE_CAPACITY; i++) {
                handler.handlePacket(conn, new KeepAlivePacket(2L + i));
            }

            // Now the control plane is saturated: thread busy + queue full. The
            // next ChannelActionPacket must be rejected and produce an explicit
            // ChannelActionResponsePacket failure to the client.
            ChannelActionPacket rejected = new ChannelActionPacket(ChannelAction.JOIN, "global");
            rejected.setRequestId(UUID.randomUUID());
            handler.handlePacket(conn, rejected);

            // The reject handler runs synchronously inside execute() when the
            // queue is full (ThreadPoolExecutor invokes rejectedExecution on the
            // caller thread), so the failure response is already written.
            Object outbound = ((EmbeddedChannel) conn.getChannel()).readOutbound();
            assertThat(outbound).isInstanceOf(ChannelActionResponsePacket.class);
            ChannelActionResponsePacket car = (ChannelActionResponsePacket) outbound;
            assertThat(car.isSuccess()).isFalse();
            assertThat(car.getErrorCode()).isEqualTo("NC-500");
            assertThat(car.getRequestId()).isEqualTo(rejected.getRequestId());
        } finally {
            releaseBlocker.countDown();
            handler.shutdown();
        }
    }

    /**
     * Saturates the message executor (1 thread, queue full) with a blocking
     * task, then submits extra ChatMessagePackets and asserts:
     * <ul>
     *   <li>the per-type drop counter for ChatMessagePacket (0x03) increments;</li>
     *   <li>no exception propagates to the caller (no crash);</li>
     *   <li>no per-message error packet is written to the client.</li>
     * </ul>
     */
    @Test
    @DisplayName("chat packet under saturation increments per-type drop metric, no crash")
    void chatPacketUnderSaturationIncrementsDropMetric() throws Exception {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, true);
        ClientConnection conn = connection(handler);
        CountDownLatch blockerEntered = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        try {
            handler.registerHandler(ChatMessagePacket.class, (connection, packet) -> {
                blockerEntered.countDown();
                await(releaseBlocker);
            });

            // Fill the thread + the message queue (capacity MESSAGE_QUEUE_CAPACITY).
            handler.handlePacket(conn, new ChatMessagePacket(
                    UUID.randomUUID(), "Steve", "Survival", "global", "first"));
            assertThat(blockerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < MESSAGE_QUEUE_CAPACITY; i++) {
                handler.handlePacket(conn, new ChatMessagePacket(
                        UUID.randomUUID(), "Steve", "Survival", "global", "queue-" + i));
            }

            long dropsBefore = handler.getDropCount(PacketIds.CHAT_MESSAGE);
            // Two extra submits (queue already full) must be rejected.
            handler.handlePacket(conn, new ChatMessagePacket(
                    UUID.randomUUID(), "Steve", "Survival", "global", "drop1"));
            handler.handlePacket(conn, new ChatMessagePacket(
                    UUID.randomUUID(), "Steve", "Survival", "global", "drop2"));

            // Reject handler runs synchronously on the caller thread.
            assertThat(handler.getDropCount(PacketIds.CHAT_MESSAGE))
                    .isGreaterThanOrEqualTo(2L + dropsBefore);
            // No per-message error packet was written to the client for chat drops.
            assertThat((Object) ((EmbeddedChannel) conn.getChannel()).readOutbound()).isNull();
        } finally {
            releaseBlocker.countDown();
            handler.shutdown();
        }
    }

    /**
     * Asserts {@link ServerNetworkHandler#getDropCounts()} returns an
     * unmodifiable snapshot whose values sum to {@link #getDropCountTotal()},
     * and that chat + control drops are independently keyed by packet id.
     */
    @Test
    @DisplayName("getDropCounts snapshot matches sum of per-type drops")
    void dropCountSnapshotMatchesSum() throws Exception {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, true);
        ClientConnection conn = connection(handler);
        CountDownLatch chatBlockerEntered = new CountDownLatch(1);
        CountDownLatch releaseChat = new CountDownLatch(1);
        CountDownLatch controlBlockerEntered = new CountDownLatch(1);
        CountDownLatch releaseControl = new CountDownLatch(1);
        try {
            handler.registerHandler(ChatMessagePacket.class, (connection, packet) -> {
                chatBlockerEntered.countDown();
                await(releaseChat);
            });
            handler.registerHandler(KeepAlivePacket.class, (connection, packet) -> {
                controlBlockerEntered.countDown();
                await(releaseControl);
            });
            // No-op handlers so ChannelActionPacket / AdminActionPacket pass the
            // registered-handler check and actually reach the executor (where
            // they get rejected under saturation). They never run.
            handler.registerHandler(ChannelActionPacket.class, (connection, packet) -> { });
            handler.registerHandler(AdminActionPacket.class, (connection, packet) -> { });

            // Saturate message plane + drop 3 chat packets.
            handler.handlePacket(conn, new ChatMessagePacket(
                    UUID.randomUUID(), "Steve", "Survival", "global", "blocker"));
            assertThat(chatBlockerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < MESSAGE_QUEUE_CAPACITY; i++) {
                handler.handlePacket(conn, new ChatMessagePacket(
                        UUID.randomUUID(), "Steve", "Survival", "global", "queue-" + i));
            }
            for (int i = 0; i < 3; i++) {
                handler.handlePacket(conn, new ChatMessagePacket(
                        UUID.randomUUID(), "Steve", "Survival", "global", "drop-" + i));
            }

            // Saturate control plane + drop control packets.
            handler.handlePacket(conn, new KeepAlivePacket(1L));
            assertThat(controlBlockerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < CONTROL_QUEUE_CAPACITY; i++) {
                handler.handlePacket(conn, new KeepAlivePacket(2L + i));
            }
            ChannelActionPacket cap = new ChannelActionPacket(ChannelAction.JOIN, "global");
            cap.setRequestId(UUID.randomUUID());
            handler.handlePacket(conn, cap);
            AdminActionPacket aap = AdminActionPacket.createAuthPacket(UUID.randomUUID(), "deadbeef");
            handler.handlePacket(conn, aap);

            Map<Integer, Long> snapshot = handler.getDropCounts();
            assertThat(snapshot).isNotEmpty();
            assertThat(snapshot).containsKey(PacketIds.CHAT_MESSAGE);
            assertThat(snapshot.get(PacketIds.CHAT_MESSAGE)).isEqualTo(3L);
            // KeepAlivePacket (0x07) + ChannelActionPacket (0x04) + AdminActionPacket (0x0B)
            long controlDrops = snapshot.getOrDefault(PacketIds.KEEP_ALIVE, 0L)
                    + snapshot.getOrDefault(PacketIds.CHANNEL_ACTION, 0L)
                    + snapshot.getOrDefault(PacketIds.ADMIN_ACTION, 0L);
            assertThat(controlDrops).isEqualTo(2L);

            long sum = snapshot.values().stream().mapToLong(Long::longValue).sum();
            assertThat(sum).isEqualTo(handler.getDropCountTotal());
            assertThat(handler.getDropCountTotal())
                    .isEqualTo(handler.getDropCount(PacketIds.CHAT_MESSAGE)
                            + handler.getDropCount(PacketIds.KEEP_ALIVE)
                            + handler.getDropCount(PacketIds.CHANNEL_ACTION)
                            + handler.getDropCount(PacketIds.ADMIN_ACTION));

            // Snapshot is unmodifiable.
            assertThat(snapshot).isUnmodifiable();
        } finally {
            releaseChat.countDown();
            releaseControl.countDown();
            handler.shutdown();
        }
    }

    @Test
    @DisplayName("shutdown retires queued work before releasing the running handler")
    void shutdownCancelsPendingTasks() throws Exception {
        checkShutdown(false);
    }

    @Test
    @DisplayName("shutdown interrupts a blocked handler after the grace period")
    void shutdownInterruptsBlockedHandler() throws Exception {
        checkShutdown(true);
    }

    private void checkShutdown(boolean force) throws Exception {
        ServerNetworkHandler handler = new ServerNetworkHandler(1, true);
        ClientConnection conn = connection(handler);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch retired = new CountDownLatch(1);
        CountDownLatch startShutdown = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger handled = new AtomicInteger();
        handler.setDisconnectListener((connection, active) -> retired.countDown());
        Thread shutdownThread = new Thread(() -> { await(startShutdown); handler.shutdown(); });
        try {
            // Authentication releases its generation lock before invoking business work.
            handler.registerHandler(HandshakePacket.class, (connection, packet) -> {
                handled.incrementAndGet();
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException error) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            });
            handler.registerHandler(KeepAlivePacket.class, (connection, packet) -> handled.incrementAndGet());
            handler.handlePacket(conn, new HandshakePacket());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            shutdownThread.start();
            for (int i = 0; i < 5; i++) handler.handlePacket(conn, new KeepAlivePacket(2L + i));
            assertThat(handler.getControlQueueDepth()).isEqualTo(5);
            startShutdown.countDown();
            assertThat(retired.await(5, TimeUnit.SECONDS)).isTrue();
            if (force) assertThat(interrupted.await(10, TimeUnit.SECONDS)).isTrue();
            else release.countDown();
            shutdownThread.join(10_000L);
            assertThat(shutdownThread.isAlive()).isFalse();
            assertThat(handled.get()).isEqualTo(1);
            assertThat(handler.getControlQueueDepth()).isZero();
            assertThat(conn.isActive()).isFalse();
        } finally {
            startShutdown.countDown();
            release.countDown();
            shutdownThread.join(10_000L);
            handler.shutdown();
            ((EmbeddedChannel) conn.getChannel()).finishAndReleaseAll();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Local copy of the wire packet ids used by the assertions (avoid cross-module constant churn). */
    private static final class PacketIds {
        static final int CHAT_MESSAGE = 0x03;
        static final int CHANNEL_ACTION = 0x04;
        static final int KEEP_ALIVE = 0x07;
        static final int ADMIN_ACTION = 0x0B;
        private PacketIds() {
        }
    }
}
