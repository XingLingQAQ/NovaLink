package com.nova.chat.pnx.world;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.entity.EntityLevelChangeEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import com.nova.chat.pnx.NovaChatPNX;

/**
 * Monitors player world changes and automatically switches channels based on configuration.
 */
public class WorldMonitor implements Listener {

    private final NovaChatPNX plugin;

    public WorldMonitor(NovaChatPNX plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        updatePlayerChannel(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(EntityLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getEntity();
        
        // Schedule channel update for next tick (after world change completes)
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            updatePlayerChannel(player);
        }, 1);
    }

    /**
     * Update a player's channel based on their current world.
     * Implements automatic channel switching (Requirements: 29.6)
     */
    private void updatePlayerChannel(Player player) {
        if (!plugin.getNovaChatConfig().isWorldRoutingEnabled()) {
            return;
        }

        // Check if player has bypass permission
        if (player.hasPermission("novachat.bypass.world")) {
            return;
        }

        String worldName = player.getLevel().getName();
        String channelId = plugin.getNovaChatConfig().getWorldChannel(worldName);

        if (channelId != null) {
            String currentChannel = plugin.getChatInterceptor().getPlayerChannel(player);
            
            if (!channelId.equals(currentChannel)) {
                plugin.getChatInterceptor().setPlayerChannel(player, channelId);
                
                // Notify player of channel switch
                String prefix = plugin.getNovaChatConfig().getFormatPrefix();
                player.sendMessage(prefix + "§7已自动切换到频道: §e" + channelId);
                
                plugin.debug("Auto-switched " + player.getName() + 
                    " to channel " + channelId + " (world: " + worldName + ")");
            }
        }
    }
}
