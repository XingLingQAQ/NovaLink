package com.nova.chat.folia.welcome;

import com.nova.chat.client.command.WelcomeMessageService;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.command.MessageHelper;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Pushes the shared first-join welcome line once to genuinely first-time
 * players on Folia (UX-DESIGN §8.1).
 *
 * <p>Uses {@link Player#hasPlayedBefore()} so the welcome fires only for
 * players the server has never seen before. {@link PlayerJoinEvent} fires on
 * the player's region thread, which is safe for sending a chat message; to
 * stay consistent with the rest of the Folia interceptor, the actual send is
 * routed through {@link FoliaSchedulerAdapter#runForPlayer(Player, Runnable)}.
 * Single non-intrusive chat line, no title.
 */
public class WelcomeListener implements Listener {

    private final NovaChatFolia plugin;
    private final MessageHelper messageHelper;

    public WelcomeListener(NovaChatFolia plugin) {
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

        plugin.getScheduler().runForPlayer(player, () ->
                messageHelper.sendRaw(player, WelcomeMessageService.getWelcomeLine()));
        plugin.debug("Sent first-join welcome to " + player.getName());
    }
}
