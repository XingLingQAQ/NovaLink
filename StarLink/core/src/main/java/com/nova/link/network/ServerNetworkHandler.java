package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.HandshakeAuthenticatePacket;
import com.nova.chat.common.protocol.packets.HandshakeChallengePacket;
import com.nova.chat.common.protocol.packets.HandshakeInitPacket;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Server-side network handler for routing packets to appropriate handlers.
 * Implements async business logic processing with a dedicated thread pool.
 *
 * Requirements: 3.2 - Message routing based on channel scope
 */
public class ServerNetworkHandler {

    private static final Logger logger = LoggerFactory.getLogger(ServerNetworkHandler.class);

    private final Map<Class<? extends Packet>, PacketHandler<? extends Packet>> handlers = new ConcurrentHashMap<>();
    private final Set<ClientConnection> connections = ConcurrentHashMap.newKeySet();
    /** The only connection allowed to represent each authenticated clientId. */
    private final ConcurrentHashMap<String, ClientConnection> activeConnections = new ConcurrentHashMap<>();
    private final AtomicLong nextGeneration = new AtomicLong();
    /**
     * OPS-001: control-plane executor. Bounded queue, smaller pool. Drives
     * every response-bearing / state-changing packet (handshake authenticate,
     * channel actions, admin actions, private messages). On saturation it
     * sends an explicit failure response (or closes the channel) instead of
     * silently dropping, so a client never waits forever for a reply.
     */
    private final ExecutorService controlExecutor;
    /**
     * OPS-001: message-plane executor. Drives best-effort chat-like fan-out
     * (ChatMessagePacket). Chat has no per-message ack, so on saturation this
     * executor logs + records a per-type drop metric and discards the task
     * rather than amplifying load with per-message error packets.
     */
    private final ExecutorService messageExecutor;
    private final boolean asyncProcessing;
    /**
     * OPS-001: per-packet-type drop counters, incremented when a queued task
     * is rejected by {@link #messageExecutor} (chat drop) or proactively
     * failed by {@link #controlExecutor}'s reject handler (control-plane
     * saturated). Keyed by {@link Packet#getPacketId()} so non-JVM forks can
     * mirror the same numeric type tag.
     */
    private final ConcurrentHashMap<Integer, AtomicLong> dropCountsByPacketId = new ConcurrentHashMap<>();
    private volatile BiConsumer<ClientConnection, Boolean> disconnectListener;

    /**
     * OPS-001: routing classification. Mirrors the audit's two-plane split.
     * PRE_AUTH packets (HandshakeInit 0x15, HandshakeChallenge 0x16) run
     * inline on the Netty event loop and are NOT routed to either executor.
     * CHAT packets (ChatMessagePacket) route to {@link #messageExecutor}.
     * Everything else with a registered handler routes to
     * {@link #controlExecutor} (fail-safe: ambiguous packets go to control
     * so a response-bearing packet is never silently dropped).
     */
    private enum PacketRoute {
        /** Inline on the Netty event loop; never queued. */
        PRE_AUTH,
        /** Queued on {@link #messageExecutor}; dropped + counted on saturation. */
        CHAT,
        /** Queued on {@link #controlExecutor}; explicit failure on saturation. */
        CONTROL
    }

    /**
     * Creates a new ServerNetworkHandler with async processing enabled.
     *
     * @param threadPoolSize the size of the business logic thread pool
     */
    public ServerNetworkHandler(int threadPoolSize) {
        this(threadPoolSize, true);
    }

    /**
     * Creates a new ServerNetworkHandler.
     *
     * @param threadPoolSize  the size of the business logic thread pool
     * @param asyncProcessing whether to process packets asynchronously
     */
    public ServerNetworkHandler(int threadPoolSize, boolean asyncProcessing) {
        this.asyncProcessing = asyncProcessing;
        int controlThreads = Math.max(1, Math.min(threadPoolSize, 4));
        int messageThreads = Math.max(1, Math.min(threadPoolSize, 8));
        this.controlExecutor = new ThreadPoolExecutor(
                controlThreads,
                controlThreads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(CONTROL_QUEUE_CAPACITY),
                new NamedDaemonThreadFactory("NovaLink-Control"),
                // Never DiscardPolicy: a saturated control plane must surface an
                // explicit failure so the caller does not wait forever. The
                // wrapper Runnable carries the packet tag so the handler can
                // emit the right *ResponsePacket (or close for handshake).
                new ControlRejectHandler(this)
        );
        this.messageExecutor = new ThreadPoolExecutor(
                messageThreads,
                messageThreads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(MESSAGE_QUEUE_CAPACITY),
                new NamedDaemonThreadFactory("NovaLink-Message"),
                // Never DiscardPolicy: record a per-type drop metric + warn so
                // the drop is observable. Chat has no per-message ack, so we do
                // NOT send a per-message error packet (that would amplify load).
                new MessageRejectHandler(this)
        );
    }

    /** Bounded queue for the control-plane executor. */
    private static final int CONTROL_QUEUE_CAPACITY = 1024;
    /** Bounded queue for the message-plane executor. */
    private static final int MESSAGE_QUEUE_CAPACITY = 10000;
    /**
     * NC code returned to action packets when the control plane is saturated
     * (server too busy to even enqueue the request). Reuses the existing
     * NC-500 "internal server error" family used by ChannelActionHandler /
     * AdminActionHandler for server-side faults.
     */
    private static final String NC_SERVER_BUSY = "NC-500";
    /**
     * NC code returned when a handshake/authenticate task is rejected: the
     * connection cannot complete the challenge-response dance, so it is closed
     * with NC-420 (the existing protocol-mismatch close pattern used by the
     * handshake handlers in NovaLinkMain).
     */
    private static final String NC_HANDSHAKE_BUSY = "NC-420";

    /**
     * Registers a packet handler for a specific packet type.
     *
     * @param packetClass the packet class to handle
     * @param handler     the handler for this packet type
     * @param <T>         the packet type
     */
    public <T extends Packet> void registerHandler(Class<T> packetClass, PacketHandler<T> handler) {
        handlers.put(packetClass, handler);
        logger.debug("Registered handler for packet type: {}", packetClass.getSimpleName());
    }

    /**
     * Unregisters a packet handler.
     *
     * @param packetClass the packet class to unregister
     */
    public void unregisterHandler(Class<? extends Packet> packetClass) {
        handlers.remove(packetClass);
        logger.debug("Unregistered handler for packet type: {}", packetClass.getSimpleName());
    }

    /**
     * Called when a client connects.
     *
     * @param connection the new client connection
     */
    public void onClientConnected(ClientConnection connection) {
        if (connection == null) {
            return;
        }
        connections.add(connection);
        logger.info("Client connected: {} (total: {})", connection.getConnectionId(), connections.size());
    }

    /**
     * Optional listener invoked after a connection is removed on disconnect
     * (e.g. clear client permission grants). The second argument is true only
     * when this physical connection was still the active generation for its
     * clientId. Callers must gate client-scoped cleanup on that value.
     */
    public void setDisconnectListener(BiConsumer<ClientConnection, Boolean> disconnectListener) {
        this.disconnectListener = disconnectListener;
    }

    /**
     * Atomically makes a successfully authenticated connection the active
     * generation for its clientId. A previous generation is invalidated before
     * it is closed, so its late inbound tasks and outbound writes are rejected.
     *
     * @return false when the channel was already removed or inactive
     */
    public boolean activateAuthenticated(ClientConnection connection, String clientId) {
        return activateAuthenticated(connection, clientId, null);
    }

    /**
     * Activates a connection and initializes its client-scoped state in the
     * same per-client critical section as registration and disconnect cleanup.
     */
    public boolean activateAuthenticated(ClientConnection connection, String clientId, Runnable initializeState) {
        if (connection == null || clientId == null || clientId.isBlank()) {
            return false;
        }

        AtomicReference<ClientConnection> replaced = new AtomicReference<>();
        boolean activated;
        synchronized (connection) {
            // Synchronizing the physical connection with channelInactive closes
            // the disconnect-vs-auth gap between membership validation and map
            // replacement.
            if (!connections.contains(connection) || !connection.isActive()
                    || connection.getGeneration() != 0L) {
                return false;
            }

            activeConnections.compute(clientId, (id, current) -> {
                if (current != null && current != connection) {
                    current.supersede();
                    replaced.set(current);
                }
                connection.prepareGeneration(clientId, nextGeneration.incrementAndGet());
                if (initializeState != null) {
                    initializeState.run();
                }
                return connection;
            });
            // ConcurrentHashMap publishes the new value only after the remapping
            // function returns. Do not expose authentication or writes before
            // that publication; a still newer takeover may also have won since.
            activated = connection.activatePreparedGeneration(
                    () -> activeConnections.get(clientId) == connection);
        }

        ClientConnection previous = replaced.get();
        if (previous != null) {
            logger.info("Client '{}' generation {} replaced by connection {} generation {}",
                    clientId, previous.getGeneration(), connection.getConnectionId(), connection.getGeneration());
            previous.close().whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.debug("Failed to close replaced connection {} for client '{}': {}",
                            previous.getConnectionId(), clientId, error.getMessage());
                }
            });
        }
        return activated;
    }

    /**
     * Returns whether a connection is the current authenticated generation.
     * Identity comparison is intentional: a late callback from an old channel
     * must never be able to act through a newer registration with the same ID.
     */
    public boolean isActiveGeneration(ClientConnection connection) {
        if (connection == null || !connection.isAuthenticated()) {
            return false;
        }
        String clientId = connection.getClientId();
        return clientId != null && activeConnections.get(clientId) == connection;
    }

    /**
     * Called when a client disconnects.
     *
     * @param connection the disconnected client connection
     */
    public void onClientDisconnected(ClientConnection connection) {
        if (connection == null) {
            return;
        }

        boolean removed;
        synchronized (connection) {
            removed = connections.remove(connection);
            if (!removed) {
                return;
            }
            connection.markDisconnected();
        }
        logger.info("Client disconnected: {} (total: {})", connection.getConnectionId(), connections.size());
        retireRegistration(connection);
    }

    /**
     * Handles an incoming packet from a client.
     * The packet is dispatched to the appropriate handler based on its type.
     *
     * @param connection the client connection that sent the packet
     * @param packet     the packet to handle
     */
    @SuppressWarnings("unchecked")
    public void handlePacket(ClientConnection connection, Packet packet) {
        if (connection == null || packet == null) {
            return;
        }

        if (!isAllowedAtIngress(connection, packet)) {
            logger.debug("Rejected {} from stale or unauthenticated connection {} (clientId={}, generation={})",
                    packet.getClass().getSimpleName(), connection.getConnectionId(),
                    connection.getClientId(), connection.getGeneration());
            return;
        }

        PacketHandler<Packet> handler = (PacketHandler<Packet>) handlers.get(packet.getClass());

        if (handler == null) {
            logger.warn("No handler registered for packet type: {}", packet.getClass().getSimpleName());
            return;
        }

        PacketRoute route = classify(packet);

        // Pre-auth packets (HandshakeInit 0x15, HandshakeChallenge 0x16) do not
        // touch generation state and are cheap; run them inline on the Netty
        // event loop so the handshake dance is never queued behind business
        // traffic. This mirrors the pre-OPS-001 inline behavior.
        if (route == PacketRoute.PRE_AUTH) {
            runHandlerInline(connection, packet, handler);
            return;
        }

        if (!asyncProcessing) {
            // Synchronous path (for testing): dispatch inline, but still
            // honor the control/chat split via the reject handlers? No — under
            // sync mode there is no queue to saturate, so just run inline.
            runHandlerInline(connection, packet, handler);
            return;
        }

        // Async path: wrap the task with a packet-tagged Runnable so the
        // reject handler can distinguish control vs chat and emit the right
        // failure feedback (or drop+count) on saturation.
        PacketTask task = new PacketTask(this, connection, packet, handler, route);
        ExecutorService target = route == PacketRoute.CHAT ? messageExecutor : controlExecutor;
        try {
            target.execute(task);
        } catch (RejectedExecutionException rejected) {
            // The custom RejectedExecutionHandler normally swallows the abort
            // and handles feedback itself; a direct RejectedExecutionException
            // here means the handler re-threw (e.g. executor already shut
            // down). Fall back to explicit feedback so we never silently drop.
            task.onReject(true);
        }
    }

    /** Inline dispatch used by the pre-auth and synchronous paths. */
    private void runHandlerInline(ClientConnection connection, Packet packet, PacketHandler<Packet> handler) {
        try {
            if (!executeHandler(connection, packet, handler)) {
                logger.debug("Rejected {} from stale connection {} (generation={})",
                        packet.getClass().getSimpleName(), connection.getConnectionId(),
                        connection.getGeneration());
            }
        } catch (Exception e) {
            logger.error("Error handling packet {} from {}",
                    packet.getClass().getSimpleName(), connection.getConnectionId(), e);
        }
    }

    /**
     * OPS-001: classifies a packet into its routing plane. Pre-auth handshake
     * dance packets run inline; chat-like fan-out goes to the message plane;
     * everything else (response-bearing control packets) goes to the control
     * plane. Ambiguous / unknown packets default to CONTROL so a potential
     * response is never silently dropped.
     */
    private static PacketRoute classify(Packet packet) {
        if (packet instanceof HandshakeInitPacket || packet instanceof HandshakeChallengePacket) {
            return PacketRoute.PRE_AUTH;
        }
        if (packet instanceof ChatMessagePacket) {
            return PacketRoute.CHAT;
        }
        return PacketRoute.CONTROL;
    }

    /**
     * Broadcasts a packet to all connected clients.
     *
     * @param packet the packet to broadcast
     */
    public void broadcast(Packet packet) {
        for (ClientConnection connection : connections) {
            if (connection.isActive()) {
                connection.sendPacket(packet);
            }
        }
    }

    /**
     * Broadcasts a packet to all authenticated clients.
     *
     * @param packet the packet to broadcast
     */
    public void broadcastAuthenticated(Packet packet) {
        for (ClientConnection connection : connections) {
            if (connection.isActive() && isActiveGeneration(connection)) {
                connection.sendPacket(packet);
            }
        }
    }

    /**
     * Gets all active connections.
     *
     * @return an unmodifiable set of active connections
     */
    public Set<ClientConnection> getConnections() {
        return Set.copyOf(connections);
    }

    /**
     * Gets the number of active connections.
     *
     * @return the connection count
     */
    public int getConnectionCount() {
        return connections.size();
    }

    /**
     * Finds a connection by client ID.
     *
     * @param clientId the client ID to search for
     * @return the connection, or null if not found
     */
    public ClientConnection findByClientId(String clientId) {
        return clientId != null ? activeConnections.get(clientId) : null;
    }

    private boolean isAllowedAtIngress(ClientConnection connection, Packet packet) {
        if (isAuthenticationPacket(packet)) {
            // A physical connection may authenticate only once. In particular,
            // a superseded connection cannot re-enter the map during its late
            // channel lifecycle. HandshakeAuthenticatePacket (0x17) is included
            // here because it completes authentication rather than carrying
            // business traffic; it must NOT take a generation read lease (see
            // executeHandler).
            return connection.getGeneration() == 0L
                    && !connection.isAuthenticated()
                    && connections.contains(connection);
        }
        // Preserve the existing per-handler NC-401 response behavior before a
        // connection has ever authenticated. A superseded connection has a
        // non-zero generation and must pass the active-index identity check.
        if (connection.getGeneration() == 0L) {
            return connections.contains(connection);
        }
        return isActiveGeneration(connection);
    }

    private boolean executeHandler(ClientConnection connection, Packet packet, PacketHandler<Packet> handler) {
        long startTime = System.currentTimeMillis();
        boolean handled;
        if (isAuthenticationPacket(packet)) {
            // Claim authentication once per physical channel. Successful
            // activation upgrades the connection to a generation, so the
            // handler itself cannot execute while holding a read lease.
            //
            // HandshakeAuthenticatePacket (0x17) shares this path, NOT the
            // executeWithGenerationLease path, because its handler calls
            // activateAuthenticated -> prepareGeneration/activatePreparedGeneration,
            // both of which acquire the write lock. Running it under a held
            // read lease would self-deadlock (ReentrantReadWriteLock forbids
            // read->write upgrade). The two-step tryBeginAuthentication() then
            // run-without-lease sequence matches the legacy HandshakePacket.
            if (!connection.tryBeginAuthentication()) {
                return false;
            }
            handler.handle(connection, packet);
            handled = true;
        } else {
            handled = connection.executeWithGenerationLease(
                    () -> activeConnections.get(connection.getClientId()) == connection,
                    () -> handler.handle(connection, packet));
        }

        long duration = System.currentTimeMillis() - startTime;
        if (handled && duration > 100) {
            logger.warn("Packet {} processing took {}ms (exceeds 100ms threshold)",
                    packet.getClass().getSimpleName(), duration);
        }
        return handled;
    }

    /**
     * The packets that drive the authentication lifecycle and therefore must
     * bypass the generation read lease: the legacy replayable handshake
     * (HandshakePacket, 0x01) and the AUTH-002 challenge-response finalize
     * (HandshakeAuthenticatePacket, 0x17). HandshakeInitPacket (0x15) and
     * HandshakeChallengePacket (0x16) are pre-authentication and do not touch
     * generation state; they flow through the ordinary read-lease path.
     */
    private static boolean isAuthenticationPacket(Packet packet) {
        return packet instanceof HandshakePacket
                || packet instanceof HandshakeAuthenticatePacket;
    }

    // ==================== OPS-001: per-type drop metrics ====================

    /**
     * Returns an unmodifiable snapshot of per-packet-type drop counts keyed
     * by the numeric packet id ({@link Packet#getPacketId()}). Non-JVM forks
     * can mirror the same numeric type tags. Entries with a zero count are
     * omitted for stability of the snapshot.
     *
     * @return an unmodifiable map of packet id -> dropped task count
     */
    public Map<Integer, Long> getDropCounts() {
        Map<Integer, Long> snapshot = new HashMap<>();
        for (Map.Entry<Integer, AtomicLong> entry : dropCountsByPacketId.entrySet()) {
            long value = entry.getValue().get();
            if (value != 0L) {
                snapshot.put(entry.getKey(), value);
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Returns the number of dropped tasks for a specific packet type.
     *
     * @param packetId the numeric packet id ({@link Packet#getPacketId()})
     * @return the drop count for that packet type (0 if none recorded)
     */
    public long getDropCount(int packetId) {
        AtomicLong counter = dropCountsByPacketId.get(packetId);
        return counter != null ? counter.get() : 0L;
    }

    /**
     * Convenience overload keyed by {@link Packet#getPacketId()}.
     *
     * @param packet the packet type whose drop count to read
     * @return the drop count for that packet type (0 if none recorded)
     */
    public long getDropCount(Packet packet) {
        return packet != null ? getDropCount(packet.getPacketId()) : 0L;
    }

    /** Returns the total number of dropped tasks across all packet types. */
    public long getDropCountTotal() {
        long total = 0L;
        for (AtomicLong counter : dropCountsByPacketId.values()) {
            total += counter.get();
        }
        return total;
    }

    // ==================== §11.6 Project 17: observability getters ====================
    // Read-only queue-depth snapshots for the Prometheus /api/metrics endpoint.
    // Each getter degrades to 0 when the executor is not a ThreadPoolExecutor
    // (defensive — never let a health probe 500) so the metrics endpoint stays
    // safe to invoke from the Netty IO thread.

    /**
     * Current depth of the control-plane executor's bounded work queue.
     *
     * @return queued control-plane task count, or 0 when unavailable
     */
    public int getControlQueueDepth() {
        try {
            if (controlExecutor instanceof ThreadPoolExecutor tpe) {
                return tpe.getQueue().size();
            }
        } catch (Exception ignored) {
            // best-effort snapshot; never let a health probe 500
        }
        return 0;
    }

    /**
     * @return the configured capacity of the control-plane executor's queue
     */
    public int getControlQueueCapacity() {
        return CONTROL_QUEUE_CAPACITY;
    }

    /**
     * Current depth of the message-plane executor's bounded work queue.
     *
     * @return queued message-plane task count, or 0 when unavailable
     */
    public int getMessageQueueDepth() {
        try {
            if (messageExecutor instanceof ThreadPoolExecutor tpe) {
                return tpe.getQueue().size();
            }
        } catch (Exception ignored) {
            // best-effort snapshot; never let a health probe 500
        }
        return 0;
    }

    /**
     * @return the configured capacity of the message-plane executor's queue
     */
    public int getMessageQueueCapacity() {
        return MESSAGE_QUEUE_CAPACITY;
    }

    /**
     * Atomically increments the drop counter for a packet type. Called by the
     * reject handlers when a queued task is rejected (chat drop) or
     * proactively failed (control-plane saturation).
     */
    private void recordDrop(int packetId) {
        dropCountsByPacketId
                .computeIfAbsent(packetId, k -> new AtomicLong())
                .incrementAndGet();
    }

    /**
     * OPS-001: sends an explicit failure response for a control-plane packet
     * that was rejected because the control executor is saturated. Mirrors
     * the response patterns the registered handlers themselves produce, so
     * the client receives a uniform error instead of timing out.
     *
     * <p>Handshake/authenticate packets close the channel with NC-420 (the
     * existing protocol-mismatch close pattern); action packets get a
     * {@code *ResponsePacket} carrying NC-500 / server-busy.
     */
    private void sendControlRejectionResponse(ClientConnection connection, Packet packet) {
        try {
            if (packet instanceof HandshakePacket || packet instanceof HandshakeAuthenticatePacket) {
                // Cannot complete the handshake dance under saturation; close
                // the connection with an explicit failure (mirrors
                // NovaLinkMain#sendResponseAndClose for NC-420 protocol mismatch).
                HandshakeResponsePacket response = HandshakeResponsePacket.failure(
                        NC_HANDSHAKE_BUSY,
                        "Server busy, please reconnect");
                response.setRequestId(packet.getRequestId());
                connection.sendPacket(response).whenComplete((ignored, error) -> {
                    if (error != null) {
                        logger.debug("Failed to write handshake-busy response to {}: {}",
                                connection.getRemoteAddress(),
                                error.getMessage());
                    }
                    connection.close();
                });
                return;
            }
            if (packet instanceof ChannelActionPacket) {
                ChannelActionPacket cap = (ChannelActionPacket) packet;
                ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                        false,
                        cap.getAction() != null ? cap.getAction() : ChannelAction.JOIN,
                        cap.getChannelId() != null ? cap.getChannelId() : "",
                        NC_SERVER_BUSY,
                        "Server busy, please try again later");
                response.setRequestId(packet.getRequestId());
                connection.sendPacket(response);
                return;
            }
            if (packet instanceof AdminActionPacket) {
                AdminActionPacket aap = (AdminActionPacket) packet;
                AdminActionResponsePacket response = AdminActionResponsePacket.failure(
                        aap.getAction(),
                        NC_SERVER_BUSY,
                        "Server busy, please try again later");
                response.setRequestId(packet.getRequestId());
                connection.sendPacket(response);
                return;
            }
            if (packet instanceof PrivateMessagePacket) {
                // Private messages share the chat token bucket but carry a
                // response-bearing semantic (the sender expects either delivery
                // or an error). Send a generic ChannelActionResponsePacket
                // (the protocol's error carrier, same shape as sendThrottleError).
                PrivateMessagePacket pmp = (PrivateMessagePacket) packet;
                ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                        false,
                        null,
                        "",
                        NC_SERVER_BUSY,
                        "Server busy, private message dropped");
                response.setRequestId(packet.getRequestId());
                response.addExtra("reason", "server_busy");
                response.addExtra("targetName", pmp.getTargetName() != null ? pmp.getTargetName() : "");
                connection.sendPacket(response);
                return;
            }
            if (packet instanceof ItemDisplayPacket) {
                // Item display reuses ChannelActionResponsePacket for errors
                // (same as ItemDisplayHandler#sendError). Treat as chat-like:
                // no per-message ack is required, but since it landed on the
                // control plane (default route), give it the same explicit
                // error so the client can stop waiting.
                ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                        false,
                        null,
                        "",
                        NC_SERVER_BUSY,
                        "Server busy, item display dropped");
                response.setRequestId(packet.getRequestId());
                response.addExtra("reason", "server_busy");
                connection.sendPacket(response);
                return;
            }
            // Unknown / ambiguous control packet: log only (no response shape
            // is known). The drop is still counted via recordDrop().
            logger.warn("Control-plane rejection for untyped packet {} from {} (no response sent)",
                    packet.getClass().getSimpleName(), connection.getConnectionId());
        } catch (Exception e) {
            logger.debug("Failed to send control rejection response for {} to {}: {}",
                    packet.getClass().getSimpleName(), connection.getConnectionId(), e.getMessage());
        }
    }

    /**
     * Shuts down the handler and releases resources.
     */
    public void shutdown() {
        logger.info("Shutting down ServerNetworkHandler...");

        // Retire registrations before closing channels. This makes shutdown
        // cleanup deterministic and prevents late channelInactive callbacks
        // from invoking the listener a second time.
        Set<ClientConnection> closing = Set.copyOf(connections);
        for (ClientConnection connection : closing) {
            synchronized (connection) {
                connection.markDisconnected();
                connections.remove(connection);
            }
            retireRegistration(connection);
            connection.close();
        }
        activeConnections.clear();

        // OPS-001: drain BOTH executors. Pending queued tasks are cancelled
        // (shutdownNow) after a short grace period so a stuck handler cannot
        // leak threads past shutdown.
        shutdownExecutor(controlExecutor, "control");
        shutdownExecutor(messageExecutor, "message");

        logger.info("ServerNetworkHandler shut down successfully");
    }

    /**
     * OPS-001: drains one executor with a bounded grace period then cancels
     * any remaining queued tasks. Never throws (best-effort cleanup).
     */
    private void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                List<Runnable> pending = executor.shutdownNow();
                if (!pending.isEmpty()) {
                    logger.info("{} executor shutdown cancelled {} pending task(s)", name, pending.size());
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void notifyDisconnected(ClientConnection connection, boolean activeGenerationEnded) {
        BiConsumer<ClientConnection, Boolean> listener = disconnectListener;
        if (listener != null) {
            try {
                listener.accept(connection, activeGenerationEnded);
            } catch (Exception e) {
                logger.debug("Disconnect listener error for {}: {}", connection.getConnectionId(), e.getMessage());
            }
        }
    }

    /**
     * Removes a generation and runs client-scoped cleanup under the same
     * per-client map operation as the removal. A concurrent takeover therefore
     * occurs entirely before cleanup (old generation is ignored) or after it
     * (new bootstrap wins); cleanup can never run between registration and
     * bootstrap for a newer active generation.
     */
    private void retireRegistration(ClientConnection connection) {
        String clientId = connection.getClientId();
        if (clientId == null) {
            notifyDisconnected(connection, false);
            return;
        }

        AtomicReference<Boolean> activeGenerationEnded = new AtomicReference<>(false);
        activeConnections.computeIfPresent(clientId, (id, current) -> {
            if (current == connection) {
                activeGenerationEnded.set(true);
                notifyDisconnected(connection, true);
                return null;
            }
            return current;
        });
        if (!activeGenerationEnded.get()) {
            notifyDisconnected(connection, false);
        }
    }

    /**
     * OPS-001: a packet-tagged {@link Runnable} wrapping one dispatch. The
     * reject handlers cast the rejected task to this type to read the packet
     * tag and emit the right failure feedback (or drop+count).
     */
    private static final class PacketTask implements Runnable {
        private final ServerNetworkHandler owner;
        private final ClientConnection connection;
        private final Packet packet;
        private final PacketHandler<Packet> handler;
        private final PacketRoute route;

        PacketTask(ServerNetworkHandler owner, ClientConnection connection, Packet packet,
                   PacketHandler<Packet> handler, PacketRoute route) {
            this.owner = owner;
            this.connection = connection;
            this.packet = packet;
            this.handler = handler;
            this.route = route;
        }

        @Override
        public void run() {
            try {
                if (!owner.executeHandlerRouted(connection, packet, handler)) {
                    logger.debug("Rejected queued {} from stale connection {} (generation={})",
                            packet.getClass().getSimpleName(), connection.getConnectionId(),
                            connection.getGeneration());
                }
            } catch (Exception e) {
                logger.error("Error handling packet {} from {}",
                        packet.getClass().getSimpleName(), connection.getConnectionId(), e);
            }
        }

        /** Invoked by the reject handlers (and the direct-execute fallback) when the task is rejected. */
        void onReject(boolean countDrop) {
            if (countDrop) {
                owner.recordDrop(packet.getPacketId());
            }
            String clientId = connection.getClientId();
            if (route == PacketRoute.CHAT) {
                // Chat: log + count only. No per-message error to the client
                // (would amplify load under saturation).
                logger.warn("Message executor saturated; dropping {} from client {} (clientId={})",
                        packet.getClass().getSimpleName(),
                        connection.getConnectionId(),
                        clientId != null ? clientId : "<unauthenticated>");
                return;
            }
            // Control: explicit failure response so the caller does not wait.
            logger.warn("Control executor saturated; sending explicit failure for {} " +
                            "from connection {} (clientId={})",
                    packet.getClass().getSimpleName(), connection.getConnectionId(), clientId);
            owner.sendControlRejectionResponse(connection, packet);
        }
    }

    /**
     * OPS-001: control-plane reject handler. Intercepts the rejected
     * {@link PacketTask} to send an explicit failure response and record the
     * drop. Never silently discards a control-plane task.
     */
    private static final class ControlRejectHandler implements RejectedExecutionHandler {
        private final ServerNetworkHandler owner;

        ControlRejectHandler(ServerNetworkHandler owner) {
            this.owner = owner;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                // Executor shutting down: drop silently (shutdown path).
                return;
            }
            if (r instanceof PacketTask) {
                ((PacketTask) r).onReject(true);
            } else {
                logger.warn("Control executor rejected untagged task (active={}, queueSize={})",
                        executor.getActiveCount(), executor.getQueue().size());
            }
        }
    }

    /**
     * OPS-001: message-plane reject handler. Logs + counts the dropped chat
     * task. Does NOT send a per-message error to the client (chat has no ack,
     * and erroring would amplify load).
     */
    private static final class MessageRejectHandler implements RejectedExecutionHandler {
        private final ServerNetworkHandler owner;

        MessageRejectHandler(ServerNetworkHandler owner) {
            this.owner = owner;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                return;
            }
            if (r instanceof PacketTask) {
                ((PacketTask) r).onReject(true);
            } else {
                logger.warn("Message executor rejected untagged task (active={}, queueSize={})",
                        executor.getActiveCount(), executor.getQueue().size());
            }
        }
    }

    /**
     * OPS-001: daemon thread factory with a named, numbered pool. Replaces the
     * inline anonymous {@link ThreadFactory} from the single-executor design.
     */
    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final java.util.concurrent.atomic.AtomicInteger counter =
                new java.util.concurrent.atomic.AtomicInteger();
        private final String prefix;

        NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    /** Routed entry point used by {@link PacketTask} (decouples the nested class). */
    boolean executeHandlerRouted(ClientConnection connection, Packet packet, PacketHandler<Packet> handler) {
        return executeHandler(connection, packet, handler);
    }
}
