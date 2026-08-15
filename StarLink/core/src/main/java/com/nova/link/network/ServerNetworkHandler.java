package com.nova.link.network;

import com.nova.chat.common.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

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
    private final ExecutorService businessLogicExecutor;
    private final boolean asyncProcessing;
    private volatile Consumer<ClientConnection> disconnectListener;

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
        this.businessLogicExecutor = new ThreadPoolExecutor(
                threadPoolSize,
                threadPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10000),
                new ThreadFactory() {
                    private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread thread = new Thread(r, "NovaLink-Business-" + counter.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                // Rejection policy: log and discard rather than running the task on
                // the calling (Netty IO) thread. CallerRunsPolicy would execute
                // rejected business logic directly on the Netty event-loop thread,
                // which can block IO and stall all connections on that loop. Discarding
                // the task under overload is preferable to stalling the IO thread.
                // Individual packet handlers that need reliability should use their
                // own bounded queues / back-pressure rather than relying on the
                // shared business pool.
                (Runnable r, ThreadPoolExecutor executor) -> {
                    if (!executor.isShutdown()) {
                        logger.warn("Business executor saturated; discarding packet task to protect Netty IO thread " +
                                "(active={}, poolSize={}, queueSize={})",
                                executor.getActiveCount(),
                                executor.getPoolSize(),
                                executor.getQueue().size());
                    }
                }
        );
    }

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
        connections.add(connection);
        logger.info("Client connected: {} (total: {})", connection.getConnectionId(), connections.size());
    }

    /**
     * Optional listener invoked after a connection is removed on disconnect
     * (e.g. clear client permission grants).
     */
    public void setDisconnectListener(Consumer<ClientConnection> disconnectListener) {
        this.disconnectListener = disconnectListener;
    }

    /**
     * Called when a client disconnects.
     *
     * @param connection the disconnected client connection
     */
    public void onClientDisconnected(ClientConnection connection) {
        connections.remove(connection);
        logger.info("Client disconnected: {} (total: {})", connection.getConnectionId(), connections.size());
        Consumer<ClientConnection> listener = disconnectListener;
        if (listener != null) {
            try {
                listener.accept(connection);
            } catch (Exception e) {
                logger.debug("Disconnect listener error for {}: {}", connection.getConnectionId(), e.getMessage());
            }
        }
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
        PacketHandler<Packet> handler = (PacketHandler<Packet>) handlers.get(packet.getClass());
        
        if (handler == null) {
            logger.warn("No handler registered for packet type: {}", packet.getClass().getSimpleName());
            return;
        }

        if (asyncProcessing) {
            // Process packet asynchronously in business logic thread pool
            businessLogicExecutor.submit(() -> {
                try {
                    long startTime = System.currentTimeMillis();
                    handler.handle(connection, packet);
                    long duration = System.currentTimeMillis() - startTime;
                    
                    if (duration > 100) {
                        logger.warn("Packet {} processing took {}ms (exceeds 100ms threshold)",
                                packet.getClass().getSimpleName(), duration);
                    }
                } catch (Exception e) {
                    logger.error("Error handling packet {} from {}", 
                            packet.getClass().getSimpleName(), connection.getConnectionId(), e);
                }
            });
        } else {
            // Process synchronously (for testing)
            try {
                handler.handle(connection, packet);
            } catch (Exception e) {
                logger.error("Error handling packet {} from {}", 
                        packet.getClass().getSimpleName(), connection.getConnectionId(), e);
            }
        }
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
            if (connection.isActive() && connection.isAuthenticated()) {
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
        for (ClientConnection connection : connections) {
            if (clientId.equals(connection.getClientId())) {
                return connection;
            }
        }
        return null;
    }

    /**
     * Shuts down the handler and releases resources.
     */
    public void shutdown() {
        logger.info("Shutting down ServerNetworkHandler...");
        
        // Close all connections
        for (ClientConnection connection : connections) {
            connection.close();
        }
        connections.clear();
        
        // Shutdown executor
        businessLogicExecutor.shutdown();
        try {
            if (!businessLogicExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                businessLogicExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            businessLogicExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("ServerNetworkHandler shut down successfully");
    }
}
