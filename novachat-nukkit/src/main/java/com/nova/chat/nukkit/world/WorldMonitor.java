package com.nova.chat.nukkit.world;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityLevelChangeEvent;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.chat.PlayerChatState;

/**
 * Monitors world changes for players and handles auto-routing to appropriate channels.
 * 
 * Adapted from Bukkit version for Nukkit API.
 * Note: Nukkit uses "Level" instead of "World".
 * 
 * Requirements: 6.2, 6.3, 9.1-9.4, 23.4
 */
public class WorldMonitor implements Listener {

    private final NovaChatNukkit plugin;

    public WorldMonitor(NovaChatNukkit plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles level (world) change events for players.
     * Auto-routes players to appropriate channels based on the new world.
     *
     * @param event the level change event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLevelChange(EntityLevelChangeEvent event) {
        // Only handle player level changes
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity();
        String newWorld = event.getTarget().getName();
        String oldWorld = event.getOrigin() != null ? event.getOrigin().getName() : "unknown";

        plugin.debug("Player " + player.getName() + " changed world from " + oldWorld + " to " + newWorld);

        // Get player's current state
        PlayerChatState state = plugin.getChatInterceptor().getOrCreateState(player);
        String currentChannel = state.getActiveChannel();

        // For now, just log the world change
        // The actual channel routing logic would be handled by the backend
        // based on the world filter configuration
        
        plugin.debug("Player " + player.getName() + " is in channel " + currentChannel + 
                    " after world change to " + newWorld);
        
        // Notify player of world change (optional)
        // This could be enhanced to show which channel they're now in
    }
}
