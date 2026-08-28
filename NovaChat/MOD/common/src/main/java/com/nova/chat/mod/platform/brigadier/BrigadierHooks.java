package com.nova.chat.mod.platform.brigadier;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.nova.chat.mod.platform.Platform;

/**
 * Loader-supplied bridges between the common
 * {@link AbstractBrigadierCommandRegistrar} and the Minecraft / platform APIs
 * the common layer must not import directly.
 *
 * <p>Each loader (fabric / neoforge / quilt) implements this against its own
 * typed {@code CommandSourceStack}. The common layer calls these methods with
 * the loader-typed source (captured as the generic {@code S}), so it never
 * names {@code net.minecraft.commands.CommandSourceStack},
 * {@code net.minecraft.commands.Commands},
 * {@code net.minecraft.network.chat.Component},
 * {@code net.minecraft.server.level.ServerPlayer},
 * {@code net.minecraft.server.permissions.PermissionSet} or any other Minecraft
 * type.
 *
 * @param <S> the brigadier source type — on all three loaders
 *            {@code net.minecraft.commands.CommandSourceStack}
 */
public interface BrigadierHooks<S> {

    // --- brigadier tree construction (Minecraft Commands API) ---

    /**
     * Creates a {@code Commands.literal(name)} builder for the loader. The
     * common layer uses this to build the {@code novachat}/{@code nc} subtrees
     * without naming {@code net.minecraft.commands.Commands}.
     */
    LiteralArgumentBuilder<S> literal(String name);

    /**
     * Creates a {@code Commands.argument(name, type)} builder for the loader.
     * The common layer uses this for the {@code channel}/{@code player}/
     * {@code message}/{@code target} arguments.
     */
    RequiredArgumentBuilder<S, String> argument(String name, ArgumentType<String> type);

    /**
     * @return the brigadier {@code StringArgumentType.word()} argument type
     */
    ArgumentType<String> word();

    /**
     * @return the brigadier {@code StringArgumentType.greedyString()} argument
     */
    ArgumentType<String> greedyString();

    /**
     * Reads a {@code StringArgumentType.getString(ctx, name)} from a brigadier
     * context. The common layer uses this to pull the {@code channel}/
     * {@code player}/{@code message}/{@code target} values out of the context.
     */
    String readString(CommandContext<S> ctx, String name) throws CommandSyntaxException;

    // --- player lookup + context construction (Minecraft ServerPlayer) ---

    /**
     * Extracts the current {@code ServerPlayer}'s UUID + name from the source,
     * or null when the source is not a player (console / command block / rcon).
     * Mirrors the original
     * {@code source.getEntity() instanceof ServerPlayer player ? ... : null}.
     */
    ServerPlayerInfo lookupPlayer(S source);

    /**
     * @return the loader's {@link Platform} instance (typed by the shim —
     *         e.g. {@code FabricPlatform}) for the current mod entry point
     */
    Platform getPlatform();

    /**
     * Creates a loader-specific {@link com.nova.chat.mod.platform.CommandContext}
     * from the current execution. The common layer calls this with the
     * already-resolved player identity + admin flag + the opaque source.
     *
     * @param playerId   the player UUID (never null — caller has already
     *                   verified the source is backed by a ServerPlayer)
     * @param playerName the player name
     * @param platform   the loader {@link Platform} (typed by the shim)
     * @param isAdmin    the admin flag (already resolved by
     *                   {@link #isAdmin})
     * @param source     the opaque brigadier source (loader-typed
     *                   {@code CommandSourceStack}); never null
     * @return a new loader-specific {@code CommandContext} with the source
     *         attached; the caller then calls
     *         {@code cmdContext.withServices(services)}
     */
    com.nova.chat.mod.platform.CommandContext createContext(
            java.util.UUID playerId, String playerName,
            Platform platform, boolean isAdmin, S source);

    // --- admin check (Minecraft PermissionSet) ---

    /**
     * MC 1.21.11 {@code PermissionSet.hasPermission(new
     * Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))} check.
     * Each loader implements this against its own typed source.
     */
    boolean isAdmin(S source);

    // --- message sends (Minecraft Component) ---

    /**
     * Sends a success line to the source — wraps the string in
     * {@code Component.literal(...)} and calls {@code source.sendSuccess(...)}.
     * The string already carries {@code §}-style color codes.
     */
    void sendSuccess(S source, String message);

    /**
     * Sends a failure line to the source — wraps the string in
     * {@code Component.literal(...)} and calls {@code source.sendFailure(...)}.
     */
    void sendFailure(S source, String message);

    // --- debug info ---

    /**
     * Gathers the backend host/port, connection status and replaceVanilla flag
     * from the loader's mod entry point for {@code /novachat debug}.
     */
    ModDebugInfo debugInfo(S source);

    /**
     * @return the platform display name printed in the {@code /novachat debug}
     *         banner — {@code "Fabric"}, {@code "NeoForge"} or {@code "Quilt"}
     */
    String platformDisplayName();
}
