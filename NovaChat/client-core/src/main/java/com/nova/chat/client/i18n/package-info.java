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
 * and {@code messages_en_US.properties}, read from the classpath as UTF-8
 * {@link java.util.PropertyResourceBundle}s by
 * {@link com.nova.chat.client.i18n.I18n}. They are deliberately NOT loaded
 * through a custom {@link java.util.ResourceBundle.Control}, which Java
 * rejects inside a named module — Sponge's plugin loader makes every plugin
 * one, and the Control-based loader silently broke command registration
 * there. Color codes
 * ({@code &e}, {@code §c}, …) stay inside the property values; i18n swaps
 * only natural-language text. {@link java.text.MessageFormat} {@code {0}}
 * placeholders carry dynamic values.
 *
 * <p>Architecture B: plugin-only. The backend ({@code novalink-core}) ships
 * its own independent {@code com.nova.link.i18n} package that never depends
 * on this one.
 */
package com.nova.chat.client.i18n;
