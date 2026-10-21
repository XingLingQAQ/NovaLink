package com.nova.link.moderation;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Pushes the target-side KICK/MUTE notice to the game server that owns the
 * affected player (UX-DESIGN §5).
 *
 * <p>The wire mechanism is the existing one: a <em>successful</em>
 * {@link ChannelActionResponsePacket} carrying a {@code targetId} extra. A
 * platform plugin's {@code ChannelResponseDispatcher} turns that into the
 * title + action bar notice for whichever local player matches {@code targetId}
 * and ignores it everywhere else, so addressing the packet at the owning
 * server's connection is enough — the server fans it out locally.
 *
 * <p>Two backend paths need this:
 * <ul>
 *   <li>a client-issued moderation action, whose response goes to the
 *       <em>operator</em> while the target may sit on another server
 *       ({@code NovaLinkCore});</li>
 *   <li>console/REST moderation, which produces no client response at all
 *       ({@code ConsoleCommandHandler}).</li>
 * </ul>
 *
 * <p>Every step is best-effort: a target with no cached state, no owning
 * server, or a closed connection is silently skipped. Pushing a notice must
 * never turn a successful moderation into a failed command.
 */
public final class TargetModerationNotice {

    private static final Logger logger = LoggerFactory.getLogger(TargetModerationNotice.class);

    private TargetModerationNotice() {
        // utility class
    }

    /**
     * Sends a notice packet to the server connection that owns {@code targetId}.
     *
     * <p>Only {@link ChannelAction#KICK} and {@link ChannelAction#MUTE} render
     * anything client-side; other actions are forwarded as-is and produce no
     * notice until the clients grow a rendering path for them.
     *
     * @param networkHandler    connection registry (may be null → skipped)
     * @param playerStateManager player→server lookup (may be null → skipped)
     * @param action            the moderation action that succeeded
     * @param channelId         channel the moderation applies to
     * @param targetIdRaw       the affected player's UUID as a string
     * @param operatorName      display name to attribute the action to (may be blank)
     * @param durationSeconds   mute duration in seconds (may be blank/omitted)
     * @return true when a packet was written to a connection
     */
    public static boolean pushToTarget(ServerNetworkHandler networkHandler,
                                       PlayerStateManager playerStateManager,
                                       ChannelAction action,
                                       String channelId,
                                       String targetIdRaw,
                                       String operatorName,
                                       String durationSeconds) {
        UUID targetId = parseUuid(targetIdRaw);
        if (targetId == null || networkHandler == null || playerStateManager == null) {
            return false;
        }
        PlayerState targetState = playerStateManager.getPlayerState(targetId);
        if (targetState == null) {
            // Not cached: nobody is online under this UUID, so there is no
            // in-game client to tell. The moderation itself already applied.
            return false;
        }
        String targetClientId = targetState.getClientId();
        if (targetClientId == null || targetClientId.isEmpty()) {
            return false;
        }
        ClientConnection targetConnection = networkHandler.findByClientId(targetClientId);
        if (targetConnection == null || !targetConnection.isActive()
                || !targetConnection.isAuthenticated()) {
            return false;
        }

        ChannelActionResponsePacket notice = new ChannelActionResponsePacket(
                true, action, channelId != null ? channelId : "", "", "");
        notice.addExtra("targetId", targetId.toString());
        if (operatorName != null && !operatorName.isEmpty()) {
            notice.addExtra("operatorName", operatorName);
        }
        if (durationSeconds != null && !durationSeconds.isEmpty()) {
            notice.addExtra("duration", durationSeconds);
        }
        try {
            targetConnection.sendPacket(notice);
        } catch (RuntimeException e) {
            logger.debug("Failed to push {} notice to target {} on client {}: {}",
                    action, targetId, targetClientId, e.getMessage());
            return false;
        }
        logger.debug("Pushed {} notice to target {} on client {}", action, targetId, targetClientId);
        return true;
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
