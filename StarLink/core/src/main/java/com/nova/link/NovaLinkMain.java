package com.nova.link;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.link.console.BackendConsole;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.console.BackendContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Standalone-process entry point for the NovaLink backend.
 *
 * <p>This class is intentionally a thin shell: argument parsing, the
 * interactive console, and the JVM shutdown hook live here; the backend
 * itself lives in {@link NovaLinkCore}, which is embeddable and can be
 * started/stopped repeatedly inside a host JVM.
 *
 * <p>Two run modes are supported: the default interactive console (JLine
 * readline on the main thread) and {@code --daemon} headless mode, where the
 * console is never touched and the main thread blocks until the JVM receives
 * an external stop signal. Daemon mode exists for launchers that start the
 * backend as a background child process: those give the JVM a stdin pipe that
 * is never written to and never closed, or a closed one, and the console loop
 * either blocks forever or exits immediately on EOF — neither is a usable
 * "long-lived backend" for a CI harness.
 *
 * <p>The shutdown hook is registered here — and only here — because
 * {@link NovaLinkCore} must not register one: a hook per reload pins the
 * class loader (it references the whole object graph) and never gets removed.
 */
public final class NovaLinkMain {

    private static final Logger logger = LoggerFactory.getLogger(NovaLinkMain.class);

    private NovaLinkMain() {
    }

    /**
     * Backend entry point. Honors {@code --help}/{@code -h} by printing the
     * console command list and exiting without starting a server; otherwise
     * bootstraps the core, registers the JVM shutdown hook, then runs either
     * the JLine console loop on this thread (default) or, with
     * {@code --daemon}, blocks until the JVM is asked to stop. In both modes
     * the core is stopped when the main thread leaves its run loop.
     *
     * <p>Accepted arguments: {@code --help}/{@code -h}, {@code --daemon}
     * (headless: no console, block until SIGTERM/Ctrl+C or another thread
     * requests JVM shutdown), or a positional path to {@code novalink.yml}
     * (its parent directory becomes the data dir). {@code --data-dir <dir>}
     * selects the data directory explicitly. Flags are order-independent.
     *
     * <p>Why daemon mode: the console loop terminates as soon as stdin reports
     * EOF. A launcher that starts this JVM with a redirected stdin — a closed
     * pipe, {@code /dev/null}, or a parent PowerShell's {@code -RedirectStandardInput}
     * handle — therefore makes the backend print "EOF received, shutting
     * down..." and exit about a second after "started successfully", before a
     * client can ever connect. {@code --daemon} removes stdin from the
     * liveness equation entirely: the latch is only released by the shutdown
     * hook, so the process stays up until something stops it.
     *
     * @param args raw command-line arguments
     */
    public static void main(String[] args) {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            BackendConsole.printHelpAndExit();
            return;
        }

        boolean daemon = hasFlag(args, "--daemon");
        Path dataDir = resolveDataDir(args);
        NovaLinkCore core = new NovaLinkCore();
        // Released by the shutdown hook in daemon mode; unused otherwise. A
        // CountDownLatch (not Object.wait) keeps the intent explicit and gives
        // await() an interruptible, spurious-wakeup-free contract.
        CountDownLatch stopSignal = new CountDownLatch(1);

        try {
            core.start(dataDir);
        } catch (Exception e) {
            logger.error("Fatal error during startup: {}", e.getMessage(), e);
            // start() already rolled back every component it brought up.
            core.stop();
            return;
        }

        Thread shutdownHook = new Thread(() -> {
            logger.info("Shutdown requested (JVM hook). Stopping services...");
            stopSignal.countDown();
            core.stop();
        }, "NovaLink-Shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            if (daemon) {
                awaitStopSignal(stopSignal);
            } else {
                runConsoleLoop(core);
            }
        } finally {
            core.stop();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down — the hook is running or ran.
            }
        }
        logger.info(daemon
                ? "Daemon stop signal received; NovaLink main thread returning."
                : "Console loop exited; NovaLink main thread returning.");
    }

    /**
     * Headless replacement for {@link #runConsoleLoop}: blocks the main thread
     * until the shutdown hook releases {@code stopSignal} (SIGTERM/Ctrl+C) or
     * the thread is interrupted. Never reads stdin, so a closed or absent
     * stdin cannot terminate the backend — that is the whole point of daemon
     * mode. A long {@code await} timeout with a loop is used rather than an
     * unbounded wait so an interrupted thread cannot spin forever and so the
     * blocking is observable in thread dumps as a timed wait.
     */
    private static void awaitStopSignal(CountDownLatch stopSignal) {
        logger.info("Daemon mode: console disabled; waiting for an external stop signal.");
        while (true) {
            try {
                // 1 day: long enough to be "indefinite" for a CI job, short
                // enough that a leaked latch cannot pin the JVM forever after
                // the hook failed to run.
                if (stopSignal.await(1, TimeUnit.DAYS)) {
                    return;
                }
                logger.debug("Daemon stop signal not yet received; continuing to wait.");
            } catch (InterruptedException e) {
                // Preserve the interrupt for the caller that asked us to stop,
                // then leave the loop so the finally block stops the core.
                Thread.currentThread().interrupt();
                logger.info("Daemon main thread interrupted; stopping services.");
                return;
            }
        }
    }

    /**
     * Runs the interactive console on the main thread. The console is owned by
     * this class only — {@link NovaLinkCore} never touches it, because
     * {@link BackendConsole} takes over the system terminal / stdin, which an
     * embedded plugin must leave to its host.
     */
    private static void runConsoleLoop(NovaLinkCore core) {
        BackendContext context = core.getContext();
        if (context == null) {
            logger.error("Backend context not initialized; console loop skipped.");
            return;
        }
        ConsoleCommandHandler cmdHandler = new ConsoleCommandHandler(context);
        BackendConsole console = new BackendConsole(cmdHandler, core::stop);
        console.run();
    }

    private static boolean hasFlag(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the data directory (the folder holding {@code novalink.yml}):
     * {@code --data-dir <dir>} wins, then a positional config path, then the
     * working directory.
     */
    static Path resolveDataDir(String[] args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if ("--data-dir".equals(a) && i + 1 < args.length && !args[i + 1].isBlank()) {
                    return Paths.get(args[i + 1]).toAbsolutePath();
                }
                if (a != null && a.startsWith("--data-dir=")) {
                    return Paths.get(a.substring("--data-dir=".length())).toAbsolutePath();
                }
            }
            for (String a : args) {
                if (a == null || a.isBlank() || a.startsWith("-")) {
                    continue;
                }
                Path configPath = Paths.get(a).toAbsolutePath();
                Path parent = configPath.getParent();
                return parent != null ? parent : Paths.get(".").toAbsolutePath();
            }
        }
        return Paths.get(".").toAbsolutePath();
    }

    /** @return the protocol version the backend speaks (console/diagnostic use). */
    static int protocolVersion() {
        return NovaProtocol.PROTOCOL_VERSION;
    }
}
