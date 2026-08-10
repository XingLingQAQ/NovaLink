package com.nova.chat.bukkit.welcome;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.command.MessageHelper;
import com.nova.chat.client.command.WelcomeMessageService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Pushes the shared first-join welcome line once to genuinely first-time
 * players (UX-DESIGN §8.1).
 *
 * <p>Uses Bukkit's {@link Player#hasPlayedBefore()} so the welcome fires only
 * for players the server has never seen before — not on every reconnect.
 * The message is a single non-intrusive chat line (no title) pointing at
 * {@code /nc help} and {@code /nc list}; copy lives in
 * {@link WelcomeMessageService} so every platform stays in sync.
 */
public class WelcomeListener implements Listener {

    private final NovaChatBukkit plugin;
    private final MessageHelper messageHelper;

    public WelcomeListener(NovaChatBukkit plugin) {
        this.plugin = plugin;
        this.messageHelper = plugin.getMessageHelper();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Only first-time players get the welcome line.
        if (player.hasPlayedBefore()) {
            return;
        }

        messageHelper.sendRaw(player, WelcomeMessageService.getWelcomeLine());
        plugin.debug("Sent first-join welcome to " + player.getName());
    }
}
