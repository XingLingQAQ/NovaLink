package com.nova.link.bukkit;

import com.nova.link.NovaLinkCore;
import com.nova.link.console.ConsoleCommandHandler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hosts the NovaLink backend inside a Paper/Purpur (Bukkit) server process.
 *
 * <p>Deliberately thin: it resolves the data folder, starts
 * {@link NovaLinkCore}, reports the real bound endpoints, and bridges the
 * core's console commands onto the Minecraft console. Everything else --
 * configuration, database, listeners, panel -- stays in the core, which is the
 * same code the standalone {@code java -jar} launcher runs.
 *
 * <p>Failure policy: a backend startup failure disables the plugin. The server
 * keeps running; only NovaLink is unavailable. The core rolls back every
 * component it started before the exception propagates, so there are no
 * half-open ports or leaked threads behind a disabled plugin.
 */
public final class NovaLinkBukkitPlugin extends JavaPlugin {

    /** Prefix for the bridged console commands, e.g. {@code /novalink status}. */
    private static final String COMMAND_ROOT = "novalink";

    /** Commands whose execution stops the backend and must not be bridged. */
    private static final List<String> NOT_BRIDGED = List.of("stop", "shutdown");

    /** Upper bound on a single bridged command; the backend has its own timeouts. */
    private static final long COMMAND_TIMEOUT_SECONDS = 120L;

    /**
     * How long a console/RCON command may block before the reply is abandoned.
     * Those senders are answered synchronously: RCON sends its response as soon
     * as {@code onCommand} returns, so an async dispatch produces an empty
     * reply (measured against a real Purpur server + RCON). The wait is bounded
     * so a slow database query cannot wedge the server tick indefinitely.
     */
    private static final long CONSOLE_REPLY_WAIT_SECONDS = 20L;

    private final AtomicReference<NovaLinkCore> coreRef = new AtomicReference<>();
    /** True once {@link #onEnable} started the core successfully. */
    private final AtomicBoolean backendStarted = new AtomicBoolean(false);
    /** True once {@link #onDisable} handled the teardown, so it runs at most once. */
    private final AtomicBoolean disabled = new AtomicBoolean(false);

    private volatile ConsoleCommandHandler consoleHandler;

    @Override
    public void onEnable() {
        Path dataDir = getDataFolder().toPath();
        try {
            BackendDefaults.SeedResult seeded =
                    BackendDefaults.seedIfAbsent(dataDir, resolveSeedLoopback());
            logSeedOutcome(seeded, dataDir);
        } catch (Exception e) {
            // Not fatal: the core also writes a template when the file is
            // missing. It just will not carry the loopback default.
            getLogger().warning("Could not pre-seed " + BackendDefaults.CONFIG_FILE + ": "
                    + e.getMessage() + " (the backend will create it with its own defaults)");
        }

        NovaLinkCore core = new NovaLinkCore();
        coreRef.set(core);
        try {
            core.start(dataDir);
        } catch (Throwable t) {
            // start() has already rolled back every component it brought up.
            // Do NOT rethrow: an exception from onEnable lets the server treat
            // it as a broken plugin; disabling is the controlled outcome.
            getLogger().severe("NovaLink backend failed to start: " + describe(t));
            getLogger().severe("Plugin disabled. The server keeps running without NovaLink.");
            getLogger().severe("Configuration: " + dataDir.resolve(BackendDefaults.CONFIG_FILE));
            backendStarted.set(false);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        backendStarted.set(true);
        // disablePlugin(this) run by another plugin while we were starting
        // invokes onDisable re-entrantly on THIS thread; onDisable then takes a
        // different lock and swallows the core we are still holding. If we
        // reach here with disabled already set, the backend is no longer wanted.
        if (disabled.get()) {
            stopBackend();
            return;
        }
        try {
            consoleHandler = new ConsoleCommandHandler(core.getContext());
        } catch (RuntimeException e) {
            // Bridging is a convenience; the backend itself is healthy.
            consoleHandler = null;
            getLogger().warning("Console command bridge unavailable: " + e);
        }

        getLogger().info("NovaLink backend started.");
        getLogger().info("Configuration: " + dataDir.resolve(BackendDefaults.CONFIG_FILE));
        reportBindings(core);
        reportCommandBridge();
    }

    @Override
    public void onDisable() {
        if (!disabled.compareAndSet(false, true)) {
            return;
        }
        stopBackend();
    }

    /**
     * On the failed-start path {@link #onEnable} already rolled the core back;
     * {@link NovaLinkCore#stop()} is safe after that but there is nothing left
     * to stop, so the flag keeps this from claiming a stop that never happened.
     * Idempotent: {@link #disabled} gates the callers, and the flags are
     * exchanged atomically.
     */
    private void stopBackend() {
        NovaLinkCore core = coreRef.getAndSet(null);
        consoleHandler = null;
        if (core == null || !backendStarted.getAndSet(false)) {
            return;
        }
        long begin = System.nanoTime();
        try {
            core.stop();
        } catch (Throwable t) {
            getLogger().warning("Error while stopping the NovaLink backend: " + describe(t));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        getLogger().info("NovaLink backend stopped in " + elapsedMs + " ms.");
    }

    // ==================== startup defaults ====================

    /**
     * Only loopback-seed when the operator has not opted out. The opt-out is a
     * deliberate plugin config flag rather than a special value inside
     * {@code novalink.yml}, because that file belongs to the core.
     */
    private boolean resolveSeedLoopback() {
        try {
            saveDefaultConfig();
        } catch (RuntimeException e) {
            // Bukkit's default saveConfig is best-effort; a read-only data
            // folder must not prevent the backend from starting.
            getLogger().warning("Could not write the plugin config.yml: " + e);
        }
        boolean allow = getConfig().getBoolean("network.bind-public-by-default", false);
        return !allow;
    }

    private void logSeedOutcome(BackendDefaults.SeedResult seeded, Path dataDir) {
        Path configFile = dataDir.resolve(BackendDefaults.CONFIG_FILE);
        switch (seeded) {
            case SEEDED_LOOPBACK ->
                    getLogger().info("Created " + configFile
                            + " with server.bind-address: " + BackendDefaults.LOOPBACK
                            + " and insecure-allow-plaintext: true (loopback only, so the"
                            + " plaintext gate's network-observer risk does not apply)."
                            + " The backend is reachable only from this machine; set"
                            + " network.bind-public-by-default: true in config.yml to seed"
                            + " the backend's own 0.0.0.0 default instead, then configure"
                            + " server.tls before exposing it.");
            case SEEDED_UNMODIFIED ->
                    getLogger().warning("Created " + configFile
                            + " from the backend template without applying the plugin's"
                            + " loopback defaults (the template layout changed);"
                            + " check the listener addresses logged below");
            case ALREADY_PRESENT -> getLogger().info("Using existing configuration: " + configFile);
        }
    }

    /**
     * Logs the actual endpoints, using the BOUND port (a configured port of 0
     * means an OS-assigned port). An address reachable from off-machine gets an
     * explicit warning naming the ports and the setting to change.
     */
    private void reportBindings(NovaLinkCore core) {
        var ctx = core.getContext();
        if (ctx == null) {
            return;
        }

        String tcpAddress = null;
        int tcpPort = 0;
        if (ctx.getTcpServer() != null) {
            tcpAddress = ctx.getTcpServer().getBindAddress();
            tcpPort = ctx.getTcpServer().getBoundPort();
            getLogger().info("Backend TCP listener on " + tcpAddress + ":" + tcpPort);
        }

        String wsAddress = null;
        int wsPort = 0;
        if (ctx.getWebSocketGateway() != null) {
            wsAddress = ctx.getWebSocketGateway().getBindAddress();
            wsPort = ctx.getWebSocketGateway().getBoundPort();
            getLogger().info("Backend WebSocket/REST listener on " + wsAddress + ":" + wsPort);
        }

        List<String> exposed = new ArrayList<>();
        if (tcpAddress != null && !BackendDefaults.isLoopback(tcpAddress)) {
            exposed.add("TCP " + tcpAddress + ":" + tcpPort);
        }
        if (wsAddress != null && !BackendDefaults.isLoopback(wsAddress)) {
            exposed.add("WebSocket/REST " + wsAddress + ":" + wsPort);
        }
        if (!exposed.isEmpty()) {
            getLogger().warning("The backend listeners are bound to a non-loopback address: "
                    + String.join(", ", exposed)
                    + ". They are reachable from outside this machine. Either set"
                    + " server.bind-address: 127.0.0.1 in "
                    + BackendDefaults.CONFIG_FILE
                    + " or firewall the ports.");
        }
    }

    private void reportCommandBridge() {
        if (consoleHandler != null) {
            getLogger().info("Console bridge: /" + COMMAND_ROOT
                    + " <command>, e.g. /" + COMMAND_ROOT + " status");
            return;
        }
        // Plugins cannot register console-only commands; Bukkit commands are
        // always player-visible unless a permission is declared. A half-working
        // bridge is worse than none, so say so instead of exposing the backend
        // commands to players.
        getLogger().warning("Console command bridge not available: the backend context is"
                + " missing, so /" + COMMAND_ROOT + " is disabled. Backend commands remain"
                + " available through the panel's console endpoint.");
    }

    // ==================== command bridge ====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("novalink.admin")) {
            sender.sendMessage("You do not have permission to run NovaLink commands.");
            return true;
        }
        ConsoleCommandHandler handler = consoleHandler;
        if (handler == null) {
            sender.sendMessage("NovaLink backend console is not available (backend not running).");
            return true;
        }

        String line = joinCommandLine(args);
        if (line.isEmpty()) {
            sender.sendMessage("Usage: /" + COMMAND_ROOT + " <command> [args...]");
            sender.sendMessage("Commands: " + String.join(", ", bridgedCommands()));
            return true;
        }

        String root = args[0].toLowerCase(Locale.ROOT);
        if (NOT_BRIDGED.contains(root)) {
            sender.sendMessage("/" + root + " is not available from the game console;"
                    + " stop the server normally or use /reload/plugin manager.");
            return true;
        }

        // dispatch() touches the database and the channel managers and can
        // block, so it must not run on the server tick thread. Players get the
        // output posted back later; the console/RCON sender is answered
        // synchronously by Bukkit and needs the result before we return.
        CompletableFuture<String> dispatchFuture =
                CompletableFuture.supplyAsync(() -> handler.dispatch(line))
                        .orTimeout(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (isConsoleSender(sender)) {
            sendBlock(sender, awaitForConsole(dispatchFuture));
            return true;
        }

        dispatchFuture.whenComplete((output, error) -> getServer().getScheduler()
                .runTask(this, () -> {
                    if (error != null) {
                        sender.sendMessage("NovaLink command failed: " + describe(error));
                        return;
                    }
                    sendBlock(sender, output);
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("novalink.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return bridgedCommands().stream()
                    .filter(name -> name.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private static List<String> bridgedCommands() {
        return ConsoleCommandHandler.commandNames().stream()
                .filter(name -> !NOT_BRIDGED.contains(name))
                .toList();
    }

    private static String joinCommandLine(String[] args) {
        // The command root is implicit: /novalink status -> "status". Bukkit
        // has already split on whitespace; the backend accepts the same shape.
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(arg);
        }
        return sb.toString().trim();
    }

    /**
     * The server console and RCON both answer as soon as {@code onCommand}
     * returns, so these senders need a blocking wait.
     */
    private static boolean isConsoleSender(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender instanceof RemoteConsoleCommandSender;
    }

    private String awaitForConsole(CompletableFuture<String> future) {
        try {
            return future.get(CONSOLE_REPLY_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return "NovaLink command is still running; its result is not shown here."
                    + " Check the server log.";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "NovaLink command interrupted.";
        } catch (java.util.concurrent.ExecutionException e) {
            return "NovaLink command failed: " + describe(e);
        }
    }

    private void sendBlock(CommandSender sender, String output) {
        if (output == null || output.isEmpty()) {
            return;
        }
        for (String line : output.split("\\R")) {
            if (!line.isEmpty()) {
                sender.sendMessage(line);
            }
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null
                ? t.getCause()
                : t;
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }
}
