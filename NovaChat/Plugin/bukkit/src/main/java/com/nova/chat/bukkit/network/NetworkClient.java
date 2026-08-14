package com.nova.chat.bukkit.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.command.MessageHelper;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.SchedulerBridge;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.client.itemdisplay.ItemDisplayMessages;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ConfigSyncPacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.client.privatemsg.PrivateMessageService;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Bukkit NetworkClient facade over {@link CoreNetworkClient}.
 *
 * <p>Netty bootstrap, handshake/keepalive defaults, handler map, reconnect policy,
 * and the in-flight channel-action correlation tracker all live in client-core
 * (inherited via {@link AbstractPlatformNetworkClient}). The shared
 * {@link ChannelResponseDispatcher} owns the "consume pending → judge
 * success/failure → route to adapter" skeleton for channel-action responses; this
 * class supplies the {@link BukkitChannelResponseAdapter} that renders the shared
 * outcomes on the Bukkit main thread.
 *
 * <p>What stays genuinely bukkit-only:
 * <ul>
 *   <li>Title / Mention / ConfigSync inbound handlers (main-thread rendering +
 *       world-restricted channel + known-channel-id extraction for tab complete).</li>
 *   <li>{@link #handleAdminActionResponse} — admin responses are not in the
 *       dispatcher's current scope (audit §5.2 note; folding them in is a separate,
 *       optional widening).</li>
 * </ul>
 *
 * <p>Architecture B: plugin-only. Never imported by {@code novalink-core}.
 *
 * <p>Requirements: 1.1, 1.4
 */
public class NetworkClient extends AbstractPlatformNetworkClient {

    /** Package-visible so the adapter can reach the plugin + bukkit helpers. */
    final NovaChatBukkit plugin;

    private final MentionNotifier mentionNotifier = new MentionNotifier();

    /**
     * Sentinel UUID used by console/RCON-originated moderation commands (see
     * MuteCommand/KickCommand). When a response's pending player id is this
     * value, there is no online Bukkit Player to render the outcome to, so the
     * result is logged to the server console instead of being silently dropped.
     */
    static final UUID CONSOLE_SENTINEL_UUID =
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** Shared known-channel registry (populated from ConfigSync, UX-DESIGN §2.1). */
    private final com.nova.chat.client.channel.KnownChannelRegistry knownChannelRegistry;

    /** Shared response dispatcher (DUP-3); created in the constructor. */
    private final ChannelResponseDispatcher dispatcher;

    /** Shared adapter that renders dispatcher outcomes on the Bukkit main thread. */
    final BukkitChannelResponseAdapter adapter;

    /**
     * Bukkit-only admin-action correlation: maps an in-flight
     * {@code AdminActionPacket}'s request id to the originating player id so the
     * asynchronous {@link AdminActionResponsePacket} can be rendered. Admin
     * actions are NOT in the shared {@link ChannelResponseTracker} (it only tracks
     * {@code ChannelActionPacket}s, which {@code CoreNetworkClient.sendPacket}
     * records automatically), so the admin path stays local here. Audit §5.2 note:
     * folding admin actions into the shared dispatcher is a separate, optional
     * widening and out of scope for this migration.
     */
    private final java.util.concurrent.ConcurrentMap<UUID, UUID> pendingAdminRequests =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Creates a new NetworkClient.
     *
     * @param plugin the plugin instance
     * @param config the plugin configuration
     * @param knownChannelRegistry the shared known-channel registry (ConfigSync-fed)
     */
    public NetworkClient(NovaChatBukkit plugin, NovaChatConfig config,
                         com.nova.chat.client.channel.KnownChannelRegistry knownChannelRegistry) {
        this.plugin = plugin;
        this.knownChannelRegistry = java.util.Objects.requireNonNull(knownChannelRegistry, "knownChannelRegistry");
        ClientConnectionConfig connectionConfig = config.toClientConnectionConfig();
        SchedulerBridge scheduler = new BukkitSchedulerBridge(plugin);
        ClientLogger logger = new BukkitClientLogger(plugin);
        String serverVersion = plugin.getServer().getVersion();
        initCore(
                connectionConfig,
                PlatformType.BUKKIT,
                scheduler,
                logger,
                "config.yml",
                Function.identity(),
                serverVersion
        );

        // Shared dispatcher + adapter. handleChannelActionResponse is a thin
        // delegate to dispatcher.handle, matching the other 6 platforms' shape;
        // the adapter owns the Bukkit main-thread hops and rendering for the
        // shared JOIN/LEAVE/WHO success, JOIN rollback, error routing, and
        // KICK/MUTE target notice paths.
        this.adapter = new BukkitChannelResponseAdapter(this);
        this.dispatcher = new ChannelResponseDispatcher(getChannelResponseTracker(), adapter);

        // Register Bukkit-specific (non-default) handlers on the core.
        // HandshakeResponse and KeepAlive are registered by the core itself.
        registerHandler(TitlePacket.class, this::handleTitle);
        registerHandler(ConfigSyncPacket.class, this::handleConfigSync);
        registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
        registerHandler(AdminActionResponsePacket.class, this::handleAdminActionResponse);
        registerHandler(MentionPacket.class, this::handleMention);
        registerHandler(ItemDisplayPacket.class, this::handleItemDisplay);
        registerHandler(PrivateMessagePacket.class, this::handlePrivateMessage);
    }

    /**
     * Sends a packet to the backend. For {@link AdminActionPacket}s this records
     * the {@code requestId -> playerId} correlation locally so the asynchronous
     * {@link AdminActionResponsePacket} can be routed back to the originating
     * player (admin actions are not tracked by the shared
     * {@link ChannelResponseTracker}, which only handles channel actions). For
     * all other packets — including {@code ChannelActionPacket}s, which the core
     * tracks automatically — this delegates straight to the core's single-entry
     * {@code sendPacket}.
     *
     * @param packet the packet to send
     */
    @Override
    public void sendPacket(Packet packet) {
        if (packet instanceof AdminActionPacket adminActionPacket) {
            if (adminActionPacket.getPlayerId() != null && adminActionPacket.getRequestId() != null) {
                pendingAdminRequests.put(adminActionPacket.getRequestId(), adminActionPacket.getPlayerId());
            }
        }
        super.sendPacket(packet);
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
     * Handles an inbound item display packet ({@code [item]}/{@code [i]} play,
     * packet 0x10) by rendering one hoverable chat line to every player whose
     * active channel matches the packet channel (Requirements 12.3/12.4).
     *
     * <p>Receive-side semantics are "receive = render", matching the Bedrock
     * clients (pmmp/endstone/levilamina render inbound ItemDisplay directly);
     * the backend currently registers no route for this packet, so there is no
     * echo protocol to defer to. Threading and color handling mirror
     * {@link #handleTitle}; the hover detail is the Bukkit progressive
     * enhancement allowed by the md_5 chat API (Requirements 12.3).
     *
     * <p>Package-visible so the network-package unit test can drive the
     * handler directly after reflecting the core dispatch.
     */
    void handleItemDisplay(ItemDisplayPacket packet) {
        // Must run on main thread for Bukkit API
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                String channelId = packet.getChannelId();
                var formatter = plugin.getChatInterceptor().getMessageFormatter();

                for (org.bukkit.entity.Player player : plugin.getServer().getOnlinePlayers()) {
                    var state = plugin.getChatInterceptor().getOrCreateState(player);
                    if (state == null || channelId == null || !channelId.equals(state.getActiveChannel())) {
                        continue;
                    }
                    UUID viewerId = player.getUniqueId();
                    // Skip senders the viewer has ignored (/nc ignore)
                    var ignoreService = plugin.getIgnoreListService();
                    if (ignoreService != null && ignoreService.isIgnored(viewerId, packet.getSenderName())) {
                        continue;
                    }
                    String line = formatter.translateColorCodes(
                            ItemDisplayMessages.formatLine(viewerId, packet.getSenderName(), packet.getItemJson()));
                    String hover = formatter.translateColorCodes(
                            ItemDisplayMessages.formatHoverDetail(viewerId, packet.getItemJson()));

                    TextComponent component = new TextComponent(TextComponent.fromLegacyText(line));
                    component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new Text(TextComponent.fromLegacyText(hover))));
                    player.spigot().sendMessage(component);
                }
            } catch (Exception e) {
                plugin.debug("Failed to handle ItemDisplayPacket: " + e.getMessage(), e);
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

                // Ignored mentioner: no bell, no title (/nc ignore)
                var ignoreService = plugin.getIgnoreListService();
                if (ignoreService != null
                        && ignoreService.isIgnored(mentionedId, packet.getMentionerName())) {
                    return;
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

    /**
     * Handles a completed (S→C) private message: the shared
     * {@link PrivateMessageService} resolves which local players render which
     * role (sender echo vs received line, receiver-side ignore filter, reply
     * tracking) and this handler sends the colorized lines on the main thread.
     *
     * <p>Package-visible so the network-package unit test can drive the
     * handler directly after reflecting the core dispatch.
     */
    void handlePrivateMessage(PrivateMessagePacket packet) {
        // Must run on main thread for Bukkit API
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                var deliveries = plugin.getPrivateMessageService().handleIncoming(
                        packet,
                        id -> plugin.getServer().getPlayer(id) != null,
                        plugin.getIgnoreListService());
                var formatter = plugin.getChatInterceptor().getMessageFormatter();
                for (PrivateMessageService.Delivery delivery : deliveries) {
                    org.bukkit.entity.Player player = plugin.getServer().getPlayer(delivery.getPlayerId());
                    if (player != null) {
                        player.sendMessage(formatter.translateColorCodes(delivery.getLine()));
                    }
                }
            } catch (Exception e) {
                plugin.debug("Failed to handle PrivateMessagePacket: " + e.getMessage(), e);
            }
        });
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

    // --- Channel-action response routing ---

    /**
     * Routes an asynchronous channel-action response through the shared
     * {@link ChannelResponseDispatcher} (DUP-3), which owns the "consume pending →
     * judge success/failure → route to adapter" skeleton: JOIN/LEAVE/WHO success,
     * JOIN-rejection rollback, error-code routing, and the KICK/MUTE target-side
     * notice. The {@link BukkitChannelResponseAdapter} owns the Bukkit main-thread
     * hops, rendering, and the §7 action-bar flash. Thin delegate, matching the
     * other 6 platforms' shape.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        // Private-message rejections are unsolicited (no pending context in the
        // shared tracker); the dispatcher would drop them, so route them to the
        // PrivateMessageService for player-locale rendering instead.
        if (PrivateMessageService.isPrivateMessageError(packet)) {
            plugin.getPrivateMessageService()
                    .renderError(packet, id -> plugin.getServer().getPlayer(id) != null)
                    .ifPresent(delivery -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        org.bukkit.entity.Player player =
                                plugin.getServer().getPlayer(delivery.getPlayerId());
                        if (player != null) {
                            var formatter = plugin.getChatInterceptor().getMessageFormatter();
                            player.sendMessage(formatter.translateColorCodes(delivery.getLine()));
                        }
                    }));
            return;
        }
        dispatcher.handle(packet);
    }

    /**
     * Flashes a one-shot action bar with the player's current channel and chat
     * mode after a successful join/leave (UX-DESIGN §7). Vanilla action-bar
     * fade handles the ~3s dismissal; no polling. Called by
     * {@link BukkitChannelResponseAdapter} on the main thread.
     */
    void sendChannelStatusBar(org.bukkit.entity.Player player, String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return;
        }
        PlayerChannelState state = plugin.getChatInterceptor().getState(player.getUniqueId());
        ChatMode mode = state != null ? state.getChatMode() : null;
        if (mode == null) {
            mode = ChatMode.HYBRID;
        }
        String text = MessageHelper.colorize(
                com.nova.chat.client.command.PlayerMessages.currentChannelBar(player.getUniqueId(), channelId, mode));
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
    }

    private void handleAdminActionResponse(AdminActionResponsePacket response) {
        UUID playerId = pendingAdminRequests.remove(response.getRequestId());

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (playerId == null) {
                plugin.debug("Received AdminActionResponsePacket with no pending request: " + response);
                return;
            }

            org.bukkit.entity.Player player = plugin.getServer().getPlayer(playerId);
            if (player == null) {
                // Console/RCON-originated admin action: log the outcome to the
                // server console so the operator sees it, instead of dropping it.
                if (CONSOLE_SENTINEL_UUID.equals(playerId)) {
                    if (response.isSuccess()) {
                        plugin.getLogger().info("[NovaChat console] " +
                                (response.getMessage() != null && !response.getMessage().isEmpty()
                                        ? response.getMessage() : I18n.tr((UUID) null, "chat.action.success")));
                    } else {
                        String code = response.getErrorCode();
                        String msg = response.getMessage();
                        String text = (code != null && !code.isEmpty())
                                ? code + " | " + (msg != null ? msg : I18n.tr((UUID) null, "chat.action.failed"))
                                : (msg != null ? msg : I18n.tr((UUID) null, "chat.action.failed"));
                        plugin.getLogger().warning("[NovaChat console] " + text);
                    }
                    return;
                }
                plugin.debug("AdminActionResponsePacket target player not online: " + playerId);
                return;
            }

            if (response.isSuccess()) {
                plugin.getMessageHelper().sendSuccess(player,
                        response.getMessage() != null && !response.getMessage().isEmpty()
                                ? response.getMessage()
                                : I18n.tr(playerId, "chat.action.success"));
            } else {
                String code = response.getErrorCode();
                String msg = response.getMessage();
                if (code != null && !code.isEmpty()) {
                    plugin.getErrorHandler().sendErrorFromCode(player, code, msg);
                } else {
                    plugin.getMessageHelper().sendError(player,
                            msg != null ? msg : I18n.tr(playerId, "chat.action.failed"));
                }
            }
        });
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
