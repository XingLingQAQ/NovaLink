package com.nova.chat.client.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChannelResponseDispatcher")
class ChannelResponseDispatcherTest {

    @Test
    @DisplayName("successful LEAVE sends chat confirmation and preserves the status bar callback")
    void successfulLeaveSendsConfirmationAndStatusBar() {
        UUID playerId = UUID.randomUUID();
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket request = trackedRequest(tracker, playerId, "staff");
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);
        ChannelActionResponsePacket response = responseFor(request, "staff");

        dispatcher.handle(response);

        assertThat(adapter.leavePlayerId).isEqualTo(playerId);
        assertThat(adapter.leftChannel).isEqualTo("staff");
        assertThat(adapter.leaveStatusBarPlayerId).isEqualTo(playerId);
        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("successful LEAVE falls back to the tracked channel when response channel is blank")
    void successfulLeaveUsesTrackedChannelFallback() {
        UUID playerId = UUID.randomUUID();
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket request = trackedRequest(tracker, playerId, "trade");
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);
        ChannelActionResponsePacket response = responseFor(request, "");

        dispatcher.handle(response);

        assertThat(adapter.leftChannel).isEqualTo("trade");
        assertThat(adapter.leaveStatusBarPlayerId).isEqualTo(playerId);
    }

    private static ChannelActionPacket trackedRequest(ChannelResponseTracker tracker,
                                                       UUID playerId,
                                                       String channelId) {
        ChannelActionPacket request = new ChannelActionPacket(ChannelAction.LEAVE, channelId);
        request.addExtra("playerId", playerId.toString());
        tracker.track(request);
        return request;
    }

    private static ChannelActionResponsePacket responseFor(ChannelActionPacket request, String channelId) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.LEAVE, channelId, "", "Left channel");
        response.setRequestId(request.getRequestId());
        return response;
    }

    private static final class RecordingAdapter implements ChannelResponseDispatcher.ChannelResponseAdapter {
        private UUID leavePlayerId;
        private String leftChannel;
        private UUID leaveStatusBarPlayerId;

        @Override
        public void setActiveChannel(UUID playerId, String channelId) {
        }

        @Override
        public void rollbackJoin(UUID playerId, String attemptedChannel, String previousChannel) {
        }

        @Override
        public void sendJoinSuccess(UUID playerId, String channelId) {
        }

        @Override
        public void sendLeaveSuccess(UUID playerId, String channelId) {
            this.leavePlayerId = playerId;
            this.leftChannel = channelId;
        }

        @Override
        public void sendJoinChannelStatusBar(UUID playerId, String channelId) {
        }

        @Override
        public void sendLeaveChannelStatusBar(UUID playerId) {
            this.leaveStatusBarPlayerId = playerId;
        }

        @Override
        public void sendErrorMessage(UUID playerId, String text) {
        }

        @Override
        public void notifyKickMuteTarget(ChannelResponseDispatcher.KickMuteNotice notice) {
        }
    }
}
