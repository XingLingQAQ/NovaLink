package com.nova.chat.client.format;

/**
 * Deterministic channel-name → color resolver for NovaChat visual channel disambiguation.
 *
 * <p>Maps a channel name to a fixed color from a curated palette using a stable hash
 * ({@code channelName.hashCode()}). The same channel name always resolves to the same
 * color across restarts and platforms, so players can visually distinguish channels at
 * a glance without per-channel configuration.
 *
 * <p>The palette deliberately avoids low-contrast codes (gray {@code &7}, white {@code &f},
 * black {@code &0}, dark gray {@code &8}) and reset/formatting codes, favoring the
 * brighter, distinguishable hues.
 *
 * <ul>
 *   <li>{@code null} or empty channel name → {@code &7} (gray, the neutral default)</li>
 *   <li>any non-empty name → one of the palette entries, deterministically</li>
 * </ul>
 *
 * <p>This class has no dependency on Minecraft, Adventure, or platform APIs.
 */
public final class ChannelColorResolver {

    /**
     * Curated palette of legacy color codes used for channel disambiguation.
     * Ordered so adjacent hash buckets get visually distinct hues.
     */
    private static final String[] PALETTE = {
            "&a", // green
            "&b", // aqua
            "&c", // red
            "&d", // light purple
            "&e", // yellow
            "&6", // gold
            "&5", // dark purple
            "&9", // blue
            "&3", // dark aqua
            "&2"  // dark green
    };

    /** Fallback color for null/empty channel names (neutral gray). */
    public static final String DEFAULT_COLOR = "&7";

    private ChannelColorResolver() {
    }

    /**
     * Resolves a stable legacy color code for the given channel name.
     *
     * @param channelName the channel name (id or display name); may be null or empty
     * @return a legacy color code such as {@code "&a"}; {@link #DEFAULT_COLOR} for
     *         null/empty input
     */
    public static String resolveColor(String channelName) {
        if (channelName == null || channelName.isEmpty()) {
            return DEFAULT_COLOR;
        }
        int hash = channelName.hashCode();
        int index = Math.floorMod(hash, PALETTE.length);
        return PALETTE[index];
    }

    /**
     * Returns the number of entries in the channel color palette.
     *
     * @return palette size
     */
    public static int paletteSize() {
        return PALETTE.length;
    }
}
