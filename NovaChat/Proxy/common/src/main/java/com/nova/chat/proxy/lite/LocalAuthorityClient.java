package com.nova.chat.proxy.lite;

import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.proxy.platform.ProxyPlatform;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Lite-mode stand-in for the platform {@code NetworkClient} facade.
 *
 * <p>It extends the same base as the relay clients, so every caller in the
 * shared cores keeps working unchanged, but it never constructs a
 * {@code CoreNetworkClient} and therefore never calls {@link #initCore}: the
 * inherited forwarding methods would all hit {@code core()} and throw, so
 * <b>every public method is overridden</b>. This is the complete surface:
 * {@code connect}, {@code disconnect}, {@code sendPacket},
 * {@code getChannelResponseTracker}, {@code registerHandler},
 * {@code isConnected}, {@code isAuthenticated} and {@code getPacketRegistry}.
 *
 * <p><b>Upstream</b> packets are handed to the bound {@link LocalAuthority}.
 * <p><b>Downstream</b> packets come back from the authority and are delivered to
 * the handlers registered via {@link #registerHandler}, exactly like
 * {@code CoreNetworkClient.handlePacket} does for the Netty path.
 *
 * <p><b>Asynchronous responses.</b> {@code ChannelCommandService.join} sends the
 * JOIN packet and only then performs the optimistic active-channel switch.
 * Answering synchronously from {@link #sendPacket} would let a failure
 * response roll back <em>before</em> that switch, leaving the rejected channel
 * active. Every authority round-trip is therefore scheduled on
 * {@link AsyncDispatcher} after {@link #DEFAULT_RESPONSE_DELAY_MILLIS}, and the
 * channel-action request is tracked with {@link ChannelResponseTracker}
 * <em>before</em> the task is queued, so the response carries the original
 * request id and is processed strictly after the caller finished.
 */
public final class LocalAuthorityClient extends AbstractPlatformNetworkClient
        implements AutoCloseable {

    /**
     * Delay before an upstream packet is processed. One tick (50 ms) is long
     * enough for the synchronous optimistic update to complete and short enough
     * to stay imperceptible.
     */
    public static final long DEFAULT_RESPONSE_DELAY_MILLIS = 50L;

    /** Schedules the request/response round-trip off the caller thread. */
    public interface AsyncDispatcher {

        /**
         * @param task the task to run
         * @param delayMillis minimum delay before it runs
         */
        void runLater(Runnable task, long delayMillis);

        /** Releases any resources owned by the dispatcher. Default: no-op. */
        default void shutdown() {
        }
    }

    private final AsyncDispatcher dispatcher;
    private final long responseDelayMillis;
    private final Consumer<String> debugLogger;
    private final ChannelResponseTracker tracker = new ChannelResponseTracker();
    private final PacketRegistry packetRegistry = NovaProtocol.createRegistry();
    private final Map<Class<? extends Packet>, Consumer<Packet>> handlers =
            new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(true);

    private volatile LocalAuthority authority;

    /**
     * @param dispatcher schedules the asynchronous round-trip
     * @param responseDelayMillis delay before an upstream packet is processed
     * @param debugLogger debug sink (null-tolerant)
     */
    public LocalAuthorityClient(AsyncDispatcher dispatcher,
                                long responseDelayMillis,
                                Consumer<String> debugLogger) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.responseDelayMillis = Math.max(0L, responseDelayMillis);
        this.debugLogger = debugLogger != null ? debugLogger : message -> { };
    }

    /**
     * Creates a client backed by a single daemon scheduler on the default delay.
     *
     * @param platform the platform adapter (used for debug logging)
     * @return a ready client; call {@link #bind(LocalAuthority)} before use
     */
    public static LocalAuthorityClient create(ProxyPlatform platform) {
        Objects.requireNonNull(platform, "platform");
        return new LocalAuthorityClient(
                new ScheduledDispatcher(),
                DEFAULT_RESPONSE_DELAY_MILLIS,
                platform::logDebug);
    }

    /**
     * Binds the authority that will handle upstream packets.
     *
     * @param authority the local authority
     */
    public void bind(LocalAuthority authority) {
        this.authority = authority;
    }

    // ----------------------------------------------------- AbstractPlatformNetworkClient

    /**
     * Lite has no backend: the link is always "up" from the cores' point of
     * view, so they do not degrade to "not connected" replies.
     *
     * @return a completed future with {@code true}
     */
    @Override
    public CompletableFuture<Boolean> connect(String host, int port) {
        return CompletableFuture.completedFuture(true);
    }

    /**
     * No-op: the client is process-scoped. A reload must not tear it down,
     * because the shared cores keep their handler registrations on it.
     */
    @Override
    public void disconnect() {
        debug("Lite client disconnect ignored (no backend link)");
    }

    /**
     * Hands the packet to the local authority asynchronously. Channel actions
     * are tracked before the task is queued so the response can be correlated
     * to the originating player.
     *
     * @param packet the packet a shared core tried to send to the backend
     */
    @Override
    public void sendPacket(Packet packet) {
        if (packet == null) {
            return;
        }
        if (packet instanceof ChannelActionPacket action) {
            tracker.cleanupExpired();
            tracker.track(action);
        }
        debug("Lite send: " + packet.getClass().getSimpleName());
        dispatcher.runLater(() -> process(packet), responseDelayMillis);
    }

    /**
     * @return the tracker that correlates channel actions with their local
     *         responses (the shared dispatcher consumes from it)
     */
    @Override
    public ChannelResponseTracker getChannelResponseTracker() {
        return tracker;
    }

    /**
     * Registers a downstream packet handler.
     *
     * <p>Matches {@code CoreNetworkClient}: one handler per packet class; a
     * later registration replaces the earlier one.
     *
     * @param packetClass the downstream packet type
     * @param handler the consumer invoked on the dispatcher thread
     */
    @Override
    public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
        Objects.requireNonNull(packetClass, "packetClass");
        Objects.requireNonNull(handler, "handler");
        handlers.put(packetClass, (Consumer<Packet>) handler);
    }

    /** @return {@code true} until {@link #shutdown()} is called */
    @Override
    public boolean isConnected() {
        return active.get();
    }

    /** @return {@code true} until {@link #shutdown()} is called */
    @Override
    public boolean isAuthenticated() {
        return active.get();
    }

    /** @return the local registry (no transport uses it, kept for API parity) */
    @Override
    public PacketRegistry getPacketRegistry() {
        return packetRegistry;
    }

    /**
     * Stops accepting sends and releases the owned scheduler. Called by the
     * platform shell on plugin shutdown.
     */
    @Override
    public void close() {
        if (active.compareAndSet(true, false)) {
            dispatcher.shutdown();
            debug("Lite client shut down");
        }
    }

    /** Alias for {@link #close()} using the shell's shutdown vocabulary. */
    public void shutdown() {
        close();
    }

    // ---------------------------------------------------------------- internals

    /** Runs one upstream packet through the authority and delivers the result. */
    private void process(Packet packet) {
        LocalAuthority target = authority;
        if (target == null) {
            debug("Lite authority not bound yet; dropping "
                    + packet.getClass().getSimpleName());
            return;
        }
        List<Packet> downstream;
        try {
            downstream = target.handle(packet);
        } catch (Throwable t) {
            // A faulty authority must not kill the dispatcher thread.
            debug("Lite authority failed on " + packet.getClass().getSimpleName()
                    + ": " + t.getClass().getName() + ": " + t.getMessage());
            return;
        }
        for (Packet response : downstream) {
            deliver(response);
        }
    }

    /** Invokes the handler registered for the packet's exact class, if any. */
    private void deliver(Packet packet) {
        if (packet == null) {
            return;
        }
        Consumer<Packet> handler = handlers.get(packet.getClass());
        if (handler == null) {
            debug("Lite no handler for " + packet.getClass().getSimpleName());
            return;
        }
        try {
            handler.accept(packet);
        } catch (Throwable t) {
            // Mirrors CoreNetworkClient.handlePacket: a faulty platform handler
            // must not tear down the delivery loop.
            debug("Lite handler for " + packet.getClass().getSimpleName()
                    + " threw " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private void debug(String message) {
        debugLogger.accept(message);
    }

    /** Default dispatcher: one daemon thread with millisecond scheduling. */
    private static final class ScheduledDispatcher implements AsyncDispatcher {

        private final ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "novachat-lite-authority");
                    thread.setDaemon(true);
                    return thread;
                });

        @Override
        public void runLater(Runnable task, long delayMillis) {
            try {
                executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Plugin shutting down; nothing left to answer.
            }
        }

        @Override
        public void shutdown() {
            executor.shutdownNow();
        }
    }
}
