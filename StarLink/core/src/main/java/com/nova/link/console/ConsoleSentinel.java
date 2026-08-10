package com.nova.link.console;

import java.util.UUID;

/**
 * Sentinel identity for backend console commands.
 *
 * <p>The console has no player UUID, so all console-originated moderation/admin
 * actions use {@link #CONSOLE_SENTINEL} ({@code 00000000-0000-0000-0000-000000000000}).
 * Existing business logic already bypasses permission checks for this UUID:
 * <ul>
 *   <li>{@code MuteManager.mutePlayer/unmutePlayer} treat the all-zero UUID as
 *       {@code SUPER_ADMIN}.</li>
 *   <li>{@code ChannelActionHandler.requireModerationPermission} returns null
 *       (allowed) for the all-zero operator.</li>
 * </ul>
 * Spy mode additionally requires a super-admin session (see
 * {@code SpyManager.startSpying}), so the console layer registers the sentinel
 * as a super-admin session via {@code PermissionManager} at startup.
 */
public final class ConsoleSentinel {

    /** The console sentinel UUID: 00000000-0000-0000-0000-000000000000. */
    public static final UUID CONSOLE_SENTINEL = new UUID(0L, 0L);

    /** Display name used for console-originated routed messages (e.g. announce). */
    public static final String CONSOLE_NAME = "Console";

    private ConsoleSentinel() {
        // utility class
    }

    /**
     * @return true if the given UUID is the console sentinel.
     */
    public static boolean isConsole(UUID id) {
        return id != null && id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L;
    }
}
