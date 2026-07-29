package com.nova.chat.bungee.network;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * BungeeCord NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Preserves the historical public API used by the rest of the Bungee plugin.
 * Netty bootstrap, handshake, keepalive, handler map, and reconnect policy live in
 * client-core; this class only supplies Bungee scheduler/logger adapters.
 */
public class NetworkClient {

    private final CoreNetworkClient core;

    /**
     * Creates a new NetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public NetworkClient(NovaChatBungee plugin, NovaChatConfig config) {
        ClientConnectionConfig connectionConfig = config.toClientConnectionConfig();
        SchedulerBridge scheduler = new BungeeSchedulerBridge(plugin);
        ClientLogger logger = new BungeeClientLogger(plugin);
        this.core = new CoreNetworkClient(
                connectionConfig,
                PlatformType.BUNGEECORD,
                scheduler,
                logger,
                "config.yml",
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
     * BungeeCord scheduler adapter: seconds-based delays via the proxy scheduler.
     */
    static final class BungeeSchedulerBridge implements SchedulerBridge {
        private final NovaChatBungee plugin;

        BungeeSchedulerBridge(NovaChatBungee plugin) {
            this.plugin = plugin;
        }

        @Override
        public void runAsync(Runnable task) {
            plugin.getProxy().getScheduler().runAsync(plugin, task);
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            plugin.getProxy().getScheduler().schedule(plugin, task, delaySeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * Maps BungeeCord JUL logger + plugin debug gate onto {@link ClientLogger}.
     */
    static final class BungeeClientLogger implements ClientLogger {
        private final NovaChatBungee plugin;

        BungeeClientLogger(NovaChatBungee plugin) {
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
            plugin.getLogger().severe(message);
        }

        @Override
        public void error(String message, Throwable cause) {
            if (cause == null) {
                plugin.getLogger().severe(message);
            } else {
                plugin.getLogger().severe(message + ": " + cause.getMessage());
            }
        }
    }
}
