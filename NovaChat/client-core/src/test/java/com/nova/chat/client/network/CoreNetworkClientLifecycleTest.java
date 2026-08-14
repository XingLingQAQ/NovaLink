package com.nova.chat.client.network;

import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Lifecycle tests for {@link CoreNetworkClient}: shutdown cancelling scheduled
 * reconnects, single-flight connect under contention, and handler-exception
 * isolation. Uses a capturing {@link SchedulerBridge} plus the package-private
 * event-loop-group factory hook, so no test opens outbound network connections
 * unless it binds its own local socket.
 */
@DisplayName("CoreNetworkClient lifecycle")
class CoreNetworkClientLifecycleTest {

    private static final class RecordingLogger implements ClientLogger {
        final List<String> infos = new ArrayList<>();
        final List<String> warns = new ArrayList<>();
        final List<String> debugs = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void warn(String message) {
            warns.add(message);
        }

        @Override
        public void debug(String message) {
            debugs.add(message);
        }

        @Override
        public void error(String message) {
            errors.add(message);
        }
    }

    /** Captures scheduled tasks so tests can fire the reconnect closure manually. */
    private static final class CapturingScheduler implements SchedulerBridge {
        final List<Runnable> scheduled = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public synchronized void runLater(Runnable task, long delaySeconds) {
            scheduled.add(task);
            delays.add(delaySeconds);
        }
    }

    private static ClientConnectionConfig basicConfig() {
        return ClientConnectionConfig.builder()
                .host("127.0.0.1")
                .port(8888)
                .username("proxy")
                .password("s3cret")
                .build();
    }

    @Nested
    @DisplayName("disconnect cancels scheduled reconnect")
    class DisconnectCancelsReconnect {

        @Test
        @DisplayName("reconnect closure fired after disconnect() does not connect")
        void scheduledReconnectAfterDisconnectIsNoOp() {
            RecordingLogger logger = new RecordingLogger();
            CapturingScheduler scheduler = new CapturingScheduler();
            CoreNetworkClient client = new CoreNetworkClient(
                    basicConfig(), PlatformType.BUKKIT, scheduler, logger
            );

            AtomicInteger groupCreations = new AtomicInteger();
            client.setEventLoopGroupFactory(() -> {
                groupCreations.incrementAndGet();
                throw new AssertionError("connect flow must not start after disconnect()");
            });

            // Simulate unexpected connection loss -> a reconnect gets scheduled.
            client.onDisconnect();
            assertThat(scheduler.scheduled).hasSize(1);

            // Plugin shuts down before the timer fires.
            client.disconnect();

            // Timer fires anyway (SchedulerBridge has no cancel API).
            Runnable reconnectClosure = scheduler.scheduled.get(0);
            assertThatCode(reconnectClosure::run).doesNotThrowAnyException();

            assertThat(groupCreations).hasValue(0);
            assertThat(logger.debugs).noneMatch(m -> m.startsWith("Connecting to NovaLink"));
            // And the no-op closure must not schedule another attempt either.
            assertThat(scheduler.scheduled).hasSize(1);
        }

        @Test
        @DisplayName("scheduleReconnect after disconnect() schedules nothing")
        void noNewScheduleAfterDisconnect() {
            RecordingLogger logger = new RecordingLogger();
            CapturingScheduler scheduler = new CapturingScheduler();
            CoreNetworkClient client = new CoreNetworkClient(
                    basicConfig(), PlatformType.BUKKIT, scheduler, logger
            );

            client.disconnect();
            client.onDisconnect(); // late channelInactive after shutdown

            assertThat(scheduler.scheduled).isEmpty();
        }
    }

    @Nested
    @DisplayName("concurrent connect")
    class ConcurrentConnect {

        @Test
        @DisplayName("second connect() during in-flight attempt joins it (single EventLoopGroup)")
        void concurrentConnectSingleFlow() throws Exception {
            RecordingLogger logger = new RecordingLogger();
            CapturingScheduler scheduler = new CapturingScheduler();
            CoreNetworkClient client = new CoreNetworkClient(
                    basicConfig(), PlatformType.BUKKIT, scheduler, logger
            );

            CountDownLatch enteredFactory = new CountDownLatch(1);
            CountDownLatch releaseFactory = new CountDownLatch(1);
            AtomicInteger groupCreations = new AtomicInteger();
            client.setEventLoopGroupFactory(() -> {
                groupCreations.incrementAndGet();
                enteredFactory.countDown();
                try {
                    releaseFactory.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new NioEventLoopGroup(1);
            });

            // Local listener so the winning dial has a deterministic target.
            try (ServerSocket server = new ServerSocket(0)) {
                int port = server.getLocalPort();

                AtomicReference<CompletableFuture<Boolean>> firstFuture = new AtomicReference<>();
                Thread first = new Thread(() ->
                        firstFuture.set(client.connect("127.0.0.1", port)));
                first.start();

                // Wait until thread 1 is inside the connect flow (guard held).
                assertThat(enteredFactory.await(10, TimeUnit.SECONDS)).isTrue();

                // Thread 2 (this thread) races in: must join, not start a 2nd flow.
                CompletableFuture<Boolean> secondFuture = client.connect("127.0.0.1", port);

                releaseFactory.countDown();
                first.join(10_000);

                assertThat(groupCreations).hasValue(1);
                assertThat(secondFuture).isSameAs(firstFuture.get());
            } finally {
                client.disconnect();
            }
        }
    }

    @Nested
    @DisplayName("handler exception isolation")
    class HandlerExceptionIsolation {

        @Test
        @DisplayName("throwing handler does not close the channel; later packets still dispatch")
        void throwingHandlerKeepsConnection() {
            RecordingLogger logger = new RecordingLogger();
            CapturingScheduler scheduler = new CapturingScheduler();
            CoreNetworkClient client = new CoreNetworkClient(
                    basicConfig(), PlatformType.BUKKIT, scheduler, logger
            );

            // Override the default keepalive handler with a faulty platform handler.
            client.registerHandler(KeepAlivePacket.class, packet -> {
                throw new IllegalStateException("boom from platform handler");
            });

            EmbeddedChannel channel = new EmbeddedChannel(new CoreClientChannelHandler(client));
            try {
                assertThatCode(() -> channel.writeInbound(new KeepAlivePacket(1L)))
                        .doesNotThrowAnyException();
                assertThat(channel.isOpen()).isTrue();
                assertThat(logger.errors)
                        .anyMatch(m -> m.contains("KeepAlivePacket") && m.contains("boom from platform handler"));

                // Subsequent packets keep flowing to (replaced) handlers.
                List<KeepAlivePacket> received = new ArrayList<>();
                client.registerHandler(KeepAlivePacket.class, received::add);
                channel.writeInbound(new KeepAlivePacket(2L));

                assertThat(received).hasSize(1);
                assertThat(received.get(0).getTimestamp()).isEqualTo(2L);
                assertThat(channel.isOpen()).isTrue();
            } finally {
                channel.finishAndReleaseAll();
            }
        }

        @Test
        @DisplayName("handlePacket swallows handler exceptions")
        void handlePacketDoesNotPropagate() {
            RecordingLogger logger = new RecordingLogger();
            CoreNetworkClient client = new CoreNetworkClient(
                    basicConfig(), PlatformType.BUKKIT, new CapturingScheduler(), logger
            );
            client.registerHandler(KeepAlivePacket.class, packet -> {
                throw new RuntimeException("handler failure");
            });

            assertThatCode(() -> client.handlePacket(new KeepAlivePacket(42L)))
                    .doesNotThrowAnyException();
            assertThat(logger.errors).anyMatch(m -> m.contains("handler failure"));
        }
    }
}
