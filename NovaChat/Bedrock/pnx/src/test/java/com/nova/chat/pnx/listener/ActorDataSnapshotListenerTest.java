package com.nova.chat.pnx.listener;

import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataMap;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorDataTypes;
import org.cloudburstmc.protocol.bedrock.data.actor.ActorFlags;
import org.cloudburstmc.protocol.bedrock.packet.SetActorDataPacket;
import org.cloudburstmc.protocol.bedrock.packet.TextPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.powernukkitx.Player;
import org.powernukkitx.event.server.PacketSendEvent;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ActorDataSnapshotListenerTest {

    @Mock
    private Player player;

    private final ActorDataSnapshotListener listener = new ActorDataSnapshotListener();

    @Test
    void snapshotsMetadataAndFlagsBeforeReturningToTheSender() {
        SetActorDataPacket packet = new SetActorDataPacket();
        packet.setTargetRuntimeID(7);
        packet.setTick(42);
        ActorDataMap live = packet.getActorData();
        live.put(ActorDataTypes.NAME, "before");
        EnumSet<ActorFlags> flags = EnumSet.of(ActorFlags.CAN_CLIMB);
        live.putFlags(flags);
        PacketSendEvent event = new PacketSendEvent(player, packet);

        listener.onPacketSend(event);

        ActorDataMap queued = packet.getActorData();
        live.put(ActorDataTypes.NAME, "after");
        live.put(ActorDataTypes.SCALE, 2.0f);
        flags.add(ActorFlags.SNEAKING);

        assertThat(event.isCancelled()).isFalse();
        assertThat(event.getPacket()).isSameAs(packet);
        assertThat(queued).isNotSameAs(live);
        assertThat(queued.get(ActorDataTypes.NAME)).isEqualTo("before");
        assertThat(queued.containsKey(ActorDataTypes.SCALE)).isFalse();
        assertThat(queued.getFlags()).containsExactly(ActorFlags.CAN_CLIMB);
        assertThat(queued.getFlags()).isNotSameAs(flags);
        assertThat(queued.get(ActorDataTypes.FLAGS_2)).isSameAs(queued.getFlags());
        assertThat(packet.getTargetRuntimeID()).isEqualTo(7);
        assertThat(packet.getTick()).isEqualTo(42);
        verifyNoInteractions(player);
    }

    @Test
    void repeatedRecipientEventsKeepTheFirstSnapshotValues() {
        SetActorDataPacket packet = new SetActorDataPacket();
        ActorDataMap live = packet.getActorData();
        live.put(ActorDataTypes.NAME, "before");
        live.putFlags(EnumSet.of(ActorFlags.CAN_CLIMB));

        listener.onPacketSend(new PacketSendEvent(player, packet));
        ActorDataMap first = packet.getActorData();
        live.put(ActorDataTypes.NAME, "after");
        live.getFlags().add(ActorFlags.SNEAKING);
        listener.onPacketSend(new PacketSendEvent(player, packet));
        ActorDataMap second = packet.getActorData();
        live.clear();

        assertThat(second).isNotSameAs(first);
        assertThat(second).isEqualTo(first);
        assertThat(first.get(ActorDataTypes.NAME)).isEqualTo("before");
        assertThat(second.get(ActorDataTypes.NAME)).isEqualTo("before");
        assertThat(first.getFlags()).containsExactly(ActorFlags.CAN_CLIMB);
        assertThat(second.getFlags()).containsExactly(ActorFlags.CAN_CLIMB);
        assertThat(second.getFlags()).isNotSameAs(first.getFlags());
        verifyNoInteractions(player);
    }

    @Test
    void leavesOtherPacketsUnchanged() {
        TextPacket packet = new TextPacket();
        PacketSendEvent event = new PacketSendEvent(player, packet);

        listener.onPacketSend(event);

        assertThat(event.isCancelled()).isFalse();
        assertThat(event.getPacket()).isSameAs(packet);
        verifyNoInteractions(player);
    }

    @Test
    void preservesLaterMetadataEditsAndCancellation() {
        SetActorDataPacket packet = new SetActorDataPacket();
        ActorDataMap live = packet.getActorData();
        live.put(ActorDataTypes.NAME, "before");
        PacketSendEvent event = new PacketSendEvent(player, packet);

        listener.onPacketSend(event);

        assertThat(event.isCancelled()).isFalse();
        SetActorDataPacket downstreamPacket = (SetActorDataPacket) event.getPacket();
        ActorDataMap downstreamData = downstreamPacket.getActorData();
        downstreamData.put(ActorDataTypes.NAME, "downstream");
        event.setCancelled(true);
        listener.onPacketSend(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(event.getPacket()).isSameAs(packet);
        assertThat(packet.getActorData()).isSameAs(downstreamData);
        assertThat(packet.getActorData().get(ActorDataTypes.NAME)).isEqualTo("downstream");
        assertThat(live.get(ActorDataTypes.NAME)).isEqualTo("before");
        assertThat(downstreamData.getFlags()).isNull();
        verifyNoInteractions(player);
    }
}
