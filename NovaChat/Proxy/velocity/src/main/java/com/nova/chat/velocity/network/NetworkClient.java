package com.nova.chat.velocity.network;

import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.velocity.NovaChatVelocity;
import com.nova.chat.velocity.config.NovaChatConfig;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Velocity NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Preserves the historical public API used by the rest of the Velocity plugin.
 * Netty bootstrap, handshake, keepalive, handler map, and reconnect policy live in
 * client-core; this class only supplies Velocity scheduler/logger adapters.
 */
public class NetworkClient extends AbstractPlatformNetworkClient {

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
        String serverVersion = plugin.getServer().getVersion().getVersion();
        initCore(
                connectionConfig,
                PlatformType.VELOCITY,
                scheduler,
                logger,
                "config.toml",
                Function.identity(),
                serverVersion
        );
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
