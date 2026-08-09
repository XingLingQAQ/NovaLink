package com.nova.chat.velocity.listener;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.velocity.NovaChatVelocity;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerSettingsChangedEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.PlayerSettings;

import java.util.Locale;

/**
 * Captures each player's Minecraft client locale and registers it with the
 * shared {@link I18n} service so player-facing text resolves in the player's
 * own locale (Architecture B i18n).
 *
 * <p>Velocity fires {@link PlayerSettingsChangedEvent} when the client sends
 * its settings packet (which carries the locale). We also capture the locale on
 * {@link PostLoginEvent} (proxy login) and {@link ServerConnectedEvent} (first
 * downstream server connect) as a safety net, because
 * {@link Player#getPlayerSettings()} may not have a locale until the settings
 * packet has been processed.
 *
 * <p>{@link PlayerSettings#getLocale()} returns a {@link java.util.Locale}
 * directly; {@link LocaleResolver#parse(String)} is fed its {@code toString()}
 * form to stay consistent with the shared resolution path used by every other
 * platform.
 */
public class LocaleCaptureListener {

    private final NovaChatVelocity plugin;

    public LocaleCaptureListener(NovaChatVelocity plugin) {
        this.plugin = plugin;
    }

    /**
     * Captures the locale as early as possible (proxy login). The client
     * settings (and therefore the locale) may not have arrived yet — if so,
     * the later server-connected / settings-changed handlers fill it in.
     *
     * @param event the post-login event
     */
    @Subscribe(order = PostOrder.NORMAL)
    public void onPostLogin(PostLoginEvent event) {
        register(event.getPlayer());
    }

    /**
     * Safety net: register the locale when the player connects to a downstream
     * server (the client-settings packet has usually arrived by then).
     *
     * @param event the server-connected event
     */
    @Subscribe(order = PostOrder.NORMAL)
    public void onServerConnected(ServerConnectedEvent event) {
        register(event.getPlayer());
    }

    /**
     * Re-registers the locale whenever the player changes client settings
     * (locale, view distance, chat visibility, …). This is the authoritative
     * locale-change event on Velocity.
     *
     * @param event the player-settings-changed event
     */
    @Subscribe(order = PostOrder.NORMAL)
    public void onPlayerSettingsChanged(PlayerSettingsChangedEvent event) {
        register(event.getPlayer());
    }

    private void register(Player player) {
        if (player == null) {
            return;
        }
        PlayerSettings settings = player.getPlayerSettings();
        if (settings == null) {
            return;
        }
        Locale locale = settings.getLocale();
        if (locale == null) {
            return;
        }
        I18n.registerPlayerLocale(player.getUniqueId(), LocaleResolver.parse(locale.toString()));
        plugin.debug("Registered locale " + locale + " for " + player.getUsername());
    }
}
