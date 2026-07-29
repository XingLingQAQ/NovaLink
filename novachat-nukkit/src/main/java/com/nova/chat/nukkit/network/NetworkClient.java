package com.nova.chat.nukkit.network;

import cn.nukkit.scheduler.AsyncTask;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.config.NovaChatConfig;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Nukkit NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Preserves the historical public API used by the rest of the Nukkit plugin.
 * Netty bootstrap, handshake, keepalive, handler map, and reconnect policy live in
 * client-core; this class only supplies Nukkit scheduler/logger adapters.
 */
public class NetworkClient {

    private final CoreNetworkClient core;

    /**
     * Creates a new NetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public NetworkClient(NovaChatNukkit plugin, NovaChatConfig config) {
        ClientConnectionConfig connectionConfig = config.toClientConnectionConfig();
        SchedulerBridge scheduler = new NukkitSchedulerBridge(plugin);
        ClientLogger logger = new NukkitClientLogger(plugin);
        this.core = new CoreNetworkClient(
                connectionConfig,
                PlatformType.NUKKIT,
                scheduler,
                logger,
                "config.yml",
                Function.identity()
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
     * Sends a packet to the backend.
     *
     * @param packet the packet to send
     */
    public void sendPacket(Packet packet) {
        core.sendPacket(packet);
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
     * Nukkit scheduler adapter: seconds-based delays converted to ticks (20/second),
     * async hops via {@link AsyncTask}.
     */
    static final class NukkitSchedulerBridge implements SchedulerBridge {
        private final NovaChatNukkit plugin;

        NukkitSchedulerBridge(NovaChatNukkit plugin) {
            this.plugin = plugin;
        }

        @Override
        public void runAsync(Runnable task) {
            plugin.getServer().getScheduler().scheduleAsyncTask(plugin, new AsyncTask() {
                @Override
                public void onRun() {
                    task.run();
                }
            });
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            long delayTicks = Math.max(0L, delaySeconds) * 20L;
            plugin.getServer().getScheduler().scheduleDelayedTask(plugin, task::run, (int) delayTicks);
        }
    }

    /**
     * Maps the Nukkit plugin logger + plugin debug gate onto {@link ClientLogger}.
     */
    static final class NukkitClientLogger implements ClientLogger {
        private final NovaChatNukkit plugin;

        NukkitClientLogger(NovaChatNukkit plugin) {
            this.plugin = plugin;
        }

        @Override
        public void info(String message) {
            plugin.getLogger().info(message);
        }

        @Override
        public void warn(String message) {
            plugin.getLogger().warning(message);
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
                plugin.getLogger().error(message + ": " + cause.getMessage());
            }
        }
    }
}
