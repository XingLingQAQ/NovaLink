package com.nova.chat.bukkit.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.format.DurationFormatter;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PacketRegistry;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.TitlePacket;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

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
    private final MentionNotifier mentionNotifier = new MentionNotifier();

    /**
     * Sentinel UUID used by console/RCON-originated moderation commands (see
     * MuteCommand/KickCommand). When a response's pending player id is this
     * value, there is no online Bukkit Player to render the outcome to, so the
     * result is logged to the server console instead of being silently dropped.
     */
    private static final UUID CONSOLE_SENTINEL_UUID =
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** Pending request contexts for mapping responses back to players (Bukkit UX). */
    private final Map<UUID, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private static final long REQUEST_TIMEOUT_MS = NovaConstants.PENDING_REQUEST_TIMEOUT_MS;

    /** Shared known-channel registry (populated from ConfigSync, UX-DESIGN §2.1). */
    private final com.nova.chat.client.channel.KnownChannelRegistry knownChannelRegistry;

    private static final class PendingRequest {
        private final UUID playerId;
        private final String kind; // "channel" | "admin"
        private final String action;
        private final String channelId;
        private final String previousChannel;
        private final long createdAt;
        // UX-DESIGN §5: target-side notification correlation for KICK/MUTE.
        // The backend's ChannelActionResponsePacket does not echo these, so we
        // stash them from the outgoing request to render a personalized notice to
        // the kicked/muted player once the response comes back.
        private final UUID targetId;
        private final String operatorName;
        private final String durationSeconds;
        // True when the outgoing LEAVE targets the player's current active channel.
        // Only such a leave optimistically resets the active channel to the default,
        // so the rollback must key off this flag rather than guessing from the
        // post-leave active channel value (BUG-H5/M7).
        private final boolean leavingCurrent;

        private PendingRequest(UUID playerId, String kind, String action, String channelId, String previousChannel,
                               UUID targetId, String operatorName, String durationSeconds, boolean leavingCurrent) {
            this.playerId = playerId;
            this.kind = kind;
            this.action = action;
            this.channelId = channelId;
            this.previousChannel = previousChannel;
            this.createdAt = System.currentTimeMillis();
            this.targetId = targetId;
            this.operatorName = operatorName;
            this.durationSeconds = durationSeconds;
            this.leavingCurrent = leavingCurrent;
        }

        private boolean isLeavingCurrent() {
            return leavingCurrent;
        }
    }

    /**
     * Creates a new NetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     */
    public NetworkClient(NovaChatBukkit plugin, NovaChatConfig config,
                         com.nova.chat.client.channel.KnownChannelRegistry knownChannelRegistry) {
        this.plugin = plugin;
        this.knownChannelRegistry = java.util.Objects.requireNonNull(knownChannelRegistry, "knownChannelRegistry");
        ClientConnectionConfig connectionConfig = config.toClientConnectionConfig();
        SchedulerBridge scheduler = new BukkitSchedulerBridge(plugin);
        ClientLogger logger = new BukkitClientLogger(plugin);
        String serverVersion = plugin.getServer().getVersion();
        this.core = new CoreNetworkClient(
                connectionConfig,
                PlatformType.BUKKIT,
                scheduler,
                logger,
                "config.yml",
                Function.identity(),
                serverVersion
        );

        // Register Bukkit-specific (non-default) handlers on the core.
        // HandshakeResponse and KeepAlive are registered by the core itself.
        core.registerHandler(TitlePacket.class, this::handleTitle);
        core.registerHandler(ConfigSyncPacket.class, this::handleConfigSync);
        core.registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
        core.registerHandler(AdminActionResponsePacket.class, this::handleAdminActionResponse);
        core.registerHandler(MentionPacket.class, this::handleMention);
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
        return knownChannelRegistry.getAll();
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

    /**
     * Handles a mention notification packet by playing a sound and showing a
     * title to the mentioned player (UX-DESIGN §4.1, Requirements 11.2).
     *
     * <p>The mentioned player must be online on this server; packets for
     * players on other servers are expected to be routed there by the backend.
     */
    private void handleMention(MentionPacket packet) {
        // Must run on main thread for Bukkit API
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                UUID mentionedId = packet.getMentionedId();
                UUID mentionerId = packet.getMentionerId();
                if (mentionedId == null || mentionerId == null) {
                    return;
                }
                org.bukkit.entity.Player player = plugin.getServer().getPlayer(mentionedId);
                if (player == null) {
                    return; // not on this server
                }

                mentionNotifier.notifyOrSkip(mentionedId, mentionerId, () -> {
                    var formatter = plugin.getChatInterceptor().getMessageFormatter();
                    String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
                    String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
                    String title = formatter.translateColorCodes("&e" + mentioner);
                    String subtitle = formatter.translateColorCodes(
                            I18n.tr(mentionedId, "chat.mention.subtitle", channelId));

                    player.sendTitle(title, subtitle,
                            MentionNotifier.DEFAULT_FADE_IN,
                            MentionNotifier.DEFAULT_STAY,
                            MentionNotifier.DEFAULT_FADE_OUT);
                    playMentionSound(player);
                });
            } catch (Exception e) {
                plugin.debug("Failed to handle MentionPacket: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Plays the default mention notification sound to a player.
     */
    private void playMentionSound(org.bukkit.entity.Player player) {
        // The common DEFAULT_SOUND constant names the ENTITY_EXPERIENCE_ORB_PICKUP
        // enum, which we resolve directly here to avoid the deprecated
        // Sound.valueOf(String) removal API.
        player.playSound(player.getLocation(),
                org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
    }

    private void handleConfigSync(ConfigSyncPacket packet) {
        String json = packet.getConfigJson();
        if (json == null || json.isBlank()) {
            return;
        }

        // Parse once and feed the parsed root to both extractors. Repeated
        // JsonParser.parseString for the same payload (BUG-M5) is wasteful on
        // every ConfigSync, so we parse a single JsonObject here and reuse it.
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            plugin.debug("Failed to parse ConfigSyncPacket JSON: " + e.getMessage());
            return;
        }

        java.util.Map<String, java.util.List<String>> worldRestricted = extractWorldRestrictedChannels(root);
        replaceKnownChannels(extractKnownChannelIds(root));
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.getWorldMonitor().clearMappings();
            for (var entry : worldRestricted.entrySet()) {
                plugin.getWorldMonitor().registerWorldChannel(entry.getKey(), entry.getValue());
            }
            plugin.debug("Applied ConfigSync: " + worldRestricted.size() + " world-restricted channels");
        });
    }

    private java.util.Map<String, java.util.List<String>> extractWorldRestrictedChannels(JsonObject root) {
        java.util.Map<String, java.util.List<String>> result = new java.util.HashMap<>();

        try {
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
        knownChannelRegistry.replaceAll(channels);
    }

    private java.util.Set<String> extractKnownChannelIds(JsonObject root) {
        String thisClient = plugin.getNovaChatConfig().getUsername();
        java.util.Set<String> result = new java.util.HashSet<>();

        // Global channels
        JsonObject gc = root.getAsJsonObject("global_channels");
        if (gc != null) {
            result.addAll(gc.keySet());
        }

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
                result.addAll(client.getAsJsonObject("channels").keySet());
            }
            break;
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
                // Console/RCON-originated action: the operator is the all-zeros
                // sentinel UUID and is not an online Player. Log the backend's
                // outcome to the server console so the RCON/console operator sees
                // success or the mapped error, instead of silently dropping it.
                // Still attempt the target-side kick/mute notice (no-ops when the
                // target is on another server).
                if (CONSOLE_SENTINEL_UUID.equals(pending.playerId)) {
                    if (response.isSuccess()) {
                        String msg = formatChannelActionSuccess(response, pending);
                        plugin.getLogger().info("[NovaChat console] " + msg);
                    } else {
                        String errorCode = response.getErrorCode();
                        String errorMessage = response.getMessage();
                        String text = (errorCode != null && !errorCode.isEmpty())
                                ? errorCode + " | " + (errorMessage != null ? errorMessage : "操作失败")
                                : (errorMessage != null ? errorMessage : "操作失败");
                        plugin.getLogger().warning("[NovaChat console] " + text);
                    }
                    notifyKickMuteTarget(response, pending);
                    return;
                }
                plugin.debug("ChannelActionResponsePacket target player not online: " + pending.playerId);
                return;
            }

            if (response.isSuccess()) {
                String message = formatChannelActionSuccess(response, pending);
                plugin.getMessageHelper().sendSuccess(player, message);

                // Apply local side-effects for actions that should change active channel or display extra info
                applyChannelActionSuccessSideEffects(player, response);

                // UX-DESIGN §5: KICK/MUTE target-side notification. The operator's
                // "踢出/禁言请求已处理" confirmation stays untouched above; this only
                // adds a personalized notice to the kicked/muted player themselves.
                notifyKickMuteTarget(response, pending);
                return;
            }

            // Failed: show mapped error code if available
            String errorCode = response.getErrorCode();
            String errorMessage = response.getMessage();
            if (errorCode != null && !errorCode.isEmpty()) {
                plugin.getErrorHandler().sendErrorFromCode(player, errorCode, errorMessage);
            } else {
                plugin.getMessageHelper().sendError(player,
                        errorMessage != null ? errorMessage : I18n.tr(pending.playerId, "chat.action.failed"));
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
            case JOIN: {
                // §7: flash the active channel + current mode on the action bar.
                sendChannelActionBar(player);
                break;
            }
            case LEAVE: {
                // §7: after a successful leave the active channel is the default;
                // flash the action bar so the player sees where they landed.
                sendChannelActionBar(player);
                break;
            }
            case CREATE: {
                String channelId = response.getChannelId();
                if (channelId != null && !channelId.isEmpty()) {
                    plugin.getChatInterceptor().setPlayerChannel(player, channelId);
                }

                String password = response.getExtra("password");
                String passwordGenerated = response.getExtra("passwordGenerated");
                if (password != null && !password.isEmpty()) {
                    plugin.getMessageHelper().sendMessage(player,
                            I18n.tr(player.getUniqueId(), "chat.create.result", channelId, password));
                    if ("true".equalsIgnoreCase(passwordGenerated)) {
                        plugin.getMessageHelper().sendSuggestion(player,
                                I18n.tr(player.getUniqueId(), "chat.create.password_saved"));
                    }
                }
                break;
            }
            case INVITE: {
                String code = response.getExtra("code");
                if (code != null && !code.isEmpty()) {
                    String upper = code.toUpperCase();
                    plugin.getMessageHelper().sendMessage(player,
                            I18n.tr(player.getUniqueId(), "chat.invite.code", upper));
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

    /**
     * Notifies the kicked/muted player directly (UX-DESIGN §5).
     *
     * <p>Must run on the main thread (the caller already hops there). If the target
     * is offline or on another server there is nothing to do here; the backend is
     * expected to route the notice to the server hosting the target. Falls back to
     * the request's tracked {@code operatorName}/{@code duration} since the response
     * packet does not echo them.
     */
    private void notifyKickMuteTarget(ChannelActionResponsePacket response, PendingRequest pending) {
        if (response.getAction() == null) {
            return;
        }
        if (response.getAction() != ChannelAction.KICK && response.getAction() != ChannelAction.MUTE) {
            return;
        }
        if (pending.targetId == null) {
            return;
        }
        org.bukkit.entity.Player target = plugin.getServer().getPlayer(pending.targetId);
        if (target == null) {
            return; // not on this server
        }

        String channelId = response.getChannelId() != null && !response.getChannelId().isEmpty()
                ? response.getChannelId()
                : pending.channelId;
        String operator = pending.operatorName != null && !pending.operatorName.isEmpty()
                ? pending.operatorName
                : I18n.tr(pending.targetId, "notice.operator.fallback");

        if (response.getAction() == ChannelAction.KICK) {
            String title = com.nova.chat.bukkit.command.MessageHelper.colorize(
                    I18n.tr(pending.targetId, "chat.notice.kick_title"));
            String subtitle = com.nova.chat.bukkit.command.MessageHelper.colorize(
                    I18n.tr(pending.targetId, "chat.notice.kick_subtitle", operator, channelId));
            target.sendTitle(title, subtitle,
                    com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_IN,
                    com.nova.chat.common.chat.MentionNotifier.DEFAULT_STAY,
                    com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_OUT);
            target.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent(com.nova.chat.bukkit.command.MessageHelper.colorize(
                            I18n.tr(pending.targetId, "chat.notice.kick_actionbar", operator, channelId))));
            return;
        }

        // MUTE
        String durationText = formatTrackedDuration(pending.durationSeconds);
        String title = com.nova.chat.bukkit.command.MessageHelper.colorize(
                I18n.tr(pending.targetId, "chat.notice.mute_title"));
        String subtitle = com.nova.chat.bukkit.command.MessageHelper.colorize(
                I18n.tr(pending.targetId, "chat.notice.mute_subtitle", channelId, durationText));
        target.sendTitle(title, subtitle,
                com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_IN,
                com.nova.chat.common.chat.MentionNotifier.DEFAULT_STAY,
                com.nova.chat.common.chat.MentionNotifier.DEFAULT_FADE_OUT);
        target.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                new TextComponent(com.nova.chat.bukkit.command.MessageHelper.colorize(
                        I18n.tr(pending.targetId, "chat.notice.mute_actionbar", durationText, channelId))));
    }

    /** Formats a duration given as a seconds string, or "一段时间" if unknown. */
    private String formatTrackedDuration(String durationSeconds) {
        return DurationFormatter.formatSeconds(durationSeconds);
    }

    /**
     * Flashes a one-shot action bar with the player's current channel and chat
     * mode after a successful join/leave (UX-DESIGN §7). Vanilla action-bar
     * fade handles the ~3s dismissal; no polling.
     */
    private void sendChannelActionBar(org.bukkit.entity.Player player) {
        PlayerChannelState state = plugin.getChatInterceptor().getState(player.getUniqueId());
        String channelId = (state != null) ? state.getActiveChannel() : null;
        if (channelId == null || channelId.isEmpty()) {
            return;
        }
        ChatMode mode = (state != null) ? state.getChatMode() : null;
        String text = com.nova.chat.bukkit.command.MessageHelper.colorize(
                com.nova.chat.client.command.PlayerMessages.currentChannelBar(player.getUniqueId(), channelId, mode));
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
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
                // Console/RCON-originated admin action: log the outcome to the
                // server console so the operator sees it, instead of dropping it.
                if (CONSOLE_SENTINEL_UUID.equals(pending.playerId)) {
                    if (response.isSuccess()) {
                        plugin.getLogger().info("[NovaChat console] " +
                                (response.getMessage() != null && !response.getMessage().isEmpty()
                                        ? response.getMessage() : "操作成功"));
                    } else {
                        String code = response.getErrorCode();
                        String msg = response.getMessage();
                        String text = (code != null && !code.isEmpty())
                                ? code + " | " + (msg != null ? msg : "操作失败")
                                : (msg != null ? msg : "操作失败");
                        plugin.getLogger().warning("[NovaChat console] " + text);
                    }
                    return;
                }
                plugin.debug("AdminActionResponsePacket target player not online: " + pending.playerId);
                return;
            }

            if (response.isSuccess()) {
                plugin.getMessageHelper().sendSuccess(player,
                        response.getMessage() != null && !response.getMessage().isEmpty()
                                ? response.getMessage()
                                : I18n.tr(pending.playerId, "chat.action.success"));
            } else {
                String code = response.getErrorCode();
                String msg = response.getMessage();
                if (code != null && !code.isEmpty()) {
                    plugin.getErrorHandler().sendErrorFromCode(player, code, msg);
                } else {
                    plugin.getMessageHelper().sendError(player,
                            msg != null ? msg : I18n.tr(pending.playerId, "chat.action.failed"));
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
            return I18n.tr(pending.playerId, "chat.action.success");
        }

        switch (response.getAction()) {
            case JOIN:
                return I18n.tr(pending.playerId, "chat.join.joined", channelId);
            case LEAVE:
                return I18n.tr(pending.playerId, "chat.action.leave_simple", channelId);
            case CREATE:
                return I18n.tr(pending.playerId, "chat.action.create_processed");
            case DELETE:
                return I18n.tr(pending.playerId, "chat.action.delete_processed");
            case INVITE:
                return I18n.tr(pending.playerId, "chat.action.invite_processed");
            case ACCEPT:
                return I18n.tr(pending.playerId, "chat.action.accepted");
            case KICK:
                return I18n.tr(pending.playerId, "chat.action.kick_processed");
            case MUTE:
                return I18n.tr(pending.playerId, "chat.action.mute_processed");
            case UNMUTE:
                return I18n.tr(pending.playerId, "chat.action.unmute_processed");
            default:
                return I18n.tr(pending.playerId, "chat.action.success");
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
                // Only a leave of the current channel optimistically resets the
                // active channel to default, so only such a pending request can
                // roll back. Key off the explicit flag (set by LeaveCommand) rather
                // than guessing from current.equals(defaultChannel), which would
                // spuriously trigger when a non-current leave fails (BUG-H5/M7).
                if (pending.isLeavingCurrent()) {
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

            // UX-DESIGN §5: KICK/MUTE target-side notification needs target id +
            // operator display name + mute duration. These travel on the request
            // packet's extras (set by KickCommand/MuteCommand), not on the response.
            UUID targetId = extractUuid(channelActionPacket.getExtra("targetId"));
            String operatorName = channelActionPacket.getExtra("operatorName");
            String durationSeconds = channelActionPacket.getExtra("duration");

            // BUG-H5/M7: a LEAVE only optimistically resets the active channel to
            // the default when it targets the player's current channel. LeaveCommand
            // calls the shared service with channelId == active channel in that case,
            // and the service sends before clearing membership, so at track time the
            // state's active channel still equals the leave target. Derive the flag
            // from that equality rather than a separate extra so we don't have to
            // thread a new field through the shared client-core service.
            boolean leavingCurrent = ChannelAction.LEAVE.equals(channelActionPacket.getAction())
                    && previousChannel != null
                    && previousChannel.equals(channelActionPacket.getChannelId());

            pendingRequests.put(packet.getRequestId(), new PendingRequest(
                    playerId,
                    "channel",
                    channelActionPacket.getAction() != null ? channelActionPacket.getAction().name() : "UNKNOWN",
                    channelActionPacket.getChannelId(),
                    previousChannel,
                    targetId,
                    operatorName,
                    durationSeconds,
                    leavingCurrent
            ));
            return;
        }

        if (packet instanceof AdminActionPacket adminActionPacket) {
            pendingRequests.put(packet.getRequestId(), new PendingRequest(
                    adminActionPacket.getPlayerId(),
                    "admin",
                    adminActionPacket.getAction() != null ? adminActionPacket.getAction().name() : "UNKNOWN",
                    adminActionPacket.getTarget(),
                    null,
                    null,
                    null,
                    null,
                    false
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
