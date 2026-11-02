package com.nova.chat.pnx.chat;

import org.powernukkitx.Player;
import org.powernukkitx.event.EventHandler;
import org.powernukkitx.event.EventPriority;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.player.PlayerChatEvent;
import org.powernukkitx.event.player.PlayerJoinEvent;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.itemdisplay.ItemDisplayTokens;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.client.state.PlayerStateStore;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.pnx.NovaChatPNX;

import java.util.Map;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts player chat events and forwards them to the NovaLink backend.
 * Uses novachat-common protocol for packet creation.
 *
 * <p>DUP-7 migration: per-player state is hosted in the shared
 * {@link PlayerStateStore} (same as the other six platforms), and the
 * effective chat mode honours both the global {@code chat.replace_vanilla}
 * config and any per-player override produced by {@code /nc toggle} /
 * the settings form. The legacy local {@code PlayerChatState} wrapper has
 * been retired; callers now receive the shared {@link PlayerChannelState}
 * directly so {@code /nc toggle} flips {@link ChatMode} instead of a
 * PNX-only forwarding flag.
 *
 * Requirements: 28.4
 */
public class ChatInterceptor implements Listener {

    private final NovaChatPNX plugin;

    /** Legacy color prefix applied to @name mentions when rendering chat (UX-DESIGN §4.2). */
    static final String MENTION_HIGHLIGHT_COLOR = MentionNotifier.DEFAULT_HIGHLIGHT_COLOR;

    /** Player chat states indexed by UUID (shared store). */
    private final PlayerStateStore playerStates = new PlayerStateStore();
    private final RecentChannelSelections recentSelections = new RecentChannelSelections();
    private final Map<UUID, PendingChannelRestore> channelRestores = new ConcurrentHashMap<>();

    private record PendingChannelRestore(UUID playerId, PlayerChannelState session, String channel,
                                         String previousChannel, ChannelResponseTracker tracker) { }

    /** Single dedup state shared by all inbound mention notifications on this client. */
    private final MentionNotifier mentionNotifier = new MentionNotifier();

    /** Shared [item]/[i] token detection + per-player cooldown (client-core). */
    private final ItemDisplayTokens itemDisplayTokens = new ItemDisplayTokens();

    /**
     * UUIDs of players already shown the first-join welcome line this session
     * (UX-DESIGN §8.1). PowerNukkitX exposes no reliable {@code hasPlayedBefore},
     * so we track "welcomed this session" in memory and clear it on quit.
     */
    private final java.util.Set<UUID> welcomedPlayers = ConcurrentHashMap.newKeySet();

    /** Global chat mode derived from {@code chat.replace_vanilla}. */
    private volatile ChatMode globalMode;

    public ChatInterceptor(NovaChatPNX plugin) {
        this.plugin = plugin;
        this.globalMode = plugin.getNovaChatConfig().isReplaceVanilla()
                ? ChatMode.REPLACE
                : ChatMode.HYBRID;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(PlayerChatEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String message = event.getMessage();

        // Get player's chat state (shared PlayerChannelState).
        PlayerChannelState state = getOrCreateState(player);
        ChatMode effectiveMode = state.isModeOverridden()
                ? state.getChatMode()
                : globalMode;

        // In HYBRID mode, let vanilla chat proceed.
        if (effectiveMode == ChatMode.HYBRID) {
            return;
        }

        // In REPLACE mode, cancel vanilla chat and forward to the current channel.
        event.setCancelled(true);

        // Check if chat is enabled for this player (forwarding toggle).
        if (!state.isForwardingEnabled()) {
            player.sendMessage(plugin.getMessageFormatter().formatError(
                    I18n.tr(playerId, "chat.status.chat_disabled")));
            return;
        }

        // Channel-prefix routing (e.g. "!hi" -> global) before the
        // active-channel send; escape/unknown-prefix cases fall through with
        // the resolver-produced message (UX: prefix = /nc <channel> shorthand).
        com.nova.chat.client.channel.ChannelPrefixResolver.Resolution resolution =
                com.nova.chat.client.channel.ChannelPrefixResolver.resolve(
                        plugin.getNovaChatConfig().getChannelPrefixes(), message,
                        plugin.getKnownChannelRegistry() != null
                                ? plugin.getKnownChannelRegistry().getAll() : null);
        String targetChannel = resolution.isRedirect()
                ? resolution.getChannelId() : state.getActiveChannel();

        // Forward message to backend on the resolved channel.
        sendChatMessage(player, targetChannel, resolution.getMessage());

        plugin.debug("Chat intercepted: " + player.getName() + " -> "
                + targetChannel + ": " + resolution.getMessage());
    }

    /**
     * Pushes the shared first-join welcome line once per session to first-time
     * players (UX-DESIGN §8.1). Single non-intrusive chat line, no title.
     * Gated by a session memory set because PNX has no hasPlayedBefore.
     *
     * @param event the join event
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        getOrCreateState(player);
        restorePlayerChannels(player, true);
        if (welcomedPlayers.add(player.getUniqueId())) {
            player.sendMessage(plugin.getMessageFormatter().colorize(
                    com.nova.chat.client.command.WelcomeMessageService.getWelcomeLine()));
            plugin.debug("Sent first-join welcome to " + player.getName());
        }
    }

    /**
     * Send a chat message to the backend.
     *
     * @param player the player sending the message
     * @param channelId the target channel
     * @param message the message content
     */
    private void sendChatMessage(Player player, String channelId, String message) {
        if (!plugin.getNetworkClient().isConnected()) {
            player.sendMessage(plugin.getMessageFormatter().formatError(
                    I18n.tr(player.getUniqueId(), "chat.network.not_connected")));
            return;
        }

        if (!plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(plugin.getMessageFormatter().formatError(
                    I18n.tr(player.getUniqueId(), "chat.status.connecting")));
            return;
        }

        // Create chat message packet using novachat-common
        ChatMessagePacket packet = new ChatMessagePacket(
            player.getUniqueId(),
            player.getName(),
            plugin.getNovaChatConfig().getBackendUsername(), // clientId
            channelId,
            message
        );

        // Add placeholders for formatting
        packet.addPlaceholder("world", player.getLevel().getName());
        packet.addPlaceholder("display_name", player.getDisplayName());

        // Send packet to backend
        plugin.getNetworkClient().sendPacket(packet);

        // [item]/[i] display play: piggybacks on the outbound path (UX spec §4).
        maybeSendItemDisplay(player, channelId, message);
    }

    /**
     * Sends an {@link com.nova.chat.common.protocol.packets.ItemDisplayPacket}
     * when the outbound message carries an {@code [item]}/{@code [i]} token.
     *
     * <p>Semantics aligned with the Bedrock reference (pmmp/endstone):
     * case-insensitive {@code \[(item|i)\]} token, the shared
     * {@code novachat.feature.item} permission gate, and an empty hand still
     * sends the air payload (renders the "Empty" placeholder). The per-player
     * cooldown lives in the shared {@link ItemDisplayTokens}. Only display
     * fields (id/count/custom name) are serialized — never full NBT.
     */
    private void maybeSendItemDisplay(Player player, String channelId, String message) {
        try {
            if (!ItemDisplayTokens.hasItemToken(message)) {
                return;
            }
            if (!player.hasPermission(ItemDisplayTokens.PERMISSION_ITEM)) {
                return; // without permission the token stays plain text
            }
            if (!itemDisplayTokens.tryAcquire(player.getUniqueId())) {
                return; // rate-limited: token stays plain text
            }
            String itemJson = buildMainHandItemJson(player);
            plugin.getNetworkClient().sendPacket(ItemDisplayTokens.buildPacket(
                    player.getUniqueId(), player.getName(), channelId, itemJson));
            plugin.debug("Sent item display to channel " + channelId + ": " + itemJson);
        } catch (Exception e) {
            plugin.debug("Failed to send item display: " + e.getMessage());
        }
    }

    private String buildMainHandItemJson(Player player) {
        org.powernukkitx.item.Item hand = player.getInventory() != null
                ? player.getInventory().getItemInMainHand() : null;
        if (hand == null || hand.isNull() || hand.getCount() <= 0) {
            return ItemDisplayTokens.emptyHandJson();
        }
        String customName = hand.hasCustomName() ? hand.getCustomName() : null;
        return ItemDisplayTokens.buildItemJson(hand.getId(), hand.getCount(), customName);
    }

    /**
     * Display an incoming message from the backend.
     *
     * @param senderName the sender's name
     * @param channelId the channel ID
     * @param content the message content
     */
    public void displayIncomingMessage(String senderName, String channelId, String content) {
        // Highlight @name mentions before color translation (UX-DESIGN §4.2).
        String highlighted = MentionNotifier.highlightMentions(content, MENTION_HIGHLIGHT_COLOR);
        String formatted = plugin.getMessageFormatter().formatIncomingMessage(senderName, channelId, highlighted);

        sendToPlayersFiltered(senderName, formatted);
    }

    /**
     * Display an incoming message from the backend with placeholders.
     *
     * @param senderName the sender's name
     * @param channelId the channel ID
     * @param content the message content
     * @param placeholders additional placeholders
     */
    public void displayIncomingMessage(String senderName, String channelId, String content,
                                       Map<String, String> placeholders) {
        // Highlight @name mentions before color translation (UX-DESIGN §4.2).
        String highlighted = MentionNotifier.highlightMentions(content, MENTION_HIGHLIGHT_COLOR);
        String format = plugin.getNovaChatConfig().getChannelFormat(channelId);

        String formatted = format
            .replace("{player}", senderName)
            .replace("{channel}", channelId)
            .replace("{channel_name}", channelId)
            .replace("{message}", highlighted);

        // Apply additional placeholders
        if (placeholders != null) {
            formatted = plugin.getMessageFormatter().applyPlaceholders(formatted, placeholders);
        } else {
            formatted = plugin.getMessageFormatter().colorize(formatted);
        }

        sendToPlayersFiltered(senderName, formatted);
    }

    /**
     * Sends a rendered inbound chat line to every online player, skipping
     * recipients that have the sender on their ignore list (/nc ignore).
     * Replaces the historical {@code Server#broadcastMessage} so the filter
     * can be applied per recipient.
     */
    private void sendToPlayersFiltered(String senderName, String formatted) {
        com.nova.chat.client.ignore.IgnoreListService ignoreService = plugin.getIgnoreListService();
        for (Player player : plugin.getServer().getOnlinePlayers().values()) {
            if (ignoreService != null && ignoreService.isIgnored(player.getUniqueId(), senderName)) {
                continue;
            }
            player.sendMessage(formatted);
        }
    }

    /**
     * Get or create a player's chat state via the shared {@link PlayerStateStore}.
     *
     * <p>The default channel comes from config; the default mode is the global
     * {@code chat.replace_vanilla} setting (HYBRID by default). A per-player
     * override created by {@code /nc toggle} or the settings form flips
     * {@link ChatMode} for that player only.
     *
     * @param player the player
     * @return the player's shared channel state
     */
    public PlayerChannelState getOrCreateState(Player player) {
        return playerStates.getOrCreate(
                player.getUniqueId(),
                plugin.getNovaChatConfig().getDefaultChannel(),
                globalMode);
    }

    /**
     * Gets a player's chat state if it exists (no creation).
     *
     * @param playerId the player's UUID
     * @return the player's shared channel state, or null if not found
     */
    public PlayerChannelState getState(UUID playerId) {
        return playerStates.get(playerId);
    }

    /**
     * Alias for {@link #getState(UUID)} kept for command-compatibility with
     * the historical per-platform {@code getPlayerState} accessors.
     */
    public PlayerChannelState getPlayerState(UUID playerId) {
        return playerStates.getPlayer(playerId);
    }

    /**
     * Sets a player's chat state. Refuses null defensively.
     */
    public void setPlayerState(UUID playerId, PlayerChannelState state) {
        playerStates.set(playerId, state);
    }

    /**
     * Remove a player's chat state.
     *
     * @param player the player
     */
    public void removeState(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerChannelState removed = playerStates.remove(playerId);
        if (removed != null) {
            recentSelections.remember(playerId, removed.getActiveChannel());
            var channels = new HashSet<>(removed.getJoinedChannels());
            channelRestores.entrySet().removeIf(entry -> {
                PendingChannelRestore pending = entry.getValue();
                if (!playerId.equals(pending.playerId())) {
                    return false;
                }
                channels.add(pending.channel());
                pending.tracker().consume(entry.getKey());
                return true;
            });
            for (String channel : channels) {
                sendChannelLeave(player, channel, true);
            }
        }
        welcomedPlayers.remove(playerId);
    }

    public void restorePlayerChannels(Player player, boolean joining) {
        var client = plugin.getNetworkClient();
        if (client == null || !client.isAuthenticated()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PlayerChannelState state = getState(playerId);
        if (state == null) {
            return;
        }
        String selected = recentSelections.take(playerId);
        for (var entry : channelRestores.entrySet()) {
            if (playerId.equals(entry.getValue().playerId()) && channelRestores.remove(entry.getKey(), entry.getValue())) {
                selected = entry.getValue().channel();
                entry.getValue().tracker().consume(entry.getKey());
            }
        }
        if (selected != null || joining) {
            String channel = selected != null && canRestoreChannel(player, selected)
                    ? selected : plugin.getNovaChatConfig().getDefaultChannel();
            requestChannelRestore(player, state, channel);
        } else {
            com.nova.chat.client.channel.ChannelMembershipRestorer.restore(
                    state, player.getName(), PlatformType.POWERNUKKITX, player.getLevel().getName(), null, client::sendPacket);
        }
    }

    private boolean canRestoreChannel(Player player, String channel) {
        return player.hasPermission("novachat.use") && (player.hasPermission("novachat.channel." + channel)
                || player.hasPermission("novachat.channel.*"));
    }

    private void requestChannelRestore(Player player, PlayerChannelState state, String channel) {
        var client = plugin.getNetworkClient();
        if (client == null || !client.isAuthenticated()) {
            return;
        }
        ChannelActionPacket request = new ChannelActionPacket(ChannelAction.JOIN, channel);
        request.addExtra("playerId", player.getUniqueId().toString());
        request.addExtra("playerName", player.getName());
        request.addExtra("platform", PlatformType.POWERNUKKITX.name());
        request.addExtra("world", player.getLevel().getName());
        PendingChannelRestore pending = new PendingChannelRestore(
                player.getUniqueId(), state, channel, state.getActiveChannel(), client.getChannelResponseTracker());
        UUID requestId = request.getRequestId();
        channelRestores.put(requestId, pending);
        client.sendPacket(request);
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            PendingChannelRestore expired = channelRestores.remove(requestId);
            if (expired != null) {
                expired.tracker().consume(requestId);
                Player online = findOnlinePlayer(expired.playerId());
                if (online != null && getState(expired.playerId()) == expired.session()) {
                    sendChannelLeave(online, expired.channel(), false);
                    restoreDefaultChannel(online, expired);
                }
            }
        }, (int) (NovaConstants.PENDING_REQUEST_TIMEOUT_MS / 50));
    }

    public boolean handleChannelRestoreResponse(ChannelActionResponsePacket packet) {
        if (!channelRestores.containsKey(packet.getRequestId())) {
            return false;
        }
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            PendingChannelRestore pending = channelRestores.remove(packet.getRequestId());
            if (pending == null) {
                return;
            }
            pending.tracker().consume(packet.getRequestId());
            Player player = findOnlinePlayer(pending.playerId());
            if (player == null || getState(pending.playerId()) != pending.session()) {
                return;
            }
            if (packet.isSuccess() && packet.getAction() == ChannelAction.JOIN
                    && pending.channel().equals(packet.getChannelId()) && canRestoreChannel(player, pending.channel())) {
                pending.session().joinChannel(pending.channel());
                if (pending.previousChannel().equals(pending.session().getActiveChannel())) {
                    pending.session().setActiveChannel(pending.channel());
                }
            } else {
                if (packet.isSuccess()) {
                    sendChannelLeave(player, pending.channel(), false);
                }
                restoreDefaultChannel(player, pending);
            }
        });
        return true;
    }

    private void restoreDefaultChannel(Player player, PendingChannelRestore pending) {
        if (!pending.channel().equals(plugin.getNovaChatConfig().getDefaultChannel())
                && pending.previousChannel().equals(pending.session().getActiveChannel())) {
            requestChannelRestore(player, pending.session(), plugin.getNovaChatConfig().getDefaultChannel());
        }
    }

    private void sendChannelLeave(Player player, String channel, boolean logout) {
        var client = plugin.getNetworkClient();
        if (client == null || !client.isAuthenticated()) {
            return;
        }
        ChannelActionPacket request = new ChannelActionPacket(ChannelAction.LEAVE, channel);
        request.addExtra("playerId", player.getUniqueId().toString());
        request.addExtra("playerName", player.getName());
        request.addExtra("platform", PlatformType.POWERNUKKITX.name());
        if (logout) {
            request.addExtra("logout", "true");
        }
        client.sendPacket(request);
    }

    private Player findOnlinePlayer(UUID playerId) {
        return plugin.getServer().getPlayer(playerId).orElse(null);
    }

    /**
     * Set a player's current channel.
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(Player player, String channelId) {
        getOrCreateState(player).setActiveChannel(channelId);
    }

    /**
     * Get a player's current channel.
     *
     * @param player the player
     * @return the channel ID
     */
    public String getPlayerChannel(Player player) {
        return getOrCreateState(player).getActiveChannel();
    }

    /**
     * Toggles a player's chat mode between HYBRID and REPLACE, marking it
     * as a personal override (so subsequent global config reloads do not
     * clobber the player's choice).
     *
     * @param player the player
     * @return the new chat mode
     */
    public ChatMode togglePlayerMode(Player player) {
        return getOrCreateState(player).toggleMode();
    }

    /**
     * @return the global chat mode derived from {@code chat.replace_vanilla}
     */
    public ChatMode getGlobalMode() {
        return globalMode;
    }

    /**
     * Sets the global chat mode (used on reload).
     */
    public void setGlobalMode(ChatMode mode) {
        this.globalMode = mode;
    }

    public boolean shouldNotifyMention(UUID mentionedId, UUID mentionerId) {
        return mentionNotifier.shouldNotify(mentionedId, mentionerId);
    }

    /**
     * Reload the chat interceptor settings.
     * Called when the plugin configuration is reloaded.
     */
    public void reload() {
        this.globalMode = plugin.getNovaChatConfig().isReplaceVanilla()
                ? ChatMode.REPLACE
                : ChatMode.HYBRID;
        plugin.debug("ChatInterceptor reloaded, global mode: " + globalMode);
    }
}
