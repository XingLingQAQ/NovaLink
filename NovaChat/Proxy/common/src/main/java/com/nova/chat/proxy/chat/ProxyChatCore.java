package com.nova.chat.proxy.chat;

import com.nova.chat.client.channel.ChannelPrefixResolver;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.WelcomeMessageService;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.itemdisplay.ItemDisplayMessages;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Platform-agnostic chat core for the NovaChat proxy family.
 *
 * <p>Absorbs the body of the two near-identical proxy {@code ChatListener}
 * implementations: the inbound packet handlers (chat / title / item display /
 * mention / private message / channel action response), the outbound chat
 * intercept decision, the channel-prefix routing, the first-join welcome, the
 * disconnect cleanup and the per-player state accessors.
 *
 * <p>The platform shells keep only what is genuinely platform-owned: the event
 * subscription annotations ({@code @Subscribe} vs {@code @EventHandler}), the
 * proxy {@code Player} → {@link ProxyPlayer} adapter, rendering, and the config
 * file dialect. Everything below renders through {@link ProxyPlatform}, so this
 * class never references an Adventure or md_5 type.
 *
 * <p>Requirements: 23.2 (Velocity), 23.3 (BungeeCord).
 */
public class ProxyChatCore {

    /** Legacy color prefix applied to @name mentions when rendering chat (UX-DESIGN §4.2). */
    public static final String MENTION_HIGHLIGHT_COLOR = MentionNotifier.DEFAULT_HIGHLIGHT_COLOR;

    private final ProxyPlatform platform;
    private final ProxyPlayerStateStore states;
    private final ProxyMessageFormatter messageFormatter;

    /** Per-player ignore lists (/nc ignore); consulted by every render path. */
    private final IgnoreListService ignoreListService;

    /** Private-message core (/nc msg, /nc r). */
    private final PrivateMessageService privateMessageService;

    /** Backend-advertised channel roster for prefix routing and /nc completions. */
    private final KnownChannelRegistry knownChannelRegistry;

    /** Received-chat mention dedup (UX-DESIGN §4.2). */
    private final MentionNotifier mentionNotifier = new MentionNotifier();

    /**
     * UUIDs of players already shown the first-join welcome line this proxy
     * session (UX-DESIGN §8.1). Proxies have no hasPlayedBefore, so the welcome
     * is gated by this memory set and cleared on disconnect.
     */
    private final Set<UUID> welcomedPlayers = ConcurrentHashMap.newKeySet();

    /**
     * Players whose first server connection of this proxy session has already
     * been claimed (P3b fix 4).
     *
     * <p>The first-join decision must not read
     * {@code PlayerChannelState#getCurrentServer()}: the chat listener and the
     * server-switch handler both write it, and BungeeCord's {@code EventBus}
     * keeps same-priority listeners in a {@code HashMap}, so their execution
     * order is JVM-start random. On the bad order the default-channel JOIN and
     * the welcome line were both skipped. A
     * {@code ConcurrentHashMap.newKeySet().add()} claim is atomic regardless of
     * listener order.
     */
    private final Set<UUID> firstConnections = ConcurrentHashMap.newKeySet();

    /**
     * Supplies the live network client. Read on every send so a reload/reconnect
     * that swaps the client is picked up without re-wiring this core (matching the
     * historical {@code plugin.getNetworkClient()} reads).
     */
    private final Supplier<? extends AbstractPlatformNetworkClient> clientSupplier;

    /** Shared channel-action response dispatcher; rebuilt on network rebind. */
    private ChannelResponseDispatcher dispatcher;

    /**
     * @param platform the platform adapter
     * @param config the initial configuration snapshot
     * @param ignoreListService shared ignore lists
     * @param privateMessageService shared private-message core
     * @param knownChannelRegistry shared channel roster
     * @param clientSupplier supplies the live network client (null-tolerant)
     */
    public ProxyChatCore(ProxyPlatform platform,
                         ProxyConfigView config,
                         IgnoreListService ignoreListService,
                         PrivateMessageService privateMessageService,
                         KnownChannelRegistry knownChannelRegistry,
                         Supplier<? extends AbstractPlatformNetworkClient> clientSupplier) {
        this.platform = platform;
        this.clientSupplier = clientSupplier;
        this.states = new ProxyPlayerStateStore(config);
        this.messageFormatter = new ProxyMessageFormatter(config);
        this.ignoreListService = ignoreListService;
        this.privateMessageService = privateMessageService;
        this.knownChannelRegistry = knownChannelRegistry;
    }

    // -------------------------------------------------------- inbound handlers

    /**
     * Binds the inbound packet handlers to the current network client.
     *
     * <p>Public so the platform reload path can re-bind to the freshly created
     * client after a reconnect without reconstructing this core (H6 fix).
     *
     * @param client the live network client, or null before startup
     */
    public void rebindIncomingHandlers(AbstractPlatformNetworkClient client) {
        if (client == null) {
            return;
        }
        ChannelResponseTracker tracker = client.getChannelResponseTracker();
        this.dispatcher = new ChannelResponseDispatcher(tracker, new CoreChannelResponseAdapter());
        client.registerHandler(ChatMessagePacket.class, this::handleIncomingMessage);
        client.registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
        client.registerHandler(MentionPacket.class, this::handleMention);
        client.registerHandler(TitlePacket.class, this::handleTitle);
        client.registerHandler(ItemDisplayPacket.class, this::handleItemDisplay);
        client.registerHandler(PrivateMessagePacket.class, this::handlePrivateMessage);
    }

    /**
     * Handles an inbound channel chat message by rendering one line to every
     * player on the packet's channel who has not ignored the sender.
     *
     * @param packet the inbound chat packet
     */
    public void handleIncomingMessage(ChatMessagePacket packet) {
        platform.logDebug("Received chat message from backend: " + packet);

        String channelId = packet.getChannelId();
        String senderName = packet.getSenderName();
        String content = packet.getContent();
        Map<String, String> placeholders = packet.getPlaceholders();

        // Get channel display name from placeholders or use channel ID
        String channelName = placeholders.getOrDefault("channel_name", channelId);
        String highlighted = MentionNotifier.highlightMentions(content, MENTION_HIGHLIGHT_COLOR);

        for (ProxyPlayer player : platform.getOnlinePlayers()) {
            // Skip senders the recipient has ignored (/nc ignore)
            if (ignoreListService != null
                    && ignoreListService.isIgnored(player.getUniqueId(), senderName)) {
                continue;
            }
            PlayerChannelState state = states.getState(player.getUniqueId());
            if (state != null && channelId.equals(state.getActiveChannel())) {
                platform.sendMessage(player, messageFormatter.formatChatMessage(
                        channelId, channelName, senderName, highlighted, placeholders));
            }
        }
    }

    /**
     * Handles a title packet by showing it to players whose active channel
     * matches the packet channel (Requirements 15.1, 15.5).
     *
     * @param packet the title packet
     */
    public void handleTitle(TitlePacket packet) {
        String channelId = packet.getChannelId();
        String title = packet.getTitle() != null ? packet.getTitle() : "";
        String subtitle = packet.getSubtitle() != null ? packet.getSubtitle() : "";

        for (ProxyPlayer player : platform.getOnlinePlayers()) {
            PlayerChannelState state = states.getState(player.getUniqueId());
            if (state != null && channelId != null && channelId.equals(state.getActiveChannel())) {
                platform.showTitle(player, title, subtitle,
                        packet.getFadeIn(), packet.getStay(), packet.getFadeOut());
            }
        }
    }

    /**
     * Handles an inbound item display packet ({@code [item]}/{@code [i]} play,
     * packet 0x10) by rendering one hoverable chat line to every player whose
     * active channel matches the packet channel.
     *
     * <p>Receive-side semantics are "receive = render"; the line is formatted per
     * viewer because the copy is locale-dependent. Send side is intentionally
     * absent on the proxy: a proxy has no access to the player's held item, so
     * {@code [item]} tokens typed here pass through as plain text.
     *
     * @param packet the item display packet
     */
    public void handleItemDisplay(ItemDisplayPacket packet) {
        String channelId = packet.getChannelId();
        for (ProxyPlayer player : platform.getOnlinePlayers()) {
            PlayerChannelState state = states.getState(player.getUniqueId());
            if (state == null || channelId == null || !channelId.equals(state.getActiveChannel())) {
                continue;
            }
            UUID viewerId = player.getUniqueId();
            // Skip senders the viewer has ignored (/nc ignore)
            if (ignoreListService != null
                    && ignoreListService.isIgnored(viewerId, packet.getSenderName())) {
                continue;
            }
            platform.sendHoverableMessage(player,
                    ItemDisplayMessages.formatLine(
                            viewerId, packet.getSenderName(), packet.getItemJson()),
                    ItemDisplayMessages.formatHoverDetail(viewerId, packet.getItemJson()));
        }
    }

    /**
     * Handles a mention notification packet (UX-DESIGN §4.1, Requirements 11.2).
     *
     * <p>The dedup window is shared ({@link MentionNotifier}); the notification
     * rendering differs per platform (Velocity: title + sound, BungeeCord:
     * in-chat line) and lives in {@link ProxyPlatform#notifyMention}.
     *
     * @param packet the mention packet
     */
    public void handleMention(MentionPacket packet) {
        UUID mentionedId = packet.getMentionedId();
        UUID mentionerId = packet.getMentionerId();
        if (mentionedId == null || mentionerId == null) {
            return;
        }
        ProxyPlayer player = platform.getPlayer(mentionedId);
        if (player == null) {
            return;
        }
        // Ignored mentioner: no notification (/nc ignore)
        if (ignoreListService != null
                && ignoreListService.isIgnored(mentionedId, packet.getMentionerName())) {
            return;
        }
        mentionNotifier.notifyOrSkip(mentionedId, mentionerId, () -> {
            String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
            String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
            platform.notifyMention(player, "&e" + mentioner,
                    I18n.tr(mentionedId, "chat.mention.subtitle", channelId));
        });
    }

    /**
     * Handles a completed (S→C) private message: the shared
     * {@link PrivateMessageService} resolves which local players render which
     * role (sender echo vs received line, receiver-side ignore filter, reply
     * tracking).
     *
     * @param packet the private message packet
     */
    public void handlePrivateMessage(PrivateMessagePacket packet) {
        if (privateMessageService == null) {
            return;
        }
        var deliveries = privateMessageService.handleIncoming(
                packet, id -> platform.getPlayer(id) != null, ignoreListService);
        for (PrivateMessageService.Delivery delivery : deliveries) {
            ProxyPlayer player = platform.getPlayer(delivery.getPlayerId());
            if (player != null) {
                platform.sendMessage(player, delivery.getLine());
            }
        }
    }

    /**
     * Handles channel action responses by delegating the shared "consume pending
     * → route success/failure" skeleton to {@link ChannelResponseDispatcher}.
     *
     * <p>Private-message rejections are unsolicited (no pending context in the
     * shared tracker); the dispatcher would drop them, so they are routed to the
     * {@link PrivateMessageService} for player-locale rendering instead.
     *
     * @param packet the response packet
     */
    public void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        platform.logDebug("Received channel action response: " + packet);
        if (privateMessageService != null
                && PrivateMessageService.isPrivateMessageError(packet)) {
            privateMessageService
                    .renderError(packet, id -> platform.getPlayer(id) != null)
                    .ifPresent(delivery -> {
                        ProxyPlayer player = platform.getPlayer(delivery.getPlayerId());
                        if (player != null) {
                            platform.sendMessage(player, delivery.getLine());
                        }
                    });
            return;
        }
        if (dispatcher != null) {
            dispatcher.handle(packet);
        }
    }

    // ------------------------------------------------------- outbound chat path

    /**
     * Decides what to do with an outbound chat message.
     *
     * <p>In HYBRID mode the caller lets vanilla chat proceed. In REPLACE mode the
     * caller cancels the original message (before looking at the decision) and
     * then either replies with an error or forwards the resolved content, which
     * sidesteps chat signing by cancelling and re-sending.
     *
     * @param player the sending player
     * @param message the raw message the player typed
     * @return the decision; never null
     */
    public ChatDecision decideOutbound(ProxyPlayer player, String message) {
        UUID playerId = player.getUniqueId();
        PlayerChannelState state = states.getOrCreateState(playerId);
        ChatMode effectiveMode = state.isModeOverridden()
                ? state.getChatMode() : states.getGlobalMode();

        platform.logDebug("Player " + player.getName() + " chat event, mode: " + effectiveMode
                + ", channel: " + state.getActiveChannel());

        if (effectiveMode == ChatMode.HYBRID) {
            platform.logDebug("HYBRID mode: allowing vanilla chat for " + player.getName());
            return ChatDecision.passthrough();
        }

        if (!isBackendReady()) {
            return ChatDecision.reply(messageFormatter.formatError(
                    I18n.tr(playerId, "chat.network.not_connected_retry")));
        }

        // Channel-prefix routing (e.g. "!hi" -> global) before the active-channel
        // send; escape/unknown-prefix cases fall through with the resolver-produced
        // message (UX: prefix = /nc <channel> shorthand).
        ChannelPrefixResolver.Resolution resolution = ChannelPrefixResolver.resolve(
                states.getConfig().getChannelPrefixes(), message,
                knownChannelRegistry != null ? knownChannelRegistry.getAll() : null);
        String targetChannel = resolution.isRedirect()
                ? resolution.getChannelId() : state.getActiveChannel();

        return ChatDecision.sendToChannel(targetChannel, resolution.getMessage());
    }

    /**
     * Sends a message to a specific channel.
     *
     * @param player the sending player
     * @param channelId the target channel id
     * @param message the message content
     */
    public void sendToChannel(ProxyPlayer player, String channelId, String message) {
        AbstractPlatformNetworkClient client = getNetworkClient();
        if (client == null || !client.isAuthenticated()) {
            platform.sendMessage(player, messageFormatter.formatError(
                    I18n.tr(player.getUniqueId(), "chat.network.not_connected")));
            return;
        }

        ChatMessagePacket packet = new ChatMessagePacket(
                player.getUniqueId(),
                player.getName(),
                states.getConfig().getUsername(), // Client ID
                channelId,
                message
        );

        // Add basic placeholders
        packet.addPlaceholder("player", player.getName());
        packet.addPlaceholder("display_name", player.getDisplayName());

        // Add server info if available
        String server = player.getCurrentServer();
        if (server != null) {
            packet.addPlaceholder("server", server);
        }

        client.sendPacket(packet);
        platform.logDebug("Sent message to channel " + channelId + ": " + message);
    }

    /**
     * @return whether the backend link is authenticated
     */
    public boolean isBackendReady() {
        AbstractPlatformNetworkClient client = getNetworkClient();
        return client != null && client.isAuthenticated();
    }

    /**
     * Sends a pre-formatted chat line to a player (used for the outbound
     * intercept's error reply).
     *
     * @param player the recipient
     * @param legacyText legacy-coded text
     */
    public void sendFormatted(ProxyPlayer player, String legacyText) {
        platform.sendMessage(player, legacyText);
    }

    /**
     * @return the shared mention notifier (dedup window) used by the proxies
     */
    public MentionNotifier getMentionNotifier() {
        return mentionNotifier;
    }

    // ------------------------------------------------------------ server switch

    /**
     * Records the player's current downstream server on their state.
     *
     * @param player the player
     * @param serverName the downstream server name
     */
    public void recordServer(ProxyPlayer player, String serverName) {
        PlayerChannelState state = states.getOrCreateState(player.getUniqueId());
        state.setCurrentServer(serverName);
        platform.logDebug("Player " + player.getName() + " connected to server: " + serverName);
    }

    /**
     * Velocity semantics: records the server and pushes the first-join welcome
     * line once per proxy session (UX-DESIGN §8.1).
     *
     * @param player the connecting player
     * @param serverName the downstream server name
     */
    public void onServerConnected(ProxyPlayer player, String serverName) {
        recordServer(player, serverName);
        pushWelcomeIfFirst(player);
    }

    /**
     * Pushes the shared first-join welcome line once per proxy session
     * (UX-DESIGN §8.1). No-op if already welcomed this session.
     *
     * @param player the freshly connected player
     */
    public void pushWelcomeIfFirst(ProxyPlayer player) {
        if (welcomedPlayers.add(player.getUniqueId())) {
            platform.sendMessage(player, WelcomeMessageService.getWelcomeLine());
            platform.logDebug("Sent first-join welcome to " + player.getName());
        }
    }

    /**
     * Handles a player disconnect: drops the chat state, the welcome marker and
     * the private-message reply target.
     *
     * @param playerId the disconnecting player's UUID
     */
    public void onPlayerDisconnect(UUID playerId) {
        PlayerChannelState state = states.getState(playerId);
        notifyBackendDisconnect(playerId, state);
        states.removeState(playerId);
        welcomedPlayers.remove(playerId);
        firstConnections.remove(playerId);
        if (privateMessageService != null) {
            privateMessageService.onPlayerQuit(playerId);
        }
    }

    /**
     * Sends a LEAVE for every channel the departing player had joined, so the
     * backend drops both the membership and the cached player state (P3b fix 2).
     *
     * <p>One packet per joined channel is required:
     * {@code ChannelActionHandler#handleLeave} rejects a blank channel id
     * (NC-400), and a player that joined several channels would otherwise stay
     * a member of the rest. Extras use the names the backend actually reads
     * ({@code playerId} / {@code playerName} / {@code platform}); the previous
     * {@code player_uuid} spelling never matched
     * {@code ChannelResponseTracker}'s correlation key. {@code logout=true}
     * distinguishes this from a plain {@code /nc leave}.
     *
     * @param playerId the disconnecting player's UUID
     * @param state the player's chat state, or {@code null} if it was never created
     */
    private void notifyBackendDisconnect(UUID playerId, PlayerChannelState state) {
        if (state == null || !isBackendReady()) {
            return;
        }
        AbstractPlatformNetworkClient client = getNetworkClient();
        ProxyPlayer player = platform.getPlayer(playerId);
        String playerName = player != null ? player.getName() : "";
        Set<String> channels = new java.util.LinkedHashSet<>(state.getJoinedChannels());
        if (state.getActiveChannel() != null) {
            channels.add(state.getActiveChannel());
        }
        for (String channelId : channels) {
            if (channelId == null || channelId.isBlank()) {
                continue;
            }
            ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.LEAVE, channelId);
            packet.addExtra("playerId", playerId.toString());
            packet.addExtra("playerName", playerName);
            packet.addExtra("platform", platform.getPlatformType().name());
            // Distinguishes "player is logging off this network" from a plain
            // /nc leave. The backend currently ignores this extra (see the
            // companion change required in ChannelActionHandler#handleLeave);
            // it is carried here so the cache eviction can be keyed on it.
            packet.addExtra("logout", "true");
            client.sendPacket(packet);
            platform.logDebug("Notified backend that " + playerName
                    + " left channel " + channelId);
        }
    }

    /**
     * Atomically claims the player's first server connection of this proxy
     * session (P3b fix 4).
     *
     * @param playerId the connecting player
     * @return {@code true} the first time this player connects in this session
     */
    public boolean claimFirstConnection(UUID playerId) {
        return firstConnections.add(playerId);
    }

    // ------------------------------------------------------------- state access

    /**
     * @param player the player
     * @return the player's state, created with the configured defaults if absent
     */
    public PlayerChannelState getOrCreateState(ProxyPlayer player) {
        return states.getOrCreateState(player.getUniqueId());
    }

    /**
     * @param playerId the player UUID
     * @return the existing state, or null
     */
    public PlayerChannelState getState(UUID playerId) {
        return states.getState(playerId);
    }

    /**
     * Alias for {@link #getState(UUID)} kept for command compatibility.
     *
     * @param playerId the player UUID
     * @return the existing state, or null
     */
    public PlayerChannelState getPlayerState(UUID playerId) {
        return states.getPlayerState(playerId);
    }

    /**
     * @param playerId the player UUID
     * @param state the state to store
     */
    public void setPlayerState(UUID playerId, PlayerChannelState state) {
        states.setPlayerState(playerId, state);
    }

    /**
     * @param mode the new global mode
     */
    public void setGlobalMode(ChatMode mode) {
        states.setGlobalMode(mode);
    }

    /**
     * @return the global chat mode
     */
    public ChatMode getGlobalMode() {
        return states.getGlobalMode();
    }

    /**
     * @param player the player
     * @return the new personal mode
     */
    public ChatMode togglePlayerMode(ProxyPlayer player) {
        return states.togglePlayerMode(player.getUniqueId());
    }

    /**
     * @param player the player
     * @param channelId the channel to make active
     */
    public void setPlayerChannel(ProxyPlayer player, String channelId) {
        states.setPlayerChannel(player.getUniqueId(), channelId);
    }

    /**
     * @param player the player
     * @return the player's active channel (creating state if needed)
     */
    public String getPlayerChannel(ProxyPlayer player) {
        return states.getPlayerChannel(player.getUniqueId());
    }

    // ---------------------------------------------------------- shell plumbing

    /**
     * @return the live network client, or null before startup
     */
    public AbstractPlatformNetworkClient getNetworkClient() {
        return clientSupplier.get();
    }

    /**
     * Installs a new configuration snapshot and cascades it to the state store.
     *
     * @param config the fresh snapshot (null is ignored)
     */
    public void setConfig(ProxyConfigView config) {
        states.setConfig(config);
        platform.logDebug("Chat core reloaded, global mode: " + states.getGlobalMode());
    }

    /**
     * @return the shared message formatter
     */
    public ProxyMessageFormatter getMessageFormatter() {
        return messageFormatter;
    }

    /**
     * @return the shared message formatter (command-layer alias)
     */
    public ProxyMessageFormatter getFormatter() {
        return messageFormatter;
    }

    /**
     * @return the shared player state store (command layer reads joined channels)
     */
    public ProxyPlayerStateStore getStates() {
        return states;
    }

    /**
     * @return the shared private-message service, or null before startup
     */
    public PrivateMessageService getPrivateMessageService() {
        return privateMessageService;
    }

    // ------------------------------------------------------ dispatcher adapter

    /**
     * The shared {@link ChannelResponseDispatcher.ChannelResponseAdapter}: maps
     * the nine dispatcher callbacks onto platform state mutations and rendering.
     *
     * <p>Every callback hops through {@link ProxyPlatform#execute(Runnable)}
     * before touching player state, mirroring the historical GAP-3 fix: the
     * dispatcher fires from the Netty event loop, while the shared store and the
     * proxy event thread are not atomic across read-modify-write sequences.
     */
    private final class CoreChannelResponseAdapter
            implements ChannelResponseDispatcher.ChannelResponseAdapter {

        @Override
        public void setActiveChannel(UUID playerId, String channelId) {
            platform.execute(() -> {
                PlayerChannelState state = states.getState(playerId);
                if (state != null) {
                    state.setActiveChannel(channelId);
                }
            });
        }

        @Override
        public void rollbackJoin(UUID playerId, String attemptedChannel, String previousChannel) {
            platform.execute(() -> {
                PlayerChannelState state = states.getState(playerId);
                if (state == null) {
                    return;
                }
                // Only roll back if the optimistic channel is still in place; if the
                // player has since switched channels manually, leave their choice alone.
                String current = state.getActiveChannel();
                if (current != null && current.equals(attemptedChannel)) {
                    state.setActiveChannel(previousChannel);
                }
            });
        }

        @Override
        public void sendJoinSuccess(UUID playerId, String channelId) {
            platform.execute(() -> {
                ProxyPlayer player = platform.getPlayer(playerId);
                if (player != null) {
                    platform.sendMessage(player, messageFormatter.formatSuccess(
                            PlayerMessages.joined(playerId, channelId)));
                }
            });
        }

        @Override
        public void sendLeaveSuccess(UUID playerId, String channelId) {
            platform.execute(() -> {
                ProxyPlayer player = platform.getPlayer(playerId);
                if (player != null) {
                    platform.sendMessage(player, messageFormatter.formatSuccess(
                            PlayerMessages.left(
                                    playerId, channelId, states.getConfig().getDefaultChannel())));
                }
            });
        }

        @Override
        public void sendJoinChannelStatusBar(UUID playerId, String channelId) {
            platform.execute(() -> {
                if (channelId == null || channelId.isEmpty()) {
                    return;
                }
                ProxyPlayer player = platform.getPlayer(playerId);
                if (player != null) {
                    PlayerChannelState state = states.getState(playerId);
                    ChatMode mode = state != null ? state.getChatMode() : ChatMode.HYBRID;
                    platform.showChannelStatusBar(player, channelId, mode);
                }
            });
        }

        @Override
        public void sendLeaveChannelStatusBar(UUID playerId) {
            // After a successful leave the active channel falls back locally
            // (ChannelCommandService.leave → PlayerChannelState#leaveChannel), so
            // resolve the current channel here and flash the same bar. Nothing to
            // name when the player joined nothing else — skip.
            platform.execute(() -> {
                ProxyPlayer player = platform.getPlayer(playerId);
                if (player == null) {
                    return;
                }
                PlayerChannelState state = states.getState(playerId);
                String channelId = state != null ? state.getActiveChannel() : null;
                if (channelId == null || channelId.isEmpty()) {
                    return;
                }
                platform.showChannelStatusBar(player, channelId,
                        state.getChatMode());
            });
        }

        @Override
        public void sendErrorMessage(UUID playerId, String text) {
            platform.execute(() -> {
                ProxyPlayer player = platform.getPlayer(playerId);
                if (player != null) {
                    platform.sendMessage(player, messageFormatter.formatError(text));
                }
            });
        }

        @Override
        public void sendWhoResult(UUID playerId, String channelId, String displayName,
                                  String membersCsv, String memberCount) {
            platform.execute(() -> {
                ProxyPlayer player = platform.getPlayer(playerId);
                if (player == null) {
                    return;
                }
                String text = WhoCommandService.formatMemberList(
                        playerId, channelId, displayName, membersCsv, memberCount);
                // Render each line independently so per-line color codes are parsed
                // separately (neither proxy has a native multi-line chat component;
                // splitting keeps the header/body color codes intact).
                for (String line : text.split("\n")) {
                    if (!line.isEmpty()) {
                        platform.sendMessage(player, line);
                    }
                }
            });
        }

        @Override
        public void notifyKickMuteTarget(ChannelResponseDispatcher.KickMuteNotice notice) {
            platform.execute(() -> {
                ProxyPlayer target = platform.getPlayer(notice.getTargetId());
                if (target == null) {
                    return; // not on this proxy
                }
                platform.notifyKickMute(target, notice.getAction(), notice.getChannelId(),
                        notice.getOperator(), notice.getDurationText());
            });
        }
    }

    /** Outbound chat decision returned by {@link #decideOutbound}. */
    public static final class ChatDecision {

        private enum Kind { PASSTHROUGH, REPLY, SEND }

        private final Kind kind;
        private final String channelId;
        private final String content;

        private ChatDecision(Kind kind, String channelId, String content) {
            this.kind = kind;
            this.channelId = channelId;
            this.content = content;
        }

        static ChatDecision passthrough() {
            return new ChatDecision(Kind.PASSTHROUGH, null, null);
        }

        static ChatDecision reply(String message) {
            return new ChatDecision(Kind.REPLY, null, message);
        }

        static ChatDecision sendToChannel(String channelId, String message) {
            return new ChatDecision(Kind.SEND, channelId, message);
        }

        /** @return true when vanilla chat should be let through untouched */
        public boolean isPassthrough() {
            return kind == Kind.PASSTHROUGH;
        }

        /** @return true when the original message is cancelled and forwarded */
        public boolean isSend() {
            return kind == Kind.SEND;
        }

        /** @return true when the caller should reply to the player without sending */
        public boolean isReply() {
            return kind == Kind.REPLY;
        }

        /** @return the pre-formatted reply for an {@link #isReply()} decision, else null */
        public String getReplyMessage() {
            return kind == Kind.REPLY ? content : null;
        }

        /** @return the target channel for an {@link #isSend()} decision */
        public String getChannelId() {
            return channelId;
        }

        /** @return the message content for an {@link #isSend()} decision */
        public String getContent() {
            return content;
        }
    }
}
