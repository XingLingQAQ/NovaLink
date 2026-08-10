package com.nova.chat.bungee.listener;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.SettingsChangedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.Locale;

/**
 * Captures each player's Minecraft client locale and registers it with the
 * shared {@link I18n} service so player-facing text resolves in the player's
 * own locale (Architecture B i18n).
 *
 * <p>BungeeCord fires {@link SettingsChangedEvent} when a player changes client
 * settings (including locale). We also capture the locale on
 * {@link PostLoginEvent} (proxy login, before any server is chosen) and again on
 * {@link ServerConnectedEvent} (first downstream server connect) as a safety
 * net, because {@link ProxiedPlayer#getLocale()} may return {@code null} until
 * the client-settings packet has been processed.
 *
 * <p>{@link ProxiedPlayer#getLocale()} returns a {@link java.util.Locale}
 * directly; {@link LocaleResolver#parse(String)} is fed its {@code toString()}
 * form to stay consistent with the shared resolution path used by every other
 * platform.
 */
public class LocaleCaptureListener implements Listener {

    private final NovaChatBungee plugin;

    public LocaleCaptureListener(NovaChatBungee plugin) {
        this.plugin = plugin;
    }

    /**
     * Captures the locale as early as possible (proxy login). The client locale
     * may not be available yet — if so, the later server-connected / settings
     * handlers will fill it in.
     *
     * @param event the post-login event
     */
    @EventHandler
    public void onPostLogin(PostLoginEvent event) {
        register(event.getPlayer());
    }

    /**
     * Safety net: register the locale when the player connects to their first
     * downstream server (the client-settings packet has usually arrived by
     * then).
     *
     * @param event the server-connected event
     */
    @EventHandler
    public void onServerConnected(ServerConnectedEvent event) {
        register(event.getPlayer());
    }

    /**
     * Re-registers the locale whenever the player changes client settings
     * (locale, view distance, chat visibility, …). This is the authoritative
     * locale-change event on BungeeCord.
     *
     * @param event the settings-changed event
     */
    @EventHandler
    public void onSettingsChanged(SettingsChangedEvent event) {
        register(event.getPlayer());
    }

    private void register(ProxiedPlayer player) {
        if (player == null) {
            return;
        }
        Locale locale = player.getLocale();
        if (locale == null) {
            return;
        }
        I18n.registerPlayerLocale(player.getUniqueId(), LocaleResolver.parse(locale.toString()));
        plugin.debug("Registered locale " + locale + " for " + player.getName());
    }
}
