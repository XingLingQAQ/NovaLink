package com.nova.link;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle tests for the embeddable {@link NovaLinkCore}: repeated
 * start/stop inside one JVM, failure rollback, and shutdown idempotency.
 *
 * <p>Thread counting filters to the threads a NovaLink instance actually
 * owns (Netty event loops, watcher/schedulers/cleanup threads and the REST /
 * message-log / webhook pools). Everything else — Gradle test workers, the JVM
 * itself, other tests' pools — is ignored, so the assertions are about this
 * core instance and nothing else.
 */
@DisplayName("NovaLinkCore lifecycle (embedded start/stop)")
class NovaLinkCoreLifecycleTest {

    /** Thread names created (directly or via a pool) by one NovaLink core. */
    private static final Predicate<Thread> NOVALINK_THREADS = thread -> {
        String name = thread.getName();
        return name.startsWith("nioEventLoopGroup-")
                || name.startsWith("ConfigWatcher")
                || name.startsWith("NovaLink-")
                || name.startsWith("WebSocket-Status-Scheduler")
                || name.startsWith("AnnouncementManager-Scheduler")
                || name.startsWith("CampaignManager-Scheduler")
                || name.startsWith("MuteManager-Cleanup")
                || name.startsWith("BanManager-Cleanup")
                || name.startsWith("Webhook-Delivery")
                || name.startsWith("Webhook-Retry-Scheduler");
    };

    @TempDir
    Path tempDir;

    private final List<NovaLinkCore> cores = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (NovaLinkCore core : cores) {
            try {
                core.stop();
            } catch (Exception ignored) {
                // assertions about stop() live in the tests themselves
            }
        }
        cores.clear();
    }

    @Test
    @DisplayName("five consecutive start/stop rounds return to the thread baseline")
    void repeatedStartStopLeavesNoThreads() throws Exception {
        writeBootableConfig(tempDir);

        NovaLinkCore warmup = newCore();
        warmup.start(tempDir, loopbackEphemeral());
        warmup.stop();
        waitForThreadBaseline(novalinkThreadCount(), 10_000);

        int baseline = novalinkThreadCount();

        long totalStopMillis = 0;
        for (int round = 1; round <= 5; round++) {
            NovaLinkCore core = newCore();
            core.start(tempDir, loopbackEphemeral());
            assertThat(core.isRunning())
                    .as("round %d: core is running after start", round)
                    .isTrue();
            assertThat(core.getContext()).as("round %d: context published", round).isNotNull();
            assertThat(novalinkThreadCount())
                    .as("round %d: start creates NovaLink threads", round)
                    .isGreaterThan(baseline);

            long begin = System.nanoTime();
            core.stop();
            totalStopMillis += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

            assertThat(core.isRunning()).as("round %d: stopped", round).isFalse();
            assertThat(core.getContext()).as("round %d: context cleared", round).isNull();
            waitForThreadBaseline(baseline, 10_000);
            assertThat(novalinkThreadCount())
                    .as("round %d: NovaLink threads return to baseline", round)
                    .isLessThanOrEqualTo(baseline);
        }

        double averageStopMillis = totalStopMillis / 5.0;
        System.out.printf(Locale.ROOT,
                "[lifecycle] average stop() over 5 rounds: %.1f ms (total %d ms)%n",
                averageStopMillis, totalStopMillis);
        // Netty's default shutdownGracefully() alone costs ~2s per group and
        // the old code shut the two listeners down serially. Bound the whole
        // sequence so a regression to 4s+ fails loudly.
        assertThat(averageStopMillis)
                .as("stop() should be fast after the 0/5s graceful shutdown tuning")
                .isLessThan(3_000.0);
    }

    @Test
    @DisplayName("fresh install (default config, no TLS, plaintext refused) fails without leaking threads")
    void freshInstallFailureLeavesNoThreads() throws Exception {
        // NO novalink.yml: ConfigManager writes the bundled default template.
        // That template ships insecure-allow-plaintext: false with the tls:
        // block commented out, so InsecureModeGate rejects the TCP listener.
        // Everything before the gate (DB pool, moderation cleanups, slow-mode
        // cleanup, message-log cleanup, config watcher) has already started.
        Path freshInstallDir = tempDir.resolve("fresh-install");
        Files.createDirectories(freshInstallDir);

        int baseline = novalinkThreadCount();

        NovaLinkCore core = newCore();
        assertThatThrownBy(() -> core.start(freshInstallDir))
                .as("fresh install without TLS must fail closed")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insecure-allow-plaintext");

        assertThat(freshInstallDir.resolve("novalink.yml"))
                .as("the default template was written by the failed startup")
                .exists();

        assertThat(core.isRunning()).isFalse();
        assertThat(core.getContext()).isNull();
        // A fresh install is the default path: the database is `memory`, but
        // SQLite users would also start a Hikari pool here. Give the watcher
        // and cleanup executors a moment to actually terminate.
        waitForThreadBaseline(baseline, 10_000);
        assertThat(novalinkThreadCount())
                .as("failed startup must not leave NovaLink threads behind")
                .isLessThanOrEqualTo(baseline);

        // stop() after a failed start must be a no-op, not an error.
        core.stop();
        core.stop();
        assertThat(novalinkThreadCount())
                .as("stop() after failed start stays at baseline")
                .isLessThanOrEqualTo(baseline);
    }

    @Test
    @DisplayName("stop() is idempotent: repeated calls never throw")
    void stopIsIdempotent() throws Exception {
        writeBootableConfig(tempDir);

        NovaLinkCore neverStarted = newCore();
        neverStarted.stop();
        neverStarted.stop();

        NovaLinkCore core = newCore();
        core.start(tempDir, loopbackEphemeral());
        core.stop();
        core.stop();
        core.stop();
        assertThat(core.isRunning()).isFalse();
        assertThat(core.getContext()).isNull();
        assertThat(core.getStartedAtMillis()).isZero();

        // close() delegates to stop() and is equally safe to repeat.
        core.close();
    }

    @Test
    @DisplayName("start() does not block: it returns as soon as the listeners are bound")
    void startDoesNotBlock() throws Exception {
        writeBootableConfig(tempDir);

        NovaLinkCore core = newCore();
        long begin = System.nanoTime();
        core.start(tempDir, loopbackEphemeral());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);

        assertThat(core.isRunning()).isTrue();
        assertThat(core.getStartedAtMillis()).isPositive();
        // A blocking start (console latch) would never return at all; a slow
        // one would exceed this generous bound.
        assertThat(elapsedMillis).as("start() wall time").isLessThan(30_000L);
        System.out.printf(Locale.ROOT, "[lifecycle] start() took %d ms%n", elapsedMillis);
    }

    @Test
    @DisplayName("a second start on the same instance is rejected; stopping re-enables start")
    void doubleStartRejectedAndRestartWorks() throws Exception {
        writeBootableConfig(tempDir);

        NovaLinkCore core = newCore();
        core.start(tempDir, loopbackEphemeral());
        assertThatThrownBy(() -> core.start(tempDir, loopbackEphemeral()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already running");

        core.stop();
        core.start(tempDir, loopbackEphemeral());
        assertThat(core.isRunning()).isTrue();
        core.stop();
    }

    @Test
    @DisplayName("relative SQLite file path resolves against dataDir, not the JVM working directory")
    void sqlitePathResolvesAgainstDataDir() throws Exception {
        Path dataDir = tempDir.resolve("sqlite-install");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("novalink.yml"),
                minimalConfig(true, freePort(), freePort())
                        .replace("type: memory", "type: sqlite"),
                StandardCharsets.UTF_8);

        NovaLinkCore core = newCore();
        core.start(dataDir, loopbackEphemeral());
        try {
            assertThat(core.getContext().getDatabaseProvider().isConnected()).isTrue();
        } finally {
            core.stop();
        }

        assertThat(dataDir.resolve("data").resolve("novalink.db"))
                .as("SQLite file created under <dataDir>/data")
                .exists();
        assertThat(Path.of("data", "novalink.db").toAbsolutePath())
                .as("no database file written into the JVM working directory")
                .doesNotExist();
    }

    /** Writes a config that lets the listeners bind on two currently-free ports. */
    private static void writeBootableConfig(Path dir) throws java.io.IOException {
        Files.writeString(dir.resolve("novalink.yml"),
                minimalConfig(true, freePort(), freePort()), StandardCharsets.UTF_8);
    }

    /** Finds a port the OS is not currently using (config validation forbids 0). */
    private static int freePort() throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private NovaLinkCore newCore() {
        NovaLinkCore core = new NovaLinkCore();
        cores.add(core);
        return core;
    }

    private static NovaLinkCore.BindOverrides loopbackEphemeral() {
        return NovaLinkCore.BindOverrides.loopbackEphemeral();
    }

    private static int novalinkThreadCount() {
        int count = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && NOVALINK_THREADS.test(thread)) {
                count++;
            }
        }
        return count;
    }

    private static void waitForThreadBaseline(int baseline, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (novalinkThreadCount() > baseline && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
    }

    /**
     * A minimal but complete novalink.yml (the loader requires every section
     * to be present). Bound to loopback with ephemeral ports; the TLS knob is
     * the only thing the tests vary. Written to the data dir so ConfigManager
     * loads it instead of the bundled template.
     */
    private static String minimalConfig(boolean allowPlaintext, int tcpPort, int wsPort) {
        return """
                server:
                  bind-address: 127.0.0.1
                  port: %d
                  websocket-port: %d
                  secret-key: test-secret-key-at-least-32-characters-long
                  worker-threads: 2
                  locale: en_US
                  cors-allowed-origins:
                    - "*"
                  idle-timeout-seconds: 0
                  rest-worker-threads: 2
                  rate-limit:
                    messages-per-second: 0
                    burst: 10
                  insecure-allow-plaintext: %s

                database:
                  type: memory
                  mysql:
                    host: 127.0.0.1
                    port: 3306
                    database: novalink
                    username: root
                    password: ""
                    pool-size: 2
                    ssl-mode: REQUIRED
                  postgresql:
                    host: 127.0.0.1
                    port: 5432
                    database: novalink
                    username: postgres
                    password: ""
                    pool-size: 2
                    ssl-mode: verify-ca
                  sqlite:
                    file-path: data/novalink.db
                    pool-size: 2
                  redis:
                    enabled: false
                    host: 127.0.0.1
                    port: 6379
                    password: ""

                security:
                  allowed-ips: []
                  ip-ban-duration: 300

                super-admins: []
                panel-users: []
                debug: false
                global_channels: {}
                templates: {}
                clients: []
                features:
                  filter-enabled: false
                  message-log-enabled: false
                  cross-server-chat-enabled: true
                  private-messages-enabled: true
                  message-log-retention-days: 7
                filter:
                  words: []
                  patterns: []
                """.formatted(tcpPort, wsPort, String.valueOf(allowPlaintext));
    }

}
