package com.nova.chat.client.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChannelResponseDispatcher}, covering LEAVE success
 * confirmation, status-bar callback preservation, and rollback on rejection.
 */
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

    @Test
    @DisplayName("successful WHO routes members/count/displayName to the requesting player")
    void successfulWhoRoutesMembersToRequester() {
        UUID playerId = UUID.randomUUID();
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket request = trackedWhoRequest(tracker, playerId, "local");
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);

        dispatcher.handle(whoResponseFor(request, "local", "Local Chat", "Alice, Bob", "2"));

        assertThat(adapter.whoPlayerId).isEqualTo(playerId);
        assertThat(adapter.whoChannelId).isEqualTo("local");
        assertThat(adapter.whoDisplayName).isEqualTo("Local Chat");
        assertThat(adapter.whoMembers).isEqualTo("Alice, Bob");
        assertThat(adapter.whoCount).isEqualTo("2");
        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("successful WHO falls back to the tracked channel when response channel is blank")
    void successfulWhoUsesTrackedChannelFallback() {
        UUID playerId = UUID.randomUUID();
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket request = trackedWhoRequest(tracker, playerId, "trade");
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);

        dispatcher.handle(whoResponseFor(request, "", "Trade", "", "0"));

        assertThat(adapter.whoChannelId).isEqualTo("trade");
    }

    private static ChannelActionPacket trackedRequest(ChannelResponseTracker tracker,
                                                       UUID playerId,
                                                       String channelId) {
        ChannelActionPacket request = new ChannelActionPacket(ChannelAction.LEAVE, channelId);
        request.addExtra("playerId", playerId.toString());
        tracker.track(request);
        return request;
    }

    private static ChannelActionPacket trackedWhoRequest(ChannelResponseTracker tracker,
                                                        UUID playerId,
                                                        String channelId) {
        ChannelActionPacket request = new ChannelActionPacket(ChannelAction.WHO, channelId);
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

    private static ChannelActionResponsePacket whoResponseFor(ChannelActionPacket request,
                                                              String channelId,
                                                              String displayName,
                                                              String members,
                                                              String memberCount) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.WHO, channelId, "", "Channel members");
        response.setRequestId(request.getRequestId());
        response.addExtra("displayName", displayName);
        response.addExtra("members", members);
        response.addExtra("memberCount", memberCount);
        return response;
    }

    private static final class RecordingAdapter implements ChannelResponseDispatcher.ChannelResponseAdapter {
        private UUID leavePlayerId;
        private String leftChannel;
        private UUID leaveStatusBarPlayerId;
        private UUID whoPlayerId;
        private String whoChannelId;
        private String whoDisplayName;
        private String whoMembers;
        private String whoCount;

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

        @Override
        public void sendWhoResult(UUID playerId, String channelId, String displayName,
                                  String membersCsv, String memberCount) {
            this.whoPlayerId = playerId;
            this.whoChannelId = channelId;
            this.whoDisplayName = displayName;
            this.whoMembers = membersCsv;
            this.whoCount = memberCount;
        }
    }
}
