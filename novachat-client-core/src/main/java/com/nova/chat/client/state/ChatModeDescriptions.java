package com.nova.chat.client.state;

import com.nova.chat.client.i18n.I18n;

import java.util.UUID;

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
 * <p>All copy is resolved through {@link I18n} (keys {@code chat.mode.*}) so
 * the mode name / description follow the configured default locale. The
 * {@code public static final String} constants are kept for backward
 * compatibility and initialized from the default locale at class-load; the
 * {@link #modeName(ChatMode)} / {@link #describe(ChatMode)} methods re-resolve
 * via {@link I18n} so a locale change after startup is honored.
 *
 * <p>Requirements: 11.1, 11.2 (ChatMode visibility alignment, UX design §3)
 */
public final class ChatModeDescriptions {

    /**
     * Explanation line shown after toggling to {@link ChatMode#HYBRID}.
     * Initialized from the default locale at class-load.
     */
    public static final String HYBRID_DESCRIPTION =
            I18n.tr("chat.mode.hybrid.describe");

    /**
     * Explanation line shown after toggling to {@link ChatMode#REPLACE}.
     * Initialized from the default locale at class-load.
     */
    public static final String REPLACE_DESCRIPTION =
            I18n.tr("chat.mode.replace.describe");

    /**
     * Short display name for {@link ChatMode#HYBRID} (action bar / toggle labels).
     * Initialized from the default locale at class-load.
     */
    public static final String HYBRID_MODE_NAME =
            I18n.tr("chat.mode.hybrid.name");

    /**
     * Short display name for {@link ChatMode#REPLACE} (action bar / toggle labels).
     *
     * <p>Aligned wording: historically some platforms called this "替换模式";
     * shared copy uses "频道模式" (zh_CN) / "Channel mode" (en_US)
     * (UX-DESIGN-2 §12).
     */
    public static final String REPLACE_MODE_NAME =
            I18n.tr("chat.mode.replace.name");

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
        return I18n.tr("chat.mode." + mode.name().toLowerCase(java.util.Locale.ROOT) + ".describe");
    }

    /**
     * Player-locale variant of {@link #describe(ChatMode)} — resolves the
     * description in the player's registered client locale (falling back to
     * the default locale when no per-player locale is registered).
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @param mode     the chat mode; never null
     * @return the description line in the player's locale
     * @throws IllegalArgumentException if {@code mode} is null
     */
    public static String describe(UUID playerId, ChatMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        return I18n.tr(playerId, "chat.mode." + mode.name().toLowerCase(java.util.Locale.ROOT) + ".describe");
    }

    /**
     * Returns the short display name for the given mode
     * (e.g. action-bar labels via {@code PlayerMessages.currentChannelBar}).
     *
     * @param mode the chat mode; never null
     * @return the localized mode name
     * @throws IllegalArgumentException if {@code mode} is null
     */
    public static String modeName(ChatMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        return I18n.tr("chat.mode." + mode.name().toLowerCase(java.util.Locale.ROOT) + ".name");
    }

    /**
     * Player-locale variant of {@link #modeName(ChatMode)} — resolves the
     * short display name in the player's registered client locale (falling
     * back to the default locale when no per-player locale is registered).
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @param mode     the chat mode; never null
     * @return the localized mode name in the player's locale
     * @throws IllegalArgumentException if {@code mode} is null
     */
    public static String modeName(UUID playerId, ChatMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode must not be null");
        }
        return I18n.tr(playerId, "chat.mode." + mode.name().toLowerCase(java.util.Locale.ROOT) + ".name");
    }
}
