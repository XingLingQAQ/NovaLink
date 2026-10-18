package com.nova.link;

import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.link.console.BackendConsole;
import com.nova.link.console.ConsoleCommandHandler;
import com.nova.link.console.BackendContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Standalone-process entry point for the NovaLink backend.
 *
 * <p>This class is intentionally a thin shell: argument parsing, the
 * interactive console, and the JVM shutdown hook live here; the backend
 * itself lives in {@link NovaLinkCore}, which is embeddable and can be
 * started/stopped repeatedly inside a host JVM.
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
     * bootstraps the core, registers the JVM shutdown hook, runs the JLine
     * console loop on this thread, and stops the core when the loop returns.
     *
     * <p>Accepted arguments: {@code --help}/{@code -h}, or a positional path
     * to {@code novalink.yml} (its parent directory becomes the data dir).
     * {@code --data-dir <dir>} selects the data directory explicitly.
     *
     * @param args raw command-line arguments
     */
    public static void main(String[] args) {
        if (hasFlag(args, "--help") || hasFlag(args, "-h")) {
            BackendConsole.printHelpAndExit();
            return;
        }

        Path dataDir = resolveDataDir(args);
        NovaLinkCore core = new NovaLinkCore();

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
            core.stop();
        }, "NovaLink-Shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            runConsoleLoop(core);
        } finally {
            core.stop();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down — the hook is running or ran.
            }
        }
        logger.info("Console loop exited; NovaLink main thread returning.");
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
