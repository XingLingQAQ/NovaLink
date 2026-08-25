package com.nova.chat.client.network;

import com.nova.chat.common.protocol.PlatformType;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * VERIFY-008: asserts that {@link CoreNetworkClient} triggers exactly one
 * reconnect and one worker-group cleanup when {@code disconnect()},
 * {@code channelInactive} ({@code onDisconnect}), and a connect failure fire
 * concurrently or in interleaved order, and that repeated {@code close} is
 * idempotent, and that the Netty worker group is truly shut down with no
 * lingering event-loop threads.
 *
 * <p>Idiom matches {@link CoreNetworkClientLifecycleTest}: a capturing
 * {@link SchedulerBridge} plus the package-private
 * {@code setEventLoopGroupFactory} hook. The hook returns a real
 * {@link NioEventLoopGroup} so the bootstrap can actually dial a dead port and
 * exercise the genuine connect-failure branch (which calls
 * {@code scheduleReconnect}). No mocks of the client itself are used; only
 * {@code EmbeddedChannel} is avoided here because we need the real failure
 * path.
 */
@DisplayName("VERIFY-008: CoreNetworkClient disconnect/channelInactive/connect-failure concurrency")
class CoreNetworkClientDisconnectConcurrencyTest {

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

    /**
     * Captures every {@code runLater} closure without executing it, and latches
     * the first call so the test can synchronize on "scheduleReconnect ran".
     * {@code runAsync} runs inline (tests never schedule async work here).
     */
    private static final class CapturingScheduler implements SchedulerBridge {
        final List<Runnable> scheduled = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();
        final AtomicInteger runLaterCount = new AtomicInteger();
        final CountDownLatch runLaterLatch = new CountDownLatch(1);

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public synchronized void runLater(Runnable task, long delaySeconds) {
            scheduled.add(task);
            delays.add(delaySeconds);
            runLaterCount.incrementAndGet();
            runLaterLatch.countDown();
        }
    }

    /**
     * Records how many EventLoopGroups were created and keeps a handle to the
     * last one so the test can assert on termination/thread state. Returns a
     * real {@link NioEventLoopGroup} so the bootstrap connect failure path is
     * exercised end-to-end.
     */
    private static final class CountingGroupFactory implements Supplier<EventLoopGroup> {
        final AtomicInteger creations = new AtomicInteger();
        final AtomicReference<NioEventLoopGroup> last = new AtomicReference<>();

        @Override
        public EventLoopGroup get() {
            creations.incrementAndGet();
            NioEventLoopGroup group = new NioEventLoopGroup(1);
            last.set(group);
            return group;
        }
    }

    private static ClientConnectionConfig basicConfig() {
        return ClientConnectionConfig.builder()
                .host("127.0.0.1")
                .port(8888)
                .username("proxy")
                .password("s3cret")
                .connectTimeoutMs(2_000)
                .autoReconnect(true)
                .build();
    }

    /** Binds an ephemeral socket and immediately closes it so connect() is refused. */
    private static int deadPort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    /** Counts live threads whose name matches the Netty NIO event-loop prefix. */
    private static int nioThreadCount() {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("nioEventLoopGroup-"))
                .count();
    }

    @Nested
    @DisplayName("concurrent disconnect + channelInactive + connect failure")
    class ConcurrentDisconnectChannelInactiveConnectFailure {

        @Test
        @DisplayName("only one reconnect is scheduled and only one worker group is cleaned up")
        void singleReconnectSingleCleanup() throws Exception {
            RecordingLogger logger = new RecordingLogger();
            CapturingScheduler scheduler = new CapturingScheduler();
            CoreNetworkClient client = new CoreNetworkClient(
                    basicConfig(), PlatformType.BUKKIT, scheduler, logger
            );
            CountingGroupFactory factory = new CountingGroupFactory();
            client.setEventLoopGroupFactory(factory);

            try {
                int port = deadPort();

                // 1) Real connect attempt to a dead port -> failure branch fires
                //    completeAuth(false) then scheduleReconnect(). The scheduler
                //    latch tells us scheduleReconnect actually ran and captured
                //    exactly one reconnect closure (reconnecting is now true).
                //    The reconnect closure is *captured* (not executed), so the
                //    worker group is still alive at this point.
                CompletableFuture<Boolean> connectFuture = client.connect("127.0.0.1", port);
                assertThat(scheduler.runLaterLatch.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(connectFuture.get(10, TimeUnit.SECONDS)).isFalse();

                assertThat(scheduler.runLaterCount.get())
                        .as("connect failure schedules exactly one reconnect")
                        .isEqualTo(1);
                assertThat(factory.creations.get())
                        .as("exactly one worker group was created for the attempt")
                        .isEqualTo(1);
                assertThat(scheduler.scheduled).hasSize(1);
                NioEventLoopGroup groupAfterFailure = factory.last.get();
                assertThat(groupAfterFailure)
                        .as("a real worker group must exist after the connect attempt")
                        .isNotNull();
                // Captured (not fired) closure means the group is NOT yet shut down.
                assertThat(groupAfterFailure.isShuttingDown())
                        .as("worker group is still alive until the reconnect closure fires or disconnect() runs")
                        .isFalse();

                // 2) Fire channelInactive (onDisconnect) and disconnect()
                //    concurrently from two threads to simulate the race where
                //    the peer closes the socket at the same moment the plugin
                //    shuts down.
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    pool.submit(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        client.onDisconnect(); // channelInactive with no channel
                        done.countDown();
                    });
                    pool.submit(() -> {
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        client.disconnect(); // explicit shutdown
                        done.countDown();
                    });
                    start.countDown();
                    assertThat(done.await(10, TimeUnit.SECONDS))
                            .as("both concurrent events must return")
                            .isTrue();
                } finally {
                    pool.shutdownNow();
                }

                // 3) Assertions: still only one reconnect scheduled, only one
                //    group created, and that group is fully terminated.
                assertThat(scheduler.runLaterCount.get())
                        .as("no extra reconnect scheduled by the concurrent events")
                        .isEqualTo(1);
                assertThat(factory.creations.get())
                        .as("no second worker group leaked by the concurrent events")
                        .isEqualTo(1);

                NioEventLoopGroup group = factory.last.get();
                // The connect-failure branch calls scheduleReconnect() and the
                // reconnect closure (captured, not executed) shuts down the
                // worker group only when it fires. disconnect() also shuts the
                // group down. Either way, by the time we reach here the group
                // must be shutting down and must fully terminate shortly.
                assertThat(group)
                        .as("a real worker group must have been created by the connect attempt")
                        .isNotNull();
                assertThat(group.isShuttingDown())
                        .as("worker group must be shutting down after disconnect()")
                        .isTrue();
                assertThat(group.terminationFuture().await(5, TimeUnit.SECONDS))
                        .as("worker group must terminate after disconnect()")
                        .isTrue();
                assertThat(group.isTerminated()).isTrue();

                // 4) Fire the captured reconnect closure as if the timer expired
                //    after shutdown. It must no-op: no new connect, no new group,
                //    no new schedule, no throw.
                Runnable reconnectClosure = scheduler.scheduled.get(0);
                assertThatCode(reconnectClosure::run).doesNotThrowAnyException();

                assertThat(scheduler.runLaterCount.get())
                        .as("expired closure after shutdown schedules nothing")
                        .isEqualTo(1);
                assertThat(factory.creations.get())
                        .as("expired closure after shutdown creates no new group")
                        .isEqualTo(1);
                // The "Connecting to NovaLink" debug line was emitted by the
                // *first* connect attempt (step 1, before shutdown). What matters
                // is that the expired closure does NOT emit a SECOND one. So
                // count occurrences rather than asserting none exist.
                long connectingLogCount = logger.debugs.stream()
                        .filter(m -> m.startsWith("Connecting to NovaLink"))
                        .count();
                assertThat(connectingLogCount)
                        .as("only the initial connect attempt logs 'Connecting to NovaLink'; "
                                + "the expired post-shutdown closure must not start another")
                        .isEqualTo(1);
            } finally {
                client.disconnect();
                NioEventLoopGroup g = factory.last.get();
                if (g != null && !g.isTerminated()) {
                    g.shutdownGracefully().await(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Nested
    @DisplayName("repeated close is idempotent")
    class RepeatedCloseIsIdempotent {

        @Test
        @DisplayName("disconnect() called many times from many threads does not throw, does not reconnect, does not leak groups")
        void repeatedDisconnectIsIdempotent() throws Exception {
            RecordingLogger logger = new RecordingLogger();
            CapturingScheduler scheduler = new CapturingScheduler();
            CoreNetworkClient client = new CoreNetworkClient(
                    basicConfig(), PlatformType.BUKKIT, scheduler, logger
            );
            CountingGroupFactory factory = new CountingGroupFactory();
            client.setEventLoopGroupFactory(factory);

            try {
                int port = deadPort();
                client.connect("127.0.0.1", port);
                assertThat(scheduler.runLaterLatch.await(10, TimeUnit.SECONDS)).isTrue();
                final int scheduledBefore = scheduler.runLaterCount.get();
                final int groupsBefore = factory.creations.get();

                // Hammer disconnect() from 5 threads released simultaneously.
                int repeats = 5;
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(repeats);
                ExecutorService pool = Executors.newFixedThreadPool(repeats);
                try {
                    for (int i = 0; i < repeats; i++) {
                        pool.submit(() -> {
                            try {
                                start.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            assertThatCode(() -> client.disconnect())
                                    .as("repeated disconnect must not throw")
                                    .doesNotThrowAnyException();
                            done.countDown();
                        });
                    }
                    start.countDown();
                    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
                } finally {
                    pool.shutdownNow();
                }

                // A few more synchronous repeats on the test thread for good measure.
                for (int i = 0; i < 3; i++) {
                    assertThatCode(() -> client.disconnect()).doesNotThrowAnyException();
                }

                assertThat(scheduler.runLaterCount.get())
                        .as("repeated close schedules no new reconnect")
                        .isEqualTo(scheduledBefore);
                assertThat(factory.creations.get())
                        .as("repeated close creates no new worker group")
                        .isEqualTo(groupsBefore);

                NioEventLoopGroup group = factory.last.get();
                assertThat(group.isShuttingDown())
                        .as("repeated close must leave the worker group shutting down")
                        .isTrue();
                assertThat(group.terminationFuture().await(5, TimeUnit.SECONDS))
                        .as("repeated close must fully terminate the worker group")
                        .isTrue();
                assertThat(group.isTerminated()).isTrue();
            } finally {
                client.disconnect();
                NioEventLoopGroup g = factory.last.get();
                if (g != null && !g.isTerminated()) {
                    g.shutdownGracefully().await(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Nested
    @DisplayName("worker group thread cleanup")
    class WorkerGroupThreadCleanup {

        @Test
        @DisplayName("worker group is truly shut down and its NIO thread is reclaimed after disconnect")
        void noThreadLeakAfterDisconnect() throws Exception {
            RecordingLogger logger = new RecordingLogger();
            CapturingScheduler scheduler = new CapturingScheduler();
            CoreNetworkClient client = new CoreNetworkClient(
                    basicConfig(), PlatformType.BUKKIT, scheduler, logger
            );
            CountingGroupFactory factory = new CountingGroupFactory();
            client.setEventLoopGroupFactory(factory);

            try {
                int baseline = nioThreadCount();
                int port = deadPort();

                client.connect("127.0.0.1", port);
                assertThat(scheduler.runLaterLatch.await(10, TimeUnit.SECONDS)).isTrue();

                // While connected-ish (the TCP dial failed, but the worker group
                // thread is alive servicing the event loop), a NIO thread exists.
                int during = nioThreadCount();
                assertThat(during)
                        .as("worker group NIO thread must be live while the group is in use")
                        .isGreaterThanOrEqualTo(baseline + 1);

                client.disconnect();

                NioEventLoopGroup group = factory.last.get();
                assertThat(group.terminationFuture().await(5, TimeUnit.SECONDS))
                        .as("worker group must terminate after disconnect()")
                        .isTrue();
                assertThat(group.isTerminated()).isTrue();

                int after = nioThreadCount();
                assertThat(after)
                        .as("NIO worker thread must be reclaimed after group shutdown (no thread leak)")
                        .isLessThanOrEqualTo(baseline);
            } finally {
                client.disconnect();
                NioEventLoopGroup g = factory.last.get();
                if (g != null && !g.isTerminated()) {
                    g.shutdownGracefully().await(5, TimeUnit.SECONDS);
                }
            }
        }
    }
}
