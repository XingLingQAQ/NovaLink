package com.nova.link.network;

import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns every KeepAlive the backend emits or answers.
 *
 * <p>An inbound KeepAlive has two possible meanings and the protocol carries no
 * direction bit:</p>
 * <ul>
 *   <li>a client-initiated ping (Bedrock clients heartbeat every 15s) — echo
 *       it back so the client can measure round-trip latency;</li>
 *   <li>the echo of a server-initiated ping (Java clients answer every
 *       KeepAlive unconditionally) — record latency and send <em>nothing</em>,
 *       otherwise the two sides ping-pong forever.</li>
 * </ul>
 *
 * <p>Recognition uses three independent criteria so a client that loses or
 * rewrites part of the packet cannot re-open the loop:</p>
 * <ol>
 *   <li>the requestId equals the id of a KeepAlive this connection sent
 *       recently (pings and answers both count);</li>
 *   <li>the requestId equals the outstanding server ping's id (value
 *       equality — the decoder rebuilds the UUID instance);</li>
 *   <li>the echoed timestamp equals the outstanding server ping's timestamp.</li>
 * </ol>
 *
 * <p>Anything else is a genuine client ping and is answered, subject to the
 * per-connection {@link KeepAliveBudget}. The budget is the second line of
 * defence: even if a future client reintroduces a loop, the backend stops
 * amplifying it after a couple of packets per second.</p>
 */
public final class KeepAliveHandler implements PacketHandler<KeepAlivePacket> {

    private static final Logger logger = LoggerFactory.getLogger(KeepAliveHandler.class);

    /** Minimum interval between repeated budget warnings per connection. */
    private static final long WARN_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** Per-connection warn throttle: connectionId -> last warn nanos. */
    private final ConcurrentHashMap<String, AtomicLong> lastWarn = new ConcurrentHashMap<>();

    /**
     * Emits a server-initiated ping for a write-idle connection.
     *
     * <p>Returns the packet the caller must write, or {@code null} when the
     * connection exceeded its KeepAlive budget (the ping is dropped, not sent).</p>
     *
     * @param connection the idle connection
     * @param nowMillis  the ping timestamp
     * @return the packet to write, or {@code null} when suppressed
     */
    public KeepAlivePacket pingIdleConnection(ClientConnection connection, long nowMillis) {
        if (!connection.getKeepAliveBudget().tryAcquire()) {
            warnThrottled(connection, "suppressed server-initiated KeepAlive");
            return null;
        }
        KeepAlivePacket ping = new KeepAlivePacket(nowMillis);
        connection.setPendingKeepAlive(ping.getRequestId(), ping.getTimestamp());
        connection.rememberSentKeepAlive(ping.getRequestId());
        return ping;
    }

    @Override
    public void handle(ClientConnection connection, KeepAlivePacket packet) {
        long latency = packet.getLatency();
        if (latency >= 0) {
            connection.setPing(latency);
            connection.setLastPingAt(System.currentTimeMillis());
        }

        // 1) Echo of a KeepAlive we sent: liveness confirmed, nothing to send.
        //    Both checks run (no short-circuit) so a matching pending slot is
        //    always cleared, not left stale behind the recent-id memory.
        boolean pendingEcho = connection.consumePendingKeepAliveEcho(
                packet.getRequestId(), packet.getTimestamp());
        if (pendingEcho || connection.wasKeepAliveSentByUs(packet.getRequestId())) {
            return;
        }

        // 2) Client-initiated ping: answer it, rate-limited. Every outbound
        //    ping/reply is charged to the same bucket, so a loop that slips
        //    past the recognition above cannot amplify.
        if (!connection.getKeepAliveBudget().tryAcquire()) {
            warnThrottled(connection, "suppressed KeepAlive reply");
            return;
        }
        KeepAlivePacket response = new KeepAlivePacket(packet.getTimestamp());
        response.setRequestId(packet.getRequestId());
        connection.rememberSentKeepAlive(response.getRequestId());
        connection.sendPacket(response);
    }

    private void warnThrottled(ClientConnection connection, String what) {
        long now = System.nanoTime();
        String key = connection.getConnectionId();
        AtomicLong last = lastWarn.computeIfAbsent(key, k -> new AtomicLong(Long.MIN_VALUE));
        long previous = last.get();
        if (previous != Long.MIN_VALUE && now - previous < WARN_INTERVAL_NANOS) {
            return;
        }
        if (last.compareAndSet(previous, now)) {
            logger.warn("KeepAlive flood guard: {} on connection {} (clientId={}, max {} per second)",
                    what, key, connection.getClientId(),
                    connection.getKeepAliveBudget().getMaxPerWindow());
        }
    }

    /**
     * Forgets the warn throttle for a closed connection.
     *
     * @param connectionId the closing connection's id
     */
    public void forget(String connectionId) {
        if (connectionId != null) {
            lastWarn.remove(connectionId);
        }
    }
}
