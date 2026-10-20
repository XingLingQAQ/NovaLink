package com.nova.chat.mod.platform;

/**
 * Marker for a command that may be run from the server console / RCON as well
 * as by a player.
 *
 * <p>The shared registrar's default dispatch path requires a
 * {@code ServerPlayer}: the 12 pre-existing mod commands are player-only and
 * answer {@code "This command can only be executed by a player"} otherwise.
 * That behaviour is unchanged. A handler implementing this marker is instead
 * dispatched with the all-zero console sentinel identity
 * ({@link com.nova.chat.common.NovaConstants#CONSOLE_SENTINEL_UUID}) and the
 * {@code "Console"} display name, matching the bukkit/folia shells whose
 * moderation commands report {@code isPlayerOnly() == false}.
 *
 * <p>Handlers that implement this marker must therefore tolerate a console
 * sender: no active channel, and a sentinel UUID rather than a player.
 */
public interface ConsoleCapableCommand extends CommandHandler {
}
