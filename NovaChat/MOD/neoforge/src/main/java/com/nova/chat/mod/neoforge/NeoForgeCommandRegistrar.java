package com.nova.chat.mod.neoforge;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.ModServices;
import com.nova.chat.mod.platform.Platform;
import com.nova.chat.mod.platform.brigadier.AbstractBrigadierCommandRegistrar;
import com.nova.chat.mod.platform.brigadier.BrigadierHooks;
import com.nova.chat.mod.platform.brigadier.ModDebugInfo;
import com.nova.chat.mod.platform.brigadier.ServerPlayerInfo;
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

import java.util.UUID;

/**
 * NeoForge shim for the shared {@link AbstractBrigadierCommandRegistrar}.
 *
 * <p>Extends the common registrar and overrides the 13
 * {@link BrigadierHooks} methods against NeoForge's typed
 * {@link CommandSourceStack}, and subscribes a {@link CommandEventHandler} to
 * the NeoForge event bus so the shared registrar's
 * {@code novachat}/{@code nc} tree is built when
 * {@link RegisterCommandsEvent} fires. All 11 handler registrations, the
 * brigadier subtree shape, {@code executeSubCommand}, {@code isAdmin} and the
 * {@code debug} banner live in the common layer; this class is just the
 * platform glue.
 */
public class NeoForgeCommandRegistrar
        extends AbstractBrigadierCommandRegistrar<CommandSourceStack> {

    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgeCommandRegistrar.class);

    private static NeoForgeCommandRegistrar instance;

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
        if (instance == null) {
            instance = new NeoForgeCommandRegistrar();
        }
        instance.registerSharedStateAndHandlers(manager, services);

        NeoForge.EVENT_BUS.register(new CommandEventHandler());
    }

    public static class CommandEventHandler {
        @SubscribeEvent
        public void onRegisterCommands(RegisterCommandsEvent event) {
            instance.registerNovaChatCommands(event.getDispatcher());
            LOGGER.info("NovaChat commands registered");
        }
    }

    // ---------------------- BrigadierHooks implementation ----------------------

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return Commands.literal(name);
    }

    @Override
    public RequiredArgumentBuilder<CommandSourceStack, String> argument(
            String name, ArgumentType<String> type) {
        return Commands.argument(name, type);
    }

    @Override
    public ArgumentType<String> word() {
        return StringArgumentType.word();
    }

    @Override
    public ArgumentType<String> greedyString() {
        return StringArgumentType.greedyString();
    }

    @Override
    public String readString(CommandContext<CommandSourceStack> ctx, String name)
            throws CommandSyntaxException {
        return StringArgumentType.getString(ctx, name);
    }

    @Override
    public ServerPlayerInfo lookupPlayer(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return new ServerPlayerInfo(player.getUUID(), player.getName().getString());
        }
        return null;
    }

    @Override
    public Platform getPlatform() {
        return NovaChatNeoForge.getInstance().getPlatform();
    }

    @Override
    public com.nova.chat.mod.platform.CommandContext createContext(
            UUID playerId, String playerName, Platform platform,
            boolean isAdmin, CommandSourceStack source) {
        return new NeoForgeCommandContext(
                playerId, playerName, platform, isAdmin, source);
    }

    /**
     * MC 1.21.11 replaced {@code CommandSourceStack.hasPermission(int)} with a
     * {@link PermissionSet}-based model. Op level 2 maps to
     * {@link PermissionLevel#GAMEMASTERS}; {@code hasPermission(HasCommandLevel)}
     * checks "equal or higher", matching the legacy {@code hasPermission(2)}.
     */
    @Override
    public boolean isAdmin(CommandSourceStack source) {
        PermissionSet perms = source.permissions();
        return perms != null && perms.hasPermission(
                new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
    }

    @Override
    public void sendSuccess(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    @Override
    public void sendFailure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
    }

    @Override
    public ModDebugInfo debugInfo(CommandSourceStack source) {
        NovaChatNeoForge inst = NovaChatNeoForge.getInstance();
        boolean connected = inst.getNetworkClient() != null
                && inst.getNetworkClient().isConnected()
                && inst.getNetworkClient().isAuthenticated();
        return new ModDebugInfo(
                inst.getConfig().getBackend().getHost(),
                inst.getConfig().getBackend().getPort(),
                connected,
                inst.getConfig().getChat().isReplaceVanilla());
    }

    @Override
    public String platformDisplayName() {
        return "NeoForge";
    }
}
