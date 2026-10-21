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

    // ==================== CREATE / INVITE / ACCEPT success routing ====================

    @Test
    @DisplayName("successful CREATE routes the backend-generated id and password to the creator")
    void successfulCreateRoutesIdAndPassword() {
        UUID playerId = UUID.randomUUID();
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket request = trackedRequest(tracker, playerId, "myroom", ChannelAction.CREATE);
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);

        dispatcher.handle(createResponseFor(request, "NC-1A2B", "hunter2", true, "myroom"));

        assertThat(adapter.createPlayerId).isEqualTo(playerId);
        assertThat(adapter.createChannelId).isEqualTo("NC-1A2B");
        assertThat(adapter.createPassword).isEqualTo("hunter2");
        assertThat(adapter.createGenerated).isTrue();
        assertThat(adapter.createDisplayName).isEqualTo("myroom");
        assertThat(adapter.activeChannelId)
                .as("the backend makes the new channel the creator's active channel")
                .isEqualTo("NC-1A2B");
        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("CREATE does not fall back to the tracked id: the pending value is the requested name")
    void createDoesNotFallBackToTrackedName() {
        UUID playerId = UUID.randomUUID();
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket request = trackedRequest(tracker, playerId, "myroom", ChannelAction.CREATE);
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);

        dispatcher.handle(createResponseFor(request, "", "", false, "myroom"));

        assertThat(adapter.createChannelId).isEmpty();
        assertThat(adapter.activeChannelId)
                .as("a name must never be promoted to an active channel id")
                .isNull();
    }

    @Test
    @DisplayName("successful INVITE routes the backend-generated code and a relative expiry")
    void successfulInviteRoutesCode() {
        UUID playerId = UUID.randomUUID();
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket request = trackedRequest(tracker, playerId, "NC-1A2B", ChannelAction.INVITE);
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);

        String expiry = String.valueOf(System.currentTimeMillis() + 24L * 60 * 60 * 1000);
        dispatcher.handle(inviteResponseFor(request, "NC-1A2B", "a1b2c3", expiry));

        assertThat(adapter.invitePlayerId).isEqualTo(playerId);
        assertThat(adapter.inviteChannelId).isEqualTo("NC-1A2B");
        assertThat(adapter.inviteCode).isEqualTo("a1b2c3");
        assertThat(adapter.inviteExpiresIn)
                .as("an absolute epoch-millis extra becomes a relative phrase")
                .isNotEmpty();
    }

    @Test
    @DisplayName("INVITE with no usable expiry passes a blank phrase (the adapter then omits the line)")
    void inviteWithoutExpiryPassesBlankPhrase() {
        UUID playerId = UUID.randomUUID();
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket request = trackedRequest(tracker, playerId, "NC-1A2B", ChannelAction.INVITE);
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);

        dispatcher.handle(inviteResponseFor(request, "NC-1A2B", "a1b2c3", null));

        assertThat(adapter.inviteCode).isEqualTo("a1b2c3");
        assertThat(adapter.inviteExpiresIn).isEmpty();
    }

    @Test
    @DisplayName("successful ACCEPT routes the accepted channel and sets it active")
    void successfulAcceptRoutesChannel() {
        UUID playerId = UUID.randomUUID();
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        // The ACCEPT request's channelId is the invite CODE, not a channel id.
        ChannelActionPacket request = trackedRequest(tracker, playerId, "A1B2C3", ChannelAction.ACCEPT);
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);

        dispatcher.handle(acceptResponseFor(request, "NC-1A2B"));

        assertThat(adapter.acceptPlayerId).isEqualTo(playerId);
        assertThat(adapter.acceptChannelId).isEqualTo("NC-1A2B");
        assertThat(adapter.activeChannelId)
                .as("the invite code must never be promoted to an active channel id")
                .isEqualTo("NC-1A2B");
        assertThat(tracker.size()).isZero();
    }

    @Test
    @DisplayName("a refused CREATE renders only the error, with no success line and no active-channel change")
    void refusedCreateRendersErrorOnly() {
        UUID playerId = UUID.randomUUID();
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        ChannelActionPacket request = trackedRequest(tracker, playerId, "myroom", ChannelAction.CREATE);
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, ChannelAction.CREATE, "myroom", "NC-400", "Channel name already taken");
        response.setRequestId(request.getRequestId());
        dispatcher.handle(response);

        assertThat(adapter.errorPlayerId).isEqualTo(playerId);
        assertThat(adapter.errorText).contains("NC-400");
        assertThat(adapter.createChannelId).isNull();
        assertThat(adapter.activeChannelId).isNull();
    }

    @Test
    @DisplayName("a refused INVITE (expired code) and a refused ACCEPT both use the generic error path")
    void refusedInviteAndAcceptUseGenericErrorPath() {
        UUID playerId = UUID.randomUUID();
        ChannelResponseTracker tracker = new ChannelResponseTracker();
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher = new ChannelResponseDispatcher(tracker, adapter);

        ChannelActionPacket invite = trackedRequest(tracker, playerId, "NC-1A2B", ChannelAction.INVITE);
        ChannelActionResponsePacket expired = new ChannelActionResponsePacket(
                false, ChannelAction.INVITE, "NC-1A2B", "NC-410", "Invitation has expired");
        expired.setRequestId(invite.getRequestId());
        dispatcher.handle(expired);

        ChannelActionPacket accept = trackedRequest(tracker, playerId, "A1B2C3", ChannelAction.ACCEPT);
        ChannelActionResponsePacket banned = new ChannelActionResponsePacket(
                false, ChannelAction.ACCEPT, "A1B2C3", "NC-403", "You are banned from this channel");
        banned.setRequestId(accept.getRequestId());
        dispatcher.handle(banned);

        assertThat(adapter.errorTexts).hasSize(2);
        assertThat(adapter.errorTexts.get(0)).contains("NC-410");
        assertThat(adapter.errorTexts.get(1)).contains("NC-403");
        assertThat(adapter.inviteCode).isNull();
        assertThat(adapter.acceptChannelId).isNull();
    }

    // ==================== target-side moderation notices (BAN/UNBAN) ====================

    /**
     * An adapter that never grew a BAN/UNBAN arm keeps its old surface: the
     * dispatcher must not deliver a notice it cannot render, otherwise the
     * "// MUTE" fall-through would announce a ban as a mute.
     */
    @Test
    @DisplayName("BAN notice is dropped for an adapter that only declares KICK/MUTE")
    void banNoticeDroppedWhenAdapterDoesNotSupportIt() {
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher =
                new ChannelResponseDispatcher(new ChannelResponseTracker(), adapter);

        dispatcher.handle(moderationResponse(ChannelAction.BAN, "global", "Bob"));

        assertThat(adapter.notice).isNull();
    }

    @Test
    @DisplayName("UNBAN notice is dropped for an adapter that only declares KICK/MUTE")
    void unbanNoticeDroppedWhenAdapterDoesNotSupportIt() {
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher =
                new ChannelResponseDispatcher(new ChannelResponseTracker(), adapter);

        dispatcher.handle(moderationResponse(ChannelAction.UNBAN, "global", "Bob"));

        assertThat(adapter.notice).isNull();
    }

    @Test
    @DisplayName("BAN notice reaches an adapter that declares it, carrying operator + duration")
    void banNoticeReachesSupportingAdapter() {
        WideAdapter adapter = new WideAdapter();
        ChannelResponseDispatcher dispatcher =
                new ChannelResponseDispatcher(new ChannelResponseTracker(), adapter);

        dispatcher.handle(moderationResponse(ChannelAction.BAN, "staff", "Bob"));

        assertThat(adapter.notice).isNotNull();
        assertThat(adapter.notice.getAction()).isEqualTo(ChannelAction.BAN);
        assertThat(adapter.notice.getChannelId()).isEqualTo("staff");
        assertThat(adapter.notice.getOperator()).isEqualTo("Mod");
    }

    @Test
    @DisplayName("UNBAN notice reaches an adapter that declares it")
    void unbanNoticeReachesSupportingAdapter() {
        WideAdapter adapter = new WideAdapter();
        ChannelResponseDispatcher dispatcher =
                new ChannelResponseDispatcher(new ChannelResponseTracker(), adapter);

        dispatcher.handle(moderationResponse(ChannelAction.UNBAN, "staff", "Bob"));

        assertThat(adapter.notice).isNotNull();
        assertThat(adapter.notice.getAction()).isEqualTo(ChannelAction.UNBAN);
        assertThat(adapter.notice.getChannelId()).isEqualTo("staff");
    }

    @Test
    @DisplayName("a global ban (blank channelId) names a scope instead of rendering an empty name")
    void globalBanSubstitutesChannelLabel() {
        WideAdapter adapter = new WideAdapter();
        ChannelResponseDispatcher dispatcher =
                new ChannelResponseDispatcher(new ChannelResponseTracker(), adapter);

        dispatcher.handle(moderationResponse(ChannelAction.BAN, "", "Bob"));

        assertThat(adapter.notice).isNotNull();
        assertThat(adapter.notice.getChannelId())
                .as("a blank channelId would read as 'banned from  by Mod'")
                .isNotBlank();
    }

    @Test
    @DisplayName("a KICK notice is still delivered to the legacy adapter")
    void kickNoticeStillDeliveredToLegacyAdapter() {
        RecordingAdapter adapter = new RecordingAdapter();
        ChannelResponseDispatcher dispatcher =
                new ChannelResponseDispatcher(new ChannelResponseTracker(), adapter);

        dispatcher.handle(moderationResponse(ChannelAction.KICK, "staff", "Bob"));

        assertThat(adapter.notice).isNotNull();
        assertThat(adapter.notice.getAction()).isEqualTo(ChannelAction.KICK);
    }

    @Test
    @DisplayName("a failed BAN response pushes no notice")
    void failedBanPushesNoNotice() {
        WideAdapter adapter = new WideAdapter();
        ChannelResponseDispatcher dispatcher =
                new ChannelResponseDispatcher(new ChannelResponseTracker(), adapter);

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, ChannelAction.BAN, "staff", "NC-403", "Permission denied");
        dispatcher.handle(response);

        assertThat(adapter.notice).isNull();
    }

    /** An uncorrelated successful moderation response carrying a targetId. */
    private static ChannelActionResponsePacket moderationResponse(ChannelAction action,
                                                                  String channelId,
                                                                  String targetName) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, action, channelId, "", "ok");
        response.addExtra("targetId", UUID.randomUUID().toString());
        response.addExtra("operatorName", "Mod");
        response.addExtra("duration", "600");
        return response;
    }

    private static ChannelActionPacket trackedRequest(ChannelResponseTracker tracker,
                                                       UUID playerId,
                                                       String channelId) {
        return trackedRequest(tracker, playerId, channelId, ChannelAction.LEAVE);
    }

    private static ChannelActionPacket trackedRequest(ChannelResponseTracker tracker,
                                                       UUID playerId,
                                                       String channelId,
                                                       ChannelAction action) {
        ChannelActionPacket request = new ChannelActionPacket(action, channelId);
        request.addExtra("playerId", playerId.toString());
        tracker.track(request);
        return request;
    }

    /** A successful CREATE response shaped like the backend's. */
    private static ChannelActionResponsePacket createResponseFor(ChannelActionPacket request,
                                                                 String channelId,
                                                                 String password,
                                                                 boolean generated,
                                                                 String displayName) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.CREATE, channelId, "", "Private channel created");
        response.setRequestId(request.getRequestId());
        response.addExtra("password", password);
        response.addExtra("passwordGenerated", String.valueOf(generated));
        response.addExtra("displayName", displayName);
        return response;
    }

    /** A successful INVITE response shaped like the backend's. */
    private static ChannelActionResponsePacket inviteResponseFor(ChannelActionPacket request,
                                                                 String channelId,
                                                                 String code,
                                                                 String expireTimeMillis) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.INVITE, channelId, "", "Invitation created");
        response.setRequestId(request.getRequestId());
        response.addExtra("code", code);
        if (expireTimeMillis != null) {
            response.addExtra("expireTime", expireTimeMillis);
        }
        return response;
    }

    /** A successful ACCEPT response shaped like the backend's. */
    private static ChannelActionResponsePacket acceptResponseFor(ChannelActionPacket request,
                                                                 String channelId) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.ACCEPT, channelId, "", "Invitation accepted");
        response.setRequestId(request.getRequestId());
        response.addExtra("code", "A1B2C3");
        return response;
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

    private static class RecordingAdapter implements ChannelResponseDispatcher.ChannelResponseAdapter {
        private UUID leavePlayerId;
        private String leftChannel;
        private UUID leaveStatusBarPlayerId;
        private UUID whoPlayerId;
        private String whoChannelId;
        private String whoDisplayName;
        private String whoMembers;
        private String whoCount;
        private UUID createPlayerId;
        private String createChannelId;
        private String createPassword;
        private boolean createGenerated;
        private String createDisplayName;
        private UUID invitePlayerId;
        private String inviteChannelId;
        private String inviteCode;
        private String inviteExpiresIn;
        private UUID acceptPlayerId;
        private String acceptChannelId;
        private String activeChannelId;
        private UUID errorPlayerId;
        private String errorText;
        private final java.util.List<String> errorTexts = new java.util.ArrayList<>();
        ChannelResponseDispatcher.KickMuteNotice notice;

        @Override
        public void setActiveChannel(UUID playerId, String channelId) {
            this.activeChannelId = channelId;
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
            this.errorPlayerId = playerId;
            this.errorText = text;
            this.errorTexts.add(text);
        }

        @Override
        public void sendCreateResult(UUID playerId, String channelId, String password,
                                     boolean generated, String displayName) {
            this.createPlayerId = playerId;
            this.createChannelId = channelId;
            this.createPassword = password;
            this.createGenerated = generated;
            this.createDisplayName = displayName;
        }

        @Override
        public void sendInviteResult(UUID playerId, String channelId, String code, String expiresIn) {
            this.invitePlayerId = playerId;
            this.inviteChannelId = channelId;
            this.inviteCode = code;
            this.inviteExpiresIn = expiresIn;
        }

        @Override
        public void sendAcceptResult(UUID playerId, String channelId) {
            this.acceptPlayerId = playerId;
            this.acceptChannelId = channelId;
        }

        @Override
        public void notifyKickMuteTarget(ChannelResponseDispatcher.KickMuteNotice notice) {
            this.notice = notice;
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

    /** An adapter that has ported the BAN/UNBAN arms and declares them. */
    private static final class WideAdapter extends RecordingAdapter {
        @Override
        public boolean supportsTargetNotice(ChannelAction action) {
            return action == ChannelAction.KICK || action == ChannelAction.MUTE
                    || action == ChannelAction.BAN || action == ChannelAction.UNBAN;
        }
    }
}
