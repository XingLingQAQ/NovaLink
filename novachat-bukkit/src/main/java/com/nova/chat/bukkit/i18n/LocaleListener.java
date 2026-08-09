package com.nova.chat.bukkit.i18n;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLocaleChangeEvent;

/**
 * Captures each player's Minecraft client locale and registers it with the
 * shared {@link I18n} service so player-facing text resolves in the player's
 * own language (zh_CN default, en_US secondary).
 *
 * <p>Two events are tracked:
 * <ul>
 *   <li>{@link PlayerLocaleChangeEvent} — fires when the player switches
 *       language in the client settings (e.g. via the language menu); updates
 *       the registration live.</li>
 *   <li>{@link PlayerJoinEvent} — captures the initial locale on join so the
 *       very first messages a player sees are already localized; the client
 *       locale is available on {@link Player#getLocale()} at join time on
 *       modern Bukkit/Paper.</li>
 * </ul>
 *
 * <p>Both handlers parse via {@link LocaleResolver#parse(String)}, which
 * returns {@code null} for blank/unparseable input; {@link I18n} then falls
 * back to the configured default locale, so a missing client locale never
 * breaks rendering.
 */
public class LocaleListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerLocaleChange(PlayerLocaleChangeEvent event) {
        Player player = event.getPlayer();
        I18n.registerPlayerLocale(player.getUniqueId(), LocaleResolver.parse(player.getLocale()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        I18n.registerPlayerLocale(player.getUniqueId(), LocaleResolver.parse(player.getLocale()));
    }
}
