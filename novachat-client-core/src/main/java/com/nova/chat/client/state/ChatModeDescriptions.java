package com.nova.chat.client.state;

/**
 * Shared, platform-agnostic descriptions of what each {@link ChatMode} means
 * to a player.
 *
 * <p>Architecture B: kept in {@code novachat-client-core} so every platform
 * plugin (bukkit / nukkit / folia / velocity / bungee / sponge) renders the
 * same HYBRID / REPLACE explanation after a {@code /nc toggle}, instead of
 * each platform inventing its own wording (or just printing the mode name).
 *
 * <p>Platforms call {@link #describe(ChatMode)} and append the returned line
 * to their toggle success message. The strings are plain text (no color
 * codes); platforms apply their own coloring around the mode name.
 *
 * <p>Requirements: 11.1, 11.2 (ChatMode visibility alignment, UX design §3)
 */
public final class ChatModeDescriptions {

    /**
     * Explanation line shown after toggling to {@link ChatMode#HYBRID}.
     */
    public static final String HYBRID_DESCRIPTION =
            "原版聊天保留，/nc <频道> <消息> 发频道消息";

    /**
     * Explanation line shown after toggling to {@link ChatMode#REPLACE}.
     */
    public static final String REPLACE_DESCRIPTION =
            "所有聊天消息将发送到当前频道";

    private ChatModeDescriptions() {
        // Utility class — no instances.
    }

    /**
     * Returns the player-facing behavior description for the given mode.
     *
     * @param mode the chat mode; never null
     * @return the description line (plain text, no color codes)
     * @throws IllegalArgumentException if {@code mode} is null
     */
    public static String describe(ChatMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        return switch (mode) {
            case HYBRID -> HYBRID_DESCRIPTION;
            case REPLACE -> REPLACE_DESCRIPTION;
        };
    }
}
