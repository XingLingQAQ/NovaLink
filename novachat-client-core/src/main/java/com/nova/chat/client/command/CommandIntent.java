package com.nova.chat.client.command;

/**
 * High-level channel / chat command intents shared across platforms.
 *
 * <p>Platform command handlers map player input onto these intents and
 * delegate to {@link ChannelCommandService}. Keep this set minimal; add
 * values only when multiple platforms need the same intent.
 *
 * <p>Not wired into Bukkit/Velocity (or other) command trees yet — skeleton only.
 */
public enum CommandIntent {

    /** Join a channel (may include optional password). */
    JOIN,

    /** Leave a channel. */
    LEAVE,

    /** Toggle local chat mode (HYBRID ↔ REPLACE). Local-only; no backend packet. */
    TOGGLE,

    /**
     * Reload plugin config / reconnect budget.
     *
     * <p>{@link ChannelCommandService#reload()} does <strong>not</strong> send a
     * network packet and does not mutate channel state. Platforms that expose
     * {@code /nc reload} (or equivalent) should handle the actual reload after
     * receiving a successful {@link CommandResult} with this intent.
     */
    RELOAD
}
