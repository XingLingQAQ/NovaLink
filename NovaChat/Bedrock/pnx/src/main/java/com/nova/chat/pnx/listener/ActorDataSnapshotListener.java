package com.nova.chat.pnx.listener;

import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataMap;
import org.cloudburstmc.protocol.bedrock.packet.SetActorDataPacket;
import org.powernukkitx.event.EventHandler;
import org.powernukkitx.event.EventPriority;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.server.PacketSendEvent;

public final class ActorDataSnapshotListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled() || !(event.getPacket() instanceof SetActorDataPacket packet)) {
            return;
        }

        // PNX 3.0.6: live metadata reaches Netty's async encoder
        ActorDataMap snapshot = new ActorDataMap();
        snapshot.putAll(packet.getActorData());
        var flags = snapshot.getFlags();
        if (flags != null) {
            snapshot.putFlags(flags.clone());
        }

        packet.setActorData(snapshot);
    }
}
