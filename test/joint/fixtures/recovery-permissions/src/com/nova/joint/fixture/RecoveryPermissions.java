package com.nova.joint.fixture;

import org.powernukkitx.event.EventHandler;
import org.powernukkitx.event.EventPriority;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.player.PlayerJoinEvent;
import org.powernukkitx.plugin.PluginBase;

public final class RecoveryPermissions extends PluginBase implements Listener {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if ("E2ERecoverBed".equals(event.getPlayer().getName())) {
            event.getPlayer().addAttachment(this, "novachat.channel.local", true);
        }
    }
}
