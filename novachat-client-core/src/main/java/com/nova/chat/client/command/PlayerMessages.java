package com.nova.chat.client.command;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.ChatModeDescriptions;

/**
 * Shared, platform-agnostic player-facing message templates for high-frequency
 * join / leave / status / chat-toggle copy (UX-DESIGN-2 §13).
 *
 * <p>Architecture B: pure static helpers in {@code novachat-client-core}. Platforms
 * replace hard-coded literals with these methods so wording stays aligned across
 * bukkit / folia / multipaper / velocity / bungee / sponge / nukkit / pnx.
 *
 * <p>Color codes use the shared {@code &} form; platforms colorize / convert to
 * {@code §} via their own helper (or {@code LegacyColorCodes.ampersandToSection})
 * before sending.
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
     * @return colored message, e.g. {@code 正在加入频道 &eglobal&7...}
     */
    public static String joining(String channel) {
        requireChannel(channel);
        return "正在加入频道 &e" + channel + "&7...";
    }

    /**
     * Confirmation shown after the backend accepts a JOIN.
     *
     * @param channel the channel id that was joined; never null
     * @return colored message, e.g. {@code 已加入频道 &eglobal}
     */
    public static String joined(String channel) {
        requireChannel(channel);
        return "已加入频道 &e" + channel;
    }

    /**
     * Immediate ack shown when a leave is requested (before backend confirmation).
     *
     * @param channel the channel id being left; never null
     * @return colored message, e.g. {@code 正在离开频道 &eglobal&7...}
     */
    public static String leaving(String channel) {
        requireChannel(channel);
        return "正在离开频道 &e" + channel + "&7...";
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
        return "已离开频道 &e" + channel + "&7，已切换到默认频道: &e" + defaultChannel;
    }

    /**
     * Action-bar / status line for the player's current channel and chat mode
     * (UX-DESIGN §7). Reuses {@link ChatModeDescriptions#modeName(ChatMode)}.
     *
     * @param channel the active channel id; never null
     * @param mode    the player's chat mode; never null
     * @return colored bar text, e.g. {@code &7当前频道：&bglobal &7（混合模式）}
     */
    public static String currentChannelBar(String channel, ChatMode mode) {
        requireChannel(channel);
        return "&7当前频道：&b" + channel + " &7（" + ChatModeDescriptions.modeName(mode) + "）";
    }

    /**
     * PNX-style chat toggle confirmation when chat is enabled.
     *
     * @return plain text {@code 聊天已开启}
     */
    public static String chatOn() {
        return "聊天已开启";
    }

    /**
     * PNX-style chat toggle confirmation when chat is disabled.
     *
     * @return plain text {@code 聊天已关闭}
     */
    public static String chatOff() {
        return "聊天已关闭";
    }

    private static void requireChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("channel must not be null or blank");
        }
    }
}
