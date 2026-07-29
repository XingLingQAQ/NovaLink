package com.nova.chat.client.state;

/**
 * Defines how a platform plugin handles vanilla chat messages.
 *
 * <p>Shared across Bukkit / Velocity / Nukkit / etc. so per-platform copies
 * can be retired during migration.
 *
 * <p>Requirements: 11.1, 11.2
 */
public enum ChatMode {

    /**
     * Vanilla chat is preserved; only command messages go to channels.
     * When {@code replace_vanilla} is false, players can use both vanilla chat
     * and {@code /nc} commands.
     */
    HYBRID,

    /**
     * All chat messages are intercepted and sent to the current channel.
     * When {@code replace_vanilla} is true, vanilla chat is completely replaced.
     */
    REPLACE;

    /**
     * Returns the opposite mode (HYBRID ↔ REPLACE).
     */
    public ChatMode toggled() {
        return this == HYBRID ? REPLACE : HYBRID;
    }

    /**
     * Parses a chat mode from config strings such as {@code "hybrid"},
     * {@code "REPLACE"}, or {@code "replace_vanilla"}.
     *
     * @param raw config value; null or blank yields {@link #HYBRID}
     * @return matching mode
     * @throws IllegalArgumentException if the value is non-blank but unrecognized
     */
    public static ChatMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return HYBRID;
        }
        String normalized = raw.trim().toUpperCase().replace('-', '_');
        return switch (normalized) {
            case "HYBRID", "VANILLA", "FALSE", "0", "NO", "OFF" -> HYBRID;
            case "REPLACE", "REPLACE_VANILLA", "TRUE", "1", "YES", "ON" -> REPLACE;
            default -> {
                try {
                    yield ChatMode.valueOf(normalized);
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(
                            "Unknown chat mode: '" + raw + "' (expected HYBRID or REPLACE)", ex);
                }
            }
        };
    }
}
