package com.nova.chat.client.command;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.ChatModeDescriptions;

import java.util.UUID;

/**
 * Shared, platform-agnostic player-facing message templates for high-frequency
 * join / leave / status / chat-toggle copy (UX-DESIGN-2 §13).
 *
 * <p>Architecture B: pure static helpers in {@code novachat-client-core}. Platforms
 * replace hard-coded literals with these methods so wording stays aligned across
 * bukkit / folia / velocity / bungee / sponge / nukkit / pnx.
 *
 * <p>All copy is resolved through {@link I18n} so a player sees messages in
 * their own Minecraft client locale (zh_CN default, en_US secondary). Color
 * codes use the shared {@code &} form and stay inside the i18n property
 * values; platforms colorize / convert to {@code §} via their own helper
 * (or {@code LegacyColorCodes.ampersandToSection}) before sending.
 *
 * <p>The {@code {0}} / {@code {1}} placeholders in the bundle values are
 * filled by {@link java.text.MessageFormat}.
 *
 * <p>Config-driven platforms may treat these as defaults and still allow config
 * overrides for prefixes / entire strings.
 */
public final class PlayerMessages {

    private PlayerMessages() {
        // Utility class — no instances.
    }

    /**
     * Immediate ack shown when a join is requested (before backend confirmation).
     *
     * @param channel the channel id being joined; never null
     * @return colored message, e.g. {@code 正在加入频道 &eglobal&7...} (zh_CN) /
     *         {@code Joining channel &eglobal&7...} (en_US)
     */
    public static String joining(String channel) {
        requireChannel(channel);
        return I18n.tr("chat.join.joining", channel);
    }

    /**
     * Player-locale variant of {@link #joining(String)} — resolves the message
     * in the player's registered client locale (falling back to the default
     * locale when no per-player locale is registered).
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @param channel  the channel id being joined; never null
     * @return colored message in the player's locale
     */
    public static String joining(UUID playerId, String channel) {
        requireChannel(channel);
        return I18n.tr(playerId, "chat.join.joining", channel);
    }

    /**
     * Confirmation shown after the backend accepts a JOIN.
     *
     * @param channel the channel id that was joined; never null
     * @return colored message, e.g. {@code 已加入频道 &eglobal} (zh_CN) /
     *         {@code Joined channel &eglobal} (en_US)
     */
    public static String joined(String channel) {
        requireChannel(channel);
        return I18n.tr("chat.join.joined", channel);
    }

    /**
     * Player-locale variant of {@link #joined(String)}.
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @param channel  the channel id that was joined; never null
     * @return colored message in the player's locale
     */
    public static String joined(UUID playerId, String channel) {
        requireChannel(channel);
        return I18n.tr(playerId, "chat.join.joined", channel);
    }

    /**
     * Immediate ack shown when a leave is requested (before backend confirmation).
     *
     * @param channel the channel id being left; never null
     * @return colored message, e.g. {@code 正在离开频道 &eglobal&7...} (zh_CN) /
     *         {@code Leaving channel &eglobal&7...} (en_US)
     */
    public static String leaving(String channel) {
        requireChannel(channel);
        return I18n.tr("chat.leave.leaving", channel);
    }

    /**
     * Player-locale variant of {@link #leaving(String)}.
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @param channel  the channel id being left; never null
     * @return colored message in the player's locale
     */
    public static String leaving(UUID playerId, String channel) {
        requireChannel(channel);
        return I18n.tr(playerId, "chat.leave.leaving", channel);
    }

    /**
     * Confirmation shown after the backend accepts a LEAVE and the player is
     * switched to the default channel.
     *
     * @param channel        the channel id that was left; never null
     * @param defaultChannel the default channel the player fell back to; never null
     * @return colored message
     */
    public static String left(String channel, String defaultChannel) {
        requireChannel(channel);
        if (defaultChannel == null || defaultChannel.isBlank()) {
            throw new IllegalArgumentException("defaultChannel must not be null or blank");
        }
        return I18n.tr("chat.leave.left", channel, defaultChannel);
    }

    /**
     * Player-locale variant of {@link #left(String, String)}.
     *
     * @param playerId       the player's UUID (may be null → default locale)
     * @param channel        the channel id that was left; never null
     * @param defaultChannel the default channel the player fell back to; never null
     * @return colored message in the player's locale
     */
    public static String left(UUID playerId, String channel, String defaultChannel) {
        requireChannel(channel);
        if (defaultChannel == null || defaultChannel.isBlank()) {
            throw new IllegalArgumentException("defaultChannel must not be null or blank");
        }
        return I18n.tr(playerId, "chat.leave.left", channel, defaultChannel);
    }

    /**
     * Action-bar / status line for the player's current channel and chat mode
     * (UX-DESIGN §7). Reuses {@link ChatModeDescriptions#modeName(ChatMode)}.
     *
     * @param channel the active channel id; never null
     * @param mode    the player's chat mode; never null
     * @return colored bar text, e.g. {@code &7当前频道：&bglobal &7（混合模式）} (zh_CN) /
     *         {@code &7Current channel: &bglobal &7(Hybrid mode)} (en_US)
     */
    public static String currentChannelBar(String channel, ChatMode mode) {
        requireChannel(channel);
        return I18n.tr("chat.status.current_bar", channel, ChatModeDescriptions.modeName(mode));
    }

    /**
     * Player-locale variant of {@link #currentChannelBar(String, ChatMode)}.
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @param channel  the active channel id; never null
     * @param mode     the player's chat mode; never null
     * @return colored bar text in the player's locale
     */
    public static String currentChannelBar(UUID playerId, String channel, ChatMode mode) {
        requireChannel(channel);
        return I18n.tr(playerId, "chat.status.current_bar", channel, ChatModeDescriptions.modeName(mode));
    }

    /**
     * PNX-style chat toggle confirmation when chat is enabled.
     *
     * @return plain text {@code 聊天已开启} (zh_CN) / {@code Chat enabled} (en_US)
     */
    public static String chatOn() {
        return I18n.tr("chat.toggle.on");
    }

    /**
     * Player-locale variant of {@link #chatOn()}.
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @return plain text in the player's locale
     */
    public static String chatOn(UUID playerId) {
        return I18n.tr(playerId, "chat.toggle.on");
    }

    /**
     * PNX-style chat toggle confirmation when chat is disabled.
     *
     * @return plain text {@code 聊天已关闭} (zh_CN) / {@code Chat disabled} (en_US)
     */
    public static String chatOff() {
        return I18n.tr("chat.toggle.off");
    }

    /**
     * Player-locale variant of {@link #chatOff()}.
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @return plain text in the player's locale
     */
    public static String chatOff(UUID playerId) {
        return I18n.tr(playerId, "chat.toggle.off");
    }

    private static void requireChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be null or blank");
        }
    }
}
