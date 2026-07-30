package com.nova.chat.client.network;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.format.DurationFormatter;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;

import java.util.UUID;

/**
 * Shared skeleton that correlates an asynchronous
 * {@link ChannelActionResponsePacket} back to its originating player (via the
 * {@link ChannelResponseTracker} the backend echoes the request id) and routes
 * the outcome to platform-supplied callbacks.
 *
 * <p>This removes the per-platform duplication of the "consume pending → judge
 * success/failure → route" boilerplate documented as DUP-3, while preserving
 * the BUG-H1 fix (operator/duration are read from the pending context captured
 * at send time, never from the response extras — the backend never echoes them)
 * and the BUG-H2 fix (JOIN rejected by the backend rolls back the optimistic
 * active-channel switch made by {@code ChannelCommandService.join}).
 *
 * <p>The dispatcher runs synchronously on whatever thread the packet arrived
 * on (typically the Netty event loop). Every {@link ChannelResponseAdapter}
 * callback is responsible for its own thread hop (e.g. Folia region thread,
 * Sponge/Nukkit/PNX main thread) and platform rendering (Adventure
 * {@code Component}, Bungee {@code BaseComponent}, legacy {@code String}),
 * exactly as the historical handlers did. The dispatcher only owns the shared
 * control flow and the shared text resolution.
 *
 * <p>Architecture B: plugin-only. Never imported by {@code novalink-core}.
 */
public final class ChannelResponseDispatcher {

    private final ChannelResponseTracker tracker;
    private final ChannelResponseAdapter adapter;

    /**
     * @param tracker  the shared in-flight action tracker
     * @param adapter  platform-supplied rendering / state callbacks
     */
    public ChannelResponseDispatcher(ChannelResponseTracker tracker, ChannelResponseAdapter adapter) {
        this.tracker = tracker;
        this.adapter = adapter;
    }

    /**
     * Handles a channel-action response by correlating it to its pending
     * request and routing success/failure to the platform adapter. Safe to call
     * from any thread; the adapter handles thread hops.
     *
     * @param packet the incoming response (backend echoes the request id)
     */
    public void handle(ChannelActionResponsePacket packet) {
        ChannelResponseTracker.PendingChannelAction pending = tracker.consume(packet.getRequestId());

        // UX-DESIGN §5: KICK/MUTE target-side notification. On success the
        // affected player is notified regardless of whether we have a local
        // pending context (backend pushes cross-server kicks/mutes carry no
        // local pending). notifyKickMuteTarget resolves the BUG-H1 fallbacks
        // internally.
        if (packet.isSuccess() && (packet.getAction() == ChannelAction.KICK
                || packet.getAction() == ChannelAction.MUTE)) {
            KickMuteNotice notice = resolveKickMuteNotice(packet, pending);
            if (notice != null) {
                adapter.notifyKickMuteTarget(notice);
            }
        }

        if (pending == null || pending.getPlayerId() == null) {
            // No correlation context (e.g. backend push, console-issued, or already
            // expired) — nothing to render for the originating player.
            return;
        }

        UUID playerId = pending.getPlayerId();

        if (packet.isSuccess()) {
            if (packet.getAction() == ChannelAction.JOIN) {
                String confirmedChannel = (packet.getChannelId() != null && !packet.getChannelId().isEmpty())
                        ? packet.getChannelId()
                        : pending.getChannelId();
                if (packet.getChannelId() != null && !packet.getChannelId().isEmpty()) {
                    adapter.setActiveChannel(playerId, packet.getChannelId());
                }
                if (confirmedChannel != null && !confirmedChannel.isEmpty()) {
                    adapter.sendJoinSuccess(playerId, confirmedChannel);
                }
                adapter.sendJoinChannelStatusBar(playerId, confirmedChannel);
            } else if (packet.getAction() == ChannelAction.LEAVE) {
                // §7: after a successful leave the active channel is the default;
                // the adapter resolves the current channel on its own thread (the
                // active-channel read is state-sensitive on region/main-thread
                // platforms) and flashes the status bar.
                adapter.sendLeaveChannelStatusBar(playerId);
            }
            return;
        }

        // Failure: BUG-H2 — roll back the optimistic active-channel switch before
        // the network-down early-return so a true rejection with an empty/SERVICE
        // code still restores the active channel.
        if (packet.getAction() == ChannelAction.JOIN) {
            String previousChannel = pending.getPreviousChannel();
            if (previousChannel != null && !previousChannel.isEmpty()) {
                adapter.rollbackJoin(playerId, pending.getChannelId(), previousChannel);
            }
        }

        String code = packet.getErrorCode();
        if (code == null || code.isEmpty() || ErrorCode.SERVICE_UNAVAILABLE.getCode().equals(code)) {
            // Network-down is already reported at command send time; skip double prompt.
            return;
        }
        adapter.sendErrorMessage(playerId, ErrorMessageFormatter.format(code));
    }

    /**
     * Resolves the target-side KICK/MUTE notice text from the pending context
     * (BUG-H1: prefer values captured at send time; fall back to the response
     * extras, which the backend does not write either, so the "管理员"/"一段时间"
     * fallbacks intentionally apply for backend pushes with no local pending).
     *
     * @return the notice, or {@code null} if the response carries no usable
     *         {@code targetId} extra
     */
    private KickMuteNotice resolveKickMuteNotice(ChannelActionResponsePacket packet,
                                                 ChannelResponseTracker.PendingChannelAction pending) {
        String targetIdRaw = packet.getExtra("targetId");
        if (targetIdRaw == null || targetIdRaw.isEmpty()) {
            return null;
        }
        UUID targetId;
        try {
            targetId = UUID.fromString(targetIdRaw);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String operator = pending != null ? pending.getOperatorName() : null;
        if (operator == null || operator.isEmpty()) {
            operator = packet.getExtra("operatorName");
        }
        if (operator == null || operator.isEmpty()) {
            operator = "管理员";
        }
        String durationSeconds = pending != null ? pending.getDurationSeconds() : null;
        if (durationSeconds == null || durationSeconds.isEmpty()) {
            durationSeconds = packet.getExtra("duration");
        }
        String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
        return new KickMuteNotice(
                targetId,
                packet.getAction(),
                channelId,
                operator,
                DurationFormatter.formatSeconds(durationSeconds));
    }

    /**
     * Platform-supplied callbacks the dispatcher drives. Every method owns its
     * own thread hop (region/main thread) and platform rendering; the dispatcher
     * calls them synchronously from the network thread.
     *
     * <p>The {@code playerId} passed to every method is the non-null id from the
     * pending context (validated before any callback fires), except
     * {@link #notifyKickMuteTarget(KickMuteNotice)} which carries its own target id.
     */
    public interface ChannelResponseAdapter {

        /**
         * Sets the player's active channel after a successful JOIN whose
         * response echoed a non-blank {@code channelId}. The platform must hop
         * to the correct thread before mutating state (the shared
         * {@code PlayerChannelState} joined-channel set is not thread-safe) and
         * may create state lazily.
         *
         * @param playerId  the originating player
         * @param channelId the backend-confirmed channel id
         */
        void setActiveChannel(UUID playerId, String channelId);

        /**
         * Rolls back the optimistic active-channel switch when a JOIN is
         * rejected (BUG-H2). Only called when {@code previousChannel} is
         * non-blank. The platform must hop to the correct thread and only roll
         * back if the optimistic channel is still set (i.e. the player has not
         * since switched manually).
         *
         * @param playerId        the originating player
         * @param attemptedChannel the channel the optimistic switch targeted
         * @param previousChannel the active channel to restore
         */
        void rollbackJoin(UUID playerId, String attemptedChannel, String previousChannel);

        /**
         * Sends the "已加入频道 X" confirmation after the backend accepts a
         * JOIN (§7). Called with a non-blank {@code channelId}; the platform
         * hops to the correct thread before sending.
         */
        void sendJoinSuccess(UUID playerId, String channelId);

        /**
         * Flashes the §7 channel status action bar after a successful JOIN.
         * Proxies (velocity/bungee) with no reliable action-bar API implement
         * this as a no-op. {@code channelId} is non-blank; the platform hops to
         * the correct thread before sending.
         */
        void sendJoinChannelStatusBar(UUID playerId, String channelId);

        /**
         * Flashes the §7 channel status action bar after a successful LEAVE.
         * The platform resolves the player's current active channel on its own
         * thread (the read is state-sensitive on region/main-thread platforms)
         * and renders the bar; proxies implement this as a no-op.
         */
        void sendLeaveChannelStatusBar(UUID playerId);

        /**
         * Sends a formatted error message (shared {@link ErrorMessageFormatter}
         * text) to the originating player. NC-503 and empty codes are already
         * filtered by the dispatcher and never reach this callback. The platform
         * hops to the correct thread before sending.
         */
        void sendErrorMessage(UUID playerId, String text);

        /**
         * Renders the target-side KICK/MUTE notice (UX-DESIGN §5). The text is
         * fully resolved by the dispatcher; the platform only renders it via its
         * own title / action-bar / chat API and thread hop. The target may be
         * offline — implementations must no-op in that case.
         */
        void notifyKickMuteTarget(KickMuteNotice notice);
    }

    /**
     * Fully-resolved target-side notice for a KICK or MUTE. All text uses
     * {@code &}-style color codes suitable for the legacy-format platforms;
     * Adventure platforms strip/translate them as needed.
     */
    public static final class KickMuteNotice {
        private final UUID targetId;
        private final ChannelAction action;
        private final String channelId;
        private final String operator;
        private final String durationText;

        public KickMuteNotice(UUID targetId, ChannelAction action, String channelId,
                              String operator, String durationText) {
            this.targetId = targetId;
            this.action = action;
            this.channelId = channelId;
            this.operator = operator;
            this.durationText = durationText;
        }

        public UUID getTargetId() {
            return targetId;
        }

        public ChannelAction getAction() {
            return action;
        }

        public String getChannelId() {
            return channelId;
        }

        public String getOperator() {
            return operator;
        }

        public String getDurationText() {
            return durationText;
        }
    }
}
