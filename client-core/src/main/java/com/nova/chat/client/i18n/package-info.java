/**
 * Shared internationalization (i18n) layer for the NovaChat client
 * ({@code novachat-client-core}).
 *
 * <p>Every platform plugin (bukkit / folia / velocity / bungee / sponge /
 * nukkit / pnx / mod) renders player-facing text through {@link com.nova.chat.client.i18n.I18n}
 * so a player sees messages in their own Minecraft client locale. The
 * configured default locale ({@code chat.locale}) is used when no
 * player-specific locale is registered (e.g. console senders).
 *
 * <p>Bundles: {@code messages_zh_CN.properties} (default / hard fallback)
 * and {@code messages_en_US.properties}, loaded as UTF-8 via
 * {@link com.nova.chat.client.i18n.Utf8Control}. Color codes
 * ({@code &e}, {@code §c}, …) stay inside the property values; i18n swaps
 * only natural-language text. {@link java.text.MessageFormat} {@code {0}}
 * placeholders carry dynamic values.
 *
 * <p>Architecture B: plugin-only. The backend ({@code novalink-core}) ships
 * its own independent {@code com.nova.link.i18n} package that never depends
 * on this one.
 */
package com.nova.chat.client.i18n;
