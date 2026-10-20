package com.nova.chat.mod.platform.brigadier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.mod.command.AnnounceCommand;
import com.nova.chat.mod.command.AuthCommand;
import com.nova.chat.mod.command.BanCommand;
import com.nova.chat.mod.command.HelpCommand;
import com.nova.chat.mod.command.IgnoreCommand;
import com.nova.chat.mod.command.JoinCommand;
import com.nova.chat.mod.command.KickCommand;
import com.nova.chat.mod.command.LeaveCommand;
import com.nova.chat.mod.command.ListCommand;
import com.nova.chat.mod.command.MsgCommand;
import com.nova.chat.mod.command.MuteCommand;
import com.nova.chat.mod.command.ReloadCommand;
import com.nova.chat.mod.command.ReplyCommand;
import com.nova.chat.mod.command.TitleCommand;
import com.nova.chat.mod.command.ToggleCommand;
import com.nova.chat.mod.command.UnbanCommand;
import com.nova.chat.mod.command.UnignoreCommand;
import com.nova.chat.mod.command.WhoCommand;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.ConsoleCapableCommand;
import com.nova.chat.mod.platform.ModServices;
import com.nova.chat.mod.platform.Platform;

/**
 * Shared brigadier command registrar for the NovaChat mod loaders
 * (fabric / neoforge / quilt).
 *
 * <p>Owns the parts of the per-loader {@code CommandRegistrar} classes that are
 * byte-identical across all three loaders:
 * <ul>
 *   <li>the shared handler registrations on {@link CommandManager} (11 baseline
 *       + 6 moderation + hidden {@code auth}) —
 *       {@link #registerSharedStateAndHandlers};</li>
 *   <li>the {@code novachat} + {@code nc} brigadier subtree shape —
 *       {@link #buildLiteral}, parameterised by the loader-supplied
 *       {@link BrigadierHooks} methods so the common layer never imports
 *       Minecraft {@code Commands} / {@code CommandSourceStack}; the
 *       internal novachat/nc dup each loader carried is collapsed into a
 *       loop over {@link #LITERAL_NAMES};</li>
 *   <li>{@code executeSubCommand} — the player-extraction, services-attach and
 *       handler-dispatch path, delegating player lookup, admin check and
 *       context construction to the {@link BrigadierHooks} methods the
 *       loader shim overrides. A non-player source is refused unless the
 *       handler implements {@link ConsoleCapableCommand}; those run with the
 *       all-zero console sentinel identity instead;</li>
 *   <li>{@code executeDebug} — prints the same 5-line banner the per-loader
 *       implementations printed, with the platform name supplied by
 *       {@link BrigadierHooks#platformDisplayName()}.</li>
 * </ul>
 *
 * <p>The common layer has a {@code compileOnly} dependency on
 * {@code com.mojang:brigadier} only — it does <em>not</em> depend on the
 * Minecraft jar. Any API that touches a Minecraft type
 * ({@code CommandSourceStack}, {@code Commands.literal}, {@code Component},
 * {@code ServerPlayer}, {@code PermissionSet}, …) is routed through the
 * {@link BrigadierHooks} methods (admin check, player lookup, debug info,
 * platform display name) or built by the loader shim via the hooks
 * ({@link BrigadierHooks#literal} / {@link BrigadierHooks#argument} /
 * {@link BrigadierHooks#word} / {@link BrigadierHooks#greedyString} /
 * {@link BrigadierHooks#readString} / {@link BrigadierHooks#sendSuccess} /
 * {@link BrigadierHooks#sendFailure}).
 *
 * <p>This class {@code implements BrigadierHooks<S>} so the loader shim simply
 * extends it and overrides the 13 hooks methods — no separate hooks field,
 * no self-injection in the shim constructor.
 *
 * <p>Each loader's {@code *CommandRegistrar} becomes a ~90 line shim that:
 * <ol>
 *   <li>extends this class (inheriting {@link #registerSharedStateAndHandlers},
 *       {@link #registerNovaChatCommands} and all the shared logic);</li>
 *   <li>overrides the 13 {@link BrigadierHooks} methods (5-line bodies each,
 *       each just delegating to the platform's Minecraft API);</li>
 *   <li>exposes a static {@code registerCommands(CommandManager, ModServices)}
 *       that constructs the shim, calls
 *       {@link #registerSharedStateAndHandlers} once, and registers its
 *       platform callback (Fabric/Quilt
 *       {@code CommandRegistrationCallback.EVENT.register}, NeoForge
 *       {@code NeoForge.EVENT_BUS.register} with {@code @SubscribeEvent})
 *       which calls {@link #registerNovaChatCommands} for both
 *       {@code "novachat"} and {@code "nc"} literals.</li>
 * </ol>
 *
 * @param <S> the brigadier source type — on all three loaders
 *            {@code net.minecraft.commands.CommandSourceStack}, but kept as a
 *            generic so the common layer never names the Minecraft type
 */
public abstract class AbstractBrigadierCommandRegistrar<S> implements BrigadierHooks<S> {

    /** The 11 shared subcommand names registered on the {@link CommandManager}. */
    public static final String[] SHARED_COMMAND_NAMES = {
            "help", "join", "leave", "list", "who", "toggle",
            "msg", "r", "ignore", "unignore", "reload"
    };

    /**
     * The moderation subcommand names, matching the bukkit/folia sets. All six
     * are admin-gated ({@link #isAdmin}) and console-capable, mirroring the
     * references' {@code isPlayerOnly() == false} shells.
     */
    public static final String[] MODERATION_COMMAND_NAMES = {
            "mute", "kick", "ban", "unban", "announce", "title"
    };

    /**
     * Hidden subcommand names: registered and runnable, never listed in
     * {@code /nc help} (matching bukkit/folia's hidden {@code auth}).
     */
    public static final String[] HIDDEN_COMMAND_NAMES = {"auth"};

    /** The two top-level literal names ({@code novachat} + the {@code nc} alias). */
    public static final String[] LITERAL_NAMES = {"novachat", "nc"};

    /**
     * Display name carried by a console/RCON sender, matching the backend's
     * {@code ConsoleSentinel.CONSOLE_NAME} so console-issued moderation reads
     * the same as it does from the backend console.
     */
    public static final String CONSOLE_SENDER_NAME = "Console";

    protected static CommandManager commandManager;
    protected static ModServices services;

    protected AbstractBrigadierCommandRegistrar() {
    }

    // ------------------------------------------------------------------
    // shared handler registrations (identical across all 3 loaders)
    // ------------------------------------------------------------------

    /**
     * Stashes the shared manager / services and registers the shared command
     * handlers on the {@link CommandManager}: the 11 baseline commands
     * (help/join/leave/list/who/toggle/msg/r/ignore/unignore/reload), the six
     * moderation commands ({@link #MODERATION_COMMAND_NAMES}) and the hidden
     * super-admin {@code auth} ({@link #HIDDEN_COMMAND_NAMES}). Each loader's
     * {@code registerCommands(manager, services)} calls this.
     */
    public final void registerSharedStateAndHandlers(CommandManager manager, ModServices svc) {
        commandManager = manager;
        services = svc;
        manager.registerCommand("help", new HelpCommand());
        manager.registerCommand("join", new JoinCommand());
        manager.registerCommand("leave", new LeaveCommand());
        manager.registerCommand("list", new ListCommand());
        manager.registerCommand("who", new WhoCommand());
        manager.registerCommand("toggle", new ToggleCommand());
        manager.registerCommand("msg", new MsgCommand());
        manager.registerCommand("r", new ReplyCommand());
        manager.registerCommand("ignore", new IgnoreCommand());
        manager.registerCommand("unignore", new UnignoreCommand());
        manager.registerCommand("reload", new ReloadCommand());

        // Moderation (admin-gated, console-capable) + the hidden super-admin auth.
        manager.registerCommand("mute", new MuteCommand());
        manager.registerCommand("kick", new KickCommand());
        manager.registerCommand("ban", new BanCommand());
        manager.registerCommand("unban", new UnbanCommand());
        manager.registerCommand("announce", new AnnounceCommand());
        manager.registerCommand("title", new TitleCommand());
        manager.registerCommand("auth", new AuthCommand());
    }

    // ------------------------------------------------------------------
    // brigadier tree building — the literal name loop is here (kills the
    // internal novachat/nc dup that each loader carried)
    // ------------------------------------------------------------------

    /**
     * Registers both the {@code novachat} and {@code nc} literals on the given
     * dispatcher, each built by {@link #buildLiteral} with the same subtree.
     * Loader shim calls this from its platform callback.
     */
    public final void registerNovaChatCommands(CommandDispatcher<S> dispatcher) {
        for (String name : LITERAL_NAMES) {
            dispatcher.register(buildLiteral(name));
        }
    }

    /**
     * Builds one top-level literal subtree (e.g. {@code novachat} or
     * {@code nc}) with all 18 subcommands. The shape is identical to the
     * per-loader implementations:
     * <ul>
     *   <li>{@code help}, {@code leave}, {@code list}, {@code who},
     *       {@code toggle} — no args;</li>
     *   <li>{@code join <channel>} — word argument, channel-id suggestions,
     *       fallback {@code global}/{@code local};</li>
     *   <li>{@code msg <player> <message>} — word + greedyString;</li>
     *   <li>{@code r <message>} — greedyString;</li>
     *   <li>{@code ignore [target]} — no-arg + optional word target;</li>
     *   <li>{@code unignore <target>} — required word target;</li>
     *   <li>{@code reload}, {@code debug} — admin-gated ({@link #isAdmin});</li>
     *   <li>{@code mute <player> <duration> [channel]} — word + word + word;</li>
     *   <li>{@code kick <player> [channel]} — word + optional word;</li>
     *   <li>{@code ban <player> [duration|channel] [channel|duration]} — two
     *       neutral positional words, disambiguated by the handler;</li>
     *   <li>{@code unban <target> [channel]} — word + optional word;</li>
     *   <li>{@code announce <channel> <content>} — word + greedyString;</li>
     *   <li>{@code title <channel> <title> [subtitle]} — word + word +
     *       optional greedyString;</li>
     *   <li>{@code auth <password>} — greedyString, hidden from help;</li>
     *   <li>root executes {@code help} as the default.</li>
     * </ul>
     *
     * <p>The moderation literals and {@code auth} carry the same
     * {@link #isAdmin} requirement as {@code reload}/{@code debug} — the only
     * permission gate the mod layer has.
     *
     * <p>Calls the {@link BrigadierHooks} methods ({@link #literal},
     * {@link #argument}, {@link #word}, {@link #greedyString},
     * {@link #readString}) which the loader shim overrides, so the common
     * layer never names Minecraft's {@code Commands} /
     * {@code StringArgumentType}.
     */
    protected final LiteralArgumentBuilder<S> buildLiteral(String name) {
        return literal(name)
                // help / leave / list / who / toggle — no args
                .then(literal("help")
                        .executes(ctx -> executeSubCommand(ctx, "help", new String[0])))
                .then(literal("join")
                        .then(argument("channel", word())
                                .suggests((ctx, builder) -> {
                                    if (services != null) {
                                        for (String id : services.getKnownChannelRegistry()
                                                .getKnownChannelIds(builder.getRemainingLowerCase())) {
                                            builder.suggest(id);
                                        }
                                    }
                                    if (builder.getRemaining().isEmpty()) {
                                        builder.suggest("global");
                                        builder.suggest("local");
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    String channel = readString(ctx, "channel");
                                    return executeSubCommand(ctx, "join", new String[]{channel});
                                })))
                .then(literal("leave")
                        .executes(ctx -> executeSubCommand(ctx, "leave", new String[0])))
                .then(literal("list")
                        .executes(ctx -> executeSubCommand(ctx, "list", new String[0])))
                .then(literal("who")
                        .executes(ctx -> executeSubCommand(ctx, "who", new String[0])))
                .then(literal("toggle")
                        .executes(ctx -> executeSubCommand(ctx, "toggle", new String[0])))
                .then(literal("msg")
                        .then(argument("player", word())
                                .then(argument("message", greedyString())
                                        .executes(ctx -> {
                                            String p = readString(ctx, "player");
                                            String m = readString(ctx, "message");
                                            return executeSubCommand(ctx, "msg", new String[]{p, m});
                                        }))))
                .then(literal("r")
                        .then(argument("message", greedyString())
                                .executes(ctx -> {
                                    String m = readString(ctx, "message");
                                    return executeSubCommand(ctx, "r", new String[]{m});
                                })))
                .then(literal("ignore")
                        .executes(ctx -> executeSubCommand(ctx, "ignore", new String[0]))
                        .then(argument("target", word())
                                .executes(ctx -> {
                                    String t = readString(ctx, "target");
                                    return executeSubCommand(ctx, "ignore", new String[]{t});
                                })))
                .then(literal("unignore")
                        .then(argument("target", word())
                                .executes(ctx -> {
                                    String t = readString(ctx, "target");
                                    return executeSubCommand(ctx, "unignore", new String[]{t});
                                })))
                .then(literal("reload")
                        .requires(this::isAdmin)
                        .executes(ctx -> executeSubCommand(ctx, "reload", new String[0])))
                .then(literal("debug")
                        .requires(this::isAdmin)
                        .executes(this::executeDebug))

                // --- moderation (mirrors the bukkit/folia argument shapes) ---
                .then(literal("mute")
                        .requires(this::isAdmin)
                        .then(argument("player", word())
                                .then(argument("duration", word())
                                        .executes(ctx -> executeSubCommand(ctx, "mute",
                                                new String[]{readString(ctx, "player"),
                                                        readString(ctx, "duration")}))
                                        .then(argument("channel", word())
                                                .executes(ctx -> executeSubCommand(ctx, "mute",
                                                        new String[]{readString(ctx, "player"),
                                                                readString(ctx, "duration"),
                                                                readString(ctx, "channel")}))))))
                .then(literal("kick")
                        .requires(this::isAdmin)
                        .then(argument("player", word())
                                .executes(ctx -> executeSubCommand(ctx, "kick",
                                        new String[]{readString(ctx, "player")}))
                                .then(argument("channel", word())
                                        .executes(ctx -> executeSubCommand(ctx, "kick",
                                                new String[]{readString(ctx, "player"),
                                                        readString(ctx, "channel")})))))
                .then(literal("ban")
                        .requires(this::isAdmin)
                        .then(argument("player", word())
                                .executes(ctx -> executeSubCommand(ctx, "ban",
                                        new String[]{readString(ctx, "player")}))
                                // duration and channel may be given in either order,
                                // so the two positional names stay neutral; the
                                // handler disambiguates by shape (as the references do).
                                .then(argument("durationOrChannel", word())
                                        .executes(ctx -> executeSubCommand(ctx, "ban",
                                                new String[]{readString(ctx, "player"),
                                                        readString(ctx, "durationOrChannel")}))
                                        .then(argument("channelOrDuration", word())
                                                .executes(ctx -> executeSubCommand(ctx, "ban",
                                                        new String[]{readString(ctx, "player"),
                                                                readString(ctx, "durationOrChannel"),
                                                                readString(ctx, "channelOrDuration")}))))))
                .then(literal("unban")
                        .requires(this::isAdmin)
                        // word() admits the '-' in a UUID, so an offline target
                        // can still be named exactly
                        .then(argument("target", word())
                                .executes(ctx -> executeSubCommand(ctx, "unban",
                                        new String[]{readString(ctx, "target")}))
                                .then(argument("channel", word())
                                        .executes(ctx -> executeSubCommand(ctx, "unban",
                                                new String[]{readString(ctx, "target"),
                                                        readString(ctx, "channel")})))))
                .then(literal("announce")
                        .requires(this::isAdmin)
                        .then(argument("channel", word())
                                .then(argument("content", greedyString())
                                        .executes(ctx -> executeSubCommand(ctx, "announce",
                                                new String[]{readString(ctx, "channel"),
                                                        readString(ctx, "content")})))))
                .then(literal("title")
                        .requires(this::isAdmin)
                        .then(argument("channel", word())
                                .then(argument("title", word())
                                        .executes(ctx -> executeSubCommand(ctx, "title",
                                                new String[]{readString(ctx, "channel"),
                                                        readString(ctx, "title")}))
                                        // the rest of the line is the subtitle, matching
                                        // the references' args[2..] join
                                        .then(argument("subtitle", greedyString())
                                                .executes(ctx -> executeSubCommand(ctx, "title",
                                                        new String[]{readString(ctx, "channel"),
                                                                readString(ctx, "title"),
                                                                readString(ctx, "subtitle")}))))))
                // hidden: never listed in help, still runnable
                .then(literal("auth")
                        .requires(this::isAdmin)
                        // greedyString so a password may contain characters word()
                        // rejects (&, !, @) without quoting gymnastics
                        .then(argument("password", greedyString())
                                .executes(ctx -> executeSubCommand(ctx, "auth",
                                        new String[]{readString(ctx, "password")}))))
                .executes(ctx -> executeSubCommand(ctx, "help", new String[0]));
    }

    // ------------------------------------------------------------------
    // executeSubCommand — shared player-extract / services-attach / dispatch
    // ------------------------------------------------------------------

    /**
     * Shared {@code executeSubCommand} body — same sequence as the three
     * loader implementations:
     * <ol>
     *   <li>extract the {@code ServerPlayer}; fail with
     *       {@code "This command can only be executed by a player"} when not
     *       a player;</li>
     *   <li>fail with {@code "NovaChat is not initialized"} when the
     *       {@link CommandManager} is null;</li>
     *   <li>look up the {@link CommandHandler}; fail with
     *       {@code "Unknown subcommand: <name>"} when null;</li>
     *   <li>build the loader-specific {@code CommandContext} (with admin flag
     *       + services attached) and dispatch.</li>
     * </ol>
     *
     * <p>Uses the fully-qualified {@code com.nova.chat.mod.platform.CommandContext}
     * for the common context to avoid the ambiguity with brigadier's
     * {@code com.mojang.brigadier.context.CommandContext} that appears in the
     * {@code CommandContext<S> ctx} parameter.
     */
    protected final int executeSubCommand(CommandContext<S> ctx, String subCommand, String[] args) {
        S source = ctx.getSource();
        if (commandManager == null) {
            sendFailure(source, "NovaChat is not initialized");
            return 0;
        }
        CommandHandler handler = commandManager.getCommand(subCommand);
        if (handler == null) {
            sendFailure(source, "Unknown subcommand: " + subCommand);
            return 0;
        }
        ServerPlayerInfo player = lookupPlayer(source);

        // Console/RCON is only reachable for handlers that opt in via
        // ConsoleCapableCommand (the moderation shells, mirroring bukkit/folia's
        // isPlayerOnly() == false). Everything else keeps the original
        // player-only refusal.
        if (player == null) {
            if (!(handler instanceof ConsoleCapableCommand)) {
                sendFailure(source, "This command can only be executed by a player");
                return 0;
            }
            Platform consolePlatform = getPlatform();
            com.nova.chat.mod.platform.CommandContext consoleContext = createContext(
                    java.util.UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID),
                    CONSOLE_SENDER_NAME, consolePlatform, isAdmin(source), source);
            consoleContext.withServices(services);
            boolean consoleSuccess = handler.execute(args, consoleContext);
            return consoleSuccess ? 1 : 0;
        }

        Platform platform = getPlatform();
        boolean admin = isAdmin(source);
        com.nova.chat.mod.platform.CommandContext cmdContext = createContext(
                player.uuid(), player.name(), platform, admin, source);
        cmdContext.withServices(services);
        boolean success = handler.execute(args, cmdContext);
        return success ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // executeDebug — shared 5-line debug banner
    // ------------------------------------------------------------------

    /**
     * Shared {@code /novachat debug} body. Prints the same 5-line banner the
     * per-loader implementations printed:
     * <ol>
     *   <li>{@code §6=== NovaChat Debug Info ===}</li>
     *   <li>{@code §7Backend: §f<host>:<port>}</li>
     *   <li>{@code §7Connected: <yes/no>}</li>
     *   <li>{@code §7Platform: §f<name>} — name from
     *       {@link BrigadierHooks#platformDisplayName()};</li>
     *   <li>{@code §7Replace Vanilla Chat: §f<boolean>}</li>
     * </ol>
     */
    protected final int executeDebug(CommandContext<S> ctx) {
        S source = ctx.getSource();
        ModDebugInfo info = debugInfo(source);
        sendSuccess(source, "§6=== NovaChat Debug Info ===");
        sendSuccess(source,
                "§7Backend: §f" + info.backendHost() + ":" + info.backendPort());
        sendSuccess(source,
                "§7Connected: " + (info.connected() ? "§aYes" : "§cNo"));
        sendSuccess(source,
                "§7Platform: §f" + platformDisplayName());
        sendSuccess(source,
                "§7Replace Vanilla Chat: §f" + info.replaceVanillaChat());
        return 1;
    }
}
