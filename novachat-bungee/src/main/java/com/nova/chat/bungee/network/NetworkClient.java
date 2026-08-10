package com.nova.chat.bungee.network;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.protocol.PlatformType;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * BungeeCord NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Preserves the historical public API used by the rest of the Bungee plugin.
 * Netty bootstrap, handshake, keepalive, handler map, and reconnect policy live in
 * client-core; this class only supplies Bungee scheduler/logger adapters.
 */
public class NetworkClient extends AbstractPlatformNetworkClient {

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
        String serverVersion = plugin.getProxy().getVersion();
        initCore(
                connectionConfig,
                PlatformType.BUNGEECORD,
                scheduler,
                logger,
                "config.yml",
                Function.identity(),
                serverVersion
        );
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
