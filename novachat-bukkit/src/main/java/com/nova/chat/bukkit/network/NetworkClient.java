package com.nova.chat.bukkit.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;
import com.nova.chat.common.protocol.packets.TitlePacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Bukkit NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Netty bootstrap, handshake/keepalive defaults, handler map, and reconnect
 * policy live in client-core. This class only keeps Bukkit-specific UX local:
 * the pending-request correlation tracker, ConfigSync parsing (world-restricted
 * channels + known channel IDs for tab complete), Title rendering on the main
 * thread, and Channel/Admin action-response correlation. Those are registered
 * as handlers on the core so dispatch still flows through the shared transport.
 *
 * <p>Requirements: 1.1, 1.4
 */
public class NetworkClient {

    private final NovaChatBukkit plugin;
    private final CoreNetworkClient core;

    /** Pending request contexts for mapping responses back to players (Bukkit UX). */
    private final Map<UUID, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private static final long REQUEST_TIMEOUT_MS = 30_000;

    /** Known channel IDs from backend ConfigSync (used for tab completion UX). */
    private final java.util.Set<String> knownChannelIds = ConcurrentHashMap.newKeySet();

    private static final class PendingRequest {
        private final UUID playerId;
        private final String kind; // "channel" | "admin"
        private final String action;
        private final String channelId;
        private final String previousChannel;
        private final long createdAt;

        private PendingRequest(UUID playerId, String kind, String action, String channelId, String previousChannel) {
            this.playerId = playerId;
            this.kind = kind;
            this.action = action;
            this.channelId = channelId;
            this.previousChannel = previousChannel;
            this.createdAt = System.currentTimeMillis();
        }
    }

    /**
     * Creates a new NetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public NetworkClient(NovaChatBukkit plugin, NovaChatConfig config) {
        this.plugin = plugin;
        ClientConnectionConfig connectionConfig = config.toClientConnectionConfig();
        SchedulerBridge scheduler = new BukkitSchedulerBridge(plugin);
        ClientLogger logger = new BukkitClientLogger(plugin);
        this.core = new CoreNetworkClient(
                connectionConfig,
                PlatformType.BUKKIT,
                scheduler,
                logger,
                "config.yml",
                Function.identity()
        );

        // Register Bukkit-specific (non-default) handlers on the core.
        // HandshakeResponse and KeepAlive are registered by the core itself.
        core.registerHandler(TitlePacket.class, this::handleTitle);
        core.registerHandler(ConfigSyncPacket.class, this::handleConfigSync);
        core.registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
        core.registerHandler(AdminActionResponsePacket.class, this::handleAdminActionResponse);
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
     * Sends a packet to the backend, first recording any pending request context
     * needed to correlate the eventual response back to a player.
     *
     * @param packet the packet to send
     */
    public void sendPacket(Packet packet) {
        trackPendingRequest(packet);
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
     * Gets the known channel IDs cached from the latest ConfigSync, for tab completion.
     *
     * @return an unmodifiable view of the known channel IDs
     */
    public java.util.Set<String> getKnownChannelIds() {
        return java.util.Collections.unmodifiableSet(knownChannelIds);
    }

    // --- Bukkit-specific handlers ---

    /**
     * Handles title packets by displaying them to players whose active channel matches the packet channel.
     */
    private void handleTitle(TitlePacket packet) {
        // Must run on main thread for Bukkit API
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                String channelId = packet.getChannelId();
                String title = packet.getTitle();
                String subtitle = packet.getSubtitle();

                // Translate color codes (& and hex)
                var formatter = plugin.getChatInterceptor().getMessageFormatter();
                String renderedTitle = formatter.translateColorCodes(title != null ? title : "");
                String renderedSubtitle = formatter.translateColorCodes(subtitle != null ? subtitle : "");

                for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
                    var state = plugin.getChatInterceptor().getOrCreateState(player);
                    if (state != null && channelId != null && channelId.equals(state.getActiveChannel())) {
                        player.sendTitle(renderedTitle, renderedSubtitle,
                                packet.getFadeIn(), packet.getStay(), packet.getFadeOut());
                    }
                }
            } catch (Exception e) {
                plugin.debug("Failed to handle TitlePacket: " + e.getMessage(), e);
            }
        });
    }

    private void handleConfigSync(ConfigSyncPacket packet) {
        String json = packet.getConfigJson();
        if (json == null || json.isBlank()) {
            return;
        }

        // Parse on Netty thread, then apply on main thread
        java.util.Map<String, java.util.List<String>> worldRestricted = extractWorldRestrictedChannels(json);
        replaceKnownChannels(extractKnownChannelIds(json));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.getWorldMonitor().clearMappings();
            for (var entry : worldRestricted.entrySet()) {
                plugin.getWorldMonitor().registerWorldChannel(entry.getKey(), entry.getValue());
            }
            plugin.debug("Applied ConfigSync: " + worldRestricted.size() + " world-restricted channels");
        });
    }

    private java.util.Map<String, java.util.List<String>> extractWorldRestrictedChannels(String configJson) {
        java.util.Map<String, java.util.List<String>> result = new java.util.HashMap<>();

        try {
            JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();

            // Collect template world restrictions (templateId -> allowedWorlds)
            java.util.Map<String, java.util.List<String>> templateAllowedWorlds = new java.util.HashMap<>();
            if (root.has("templates") && root.get("templates").isJsonObject()) {
                JsonObject templates = root.getAsJsonObject("templates");
                for (var entry : templates.entrySet()) {
                    String templateId = entry.getKey();
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    JsonObject template = entry.getValue().getAsJsonObject();
                    java.util.List<String> worlds = toStringList(template.get("allowedWorlds"));
                    if (!worlds.isEmpty()) {
                        templateAllowedWorlds.put(templateId, worlds);
                    }
                }
            }

            // Apply only channels for this client (server instance)
            String thisClient = plugin.getNovaChatConfig().getUsername();

            if (!root.has("clients") || !root.get("clients").isJsonArray()) {
                return result;
            }

            JsonArray clients = root.getAsJsonArray("clients");
            for (JsonElement element : clients) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject client = element.getAsJsonObject();
                String username = client.has("username") ? safeString(client.get("username")) : null;
                if (username == null || thisClient == null || !username.equals(thisClient)) {
                    continue;
                }

                if (!client.has("channels") || !client.get("channels").isJsonObject()) {
                    continue;
                }

                JsonObject channels = client.getAsJsonObject("channels");
                for (var entry : channels.entrySet()) {
                    String channelId = entry.getKey();
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }

                    JsonObject channelCfg = entry.getValue().getAsJsonObject();
                    java.util.List<String> worlds = toStringList(channelCfg.get("allowedWorlds"));

                    // If channel has no explicit allowedWorlds, try template (useTemplate)
                    if (worlds.isEmpty() && channelCfg.has("useTemplate")) {
                        String templateId = safeString(channelCfg.get("useTemplate"));
                        if (templateId != null) {
                            java.util.List<String> templateWorlds = templateAllowedWorlds.get(templateId);
                            if (templateWorlds != null) {
                                worlds = templateWorlds;
                            }
                        }
                    }

                    if (!worlds.isEmpty()) {
                        result.put(channelId, worlds);
                    }
                }

                // Username is unique; we can stop after processing this client.
                break;
            }

        } catch (Exception e) {
            plugin.debug("Failed to parse ConfigSyncPacket: " + e.getMessage());
        }

        return result;
    }

    private String safeString(JsonElement element) {
        try {
            if (element == null || element.isJsonNull()) {
                return null;
            }
            String s = element.getAsString();
            return s != null && !s.isBlank() ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private java.util.List<String> toStringList(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return java.util.Collections.emptyList();
        }
        if (!element.isJsonArray()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> list = new java.util.ArrayList<>();
        for (JsonElement e : element.getAsJsonArray()) {
            String value = safeString(e);
            if (value != null) {
                list.add(value);
            }
        }
        return list;
    }

    private void replaceKnownChannels(java.util.Set<String> channels) {
        knownChannelIds.clear();
        if (channels != null) {
            knownChannelIds.addAll(channels);
        }
    }

    private java.util.Set<String> extractKnownChannelIds(String configJson) {
        java.util.Set<String> result = new java.util.HashSet<>();

        try {
            JsonObject root = JsonParser.parseString(configJson).getAsJsonObject();

            // Global channels
            if (root.has("global_channels") && root.get("global_channels").isJsonObject()) {
                JsonObject globals = root.getAsJsonObject("global_channels");
                result.addAll(globals.keySet());
            }

            // Client channels (only for this server instance)
            String thisClient = plugin.getNovaChatConfig().getUsername();
            if (thisClient == null || thisClient.isBlank()) {
                return result;
            }

            if (!root.has("clients") || !root.get("clients").isJsonArray()) {
                return result;
            }

            JsonArray clients = root.getAsJsonArray("clients");
            for (JsonElement element : clients) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject client = element.getAsJsonObject();
                String username = client.has("username") ? safeString(client.get("username")) : null;
                if (username == null || !username.equals(thisClient)) {
                    continue;
                }

                if (client.has("channels") && client.get("channels").isJsonObject()) {
                    JsonObject channels = client.getAsJsonObject("channels");
                    result.addAll(channels.keySet());
                }
                break;
            }

        } catch (Exception e) {
            // ignore, best-effort cache
        }

        return result;
    }

    private void handleChannelActionResponse(ChannelActionResponsePacket response) {
        PendingRequest pending = pendingRequests.remove(response.getRequestId());

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (pending == null) {
                plugin.debug("Received ChannelActionResponsePacket with no pending request: " + response);
                return;
            }
            if (pending.playerId == null) {
                // Likely invoked by console or missing context; log so it isn't silently lost.
                plugin.getLogger().info("ChannelActionResponse (no player context): " + response);
                return;
            }

            org.bukkit.entity.Player player = plugin.getServer().getPlayer(pending.playerId);
            if (player == null) {
                plugin.debug("ChannelActionResponsePacket target player not online: " + pending.playerId);
                return;
            }

            if (response.isSuccess()) {
                String message = formatChannelActionSuccess(response, pending);
                plugin.getMessageHelper().sendSuccess(player, message);

                // Apply local side-effects for actions that should change active channel or display extra info
                applyChannelActionSuccessSideEffects(player, response);
                return;
            }

            // Failed: show mapped error code if available
            String errorCode = response.getErrorCode();
            String errorMessage = response.getMessage();
            if (errorCode != null && !errorCode.isEmpty()) {
                plugin.getErrorHandler().sendErrorFromCode(player, errorCode, errorMessage);
            } else {
                plugin.getMessageHelper().sendError(player, errorMessage != null ? errorMessage : "操作失败");
            }

            // Rollback optimistic local channel switch if applicable and still relevant
            rollbackActiveChannelIfNeeded(player, response, pending);
        });
    }

    private void applyChannelActionSuccessSideEffects(org.bukkit.entity.Player player,
                                                      ChannelActionResponsePacket response) {
        if (response.getAction() == null) {
            return;
        }

        switch (response.getAction()) {
            case CREATE: {
                String channelId = response.getChannelId();
                if (channelId != null && !channelId.isEmpty()) {
                    plugin.getChatInterceptor().setPlayerChannel(player, channelId);
                }

                String password = response.getExtra("password");
                String passwordGenerated = response.getExtra("passwordGenerated");
                if (password != null && !password.isEmpty()) {
                    plugin.getMessageHelper().sendMessage(player, "私有频道ID: &e" + channelId + "&7，密码: &e" + password);
                    if ("true".equalsIgnoreCase(passwordGenerated)) {
                        plugin.getMessageHelper().sendSuggestion(player, "该密码为系统自动生成，请妥善保存");
                    }
                }
                break;
            }
            case INVITE: {
                String code = response.getExtra("code");
                if (code != null && !code.isEmpty()) {
                    plugin.getMessageHelper().sendMessage(player, "邀请码: &e" + code.toUpperCase() + "&7（对方执行 /nc accept " + code.toUpperCase() + "）");
                }
                break;
            }
            case ACCEPT: {
                String channelId = response.getChannelId();
                if (channelId != null && !channelId.isEmpty()) {
                    plugin.getChatInterceptor().setPlayerChannel(player, channelId);
                }
                break;
            }
            default:
                break;
        }
    }

    private void handleAdminActionResponse(AdminActionResponsePacket response) {
        PendingRequest pending = pendingRequests.remove(response.getRequestId());

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (pending == null) {
                plugin.debug("Received AdminActionResponsePacket with no pending request: " + response);
                return;
            }
            if (pending.playerId == null) {
                plugin.getLogger().info("AdminActionResponse (no player context): " + response);
                return;
            }

            org.bukkit.entity.Player player = plugin.getServer().getPlayer(pending.playerId);
            if (player == null) {
                plugin.debug("AdminActionResponsePacket target player not online: " + pending.playerId);
                return;
            }

            if (response.isSuccess()) {
                plugin.getMessageHelper().sendSuccess(player,
                        response.getMessage() != null && !response.getMessage().isEmpty()
                                ? response.getMessage()
                                : "操作成功");
            } else {
                String code = response.getErrorCode();
                String msg = response.getMessage();
                if (code != null && !code.isEmpty()) {
                    plugin.getErrorHandler().sendErrorFromCode(player, code, msg);
                } else {
                    plugin.getMessageHelper().sendError(player, msg != null ? msg : "操作失败");
                }
            }
        });
    }

    private String formatChannelActionSuccess(ChannelActionResponsePacket response, PendingRequest pending) {
        String msg = response.getMessage();
        if (msg != null && !msg.isEmpty() && !"Action accepted".equalsIgnoreCase(msg)) {
            return msg;
        }

        String channelId = response.getChannelId() != null && !response.getChannelId().isEmpty()
                ? response.getChannelId()
                : pending.channelId;

        if (response.getAction() == null) {
            return "操作成功";
        }

        switch (response.getAction()) {
            case JOIN:
                return "已加入频道 &e" + channelId;
            case LEAVE:
                return "已离开频道 &e" + channelId;
            case CREATE:
                return "创建频道请求已处理";
            case DELETE:
                return "删除频道请求已处理";
            case INVITE:
                return "邀请请求已处理";
            case ACCEPT:
                return "已接受邀请";
            case KICK:
                return "踢出请求已处理";
            case MUTE:
                return "禁言请求已处理";
            case UNMUTE:
                return "解除禁言请求已处理";
            default:
                return "操作成功";
        }
    }

    private void rollbackActiveChannelIfNeeded(org.bukkit.entity.Player player,
                                               ChannelActionResponsePacket response,
                                               PendingRequest pending) {
        if (pending.previousChannel == null || pending.previousChannel.isEmpty()) {
            return;
        }

        // Only rollback if the player's current channel is still the optimistic one
        var state = plugin.getChatInterceptor().getState(player.getUniqueId());
        String current = state != null ? state.getActiveChannel() : null;

        if (response.getAction() == null) {
            return;
        }

        switch (response.getAction()) {
            case JOIN:
            case ACCEPT:
                if (current != null && pending.channelId != null && current.equals(pending.channelId)) {
                    plugin.getChatInterceptor().setPlayerChannel(player, pending.previousChannel);
                }
                break;
            case LEAVE:
                // Our optimistic leave changes active channel to default channel.
                String defaultChannel = plugin.getNovaChatConfig().getDefaultChannel();
                if (current != null && defaultChannel != null && current.equals(defaultChannel)) {
                    plugin.getChatInterceptor().setPlayerChannel(player, pending.previousChannel);
                }
                break;
            default:
                break;
        }
    }

    // --- Pending-request tracker (Bukkit UX correlation) ---

    private void trackPendingRequest(Packet packet) {
        cleanupExpiredRequests();

        if (packet instanceof ChannelActionPacket channelActionPacket) {
            UUID playerId = extractUuid(channelActionPacket.getExtra("playerId"));
            if (playerId == null) {
                playerId = extractUuid(channelActionPacket.getExtra("player_id"));
            }
            if (playerId == null) {
                // Some actions use operatorId/targetId instead of playerId
                playerId = extractUuid(channelActionPacket.getExtra("operatorId"));
            }

            String previousChannel = null;
            if (playerId != null) {
                var state = plugin.getChatInterceptor().getState(playerId);
                if (state != null) {
                    previousChannel = state.getActiveChannel();
                }
            }

            pendingRequests.put(packet.getRequestId(), new PendingRequest(
                    playerId,
                    "channel",
                    channelActionPacket.getAction() != null ? channelActionPacket.getAction().name() : "UNKNOWN",
                    channelActionPacket.getChannelId(),
                    previousChannel
            ));
            return;
        }

        if (packet instanceof AdminActionPacket adminActionPacket) {
            pendingRequests.put(packet.getRequestId(), new PendingRequest(
                    adminActionPacket.getPlayerId(),
                    "admin",
                    adminActionPacket.getAction() != null ? adminActionPacket.getAction().name() : "UNKNOWN",
                    adminActionPacket.getTarget(),
                    null
            ));
        }
    }

    private void cleanupExpiredRequests() {
        long now = System.currentTimeMillis();
        pendingRequests.entrySet().removeIf(e -> now - e.getValue().createdAt > REQUEST_TIMEOUT_MS);
    }

    private UUID extractUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Bukkit scheduler adapter: seconds-based delays converted to ticks.
     */
    static final class BukkitSchedulerBridge implements SchedulerBridge {
        private final NovaChatBukkit plugin;

        BukkitSchedulerBridge(NovaChatBukkit plugin) {
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
    static final class BukkitClientLogger implements ClientLogger {
        private final NovaChatBukkit plugin;

        BukkitClientLogger(NovaChatBukkit plugin) {
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
                plugin.getLogger().log(Level.SEVERE, message, cause);
            }
        }
    }
}
