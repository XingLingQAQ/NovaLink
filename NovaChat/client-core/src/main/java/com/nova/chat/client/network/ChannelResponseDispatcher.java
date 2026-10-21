package com.nova.chat.client.network;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.format.DurationFormatter;
import com.nova.chat.client.i18n.I18n;
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

        // UX-DESIGN §5: KICK/MUTE/BAN/UNBAN target-side notification. On success
        // the affected player is notified regardless of whether we have a local
        // pending context (backend pushes cross-server moderation carry no local
        // pending). notifyKickMuteTarget resolves the BUG-H1 fallbacks
        // internally.
        //
        // The capability gate is per-adapter, not global: an adapter that has
        // not grown a BAN/UNBAN render arm would otherwise fall through to its
        // "// MUTE" branch and render a wrong (muted) notice for a ban. Adapters
        // opt in as they are ported, exactly like sendModerationSuccess.
        if (packet.isSuccess() && adapter.supportsTargetNotice(packet.getAction())) {
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
                String leftChannel = (packet.getChannelId() != null && !packet.getChannelId().isEmpty())
                        ? packet.getChannelId()
                        : pending.getChannelId();
                if (leftChannel != null && !leftChannel.isEmpty()) {
                    adapter.sendLeaveSuccess(playerId, leftChannel);
                }
                // §7: after a successful leave the active channel is the default;
                // the adapter resolves the current channel on its own thread (the
                // active-channel read is state-sensitive on region/main-thread
                // platforms) and flashes the status bar.
                adapter.sendLeaveChannelStatusBar(playerId);
            } else if (packet.getAction() == ChannelAction.WHO) {
                // /nc who result: the backend packed members / memberCount /
                // displayName into the response extras. Route the formatted list
                // to the requesting player (playerId from the pending context).
                String channelId = (packet.getChannelId() != null && !packet.getChannelId().isEmpty())
                        ? packet.getChannelId()
                        : pending.getChannelId();
                adapter.sendWhoResult(
                        playerId,
                        channelId,
                        packet.getExtra("displayName"),
                        packet.getExtra("members"),
                        packet.getExtra("memberCount"));
            } else if (isModerationAction(packet.getAction())) {
                // Moderation had no success path at all: /nc mute|kick|ban|unban
                // printed their optimistic "...ing" line and then went silent
                // even when the authority accepted the action. The adapter
                // method is a no-op by default, so a platform that has not opted
                // in keeps exactly the behaviour it had before.
                adapter.sendModerationSuccess(playerId, moderationSuccessText(playerId, packet.getAction()));
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
     * @param action a channel action, possibly null
     * @return whether {@code action} is one of the operator-issued moderation
     *         actions that report their outcome through
     *         {@link ChannelResponseAdapter#sendModerationSuccess}
     */
    private static boolean isModerationAction(ChannelAction action) {
        return action == ChannelAction.KICK
                || action == ChannelAction.MUTE
                || action == ChannelAction.UNMUTE
                || action == ChannelAction.BAN
                || action == ChannelAction.UNBAN;
    }

    /**
     * Resolves the operator-facing confirmation for a successful moderation
     * action.
     *
     * <p>Copy comes from the shared {@code chat.action.*_processed} keys so the
     * wording matches the pre-DUP-3 per-platform handlers. BAN / UNBAN have no
     * key of their own (they never had one), so they get the generic acceptance
     * line. Colors are the platform's job.
     *
     * @param playerId the originating sender (for their locale; may be null)
     * @param action   the moderation action that succeeded
     * @return the localized confirmation line
     */
    private static String moderationSuccessText(UUID playerId, ChannelAction action) {
        if (action == null) {
            return I18n.tr(playerId, "chat.action.success");
        }
        switch (action) {
            case KICK:
                return I18n.tr(playerId, "chat.action.kick_processed");
            case MUTE:
                return I18n.tr(playerId, "chat.action.mute_processed");
            case UNMUTE:
                return I18n.tr(playerId, "chat.action.unmute_processed");
            default:
                return I18n.tr(playerId, "chat.action.success");
        }
    }

    /**
     * Resolves the target-side moderation notice text from the pending context
     * (BUG-H1: prefer values captured at send time; fall back to the response
     * extras, which the backend does not write either, so the "管理员"/"一段时间"
     * fallbacks intentionally apply for backend pushes with no local pending).
     *
     * <p>Covers KICK / MUTE / BAN / UNBAN. A global ban carries a blank
     * channelId on the wire; the adapters substitute a "(global)" label.
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
            operator = I18n.tr("notice.operator.fallback");
        }
        String durationSeconds = pending != null ? pending.getDurationSeconds() : null;
        if (durationSeconds == null || durationSeconds.isEmpty()) {
            durationSeconds = packet.getExtra("duration");
        }
        String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
        // A global ban encodes "no channel" as a blank channelId. Substituting
        // the label here keeps every adapter's copy identical. The target's own
        // locale is used so the label matches the rest of the notice.
        if (channelId.isEmpty() && packet.getAction() == ChannelAction.BAN) {
            channelId = I18n.tr(targetId, "notice.channel.global");
        }
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
         * Sends the successful LEAVE confirmation. Called with the non-blank
         * channel that was left. The platform resolves its configured default
         * channel and renders {@code PlayerMessages.left(channel, default)} via
         * its normal formatter on the correct thread.
         */
        void sendLeaveSuccess(UUID playerId, String channelId);

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
         * Renders the asynchronous {@code /nc who} result (UX-DESIGN §8.2) to
         * the requesting player. The dispatcher resolves the formatted text via
         * {@link com.nova.chat.client.command.WhoCommandService#formatMemberList}
         * is intentionally <em>not</em> done here so each platform can format in
         * its own locale view and color-code style; the adapter receives the raw
         * backend extras and owns rendering. The platform hops to the correct
         * thread before sending. {@code channelId} is non-blank.
         *
         * @param playerId    the requesting player
         * @param channelId   the channel that was queried
         * @param displayName channel display name (may be blank → use channelId)
         * @param membersCsv  comma-separated member names (may be blank when empty)
         * @param memberCount online member count string (may be blank → 0)
         */
        void sendWhoResult(UUID playerId, String channelId, String displayName,
                           String membersCsv, String memberCount);

        /**
         * Renders the operator's confirmation for a successful moderation action
         * (KICK / MUTE / UNMUTE / BAN / UNBAN). The dispatcher resolves the
         * localized text; the platform only colourises it, hops to its own
         * thread, and sends it to {@code playerId}.
         *
         * <p>Default is a no-op: an adapter that does not override this keeps
         * exactly the behaviour it had before the method existed (silence on
         * those successes). Opt in per platform rather than changing all seven
         * at once.
         *
         * @param playerId the originating sender; non-null (validated by the dispatcher)
         * @param text     the resolved confirmation line, may contain {@code &} codes
         */
        default void sendModerationSuccess(UUID playerId, String text) {
            // no-op by default
        }

        /**
         * Renders the target-side moderation notice (UX-DESIGN §5). The text is
         * fully resolved by the dispatcher; the platform only renders it via its
         * own title / action-bar / chat API and thread hop. The target may be
         * offline — implementations must no-op in that case.
         *
         * <p>{@code notice.getAction()} is KICK, MUTE, BAN or UNBAN. Only the
         * actions this adapter declares in
         * {@link #supportsTargetNotice(ChannelAction)} are delivered, so an
         * implementation that has not ported a given arm never has to guess.
         */
        void notifyKickMuteTarget(KickMuteNotice notice);

        /**
         * Declares which moderation actions this platform can render a
         * target-side notice for. The dispatcher drops a notice whose action is
         * not declared, so a platform without a BAN arm keeps its pre-existing
         * silence instead of falling through to the MUTE copy.
         *
         * <p>Default is KICK + MUTE: every adapter predating the BAN/UNBAN arms
         * keeps exactly the behaviour it had. Override and widen once the extra
         * arms are implemented.
         *
         * @param action the action a successful response carries (may be null)
         * @return whether this adapter renders a notice for {@code action}
         */
        default boolean supportsTargetNotice(ChannelAction action) {
            return action == ChannelAction.KICK || action == ChannelAction.MUTE;
        }
    }

    /**
     * Fully-resolved target-side notice for a KICK, MUTE, BAN or UNBAN.
     *
     * <p>Fields the action does not use carry a harmless value: a global ban
     * has a blank {@code channelId} and an UNBAN has an unknown duration. The
     * Adapter renders only the fields its arm needs.
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
