package com.nova.chat.client.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks in-flight {@link ChannelActionPacket}s so the asynchronous
 * {@code ChannelActionResponsePacket} arriving later can be correlated back to
 * the originating player.
 *
 * <p>The shared {@link com.nova.chat.client.command.ChannelCommandService} stamps
 * each outgoing {@link ChannelActionPacket} with a {@code playerId} extra, and
 * the backend echoes the packet's {@code requestId} onto the response. Platforms
 * that do not run the Bukkit synchronous-wait path therefore need a local
 * {@code requestId -> playerId} map to render actionable error text from the
 * response's {@code errorCode} once it arrives.
 *
 * <p>Thread-safe. Entries are best-effort cleaned up on
 * {@link #consume(UUID)}; platforms may periodically call
 * {@link #cleanupExpired(long)} to evict stale entries whose response never
 * came. This class is intentionally tiny and holds no futures — it only carries
 * the correlation context, not a wait handle.
 *
 * <p>Architecture B: plugin-only. Never imported by {@code novalink-core}.
 */
public final class ChannelResponseTracker {

    private final ConcurrentMap<UUID, PendingChannelAction> pending = new ConcurrentHashMap<>();

    /** Default TTL; matches the Bukkit pending-request window. */
    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    /**
     * Records an outgoing channel action for later response correlation.
     *
     * @param packet the packet about to be sent; must carry a {@code playerId} extra
     *               (set by {@link com.nova.chat.client.command.ChannelCommandService})
     */
    public void track(ChannelActionPacket packet) {
        if (packet == null) {
            return;
        }
        UUID requestId = packet.getRequestId();
        if (requestId == null) {
            return;
        }
        UUID playerId = extractUuid(packet.getExtra("playerId"));
        String channelId = packet.getChannelId();
        ChannelAction action = packet.getAction();
        // BUG-H2: the previous active channel is stamped on the packet by
        // ChannelCommandService.join so the response handler can roll back the
        // optimistic active-channel switch when the backend rejects the JOIN.
        String previousChannel = packet.getExtra("previousChannel");
        pending.put(requestId, new PendingChannelAction(
                playerId, channelId, action, previousChannel, System.currentTimeMillis()));
    }

    /**
     * Removes and returns the pending context for {@code requestId}, if present.
     *
     * @param requestId the response's request id
     * @return the pending context, or {@code null} if none tracked (already
     *         consumed, expired, or the action was not sent through the tracker)
     */
    public PendingChannelAction consume(UUID requestId) {
        if (requestId == null) {
            return null;
        }
        return pending.remove(requestId);
    }

    /**
     * Evicts entries older than {@code timeoutMs}. Called opportunistically on
     * {@link #track(ChannelActionPacket)} to bound map growth.
     *
     * @param timeoutMs max age in milliseconds
     * @return number of entries removed
     */
    public int cleanupExpired(long timeoutMs) {
        long now = System.currentTimeMillis();
        int[] removed = {0};
        pending.entrySet().removeIf(e -> now - e.getValue().createdAtMs > timeoutMs && ++removed[0] > Integer.MIN_VALUE);
        return removed[0];
    }

    /** Convenience overload using the default TTL. */
    public int cleanupExpired() {
        return cleanupExpired(DEFAULT_TIMEOUT_MS);
    }

    public int size() {
        return pending.size();
    }

    private static UUID extractUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Immutable correlation context for one in-flight channel action.
     */
    public static final class PendingChannelAction {
        private final UUID playerId;
        private final String channelId;
        private final ChannelAction action;
        private final String previousChannel;
        private final long createdAtMs;

        private PendingChannelAction(UUID playerId, String channelId, ChannelAction action,
                                     String previousChannel, long createdAtMs) {
            this.playerId = playerId;
            this.channelId = channelId;
            this.action = action;
            this.previousChannel = previousChannel;
            this.createdAtMs = createdAtMs;
        }

        /** @return the originating player id, or {@code null} if not provided */
        public UUID getPlayerId() {
            return playerId;
        }

        /** @return the target channel id, possibly null/blank */
        public String getChannelId() {
            return channelId;
        }

        /** @return the action, or {@code null} */
        public ChannelAction getAction() {
            return action;
        }

        /**
         * @return the active channel the player had before the optimistic
         *         change (set for JOIN/ACCEPT by {@code ChannelCommandService}),
         *         or {@code null} if the originating command did not stamp one.
         *         Used by response handlers to roll back the optimistic
         *         active-channel switch when the backend rejects the action.
         */
        public String getPreviousChannel() {
            return previousChannel;
        }

        /** @return wall-clock creation time in ms */
        public long getCreatedAtMs() {
            return createdAtMs;
        }
    }
}
