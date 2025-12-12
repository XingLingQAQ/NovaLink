package com.nova.chat.mod.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.nova.chat.mod.command.HelpCommand;
import com.nova.chat.mod.command.JoinCommand;
import com.nova.chat.mod.command.LeaveCommand;
import com.nova.chat.mod.command.ToggleCommand;
import com.nova.chat.mod.platform.CommandHandler;
import com.nova.chat.mod.platform.CommandManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers NovaChat commands using Fabric's CommandRegistrationCallback
 * 
 * Requirements: 7.1-7.4
 * - Registers /novachat and /nc commands
 * - Supports all standard subcommands (help, join, leave, toggle)
 * - Admin commands (reload, debug) require operator permission
 * - Provides Tab completion functionality
 */
public class FabricCommandRegistrar {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricCommandRegistrar.class);
    
    private static CommandManager commandManager;
    
    /**
     * Register all NovaChat commands
     * @param manager the command manager
     */
    public static void registerCommands(CommandManager manager) {
        commandManager = manager;
        
        // Register command handlers in the command manager
        manager.registerCommand("help", new HelpCommand(manager));
        manager.registerCommand("join", new JoinCommand());
        manager.registerCommand("leave", new LeaveCommand());
        manager.registerCommand("toggle", new ToggleCommand());
        
        // Register with Fabric's command system
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerNovaChatCommand(dispatcher);
            LOGGER.info("NovaChat commands registered");
        });
    }

    
    /**
     * Register the /novachat and /nc commands
     * @param dispatcher the command dispatcher
     */
    private static void registerNovaChatCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Register /novachat command
        var novachatCommand = Commands.literal("novachat")
            .then(Commands.literal("help")
                .executes(ctx -> executeSubCommand(ctx, "help", new String[0])))
            .then(Commands.literal("join")
                .then(Commands.argument("channel", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        // TODO: Add channel suggestions from backend
                        builder.suggest("global");
                        builder.suggest("local");
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        String channel = StringArgumentType.getString(ctx, "channel");
                        return executeSubCommand(ctx, "join", new String[]{channel});
                    })))
            .then(Commands.literal("leave")
                .executes(ctx -> executeSubCommand(ctx, "leave", new String[0])))
            .then(Commands.literal("toggle")
                .executes(ctx -> executeSubCommand(ctx, "toggle", new String[0])))
            .then(Commands.literal("reload")
                .requires(source -> source.hasPermission(2)) // Requires operator level 2
                .executes(ctx -> executeReload(ctx)))
            .then(Commands.literal("debug")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> executeDebug(ctx)))
            .executes(ctx -> executeSubCommand(ctx, "help", new String[0]));
        
        dispatcher.register(novachatCommand);
        
        // Register /nc alias
        var ncCommand = Commands.literal("nc")
            .then(Commands.literal("help")
                .executes(ctx -> executeSubCommand(ctx, "help", new String[0])))
            .then(Commands.literal("join")
                .then(Commands.argument("channel", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        builder.suggest("global");
                        builder.suggest("local");
                        return builder.buildFuture();
                    })
                    .executes(ctx -> {
                        String channel = StringArgumentType.getString(ctx, "channel");
                        return executeSubCommand(ctx, "join", new String[]{channel});
                    })))
            .then(Commands.literal("leave")
                .executes(ctx -> executeSubCommand(ctx, "leave", new String[0])))
            .then(Commands.literal("toggle")
                .executes(ctx -> executeSubCommand(ctx, "toggle", new String[0])))
            .then(Commands.literal("reload")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> executeReload(ctx)))
            .then(Commands.literal("debug")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> executeDebug(ctx)))
            .executes(ctx -> executeSubCommand(ctx, "help", new String[0]));
        
        dispatcher.register(ncCommand);
    }

    
    /**
     * Execute a subcommand
     * @param ctx the command context
     * @param subCommand the subcommand name
     * @param args the command arguments
     * @return 1 if successful, 0 otherwise
     */
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
        
        // Create command context
        FabricPlatform platform = NovaChatFabric.getInstance().getPlatform();
        boolean isAdmin = source.hasPermission(2);
        com.nova.chat.mod.platform.CommandContext cmdContext = 
            new FabricCommandContext(player.getUUID(), player.getName().getString(), platform, isAdmin, source);
        
        boolean success = handler.execute(args, cmdContext);
        return success ? 1 : 0;
    }
    
    /**
     * Execute the reload command
     * @param ctx the command context
     * @return 1 if successful
     */
    private static int executeReload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        
        NovaChatFabric.getInstance().reloadConfig();
        source.sendSuccess(() -> Component.literal("§aNovaChat configuration reloaded"), true);
        
        return 1;
    }
    
    /**
     * Execute the debug command
     * @param ctx the command context
     * @return 1 if successful
     */
    private static int executeDebug(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        
        NovaChatFabric instance = NovaChatFabric.getInstance();
        boolean connected = instance.getNetworkClient().isConnected();
        String host = instance.getConfig().getBackend().getHost();
        int port = instance.getConfig().getBackend().getPort();
        
        source.sendSuccess(() -> Component.literal("§6=== NovaChat Debug Info ==="), false);
        source.sendSuccess(() -> Component.literal("§7Backend: §f" + host + ":" + port), false);
        source.sendSuccess(() -> Component.literal("§7Connected: " + (connected ? "§aYes" : "§cNo")), false);
        source.sendSuccess(() -> Component.literal("§7Platform: §fFabric"), false);
        source.sendSuccess(() -> Component.literal("§7Replace Vanilla Chat: §f" + instance.getConfig().getChat().isReplaceVanilla()), false);
        
        return 1;
    }
}
