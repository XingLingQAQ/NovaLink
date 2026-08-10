package com.nova.chat.nukkit.listener;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;

/**
 * Captures each Bedrock player's client locale from the login chain and
 * registers it with the shared {@link I18n} service so per-player messages
 * render in the player's own language.
 *
 * <p>Bedrock sends the language code in the login chain data (not via a
 * locale-change event like Java Edition), so a single {@link PlayerJoinEvent}
 * handler is enough. There is no later locale-change event to listen for.
 */
public class LocaleListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        try {
            String languageCode = player.getLoginChainData().getLanguageCode();
            I18n.registerPlayerLocale(player.getUniqueId(), LocaleResolver.parse(languageCode));
        } catch (Throwable t) {
            // Login chain data may be unavailable during mock/test joins; fall
            // back to the default locale by leaving the player unregistered.
        }
    }
}
