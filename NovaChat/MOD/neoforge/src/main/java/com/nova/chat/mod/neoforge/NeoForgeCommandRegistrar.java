package com.nova.chat.mod.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.nova.chat.mod.command.HelpCommand;
import com.nova.chat.mod.command.IgnoreCommand;
import com.nova.chat.mod.command.JoinCommand;
import com.nova.chat.mod.command.LeaveCommand;
import com.nova.chat.mod.command.ListCommand;
import com.nova.chat.mod.command.MsgCommand;
import com.nova.chat.mod.command.ReloadCommand;
import com.nova.chat.mod.command.ReplyCommand;
import com.nova.chat.mod.command.ToggleCommand;
import com.nova.chat.mod.command.UnignoreCommand;
import com.nova.chat.mod.command.WhoCommand;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.ModServices;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers NovaChat commands using NeoForge's RegisterCommandsEvent.
 *
 * <p>Registers all eleven subcommands (help/join/leave/list/who/toggle/
 * ignore/unignore/msg/r/reload) through the shared {@link CommandManager} and
 * attaches {@link ModServices} to each command context.
 */
public class NeoForgeCommandRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgeCommandRegistrar.class);

    private static CommandManager commandManager;
    private static ModServices services;

    /**
     * Registers all eleven NovaChat subcommands (help/join/leave/list/who/toggle/
     * ignore/unignore/msg/r/reload) with the shared {@link CommandManager} and
     * subscribes a {@link CommandEventHandler} to the NeoForge event bus so
     * the brigadier {@code novachat}/{@code nc} trees are built when
     * {@link RegisterCommandsEvent} fires.
     *
     * @param manager  the shared command manager to register handlers into
     * @param services the shared mod services attached to each command context
     */
    public static void registerCommands(CommandManager manager, ModServices services) {
        NeoForgeCommandRegistrar.commandManager = manager;
        NeoForgeCommandRegistrar.services = services;

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

        NeoForge.EVENT_BUS.register(new CommandEventHandler());
    }

    private static class CommandEventHandler {
        @SubscribeEvent
        public void onRegisterCommands(RegisterCommandsEvent event) {
            registerNovaChatCommand(event.getDispatcher());
            LOGGER.info("NovaChat commands registered");
        }
    }

    private static void registerNovaChatCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        var novachatCommand = Commands.literal("novachat")
            .then(Commands.literal("help")
                .executes(ctx -> executeSubCommand(ctx, "help", new String[0])))
            .then(Commands.literal("join")
                .then(Commands.argument("channel", StringArgumentType.word())
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
                        String channel = StringArgumentType.getString(ctx, "channel");
                        return executeSubCommand(ctx, "join", new String[]{channel});
                    })))
            .then(Commands.literal("leave")
                .executes(ctx -> executeSubCommand(ctx, "leave", new String[0])))
            .then(Commands.literal("list")
                .executes(ctx -> executeSubCommand(ctx, "list", new String[0])))
            .then(Commands.literal("who")
                .executes(ctx -> executeSubCommand(ctx, "who", new String[0])))
            .then(Commands.literal("toggle")
                .executes(ctx -> executeSubCommand(ctx, "toggle", new String[0])))
            .then(Commands.literal("msg")
                .then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String p = StringArgumentType.getString(ctx, "player");
                            String m = StringArgumentType.getString(ctx, "message");
                            return executeSubCommand(ctx, "msg", new String[]{p, m});
                        }))))
            .then(Commands.literal("r")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String m = StringArgumentType.getString(ctx, "message");
                        return executeSubCommand(ctx, "r", new String[]{m});
                    })))
            .then(Commands.literal("ignore")
                .executes(ctx -> executeSubCommand(ctx, "ignore", new String[0]))
                .then(Commands.argument("target", StringArgumentType.word())
                    .executes(ctx -> {
                        String t = StringArgumentType.getString(ctx, "target");
                        return executeSubCommand(ctx, "ignore", new String[]{t});
                    })))
            .then(Commands.literal("unignore")
                .then(Commands.argument("target", StringArgumentType.word())
                    .executes(ctx -> {
                        String t = StringArgumentType.getString(ctx, "target");
                        return executeSubCommand(ctx, "unignore", new String[]{t});
                    })))
            .then(Commands.literal("reload")
                .requires(source -> isAdmin(source))
                .executes(ctx -> executeSubCommand(ctx, "reload", new String[0])))
            .then(Commands.literal("debug")
                .requires(source -> isAdmin(source))
                .executes(ctx -> executeDebug(ctx)))
            .executes(ctx -> executeSubCommand(ctx, "help", new String[0]));

        dispatcher.register(novachatCommand);

        var ncCommand = Commands.literal("nc")
            .then(Commands.literal("help")
                .executes(ctx -> executeSubCommand(ctx, "help", new String[0])))
            .then(Commands.literal("join")
                .then(Commands.argument("channel", StringArgumentType.word())
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
                        String channel = StringArgumentType.getString(ctx, "channel");
                        return executeSubCommand(ctx, "join", new String[]{channel});
                    })))
            .then(Commands.literal("leave")
                .executes(ctx -> executeSubCommand(ctx, "leave", new String[0])))
            .then(Commands.literal("list")
                .executes(ctx -> executeSubCommand(ctx, "list", new String[0])))
            .then(Commands.literal("who")
                .executes(ctx -> executeSubCommand(ctx, "who", new String[0])))
            .then(Commands.literal("toggle")
                .executes(ctx -> executeSubCommand(ctx, "toggle", new String[0])))
            .then(Commands.literal("msg")
                .then(Commands.argument("player", StringArgumentType.word())
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String p = StringArgumentType.getString(ctx, "player");
                            String m = StringArgumentType.getString(ctx, "message");
                            return executeSubCommand(ctx, "msg", new String[]{p, m});
                        }))))
            .then(Commands.literal("r")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        String m = StringArgumentType.getString(ctx, "message");
                        return executeSubCommand(ctx, "r", new String[]{m});
                    })))
            .then(Commands.literal("ignore")
                .executes(ctx -> executeSubCommand(ctx, "ignore", new String[0]))
                .then(Commands.argument("target", StringArgumentType.word())
                    .executes(ctx -> {
                        String t = StringArgumentType.getString(ctx, "target");
                        return executeSubCommand(ctx, "ignore", new String[]{t});
                    })))
            .then(Commands.literal("unignore")
                .then(Commands.argument("target", StringArgumentType.word())
                    .executes(ctx -> {
                        String t = StringArgumentType.getString(ctx, "target");
                        return executeSubCommand(ctx, "unignore", new String[]{t});
                    })))
            .then(Commands.literal("reload")
                .requires(source -> isAdmin(source))
                .executes(ctx -> executeSubCommand(ctx, "reload", new String[0])))
            .then(Commands.literal("debug")
                .requires(source -> isAdmin(source))
                .executes(ctx -> executeDebug(ctx)))
            .executes(ctx -> executeSubCommand(ctx, "help", new String[0]));

        dispatcher.register(ncCommand);
    }

    private static int executeSubCommand(CommandContext<CommandSourceStack> ctx, String subCommand, String[] args) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command can only be executed by a player"));
            return 0;
        }

        if (commandManager == null) {
            source.sendFailure(Component.literal("NovaChat is not initialized"));
            return 0;
        }

        CommandHandler handler = commandManager.getCommand(subCommand);
        if (handler == null) {
            source.sendFailure(Component.literal("Unknown subcommand: " + subCommand));
            return 0;
        }

        NeoForgePlatform platform = NovaChatNeoForge.getInstance().getPlatform();
        boolean isAdmin = isAdmin(source);
        NeoForgeCommandContext cmdContext = new NeoForgeCommandContext(
                player.getUUID(), player.getName().getString(), platform, isAdmin, source);
        cmdContext.withServices(services);

        boolean success = handler.execute(args, cmdContext);
        return success ? 1 : 0;
    }

    /**
     * MC 1.21.11 replaced {@code CommandSourceStack.hasPermission(int)} with a
     * {@link PermissionSet}-based model. Op level 2 maps to
     * {@link PermissionLevel#GAMEMASTERS}; {@code hasPermission(HasCommandLevel)}
     * checks "equal or higher", matching the legacy {@code hasPermission(2)}.
     */
    private static boolean isAdmin(CommandSourceStack source) {
        PermissionSet perms = source.permissions();
        return perms != null && perms.hasPermission(
                new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
    }

    private static int executeDebug(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        NovaChatNeoForge instance = NovaChatNeoForge.getInstance();
        boolean connected = instance.getNetworkClient() != null
                && instance.getNetworkClient().isConnected()
                && instance.getNetworkClient().isAuthenticated();
        String host = instance.getConfig().getBackend().getHost();
        int port = instance.getConfig().getBackend().getPort();

        source.sendSuccess(() -> Component.literal("§6=== NovaChat Debug Info ==="), false);
        source.sendSuccess(() -> Component.literal("§7Backend: §f" + host + ":" + port), false);
        source.sendSuccess(() -> Component.literal("§7Connected: " + (connected ? "§aYes" : "§cNo")), false);
        source.sendSuccess(() -> Component.literal("§7Platform: §fNeoForge"), false);
        source.sendSuccess(() -> Component.literal("§7Replace Vanilla Chat: §f" + instance.getConfig().getChat().isReplaceVanilla()), false);

        return 1;
    }
}
