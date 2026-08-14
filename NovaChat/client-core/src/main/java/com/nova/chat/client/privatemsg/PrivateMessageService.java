package com.nova.chat.client.privatemsg;

import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

/**
 * Shared core of the cross-server private-message feature ({@code /nc msg},
 * {@code /nc r}).
 *
 * <p><b>Send side:</b> {@link #buildPacket} constructs the C→S form of the
 * {@link PrivateMessagePacket} (nil targetId — the backend resolves the target
 * by name and completes the packet). Platforms transmit it through their own
 * NetworkClient.
 *
 * <p><b>Receive side:</b> the backend delivers the completed packet to the
 * target's client <em>and</em> echoes it to the sender's client.
 * {@link #handleIncoming} decides, per local online player, which role(s) to
 * render:
 * <ul>
 *   <li>local player == senderId → sender echo ("你悄悄对 {target} 说: {msg}");</li>
 *   <li>local player == targetId → received line ("{sender} 悄悄对你说: {msg}"),
 *       silently dropped when the receiver has the sender on their
 *       {@link IgnoreListService ignore list}.</li>
 * </ul>
 * Lines are resolved per player locale through {@link I18n} with {@code &}
 * color codes (PlayerMessages style); platforms colorize and send them on
 * their own threads.
 *
 * <p><b>Reply tracking:</b> the most recent private-message partner is
 * remembered per player — updated on both the sender echo and the received
 * delivery (delivery-confirmed, so a failed {@code /msg} never pollutes the
 * reply target). Backed by a {@link ConcurrentHashMap}; platforms call
 * {@link #onPlayerQuit} from their quit listeners to clean up.
 *
 * <p><b>Error side:</b> backend rejections arrive as unsolicited
 * {@link ChannelActionResponsePacket} failures carrying
 * {@code extra.reason=private_message} (the shared ChannelResponseDispatcher
 * has no pending context for them and would drop them silently). Platforms
 * check {@link #isPrivateMessageError} before delegating to the dispatcher and
 * render {@link #renderError}'s outcome instead.
 *
 * <p>Architecture B: plugin-only. Never imported by {@code novalink-core}.
 */
public final class PrivateMessageService {

    /** extra.reason value the backend stamps on private-message failures. */
    public static final String ERROR_REASON = "private_message";

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    /** playerId → name of the most recent private-message partner. */
    private final ConcurrentMap<UUID, String> replyTargets = new ConcurrentHashMap<>();

    /** How a {@link Delivery} line should be understood by the platform. */
    public enum Role {
        /** The local player is the sender: render the echo/confirmation line. */
        ECHO,
        /** The local player is the target: render the received line. */
        RECEIVED,
        /** The local player's outgoing message was rejected by the backend. */
        ERROR
    }

    /** One localized line to send to one local player. */
    public static final class Delivery {
        private final UUID playerId;
        private final Role role;
        private final String line;

        Delivery(UUID playerId, Role role, String line) {
            this.playerId = playerId;
            this.role = role;
            this.line = line;
        }

        /** The local player to render to. */
        public UUID getPlayerId() {
            return playerId;
        }

        /** The render role of this line. */
        public Role getRole() {
            return role;
        }

        /** The localized, {@code &}-colored line (platform colorizes/sends). */
        public String getLine() {
            return line;
        }
    }

    // ==================== send side ====================

    /**
     * Builds the C→S form of a private message: nil {@code targetId} (the
     * backend resolves the target by name) and a provisional local timestamp
     * (the backend overwrites it with the authoritative one).
     *
     * @param senderId       the sending player's UUID
     * @param senderName     the sending player's name
     * @param senderClientId this client/server's id (may be null → empty)
     * @param targetName     the target name as typed
     * @param content        the message content
     * @return the packet ready to transmit
     */
    public static PrivateMessagePacket buildPacket(UUID senderId, String senderName,
                                                   String senderClientId, String targetName,
                                                   String content) {
        return new PrivateMessagePacket(senderId, senderName,
                senderClientId != null ? senderClientId : "",
                targetName, NIL_UUID, content, System.currentTimeMillis());
    }

    // ==================== receive side ====================

    /**
     * Handles a completed (S→C) private message: decides which local players
     * get which localized line and updates reply tracking for each rendered
     * role. The receiver's line is suppressed (and their reply target left
     * untouched) when the sender is on their ignore list.
     *
     * @param packet        the completed packet from the backend
     * @param isLocalOnline whether a player UUID is online on this server
     * @param ignoreService nullable; receiver-side ignore filter
     * @return at most two deliveries (sender echo and/or received line)
     */
    public List<Delivery> handleIncoming(PrivateMessagePacket packet,
                                         Predicate<UUID> isLocalOnline,
                                         IgnoreListService ignoreService) {
        List<Delivery> out = new ArrayList<>(2);
        UUID senderId = packet.getSenderId();
        UUID targetId = packet.getTargetId();

        if (senderId != null && isLocalOnline.test(senderId)) {
            recordConversation(senderId, packet.getTargetName());
            out.add(new Delivery(senderId, Role.ECHO,
                    I18n.tr(senderId, "chat.msg.sent",
                            safe(packet.getTargetName()), safe(packet.getContent()))));
        }

        if (targetId != null && !targetId.equals(senderId) && isLocalOnline.test(targetId)) {
            if (ignoreService != null && ignoreService.isIgnored(targetId, packet.getSenderName())) {
                return out; // silently dropped for the receiver
            }
            recordConversation(targetId, packet.getSenderName());
            out.add(new Delivery(targetId, Role.RECEIVED,
                    I18n.tr(targetId, "chat.msg.received",
                            safe(packet.getSenderName()), safe(packet.getContent()))));
        }
        return out;
    }

    // ==================== error side ====================

    /**
     * Whether this response is an unsolicited private-message failure that
     * must be routed to {@link #renderError} instead of the shared
     * ChannelResponseDispatcher.
     *
     * @param packet the incoming channel-action response
     * @return true when {@code extra.reason == private_message} on a failure
     */
    public static boolean isPrivateMessageError(ChannelActionResponsePacket packet) {
        return packet != null && !packet.isSuccess()
                && ERROR_REASON.equals(packet.getExtra("reason"));
    }

    /**
     * Renders a backend private-message rejection for the originating sender.
     * The backend stamps {@code extra.senderId} (who to notify),
     * {@code extra.targetName} and {@code extra.detail}
     * (disabled/muted/not_online/self/rate_limited) so the line is resolved in
     * the sender's locale client-side.
     *
     * @param packet        the failure response ({@link #isPrivateMessageError} must be true)
     * @param isLocalOnline whether a player UUID is online on this server
     * @return the error delivery, or empty when the sender is not local/resolvable
     */
    public Optional<Delivery> renderError(ChannelActionResponsePacket packet,
                                          Predicate<UUID> isLocalOnline) {
        UUID senderId = parseUuid(packet.getExtra("senderId"));
        if (senderId == null || !isLocalOnline.test(senderId)) {
            return Optional.empty();
        }
        String targetName = safe(packet.getExtra("targetName"));
        String detail = packet.getExtra("detail");
        String line;
        if ("not_online".equals(detail)) {
            line = I18n.tr(senderId, "chat.msg.offline", targetName);
        } else if ("self".equals(detail)) {
            line = I18n.tr(senderId, "chat.msg.self");
        } else if ("disabled".equals(detail)) {
            line = I18n.tr(senderId, "chat.msg.disabled");
        } else if ("muted".equals(detail)) {
            line = I18n.tr(senderId, "chat.msg.muted");
        } else if ("rate_limited".equals(detail)) {
            line = I18n.tr(senderId, "chat.msg.rate_limited");
        } else {
            line = ErrorMessageFormatter.format(packet.getErrorCode());
        }
        return Optional.of(new Delivery(senderId, Role.ERROR, line));
    }

    // ==================== reply tracking ====================

    /**
     * Records the most recent private-message partner for a player. Called
     * internally on echo/delivery; exposed for tests.
     *
     * @param playerId    the local player
     * @param partnerName the conversation partner's name
     */
    public void recordConversation(UUID playerId, String partnerName) {
        if (playerId == null || partnerName == null || partnerName.isBlank()) {
            return;
        }
        replyTargets.put(playerId, partnerName);
    }

    /**
     * The player's current {@code /nc r} target: the name of the most recent
     * private-message partner (sent or received).
     *
     * @param playerId the local player
     * @return the partner name, or empty when the player has no conversation yet
     */
    public Optional<String> getReplyTarget(UUID playerId) {
        return playerId == null ? Optional.empty()
                : Optional.ofNullable(replyTargets.get(playerId));
    }

    /**
     * Logout cleanup hook: platforms call this from their player-quit
     * listeners so reply targets do not accumulate for offline players.
     *
     * @param playerId the player who left this server
     */
    public void onPlayerQuit(UUID playerId) {
        if (playerId != null) {
            replyTargets.remove(playerId);
        }
    }

    // ==================== helpers ====================

    private static String safe(String s) {
        return s != null ? s : "";
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
