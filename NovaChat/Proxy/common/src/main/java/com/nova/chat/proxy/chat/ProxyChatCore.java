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
import com.nova.chat.common.protocol.Packet;
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
     * Relay link router, or null in lite / before the shell installs one.
     *
     * <p>When present it resolves the link per player server instead of the
     * single {@link #clientSupplier}, and it owns inbound handler registration
     * (the handlers must be scoped to the link's server).
     */
    private volatile com.nova.chat.proxy.relay.BackendRouter backendRouter;

    /**
     * Per-link response dispatchers. Each backend link has its own
     * {@link ChannelResponseTracker}, so a JOIN sent on server X's link is
     * correlated against X's tracker only.
     */
    private final Map<AbstractPlatformNetworkClient, ChannelResponseDispatcher> linkDispatchers =
            new ConcurrentHashMap<>();

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
     * Binds the inbound packet handlers to a single network client, rendering
     * to every proxy player.
     *
     * <p>This is the lite path (and the historical single-link relay path).
     * When a {@link #setBackendRouter relay router} is installed, handler
     * registration belongs to the router instead: each link's handlers must be
     * scoped to that link's server, and a second unscoped registration for the
     * same packet class would replace them.
     *
     * <p>Public so the platform reload path can re-bind to the freshly created
     * client after a reconnect without reconstructing this core (H6 fix).
     *
     * @param client the live network client, or null before startup
     */
    public void rebindIncomingHandlers(AbstractPlatformNetworkClient client) {
        if (client == null || backendRouter != null) {
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
     * Installs the relay link router.
     *
     * <p>From this point every send resolves the link of the player's current
     * server and stamps that link's identity on the packet, so the backend
     * attributes a player to the server they are actually on. Passing null
     * restores the single-client behaviour (lite).
     *
     * @param router the router, or null to clear
     */
    public void setBackendRouter(com.nova.chat.proxy.relay.BackendRouter router) {
        this.backendRouter = router;
        // Link bindings are per client; a stale map would only grow.
        this.linkDispatchers.clear();
    }

    /**
     * @return the installed relay router, or null when the core uses a single
     *         client (lite)
     */
    public com.nova.chat.proxy.relay.BackendRouter getBackendRouter() {
        return backendRouter;
    }

    /**
     * Handles one packet the relay router received on a backend link.
     *
     * <p>Scope-aware: {@code scopeServers} names the downstream servers whose
     * proxy players may render it ({@code null} = every player, the legacy
     * single-link behaviour). A proxy player sees the line once because the
     * backend sends one copy per connection and each copy is scoped to the
     * connection's server.
     *
     * @param client the link the packet arrived on
     * @param scopeServers the servers to render to, or null for all
     * @param packet the packet
     */
    public void handleInbound(AbstractPlatformNetworkClient client,
                              Set<String> scopeServers,
                              Packet packet) {
        if (packet == null) {
            return;
        }
        if (packet instanceof ChatMessagePacket chat) {
            handleIncomingMessage(chat, scopeServers);
        } else if (packet instanceof ChannelActionResponsePacket response) {
            // Via the shared entry point: private-message rejections are
            // rendered there, not by the dispatcher.
            handleChannelActionResponse(response, dispatcherFor(client));
        } else if (packet instanceof MentionPacket mention) {
            handleMention(mention, scopeServers);
        } else if (packet instanceof TitlePacket title) {
            handleTitle(title, scopeServers);
        } else if (packet instanceof ItemDisplayPacket itemDisplay) {
            handleItemDisplay(itemDisplay, scopeServers);
        } else if (packet instanceof PrivateMessagePacket privateMessage) {
            handlePrivateMessage(privateMessage, scopeServers);
        } else {
            platform.logDebug("Relay has no handler for "
                    + packet.getClass().getSimpleName());
        }
    }

    /**
     * Returns the response dispatcher bound to a link's own tracker.
     *
     * <p>Each link has its own {@link ChannelResponseTracker}; a JOIN sent on
     * server X's link is answered on that link and must be correlated against
     * X's pending entries, not another server's.
     *
     * @param client the link, or null for the legacy single-client dispatcher
     * @return the dispatcher, never null
     */
    private ChannelResponseDispatcher dispatcherFor(AbstractPlatformNetworkClient client) {
        ChannelResponseDispatcher current = this.dispatcher;
        if (client == null) {
            return current != null ? current
                    : new ChannelResponseDispatcher(new ChannelResponseTracker(),
                            new CoreChannelResponseAdapter());
        }
        ChannelResponseDispatcher bound = linkDispatchers.computeIfAbsent(client,
                link -> new ChannelResponseDispatcher(
                        link.getChannelResponseTracker(), new CoreChannelResponseAdapter()));
        // Keep the legacy field pointing at the most recently observed link so
        // single-client callers still work.
        this.dispatcher = bound;
        return bound;
    }

    /**
     * Handles an inbound channel chat message by rendering one line to every
     * player on the packet's channel who has not ignored the sender.
     *
     * @param packet the inbound chat packet
     */
    public void handleIncomingMessage(ChatMessagePacket packet) {
        handleIncomingMessage(packet, null);
    }

    /**
     * Renders an inbound chat line to the proxy players the packet is scoped to.
     *
     * @param packet the inbound chat packet
     * @param scopeServers the servers whose proxy players may render it; null
     *                     renders to every proxy player
     */
    public void handleIncomingMessage(ChatMessagePacket packet, Set<String> scopeServers) {
        platform.logDebug("Received chat message from backend: " + packet);

        String channelId = packet.getChannelId();
        String senderName = packet.getSenderName();
        String content = packet.getContent();
        Map<String, String> placeholders = packet.getPlaceholders();

        // Get channel display name from placeholders or use channel ID
        String channelName = placeholders.getOrDefault("channel_name", channelId);
        String highlighted = MentionNotifier.highlightMentions(content, MENTION_HIGHLIGHT_COLOR);

        for (ProxyPlayer player : platform.getOnlinePlayers()) {
            if (!inScope(player, scopeServers)) {
                continue;
            }
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
        handleTitle(packet, null);
    }

    /**
     * Shows an inbound title to the scoped proxy players.
     *
     * @param packet the title packet
     * @param scopeServers the servers whose proxy players may render it; null
     *                     renders to every proxy player
     */
    public void handleTitle(TitlePacket packet, Set<String> scopeServers) {
        String channelId = packet.getChannelId();
        String title = packet.getTitle() != null ? packet.getTitle() : "";
        String subtitle = packet.getSubtitle() != null ? packet.getSubtitle() : "";

        for (ProxyPlayer player : platform.getOnlinePlayers()) {
            if (!inScope(player, scopeServers)) {
                continue;
            }
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
        handleItemDisplay(packet, null);
    }

    /**
     * Renders an inbound item display to the scoped proxy players.
     *
     * @param packet the item display packet
     * @param scopeServers the servers whose proxy players may render it; null
     *                     renders to every proxy player
     */
    public void handleItemDisplay(ItemDisplayPacket packet, Set<String> scopeServers) {
        String channelId = packet.getChannelId();
        for (ProxyPlayer player : platform.getOnlinePlayers()) {
            if (!inScope(player, scopeServers)) {
                continue;
            }
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
        handleMention(packet, null);
    }

    /**
     * Notifies a mentioned player if they are within the packet's scope.
     *
     * @param packet the mention packet
     * @param scopeServers the servers whose proxy players may render it; null
     *                     renders to every proxy player
     */
    public void handleMention(MentionPacket packet, Set<String> scopeServers) {
        UUID mentionedId = packet.getMentionedId();
        UUID mentionerId = packet.getMentionerId();
        if (mentionedId == null || mentionerId == null) {
            return;
        }
        ProxyPlayer player = platform.getPlayer(mentionedId);
        if (player == null || !inScope(player, scopeServers)) {
            return;
        }
        // Only the players whose active channel is the message channel ever saw
        // the line (see handleIncomingMessage), so a ping to a player focused
        // elsewhere raises a notice for a line they never received. Mirrors the
        // backend's MentionResolver#isInChannel, including its fail-open case.
        PlayerChannelState state = states.getState(mentionedId);
        if (state != null && !isInChannel(state, packet.getChannelId())) {
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
        handlePrivateMessage(packet, null);
    }

    /**
     * Renders a completed private message to the scoped local participants.
     *
     * @param packet the private message packet
     * @param scopeServers the servers whose proxy players may render it; null
     *                     renders to every proxy player
     */
    public void handlePrivateMessage(PrivateMessagePacket packet, Set<String> scopeServers) {
        if (privateMessageService == null) {
            return;
        }
        var deliveries = privateMessageService.handleIncoming(
                packet,
                id -> {
                    ProxyPlayer candidate = platform.getPlayer(id);
                    return candidate != null && inScope(candidate, scopeServers);
                },
                ignoreListService);
        for (PrivateMessageService.Delivery delivery : deliveries) {
            ProxyPlayer player = platform.getPlayer(delivery.getPlayerId());
            if (player != null && inScope(player, scopeServers)) {
                platform.sendMessage(player, delivery.getLine());
            }
        }
    }

    /**
     * Whether the player's recorded active channel is {@code channelId}, i.e.
     * whether they were served the line for that channel.
     *
     * <p>Mirrors {@code MentionResolver#isInChannel} on the backend, which is the
     * authority that decided who to ping: a null/blank active channel is
     * <b>in</b> channel (fail open). A proxy player whose state was never
     * created — a direct-connect player the proxy knows only from packets, or a
     * state dropped on a reconnect race — must keep receiving mentions rather
     * than silently losing them.
     *
     * @param state the mentioned player's state, never null
     * @param channelId the mention's channel; null/blank matches anything
     * @return {@code false} only when the player is known to be focused elsewhere
     */
    private static boolean isInChannel(PlayerChannelState state, String channelId) {
        if (channelId == null || channelId.isBlank()) {
            return true;
        }
        String active = state.getActiveChannel();
        return active == null || active.isBlank() || active.equals(channelId);
    }

    /**
     * Whether a proxy player may render a packet scoped to {@code scopeServers}.
     *
     * <p>{@code null} means "no scope", i.e. the legacy single-link behaviour
     * where the backend pushed once and the proxy rendered to everyone. The
     * empty set means nobody: the packet belongs to another server's link.
     *
     * @param player the candidate
     * @param scopeServers the servers in scope, or null for all
     * @return whether the player is in scope
     */
    private static boolean inScope(ProxyPlayer player, Set<String> scopeServers) {
        if (scopeServers == null) {
            return true;
        }
        String server = player.getCurrentServer();
        if (server == null) {
            return false;
        }
        for (String candidate : scopeServers) {
            if (candidate != null && candidate.equalsIgnoreCase(server)) {
                return true;
            }
        }
        return false;
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
        handleChannelActionResponse(packet, dispatcher);
    }

    /**
     * Handles a channel action response with an explicit dispatcher.
     *
     * <p>Relay links each own a {@link ChannelResponseTracker}, so the response
     * must be consumed from the tracker of the link it arrived on. The
     * private-message error branch is part of this method (not the dispatcher),
     * so a per-link call must go through here to keep DM rejections rendered.
     *
     * @param packet the response packet
     * @param dispatcher the dispatcher bound to the link's tracker, or null
     */
    public void handleChannelActionResponse(ChannelActionResponsePacket packet,
                                            ChannelResponseDispatcher dispatcher) {
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

        if (!isBackendReadyFor(player)) {
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
        AbstractPlatformNetworkClient client = getNetworkClientForPlayer(player.getUniqueId());
        if (client == null || !client.isAuthenticated()) {
            platform.sendMessage(player, messageFormatter.formatError(
                    I18n.tr(player.getUniqueId(), "chat.network.not_connected")));
            return;
        }

        // The client id must be the identity of the link the packet rides:
        // the backend compares it with the channel owner for SERVER/PRIVATE
        // channels, and stamps it on the player state for mention/title/DM
        // routing. Reload-safe: the router resolves the current identity.
        ChatMessagePacket packet = new ChatMessagePacket(
                player.getUniqueId(),
                player.getName(),
                getIdentityForPlayer(player.getUniqueId()),
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
     * @param player the player whose server's link should be checked
     * @return whether the link carrying that player's server is authenticated
     */
    public boolean isBackendReadyFor(ProxyPlayer player) {
        if (player == null) {
            return isBackendReady();
        }
        AbstractPlatformNetworkClient client = getNetworkClientForPlayer(player.getUniqueId());
        return client != null && client.isAuthenticated();
    }

    /**
     * Resolves the backend link carrying one server's traffic.
     *
     * @param serverName the downstream server, or null when unknown
     * @return the link, or null when the deployment has none for it
     */
    public AbstractPlatformNetworkClient getNetworkClientFor(String serverName) {
        com.nova.chat.proxy.relay.BackendRouter router = this.backendRouter;
        if (router != null) {
            return router.clientFor(serverName);
        }
        return clientSupplier.get();
    }

    /**
     * Resolves the backend link for a player: their live server first, then the
     * server last recorded on their chat state (a player who is disconnecting
     * may already be gone from the proxy roster).
     *
     * @param playerId the player's UUID
     * @return the link, or null when none is available
     */
    public AbstractPlatformNetworkClient getNetworkClientForPlayer(UUID playerId) {
        if (playerId == null) {
            return getNetworkClient();
        }
        return getNetworkClientFor(resolveServer(playerId));
    }

    /**
     * @param playerId the player
     * @return the backend identity stamped on that player's packets
     */
    public String getIdentityForPlayer(UUID playerId) {
        return getIdentityFor(resolveServer(playerId));
    }

    /**
     * @param serverName the downstream server, or null when unknown
     * @return the backend identity for that server's link
     */
    public String getIdentityFor(String serverName) {
        com.nova.chat.proxy.relay.BackendRouter router = this.backendRouter;
        if (router != null) {
            String identity = router.identityFor(serverName);
            return identity != null ? identity : "";
        }
        String identity = states.getConfig().getClientId();
        return identity != null ? identity : "";
    }

    /** @return the player's server: live roster first, recorded state second */
    private String resolveServer(UUID playerId) {
        ProxyPlayer player = platform.getPlayer(playerId);
        if (player != null) {
            String server = player.getCurrentServer();
            if (server != null && !server.isBlank()) {
                return server;
            }
        }
        PlayerChannelState state = states.getState(playerId);
        return state != null ? state.getCurrentServer() : null;
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
        if (state == null) {
            return;
        }
        // Route by the server the player was last on: with a link per server
        // the LEAVE must ride that server's connection, otherwise the backend
        // evicts the cache entry of a player attributed to another client.
        AbstractPlatformNetworkClient client = getNetworkClientFor(state.getCurrentServer());
        if (client == null || !client.isAuthenticated()) {
            return;
        }
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
                // Drops the optimistic membership too: a refused JOIN must not
                // leave the channel marked joined for /nc list. rollbackJoin
                // itself refuses to override a manual switch made while the
                // JOIN was in flight.
                state.rollbackJoin(attemptedChannel, previousChannel);
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
