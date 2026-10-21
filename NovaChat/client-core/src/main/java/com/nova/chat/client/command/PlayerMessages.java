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
     * Confirmation shown after the backend accepts a CREATE, naming the
     * backend-generated channel id and password — neither of which the player
     * can discover any other way.
     *
     * @param channel  the new channel id; never null
     * @param password the new channel password; never null
     * @return colored message, e.g.
     *         {@code 私有频道ID: &eNC-1A2B&7，密码: &ehunter2} (zh_CN) /
     *         {@code Private channel id: &eNC-1A2B&7, password: &ehunter2} (en_US)
     */
    public static String created(String channel, String password) {
        requireChannel(channel);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must not be null or blank");
        }
        return I18n.tr("chat.create.result", channel, password);
    }

    /**
     * Player-locale variant of {@link #created(String, String)}.
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @param channel  the new channel id; never null
     * @param password the new channel password; never null
     * @return colored message in the player's locale
     */
    public static String created(UUID playerId, String channel, String password) {
        requireChannel(channel);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must not be null or blank");
        }
        return I18n.tr(playerId, "chat.create.result", channel, password);
    }

    /**
     * Follow-up hint rendered when the password in {@link #created} was
     * auto-generated and therefore exists nowhere else.
     *
     * @return plain text, e.g. {@code 该密码为系统自动生成，请妥善保存} (zh_CN) /
     *         {@code This password was auto-generated; keep it safe} (en_US)
     */
    public static String passwordAutoGenerated() {
        return I18n.tr("chat.create.password_saved");
    }

    /**
     * Player-locale variant of {@link #passwordAutoGenerated()}.
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @return plain text in the player's locale
     */
    public static String passwordAutoGenerated(UUID playerId) {
        return I18n.tr(playerId, "chat.create.password_saved");
    }

    /**
     * Confirmation shown after the backend accepts an INVITE, naming the
     * backend-generated code the invitee has to run {@code /nc accept} with.
     *
     * @param code the invite code; never null
     * @return colored message, e.g.
     *         {@code 邀请码: &eA1B2C&7（对方执行 /nc accept A1B2C）} (zh_CN) /
     *         {@code Invite code: &eA1B2C&7 (have them run /nc accept A1B2C)} (en_US)
     */
    public static String inviteCode(String code) {
        requireCode(code);
        return I18n.tr("chat.invite.code", code);
    }

    /**
     * Player-locale variant of {@link #inviteCode(String)}.
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @param code     the invite code; never null
     * @return colored message in the player's locale
     */
    public static String inviteCode(UUID playerId, String code) {
        requireCode(code);
        return I18n.tr(playerId, "chat.invite.code", code);
    }

    /**
     * Follow-up line giving the invite's remaining validity, appended to
     * {@link #inviteCode(String)} when the backend supplied an expiry.
     *
     * @param expiresIn a short duration phrase (e.g. {@code 24小时} / {@code 24h});
     *                  never null
     * @return plain text, e.g. {@code 有效期剩余 {0}} (zh_CN) /
     *         {@code Valid for {0}} (en_US)
     */
    public static String inviteExpiresIn(String expiresIn) {
        if (expiresIn == null || expiresIn.isBlank()) {
            throw new IllegalArgumentException("expiresIn must not be null or blank");
        }
        return I18n.tr("chat.invite.expires_in", expiresIn);
    }

    /**
     * Player-locale variant of {@link #inviteExpiresIn(String)}.
     *
     * @param playerId  the player's UUID (may be null → default locale)
     * @param expiresIn a short duration phrase; never null
     * @return plain text in the player's locale
     */
    public static String inviteExpiresIn(UUID playerId, String expiresIn) {
        if (expiresIn == null || expiresIn.isBlank()) {
            throw new IllegalArgumentException("expiresIn must not be null or blank");
        }
        return I18n.tr(playerId, "chat.invite.expires_in", expiresIn);
    }

    /**
     * Confirmation shown after the backend accepts an ACCEPT: the invite code
     * admitted the player to {@code channel}.
     *
     * @param channel the channel the invite admitted the player to; never null
     * @return colored message, e.g. {@code 已通过邀请加入频道 &eNC-1A2B} (zh_CN) /
     *         {@code Joined channel &eNC-1A2B &7via invite} (en_US)
     */
    public static String accepted(String channel) {
        requireChannel(channel);
        return I18n.tr("chat.accept.accepted", channel);
    }

    /**
     * Player-locale variant of {@link #accepted(String)}.
     *
     * @param playerId the player's UUID (may be null → default locale)
     * @param channel  the channel the invite admitted the player to; never null
     * @return colored message in the player's locale
     */
    public static String accepted(UUID playerId, String channel) {
        requireChannel(channel);
        return I18n.tr(playerId, "chat.accept.accepted", channel);
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

    private static void requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be null or blank");
        }
    }
}
