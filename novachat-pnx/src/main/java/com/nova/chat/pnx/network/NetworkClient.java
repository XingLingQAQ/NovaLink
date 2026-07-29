package com.nova.chat.pnx.network;

import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.config.NovaChatConfig;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * PNX NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Preserves the historical public API used by the rest of the PNX plugin. Netty
 * bootstrap, handshake, keepalive, handler map, and reconnect policy live in
 * client-core; this class only supplies PNX scheduler/logger adapters and registers
 * PNX-specific chat/title inbound handlers that historically lived in the
 * hard-coded {@code ClientChannelHandler}.
 *
 * <p>Architecture B: plugin-only. No backend changes.
 */
public class NetworkClient {

    private final NovaChatPNX plugin;
    private final CoreNetworkClient core;

    /**
     * Creates a new NetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public NetworkClient(NovaChatPNX plugin, NovaChatConfig config) {
        this.plugin = plugin;
        ClientConnectionConfig connectionConfig = config.toClientConnectionConfig();
        SchedulerBridge scheduler = new PNXSchedulerBridge(plugin);
        ClientLogger logger = new PNXClientLogger(plugin);
        this.core = new CoreNetworkClient(
                connectionConfig,
                PlatformType.POWERNUKKITX,
                scheduler,
                logger,
                "config.yml",
                java.util.function.Function.identity()
        );

        // Preserve PNX chat/title handling that previously lived hard-coded in
        // ClientChannelHandler. CoreNetworkClient owns HandshakeResponse/KeepAlive.
        registerPnxHandlers();
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
     * Registers PNX-specific inbound handlers (chat/title) on the core client.
     *
     * <p>These previously lived hard-coded in {@code ClientChannelHandler.channelRead0}.
     * They hop to the PNX main thread via {@code scheduleTask} for thread safety,
     * matching the historical behaviour exactly.
     */
    private void registerPnxHandlers() {
        registerHandler(ChatMessagePacket.class, this::handleChatMessage);
        registerHandler(TitlePacket.class, this::handleTitleMessage);
    }

    /**
     * Forwards an incoming chat message to the chat interceptor on the main thread.
     */
    private void handleChatMessage(ChatMessagePacket packet) {
        String senderName = packet.getSenderName();
        String channelId = packet.getChannelId();
        String content = packet.getContent();
        plugin.getServer().getScheduler().scheduleTask(plugin, () ->
                plugin.getChatInterceptor().displayIncomingMessage(
                        senderName,
                        channelId,
                        content,
                        packet.getPlaceholders()
                ));
    }

    /**
     * Broadcasts an incoming title to all online players on the main thread.
     */
    private void handleTitleMessage(TitlePacket packet) {
        String title = packet.getTitle();
        String subtitle = packet.getSubtitle();
        int fadeIn = packet.getFadeIn();
        int stay = packet.getStay();
        int fadeOut = packet.getFadeOut();
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            String coloredTitle = plugin.getMessageFormatter().colorize(title);
            String coloredSubtitle = plugin.getMessageFormatter().colorize(subtitle);
            plugin.getServer().getOnlinePlayers().values().forEach(player ->
                    player.sendTitle(coloredTitle, coloredSubtitle, fadeIn, stay, fadeOut));
        });
    }

    /**
     * PNX scheduler adapter: seconds-based delays via the Nukkit/PNX scheduler.
     *
     * <p>{@code runLater} converts seconds to ticks ({@code delay * 20}). Async hops
     * use {@code scheduleAsyncTask}; reconnect itself re-enters {@code connect} from
     * the core, which is safe off-thread.
     */
    static final class PNXSchedulerBridge implements SchedulerBridge {
        private final NovaChatPNX plugin;

        PNXSchedulerBridge(NovaChatPNX plugin) {
            this.plugin = plugin;
        }

        @Override
        public void runAsync(Runnable task) {
            plugin.getServer().getScheduler().scheduleAsyncTask(plugin, new cn.nukkit.scheduler.AsyncTask() {
                @Override
                public void onRun() {
                    task.run();
                }
            });
        }

        @Override
        public void runLater(Runnable task, long delaySeconds) {
            int ticks = (int) Math.max(0, delaySeconds) * 20;
            plugin.getServer().getScheduler().scheduleDelayedTask(plugin, task::run, ticks);
        }
    }

    /**
     * Maps the PNX SLF-style logger + plugin debug gate onto {@link ClientLogger}.
     */
    static final class PNXClientLogger implements ClientLogger {
        private final NovaChatPNX plugin;

        PNXClientLogger(NovaChatPNX plugin) {
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
                plugin.getLogger().error(message, cause);
            }
        }
    }
}
