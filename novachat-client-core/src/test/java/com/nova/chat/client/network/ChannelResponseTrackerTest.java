package com.nova.chat.client.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChannelResponseTracker")
class ChannelResponseTrackerTest {

    @DisplayName("tracks and consumes a pending channel action by request id")
    @Test
    void tracksAndConsumesByRequestId() {
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        UUID playerId = UUID.randomUUID();
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, "global");
        packet.addExtra("playerId", playerId.toString());

        tracker.track(packet);

        ChannelResponseTracker.PendingChannelAction pending = tracker.consume(packet.getRequestId());
        assertThat(pending).isNotNull();
        assertThat(pending.getPlayerId()).isEqualTo(playerId);
        assertThat(pending.getChannelId()).isEqualTo("global");
        assertThat(pending.getAction()).isEqualTo(ChannelAction.JOIN);

        // Consume is destructive — second lookup returns null.
        assertThat(tracker.consume(packet.getRequestId())).isNull();
    }

    @DisplayName("captures operatorName and duration extras for KICK/MUTE (BUG-H1)")
    @Test
    void capturesOperatorAndDurationExtras() {
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.MUTE, "global");
        packet.addExtra("operatorName", "Alice");
        packet.addExtra("duration", "120");

        tracker.track(packet);

        ChannelResponseTracker.PendingChannelAction pending = tracker.consume(packet.getRequestId());
        assertThat(pending).isNotNull();
        assertThat(pending.getOperatorName()).isEqualTo("Alice");
        assertThat(pending.getDurationSeconds()).isEqualTo("120");
    }

    @DisplayName("leaves operatorName and duration null when not stamped")
    @Test
    void leavesOperatorAndDurationNullWhenAbsent() {
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.KICK, "global");

        tracker.track(packet);

        ChannelResponseTracker.PendingChannelAction pending = tracker.consume(packet.getRequestId());
        assertThat(pending).isNotNull();
        assertThat(pending.getOperatorName()).isNull();
        assertThat(pending.getDurationSeconds()).isNull();
    }

    @DisplayName("returns null when no playerId extra was attached")
    @Test
    void returnsNullPlayerIdWhenNoExtra() {
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, "global");

        tracker.track(packet);

        ChannelResponseTracker.PendingChannelAction pending = tracker.consume(packet.getRequestId());
        assertThat(pending).isNotNull();
        assertThat(pending.getPlayerId()).isNull();
    }

    @DisplayName("consume tolerates null request id")
    @Test
    void consumeNullRequestIdReturnsNull() {
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        assertThat(tracker.consume(null)).isNull();
    }

    @DisplayName("cleanupExpired evicts stale entries")
    @Test
    void cleanupExpiredEvictsStaleEntries() {
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.JOIN, "global");
        packet.addExtra("playerId", UUID.randomUUID().toString());
        tracker.track(packet);

        // Treat everything older than a negative age as expired → evict all immediately.
        int removed = tracker.cleanupExpired(-1L);
        assertThat(removed).isEqualTo(1);
        assertThat(tracker.consume(packet.getRequestId())).isNull();
        assertThat(tracker.size()).isZero();
    }
}
