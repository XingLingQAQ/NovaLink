package com.nova.chat.bukkit.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.common.protocol.NovaProtocol;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.codec.PacketDecoder;
import com.nova.chat.common.protocol.codec.PacketEncoder;
import com.nova.chat.common.protocol.codec.Varint21FrameDecoder;
import com.nova.chat.common.protocol.codec.Varint21LengthFieldPrepender;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;
import com.nova.chat.common.protocol.packets.HandshakePacket;
import com.nova.chat.common.protocol.packets.HandshakeResponsePacket;
import com.nova.chat.common.protocol.packets.KeepAlivePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Netty-based network client for connecting to NovaLink backend.
 * Implements automatic reconnection logic and packet handling.
 * 
 * Requirements: 1.1, 1.4
 */
public class NetworkClient {

    private final NovaChatBukkit plugin;
    private final NovaChatConfig config;
    private final PacketRegistry packetRegistry;
    
    private EventLoopGroup workerGroup;
    private Channel channel;
    
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int MAX_RECONNECT_DELAY = 30; // seconds
    
    /** Packet handlers by packet class */
    private final Map<Class<? extends Packet>, Consumer<Packet>> packetHandlers = new ConcurrentHashMap<>();

    /** Pending request contexts for mapping responses back to players */
    private final Map<java.util.UUID, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private static final long REQUEST_TIMEOUT_MS = 30_000;

    /** Known channel IDs from backend ConfigSync (used for tab completion UX). */
    private final java.util.Set<String> knownChannelIds = ConcurrentHashMap.newKeySet();
    
    /** Pending authentication future */
    private CompletableFuture<Boolean> authFuture;

    private static final class PendingRequest {
        private final java.util.UUID playerId;
        private final String kind; // "channel" | "admin"
        private final String action;
        private final String channelId;
        private final String previousChannel;
        private final long createdAt;

        private PendingRequest(java.util.UUID playerId, String kind, String action, String channelId, String previousChannel) {
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
        this.config = config;
        this.packetRegistry = NovaProtocol.createRegistry();
        
        // Register default packet handlers
        registerDefaultHandlers();
    }

    /**
     * Connects to the NovaLink backend.
     *
     * @param host the backend host
     * @param port the backend port
     * @return a future that completes with true if connection and authentication succeed
     */
    public CompletableFuture<Boolean> connect(String host, int port) {
        if (connected.get()) {
            return CompletableFuture.completedFuture(true);
        }
        
        authFuture = new CompletableFuture<>();
        
        workerGroup = new NioEventLoopGroup();
        
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    
                    // Frame codecs
                    pipeline.addLast("frameDecoder", new Varint21FrameDecoder());
                    pipeline.addLast("framePrepender", new Varint21LengthFieldPrepender());
                    
                    // Packet codecs
                    pipeline.addLast("packetDecoder", new PacketDecoder(packetRegistry));
                    pipeline.addLast("packetEncoder", new PacketEncoder(packetRegistry));
                    
                    // Handler
                    pipeline.addLast("handler", new ClientChannelHandler(NetworkClient.this));
                }
            });
        
        plugin.debug("Connecting to NovaLink backend at " + host + ":" + port);
        
        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                channel = future.channel();
                connected.set(true);
                reconnectAttempts.set(0);
                plugin.debug("TCP connection established, sending handshake...");
                
                // Send handshake packet
                sendHandshake();
            } else {
                plugin.getLogger().warning("Failed to connect to NovaLink: " + future.cause().getMessage());
                authFuture.complete(false);
                scheduleReconnect();
            }
        });
        
        return authFuture;
    }

    /**
     * Disconnects from the backend.
     */
    public void disconnect() {
        reconnecting.set(false);
        authenticated.set(false);
        
        if (channel != null && channel.isActive()) {
            channel.close().syncUninterruptibly();
            channel = null;
        }
        
        connected.set(false);
        
        if (workerGroup != null && !workerGroup.isShutdown()) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        
        plugin.debug("Disconnected from NovaLink backend");
    }

    /**
     * Sends a packet to the backend.
     *
     * @param packet the packet to send
     */
    public void sendPacket(Packet packet) {
        if (channel != null && channel.isActive()) {
            trackPendingRequest(packet);
            channel.writeAndFlush(packet);
            plugin.debug("Sent packet: " + packet.getClass().getSimpleName());
        } else {
            plugin.debug("Cannot send packet: not connected");
        }
    }

    /**
     * Registers a packet handler.
     *
     * @param packetClass the packet class to handle
     * @param handler the handler function
     * @param <T> the packet type
     */
    @SuppressWarnings("unchecked")
    public <T extends Packet> void registerHandler(Class<T> packetClass, Consumer<T> handler) {
        packetHandlers.put(packetClass, (Consumer<Packet>) handler);
    }

    /**
     * Handles an incoming packet.
     *
     * @param packet the received packet
     */
    void handlePacket(Packet packet) {
        plugin.debug("Received packet: " + packet.getClass().getSimpleName());
        
        Consumer<Packet> handler = packetHandlers.get(packet.getClass());
        if (handler != null) {
            handler.accept(packet);
        } else {
            plugin.debug("No handler registered for packet: " + packet.getClass().getSimpleName());
        }
    }

    /**
     * Called when the connection is lost.
     */
    void onDisconnect() {
        connected.set(false);
        authenticated.set(false);
        
        if (!reconnecting.get()) {
            plugin.getLogger().warning("Lost connection to NovaLink backend");
            scheduleReconnect();
        }
    }

    /**
     * Schedules a reconnection attempt with exponential backoff.
     */
    private void scheduleReconnect() {
        if (reconnecting.get()) {
            return;
        }
        
        int attempts = reconnectAttempts.incrementAndGet();
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            plugin.getLogger().severe("Max reconnection attempts reached. Please check backend status and use /nc reload to retry.");
            reconnectAttempts.set(0);
            return;
        }
        
        reconnecting.set(true);
        
        // Exponential backoff: 1s, 2s, 4s, 8s, ... up to MAX_RECONNECT_DELAY
        int delay = Math.min((int) Math.pow(2, attempts - 1), MAX_RECONNECT_DELAY);
        
        plugin.getLogger().info("Reconnecting to NovaLink in " + delay + " seconds (attempt " + attempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");
        
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            reconnecting.set(false);
            
            // Cleanup old resources
            if (workerGroup != null && !workerGroup.isShutdown()) {
                workerGroup.shutdownGracefully();
            }
            
            // Attempt reconnection
            connect(config.getBackendHost(), config.getBackendPort());
        }, delay * 20L); // Convert seconds to ticks
    }

    /**
     * Sends the handshake packet for authentication.
     */
    private void sendHandshake() {
        String passwordHash = hashPassword(config.getPassword());
        
        HandshakePacket handshake = new HandshakePacket(
            NovaProtocol.PROTOCOL_VERSION,
            config.getUsername(),
            passwordHash,
            PlatformType.BUKKIT
        );
        
        sendPacket(handshake);
    }

    /**
     * Registers default packet handlers.
     */
    private void registerDefaultHandlers() {
        // Handle handshake response
        registerHandler(HandshakeResponsePacket.class, this::handleHandshakeResponse);
        
        // Handle keep-alive
        registerHandler(KeepAlivePacket.class, this::handleKeepAlive);

        // Handle title messages (Server -> Client)
        registerHandler(TitlePacket.class, this::handleTitle);

        // Handle config sync (Server -> Client)
        registerHandler(ConfigSyncPacket.class, this::handleConfigSync);

        // Handle responses to commands (Server -> Client)
        registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
        registerHandler(AdminActionResponsePacket.class, this::handleAdminActionResponse);
    }

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

    public java.util.Set<String> getKnownChannelIds() {
        return java.util.Collections.unmodifiableSet(knownChannelIds);
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

    private void trackPendingRequest(Packet packet) {
        cleanupExpiredRequests();

        if (packet instanceof ChannelActionPacket channelActionPacket) {
            java.util.UUID playerId = extractUuid(channelActionPacket.getExtra("playerId"));
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

    private java.util.UUID extractUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Handles the handshake response packet.
     * Requirements: 27.3 - Outputs clear error message when version is incompatible
     */
    private void handleHandshakeResponse(HandshakeResponsePacket response) {
        if (response.isSuccess()) {
            authenticated.set(true);
            plugin.getLogger().info("Successfully authenticated with NovaLink backend");
            
            if (authFuture != null && !authFuture.isDone()) {
                authFuture.complete(true);
            }
        } else {
            authenticated.set(false);
            plugin.getLogger().severe("Authentication failed: " + response.getErrorCode() + " - " + response.getMessage());
            
            if (authFuture != null && !authFuture.isDone()) {
                authFuture.complete(false);
            }
            
            // Handle specific error codes with clear messages
            switch (response.getErrorCode()) {
                case "NC-401":
                    plugin.getLogger().severe("Please check your username and password in config.yml");
                    break;
                case "NC-420":
                    plugin.getLogger().severe("=================================================");
                    plugin.getLogger().severe("PROTOCOL VERSION MISMATCH!");
                    plugin.getLogger().severe("Your NovaChat plugin version is incompatible with the NovaLink backend.");
                    plugin.getLogger().severe("Please update your plugin to match the backend protocol version.");
                    plugin.getLogger().severe("Current plugin protocol version: " + NovaProtocol.PROTOCOL_VERSION);
                    plugin.getLogger().severe("=================================================");
                    break;
                default:
                    // Generic error handling
                    break;
            }
        }
    }

    /**
     * Handles keep-alive packets by responding immediately.
     */
    private void handleKeepAlive(KeepAlivePacket packet) {
        // Echo back the keep-alive
        KeepAlivePacket response = new KeepAlivePacket(packet.getTimestamp());
        response.setRequestId(packet.getRequestId());
        sendPacket(response);
    }

    /**
     * Hashes a password using SHA-256.
     *
     * @param password the password to hash
     * @return the hex-encoded hash
     */
    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Checks if the client is connected.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return connected.get() && channel != null && channel.isActive();
    }

    /**
     * Checks if the client is authenticated.
     *
     * @return true if authenticated
     */
    public boolean isAuthenticated() {
        return authenticated.get();
    }

    /**
     * Gets the packet registry.
     *
     * @return the packet registry
     */
    public PacketRegistry getPacketRegistry() {
        return packetRegistry;
    }
}
