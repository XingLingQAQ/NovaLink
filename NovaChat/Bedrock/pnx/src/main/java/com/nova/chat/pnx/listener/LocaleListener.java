package com.nova.chat.pnx.listener;

import org.powernukkitx.Player;
import org.powernukkitx.event.EventHandler;
import org.powernukkitx.event.EventPriority;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.player.PlayerJoinEvent;
import org.powernukkitx.lang.LangCode;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;

public class LocaleListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        try {
            LangCode languageCode = player.getLanguageCode();
            I18n.registerPlayerLocale(player.getUniqueId(),
                    LocaleResolver.parse(languageCode == null ? null : languageCode.name()));
        } catch (Throwable t) {
            // Locale unavailable during join
        }
    }
}
