package com.nova.chat.multipaper.network;

import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.multipaper.NovaChatMultiPaper;
import com.nova.chat.multipaper.config.NovaChatConfig;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * MultiPaper NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Preserves the historical public API used by the rest of the MultiPaper plugin.
 * Netty bootstrap, handshake, keepalive, handler map, and reconnect policy live in
 * client-core; this class only supplies the Bukkit scheduler/logger adapters and the
 * MultiPaper-specific username rewrite ({@code username@instanceId}).
 */
public class NetworkClient {

    private final CoreNetworkClient core;

    /**
     * Creates a new NetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public NetworkClient(NovaChatMultiPaper plugin, NovaChatConfig config) {
        ClientConnectionConfig connectionConfig = config.toClientConnectionConfig();
        SchedulerBridge scheduler = new MultiPaperSchedulerBridge(plugin);
        ClientLogger logger = new MultiPaperClientLogger(plugin);
        Function<String, String> usernameTransformer = baseUsername -> {
            if (plugin.getMultiPaperAdapter().isMultiPaper()) {
                return baseUsername + "@" + plugin.getMultiPaperAdapter().getInstanceId();
            }
            return baseUsername;
        };
        this.core = new CoreNetworkClient(
                connectionConfig,
                PlatformType.MULTIPAPER,
                scheduler,
                logger,
                "config.yml",
                usernameTransformer
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
     * Bukkit scheduler adapter: seconds-based delays converted to ticks ({@code * 20}).
     */
    static final class MultiPaperSchedulerBridge implements SchedulerBridge {
        private final NovaChatMultiPaper plugin;

        MultiPaperSchedulerBridge(NovaChatMultiPaper plugin) {
            this.plugin = plugin;
        }

        @Override
        public void runAsync(Runnable task) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task, delaySeconds * 20L);
        }
    }

    /**
     * Maps the Bukkit JUL logger + plugin debug gate onto {@link ClientLogger}.
     */
    static final class MultiPaperClientLogger implements ClientLogger {
        private final NovaChatMultiPaper plugin;

        MultiPaperClientLogger(NovaChatMultiPaper plugin) {
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
