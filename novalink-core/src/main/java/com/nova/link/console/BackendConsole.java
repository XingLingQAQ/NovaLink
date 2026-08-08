package com.nova.link.console;

import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.Channel;
import com.nova.link.database.PlayerState;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Completer;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Backend console loop with JLine-backed readline + TAB completion.
 *
 * <p>Runs on the main thread (replaces the old {@code shutdownLatch.await()}).
 * The loop reads a line, dispatches it via {@link ConsoleCommandHandler}, and
 * prints the rendered output. The {@code stop}/{@code shutdown} command returns
 * the {@link ConsoleCommandHandler#STOP_TOKEN}, which triggers the shared
 * {@code safeShutdown} path and exits the loop cleanly. Ctrl+C / SIGTERM is
 * handled by the JVM shutdown hook (registered in {@code NovaLinkMain}), which
 * calls the same {@code safeShutdown}.
 *
 * <p>Tab completion rules:
 * <ul>
 *   <li>First token: command names.</li>
 *   <li>{@code help <TAB>}: command names.</li>
 *   <li>{@code mute/unmute/kick <player> <TAB>} after the player token: channel ids.</li>
 *   <li>{@code mute/unmute/kick <TAB>} at the player position: online player names.</li>
 *   <li>{@code channel/announce/title/delete <TAB>}: channel ids.</li>
 *   <li>{@code spy start <channel> <TAB>}: channel ids; {@code spy off <TAB>}: admin UUIDs with sessions.</li>
 * </ul>
 */
public class BackendConsole {

    private static final Logger logger = LoggerFactory.getLogger(BackendConsole.class);

    private final ConsoleCommandHandler handler;
    private final Runnable shutdownHook;
    private final Terminal terminal;
    private final LineReader reader;

    /**
     * @param handler       the command handler
     * @param shutdownHook  invoked when the user types {@code stop}/{@code shutdown};
     *                      must perform the same graceful shutdown as the JVM hook.
     * @param terminal      JLine terminal (may be null in tests — use the
     *                      {@link #BackendConsole(ConsoleCommandHandler, Runnable)}
     *                      ctor then, which builds a dumb terminal)
     */
    public BackendConsole(ConsoleCommandHandler handler, Runnable shutdownHook, Terminal terminal) {
        this.handler = java.util.Objects.requireNonNull(handler);
        this.shutdownHook = java.util.Objects.requireNonNull(shutdownHook);
        this.terminal = terminal;
        this.reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new BackendCompleter(handler.context()))
                .appName("NovaLink")
                .build();
    }

    /** Convenience constructor that builds a default terminal. */
    public BackendConsole(ConsoleCommandHandler handler, Runnable shutdownHook) {
        this(handler, shutdownHook, buildDefaultTerminal());
    }

    private static Terminal buildDefaultTerminal() {
        try {
            return TerminalBuilder.builder().system(true).build();
        } catch (IOException e) {
            logger.warn("Failed to build system terminal; falling back to dumb terminal", e);
            try {
                return TerminalBuilder.builder().dumb(true).build();
            } catch (IOException ex) {
                throw new RuntimeException("Unable to build any JLine terminal", ex);
            }
        }
    }

    /** Exposed for tests so the completer can be exercised without a live terminal. */
    public Completer completer() {
        return new BackendCompleter(handler.context());
    }

    /**
     * Runs the console loop on the calling (main) thread. Returns when the user
     * types {@code stop}/{@code shutdown} or sends EOF (Ctrl+D).
     */
    public void run() {
        System.out.println("NovaLink backend console ready. Type 'help' for commands, 'stop' to shut down.");
        while (true) {
            String line;
            try {
                line = reader.readLine("novalink> ");
            } catch (EndOfFileException e) {
                // Ctrl+D — treat as shutdown.
                System.out.println("EOF received, shutting down...");
                shutdownHook.run();
                return;
            } catch (UserInterruptException e) {
                // Ctrl+C — the JVM shutdown hook handles shutdown; just exit the loop.
                System.out.println("Interrupted, shutting down...");
                shutdownHook.run();
                return;
            } catch (Exception e) {
                logger.error("Console read error", e);
                // Fall back to a plain BufferedReader if JLine misbehaves on this terminal.
                System.err.println("Console error: " + e.getMessage() + " — continuing.");
                continue;
            }

            String output = handler.dispatch(line);
            if (ConsoleCommandHandler.STOP_TOKEN.equals(output)) {
                System.out.println("Shutting down...");
                shutdownHook.run();
                return;
            }
            if (!output.isEmpty()) {
                System.out.print(output);
            }
        }
    }

    /**
     * Prints the help/usage block to stdout and returns. Used by the
     * {@code --help}/{@code -h} CLI arg path in {@code NovaLinkMain} so the
     * wiring can be smoke-tested without starting a server.
     */
    public static void printHelpAndExit() {
        StringBuilder sb = new StringBuilder();
        sb.append("NovaLink backend — console commands:\n");
        for (String name : ConsoleCommandHandler.commandNames()) {
            sb.append("  ").append(name).append('\n');
        }
        sb.append("\nRun without --help to start the server and enter the interactive console.\n");
        System.out.print(sb.toString());
    }

    // ============================ Completer ============================

    /**
     * Contextual tab completer for the backend console. Public so tests can
     * construct it directly with a {@link com.nova.link.console.BackendContext}.
     */
    public static final class BackendCompleter implements Completer {

        private final BackendContext ctx;
        private final StringsCompleter commandCompleter;

        public BackendCompleter(BackendContext ctx) {
            this.ctx = ctx;
            this.commandCompleter = new StringsCompleter(ConsoleCommandHandler.commandNames());
        }

        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            if (line == null) return;
            List<String> words = line.words();
            if (words.isEmpty()) return;

            // First token: command names.
            if (words.size() <= 1) {
                commandCompleter.complete(reader, line, candidates);
                return;
            }

            String cmd = words.get(0).toLowerCase(Locale.ROOT);
            int argIndex = words.size() - 2; // 0-based position of the current arg being completed

            switch (cmd) {
                case "help": case "?":
                    commandCompleter.complete(reader, line, candidates);
                    return;
                case "mute":
                    // mute <player> <channel> <dur> [reason]
                    if (argIndex == 0) {
                        addPlayerCandidates(candidates);
                    } else if (argIndex == 1) {
                        addChannelCandidates(candidates);
                    }
                    return;
                case "unmute":
                    // unmute <player> <channel>
                    if (argIndex == 0) {
                        addPlayerCandidates(candidates);
                    } else if (argIndex == 1) {
                        addChannelCandidates(candidates);
                    }
                    return;
                case "kick":
                    // kick <player> <channel>
                    if (argIndex == 0) {
                        addPlayerCandidates(candidates);
                    } else if (argIndex == 1) {
                        addChannelCandidates(candidates);
                    }
                    return;
                case "mutes":
                    // mutes [player]
                    if (argIndex == 0) {
                        addPlayerCandidates(candidates);
                    }
                    return;
                case "channel": case "announce": case "title": case "delete":
                    // <channel> ...
                    if (argIndex == 0) {
                        addChannelCandidates(candidates);
                    }
                    return;
                case "spy":
                    // spy start <channel> [adminId] | spy off [adminId]
                    if (argIndex == 0) {
                        addCandidate(candidates, "start");
                        addCandidate(candidates, "off");
                    } else if (argIndex == 1 && words.size() >= 3) {
                        String sub = words.get(1).toLowerCase(Locale.ROOT);
                        if ("start".equals(sub)) {
                            addChannelCandidates(candidates);
                        } else if ("off".equals(sub)) {
                            addSpyAdminCandidates(candidates);
                        }
                    }
                    return;
                case "create":
                    // create <name> [password] [scope]
                    if (argIndex == 2) {
                        addCandidate(candidates, "global");
                        addCandidate(candidates, "private");
                    }
                    return;
                default:
                    // no completion
                    return;
            }
        }

        private void addPlayerCandidates(List<Candidate> candidates) {
            if (ctx == null) return;
            Collection<PlayerState> states = ctx.getPlayerStateManager().getAllPlayerStates();
            for (PlayerState s : states) {
                if (s.getPlayerName() != null && !s.getPlayerName().isBlank()) {
                    addCandidate(candidates, s.getPlayerName());
                }
                addCandidate(candidates, s.getPlayerId().toString());
            }
        }

        private void addChannelCandidates(List<Candidate> candidates) {
            if (ctx == null) return;
            for (Channel ch : ctx.getChannelManager().getAllChannels()) {
                addCandidate(candidates, ch.getId());
            }
        }

        private void addSpyAdminCandidates(List<Candidate> candidates) {
            if (ctx == null) return;
            // No public list-all admins API on SpyManager; surface monitored
            // channels' spies via getChannelSpies for each monitored channel.
            for (String ch : ctx.getSpyManager().getAllMonitoredChannels()) {
                for (UUID admin : ctx.getSpyManager().getChannelSpies(ch)) {
                    addCandidate(candidates, admin.toString());
                }
            }
        }

        private static void addCandidate(List<Candidate> candidates, String value) {
            if (value == null || value.isEmpty()) return;
            candidates.add(new Candidate(value));
        }
    }
}
