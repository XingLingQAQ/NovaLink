package com.nova.chat.velocity.network;

import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Velocity NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Preserves the historical public API used by the rest of the Velocity plugin.
 * Netty bootstrap, handshake, keepalive, handler map, and reconnect policy live in
 * client-core; this class only supplies Velocity scheduler/logger adapters.
 */
public class NetworkClient {

    private final CoreNetworkClient core;

    /**
     * Creates a new NetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public NetworkClient(NovaChatVelocity plugin, NovaChatConfig config) {
        ClientConnectionConfig connectionConfig = config.toClientConnectionConfig();
        SchedulerBridge scheduler = new VelocitySchedulerBridge(plugin);
        ClientLogger logger = new VelocityClientLogger(plugin);
        this.core = new CoreNetworkClient(
                connectionConfig,
                PlatformType.VELOCITY,
                scheduler,
                logger,
                "config.toml",
                java.util.function.Function.identity()
        );
    }

    /**
     * Connects to the NovaLink backend.
     *
     * @param host the backend host
     * @param port the backend port
     * @return a future that completes with true if connection and authentication succeed
     */
    public CompletableFuture<Boolean> connect(String host, int port) {
        return core.connect(host, port);
    }

    /**
     * Disconnects from the backend.
     */
    public void disconnect() {
        core.disconnect();
    }

    /**
     * Sends a packet to the backend. Channel-action correlation tracking is
     * handled inside {@link CoreNetworkClient#sendPacket} (single-entry contract).
     *
     * @param packet the packet to send
     */
    public void sendPacket(Packet packet) {
        core.sendPacket(packet);
    }

    /**
     * @return the tracker mapping in-flight channel-action request ids to players,
     *         used by the platform's {@code ChannelActionResponsePacket} handler
     */
    public ChannelResponseTracker getChannelResponseTracker() {
        return core.getChannelResponseTracker();
    }

    /**
     * Registers a packet handler.
     *
     * @param packetClass the packet class to handle
     * @param handler the handler function
     * @param <T> the packet type
     */
    public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
        core.registerHandler(packetClass, handler);
    }

    /**
     * Checks if the client is connected.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return core.isConnected();
    }

    /**
     * Checks if the client is authenticated.
     *
     * @return true if authenticated
     */
    public boolean isAuthenticated() {
        return core.isAuthenticated();
    }

    /**
     * Gets the packet registry.
     *
     * @return the packet registry
     */
    public PacketRegistry getPacketRegistry() {
        return core.getPacketRegistry();
    }

    /**
     * Package-visible for tests / advanced adapters.
     */
    CoreNetworkClient core() {
        return core;
    }

    /**
     * Velocity scheduler adapter: seconds-based delays via the proxy task builder.
     */
    static final class VelocitySchedulerBridge implements SchedulerBridge {
        private final NovaChatVelocity plugin;

        VelocitySchedulerBridge(NovaChatVelocity plugin) {
            this.plugin = plugin;
        }

        @Override
        public void runAsync(Runnable task) {
            plugin.getServer().getScheduler()
                    .buildTask(plugin, task)
                    .schedule();
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            plugin.getServer().getScheduler()
                    .buildTask(plugin, task)
                    .delay(delaySeconds, TimeUnit.SECONDS)
                    .schedule();
        }
    }

    /**
     * Maps Velocity SLF logger + plugin debug gate onto {@link ClientLogger}.
     */
    static final class VelocityClientLogger implements ClientLogger {
        private final NovaChatVelocity plugin;

        VelocityClientLogger(NovaChatVelocity plugin) {
            this.plugin = plugin;
        }

        @Override
        public void info(String message) {
            plugin.getLogger().info(message);
        }

        @Override
        public void warn(String message) {
            plugin.getLogger().warn(message);
        }

        @Override
        public void debug(String message) {
            plugin.debug(message);
        }

        @Override
        public void error(String message) {
            plugin.getLogger().error(message);
        }

        @Override
        public void error(String message, Throwable cause) {
            if (cause == null) {
                plugin.getLogger().error(message);
            } else {
                plugin.getLogger().error(message, cause);
            }
        }
    }
}
