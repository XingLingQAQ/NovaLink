package com.nova.chat.client.command;

/**
 * Shared message prefix literals used by the platform {@code MessageHelper}
 * implementations (bukkit / folia / nukkit). Centralizing these
 * keeps the platform copies in sync without a cross-module reference.
 *
 * <p><b>i18n decision: NOT internationalized.</b> These prefixes are
 * {@code &8[&bNovaChat&8]&r } / {@code &8[&cNovaChat&8]&r } /
 * {@code &8[&aNovaChat&8]&r } — pure color codes plus the literal brand
 * token "NovaChat". They contain no natural language to translate, so they
 * are identical in every locale (zh_CN and en_US). Keeping them as plain
 * {@code public static final String} constants preserves the existing
 * call sites and avoids an unnecessary {@link com.nova.chat.client.i18n.I18n}
 * lookup on every message send. Should a future prefix carry natural
 * language, route it through {@code I18n} with a {@code prefix.*} key.
 */
public final class MessagePrefixes {

    /** Standard message prefix. */
    public static final String PREFIX = "&8[&bNovaChat&8]&r ";

    /** Error message prefix. */
    public static final String ERROR_PREFIX = "&8[&cNovaChat&8]&r ";

    /** Success message prefix. */
    public static final String SUCCESS_PREFIX = "&8[&aNovaChat&8]&r ";

    private MessagePrefixes() {
    }
}
