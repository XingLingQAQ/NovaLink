package com.nova.chat.proxy.lite;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.ProtocolLimits;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.PrivateMessagePacket;
import com.nova.chat.proxy.chat.ProxyChatCore;
import com.nova.chat.proxy.chat.SlowModeTracker;
import com.nova.chat.proxy.config.LiteChannelConfig;
import com.nova.chat.proxy.config.ProxyConfigView;
import com.nova.chat.proxy.platform.ProxyPlatform;
import com.nova.chat.proxy.platform.ProxyPlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process replacement for the NovaLink backend when the proxy runs in
 * {@code lite} mode.
 *
 * <p>Lite keeps the same object graph as relay apart from the transport: the
 * shells hand the shared cores a {@link LocalAuthorityClient} instead of a
 * Netty-backed {@code NetworkClient}, and that client forwards every outgoing
 * packet here. This class owns the server half of the channel protocol the
 * backend would otherwise provide:
 *
 * <ul>
 *   <li><b>Chat fan-out</b> — a {@link ChatMessagePacket} is stamped with the
 *       configured channel display name and handed back down through the
 *       client's registered handlers, so {@link ProxyChatCore#handleIncomingMessage}
 *       keeps owning recipient selection (online + active channel), ignore
 *       filtering, @mention highlighting and per-channel formatting. None of
 *       that logic is duplicated here.</li>
 *   <li><b>Channel management</b> — JOIN / LEAVE / WHO over
 *       {@code lite.channels}, with the configured permission node, capacity
 *       and slow-mode enforced locally. The active channel and joined set are
 *       mirrored onto the shared {@code ProxyPlayerStateStore} so every
 *       existing consumer (fan-out, status bar, completions) keeps working;
 *       the authority additionally keeps its own membership table because the
 *       client applies optimistic local changes before the packet arrives.</li>
 *   <li><b>Private messages</b> — {@code msg} / {@code reply} resolved against
 *       the proxy's online players. A missing target produces an explicit
 *       {@code not_online} error response (never a silent success).</li>
 * </ul>
 *
 * <p><b>Threading:</b> {@link #handle(Packet)} is synchronized and is invoked
 * from the async executor owned by {@link LocalAuthorityClient}; it never runs
 * on the caller's thread. Downstream delivery happens after it returns, which
 * keeps the optimistic local updates performed by {@code ChannelCommandService}
 * ordered before the asynchronous response.
 *
 * <p><b>Phase 1 scope:</b> mutes, kicks, bans, titles, announcements, filters
 * and storage-backed state are intentionally absent; unsupported channel
 * actions answer {@link ErrorCode#NOT_IMPLEMENTED}.
 */
public final class LocalAuthority {

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private final ProxyPlatform platform;
    private final ProxyChatCore chatCore;
    private final IgnoreListService ignoreListService;

    /** Local slow-mode windows, keyed by (player, channel). */
    private final SlowModeTracker slowModeTracker = new SlowModeTracker();

    /**
     * Authoritative channel membership (channel id → player ids).
     *
     * <p>This mirrors the backend's {@code Channel#getMembers()} and must stay
     * independent of {@code PlayerChannelState}: the client applies optimistic
     * local changes before the packet is sent — {@code ChannelCommandService.join}
     * switches the active channel after a successful send and
     * {@code leave} removes local membership the same way — so the shared state
     * is already mutated by the time this authority runs asynchronously. Using
     * it as the join/leave source of truth would reject every legitimate LEAVE
     * with NC-433.
     */
    private final ConcurrentMap<String, Set<UUID>> members = new ConcurrentHashMap<>();

    /** Mention parsing + notification dedup; the proxy chat core owns the rendering. */
    private final MentionNotifier mentionNotifier = new MentionNotifier();

    /**
     * Persistence for the membership state above; never null, but a no-op that
     * does no I/O and owns no thread when {@code lite.storage.type=memory}.
     */
    private final LiteChannelStore store;

    /**
     * Players whose persisted membership has already been requested once, so a
     * packet burst cannot queue one database read per packet.
     */
    private final Set<UUID> restoreRequested = ConcurrentHashMap.newKeySet();

    /**
     * @param platform the platform adapter (player lookup, rendering dispatch)
     * @param chatCore the shared chat core (state store, formatter, downstream
     *                 handlers; also owns reply-target tracking through its
     *                 {@link PrivateMessageService})
     * @param ignoreListService nullable; per-player ignore lists
     */
    public LocalAuthority(ProxyPlatform platform,
                          ProxyChatCore chatCore,
                          IgnoreListService ignoreListService) {
        this(platform, chatCore, ignoreListService, LiteChannelStore.memory());
    }

    /**
     * @param platform the platform adapter (player lookup, rendering dispatch)
     * @param chatCore the shared chat core (state store, formatter, downstream
     *                 handlers)
     * @param ignoreListService nullable; per-player ignore lists
     * @param store the membership store to mirror into; {@code null} means
     *              {@link LiteChannelStore#memory()}, so a caller that does not
     *              care about persistence keeps the exact previous behaviour
     */
    public LocalAuthority(ProxyPlatform platform,
                          ProxyChatCore chatCore,
                          IgnoreListService ignoreListService,
                          LiteChannelStore store) {
        this.platform = platform;
        this.chatCore = chatCore;
        this.ignoreListService = ignoreListService;
        this.store = store != null ? store : LiteChannelStore.memory();
    }

    /**
     * Handles one upstream packet and returns the downstream packets the client
     * must deliver to its registered handlers.
     *
     * <p>An empty result means the packet was consumed without a response
     * (unknown packet type, or a private message whose sender is not online on
     * this proxy).
     *
     * @param packet the packet the shared cores tried to send to the backend
     * @return downstream packets, never null
     */
    public synchronized List<Packet> handle(Packet packet) {
        if (packet instanceof ChatMessagePacket chat) {
            return handleChatMessage(chat);
        }
        if (packet instanceof ChannelActionPacket action) {
            return List.of(handleChannelAction(action));
        }
        if (packet instanceof PrivateMessagePacket message) {
            return handlePrivateMessage(message);
        }
        platform.logDebug("Lite authority ignoring unsupported packet: "
                + packet.getClass().getSimpleName());
        return List.of();
    }

    // ------------------------------------------------------------- chat fan-out

    /**
     * Validates an outbound chat message (channel existence, sender permission,
     * slow mode) and, when accepted, returns it for downstream fan-out plus one
     * {@link MentionPacket} per mentioned player.
     */
    private List<Packet> handleChatMessage(ChatMessagePacket packet) {
        String channelId = packet.getChannelId();
        String content = packet.getContent();
        UUID senderId = packet.getSenderId();

        if (channelId == null || channelId.isBlank() || content == null || content.isBlank()) {
            platform.logDebug("Lite authority dropped empty chat packet");
            return List.of();
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > ProtocolLimits.MAX_MESSAGE_CONTENT) {
            notifySender(senderId, ErrorCode.BAD_REQUEST);
            return List.of();
        }

        LiteChannelConfig channel = channels().get(channelId);
        if (channel == null) {
            notifySender(senderId, ErrorCode.NOT_FOUND);
            return List.of();
        }

        ProxyPlayer sender = senderId != null ? platform.getPlayer(senderId) : null;
        if (!hasChannelPermission(sender, channel)) {
            notifySender(senderId, ErrorCode.FORBIDDEN);
            return List.of();
        }

        int slowMode = channel.getSlowMode();
        long remaining = slowModeTracker.tryAcquire(senderId, channelId, slowMode);
        if (remaining > 0) {
            platform.logDebug("Lite slow mode rejected " + packet.getSenderName()
                    + " in " + channelId + " (" + remaining + "s remaining)");
            notifySender(senderId, ErrorCode.RATE_LIMITED);
            return List.of();
        }

        // The relay backend enriches the message before fan-out; lite does the
        // same so ProxyChatCore's formatter resolves {channel_name} locally.
        packet.addPlaceholder("channel_name", channel.getDisplayName());
        if (sender != null) {
            packet.addPlaceholder("display_name", sender.getDisplayName());
            if (sender.getCurrentServer() != null) {
                packet.addPlaceholder("server", sender.getCurrentServer());
            }
        }

        List<Packet> downstream = new ArrayList<>(4);
        downstream.add(packet);
        downstream.addAll(mentionPackets(packet, channelId));
        return downstream;
    }

    /**
     * Builds the mention notifications for an accepted chat message. Delivery
     * (online check, ignore filter, dedup window) stays in
     * {@link ProxyChatCore#handleMention}.
     */
    private List<MentionPacket> mentionPackets(ChatMessagePacket packet, String channelId) {
        UUID senderId = packet.getSenderId();
        if (senderId == null) {
            return List.of();
        }
        String senderName = packet.getSenderName() != null ? packet.getSenderName() : "";
        List<MentionPacket> packets = new ArrayList<>(
                mentionNotifier.createMentionPackets(senderId, senderName, channelId,
                        packet.getContent(), this::resolvePlayerUUID));
        if (mentionNotifier.hasAllMention(packet.getContent())) {
            List<UUID> members = new ArrayList<>();
            for (ProxyPlayer player : activeMembers(channelId)) {
                members.add(player.getUniqueId());
            }
            packets.addAll(mentionNotifier.createAllMentionPackets(
                    senderId, senderName, channelId, packet.getContent(), members));
        }
        return packets;
    }

    /** @return the UUID for a mentioned name when that player is online here */
    private UUID resolvePlayerUUID(String name) {
        ProxyPlayer player = findOnlineByName(name);
        return player != null ? player.getUniqueId() : null;
    }

    // ------------------------------------------------------ channel management

    private ChannelActionResponsePacket handleChannelAction(ChannelActionPacket packet) {
        ChannelAction action = packet.getAction();
        if (action == null) {
            return failure(null, packet, ErrorCode.BAD_REQUEST);
        }
        return switch (action) {
            case JOIN -> join(packet);
            case LEAVE -> leave(packet);
            case WHO -> who(packet);
            default -> {
                platform.logDebug("Lite authority does not implement channel action " + action);
                yield failure(action, packet, ErrorCode.NOT_IMPLEMENTED);
            }
        };
    }

    /**
     * Handles JOIN: validates the channel, the configured permission node and
     * the capacity limit, then records membership and the active channel.
     */
    private ChannelActionResponsePacket join(ChannelActionPacket packet) {
        String channelId = packet.getChannelId();
        if (channelId == null || channelId.isBlank()) {
            return failure(ChannelAction.JOIN, packet, ErrorCode.BAD_REQUEST);
        }
        LiteChannelConfig channel = channels().get(channelId);
        if (channel == null) {
            return failure(ChannelAction.JOIN, packet, ErrorCode.NOT_FOUND);
        }
        UUID playerId = resolvePlayerId(packet);
        if (playerId == null) {
            return failure(ChannelAction.JOIN, packet, ErrorCode.BAD_REQUEST);
        }

        ProxyPlayer player = platform.getPlayer(playerId);
        if (!hasChannelPermission(player, channel)) {
            return failure(ChannelAction.JOIN, packet, ErrorCode.FORBIDDEN);
        }

        // First interaction of this player with the authority: pull the persisted
        // membership back. Deferred onto the storage thread and applied through
        // the platform dispatch hop, so a slow database cannot delay this JOIN.
        restoreOnce(playerId);

        // Capacity is checked against the authority's membership table, not the
        // shared player state: the client's optimistic JOIN would otherwise
        // make the joiner count against itself on a re-join.
        Set<UUID> channelMembers = members.computeIfAbsent(channelId,
                ignored -> ConcurrentHashMap.newKeySet());
        if (!channelMembers.contains(playerId)
                && channel.getMaxCapacity() > 0
                && channelMembers.size() >= channel.getMaxCapacity()) {
            platform.logDebug("Lite channel " + channelId + " is full; rejecting "
                    + playerName(packet, player));
            return failure(ChannelAction.JOIN, packet, ErrorCode.CHANNEL_FULL);
        }
        channelMembers.add(playerId);

        PlayerChannelState state = stateOf(playerId);
        if (state == null) {
            state = chatCore.getStates().getOrCreateState(playerId);
        }
        state.joinChannel(channelId);
        state.setActiveChannel(channelId);
        persist(playerId, state);
        platform.logDebug("Lite authority joined " + playerName(packet, player)
                + " to channel " + channelId);
        return success(ChannelAction.JOIN, packet, channelId);
    }

    /**
     * Handles LEAVE. A player that is not a member is rejected with NC-433,
     * matching the backend.
     */
    private ChannelActionResponsePacket leave(ChannelActionPacket packet) {
        String channelId = packet.getChannelId();
        if (channelId == null || channelId.isBlank()) {
            return failure(ChannelAction.LEAVE, packet, ErrorCode.BAD_REQUEST);
        }
        UUID playerId = resolvePlayerId(packet);
        if (playerId == null) {
            return failure(ChannelAction.LEAVE, packet, ErrorCode.BAD_REQUEST);
        }
        // Membership comes from the authority's own table, not the shared
        // player state: ChannelCommandService.leave already removed local
        // membership optimistically before this deferred handler runs.
        Set<UUID> channelMembers = members.get(channelId);
        boolean removed = channelMembers != null && channelMembers.remove(playerId);
        if (!removed && isLogout(packet)) {
            // Logout leaves are best-effort cleanup: the shell may already have
            // evicted the player (onLitePlayerDisconnect) and a player that
            // never joined anything still produces one. Never answer a
            // departing player with NC-433.
            return success(ChannelAction.LEAVE, packet, channelId);
        }
        if (!removed) {
            return failure(ChannelAction.LEAVE, packet, ErrorCode.NOT_IN_CHANNEL);
        }
        PlayerChannelState state = stateOf(playerId);
        if (state != null) {
            state.leaveChannel(channelId);
            persist(playerId, state);
        }
        platform.logDebug("Lite authority removed " + playerName(packet, platform.getPlayer(playerId))
                + " from channel " + channelId);
        return success(ChannelAction.LEAVE, packet, channelId);
    }

    /**
     * Handles WHO: the online members of a channel are the players whose state
     * has that channel joined, in case-insensitive name order.
     */
    private ChannelActionResponsePacket who(ChannelActionPacket packet) {
        String channelId = packet.getChannelId();
        if (channelId == null || channelId.isBlank()) {
            return failure(ChannelAction.WHO, packet, ErrorCode.BAD_REQUEST);
        }
        LiteChannelConfig channel = channels().get(channelId);
        if (channel == null) {
            return failure(ChannelAction.WHO, packet, ErrorCode.NOT_FOUND);
        }

        List<String> names = new ArrayList<>();
        Set<UUID> channelMembers = members.get(channelId);
        if (channelMembers != null) {
            for (ProxyPlayer player : platform.getOnlinePlayers()) {
                if (channelMembers.contains(player.getUniqueId())) {
                    names.add(player.getName());
                }
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);

        ChannelActionResponsePacket response = success(ChannelAction.WHO, packet, channelId);
        response.addExtra("members", String.join(", ", names));
        response.addExtra("memberCount", String.valueOf(names.size()));
        response.addExtra("displayName", channel.getDisplayName());
        return response;
    }

    // -------------------------------------------------------- private messages

    /**
     * Completes an upstream private message: sender identity is taken from the
     * proxy (never trusted from the packet), the target is resolved by name, and
     * every rejection is returned as a {@code private_message} error response so
     * {@code PrivateMessageService.renderError} renders it in the sender's
     * locale.
     */
    private List<Packet> handlePrivateMessage(PrivateMessagePacket packet) {
        UUID senderId = packet.getSenderId();
        ProxyPlayer sender = senderId != null ? platform.getPlayer(senderId) : null;
        if (sender == null) {
            // Not a player of this proxy: nothing to complete and nobody to answer.
            platform.logDebug("Lite authority dropped private message from unknown sender");
            return List.of();
        }
        String content = packet.getContent();
        if (content == null || content.isBlank()) {
            return List.of(privateMessageError(packet, sender, ErrorCode.BAD_REQUEST, "empty"));
        }

        ProxyPlayer target = findOnlineByName(packet.getTargetName());
        if (target == null) {
            return List.of(privateMessageError(packet, sender, ErrorCode.NOT_FOUND, "not_online"));
        }
        if (target.getUniqueId().equals(senderId)) {
            return List.of(privateMessageError(packet, sender, ErrorCode.FORBIDDEN, "self"));
        }

        // Directional ignore, mirroring the backend's privacy split: a target
        // that ignores the sender looks exactly like an offline target, while a
        // sender that ignores the target is told about their own choice.
        if (ignoreListService != null
                && ignoreListService.isIgnored(target.getUniqueId(), sender.getName())) {
            return List.of(privateMessageError(packet, sender, ErrorCode.NOT_FOUND, "not_online"));
        }
        if (ignoreListService != null
                && ignoreListService.isIgnored(senderId, target.getName())) {
            return List.of(privateMessageError(packet, sender, ErrorCode.FORBIDDEN, "ignored_by_sender"));
        }

        // Complete the packet exactly like the backend: authoritative identity,
        // real target id, canonical names and a server timestamp. The shared
        // PrivateMessageService then renders the sender echo and the received
        // line from this single delivery.
        packet.setSenderId(senderId);
        packet.setSenderName(sender.getName());
        packet.setSenderClientId(config().getClientId());
        packet.setTargetId(target.getUniqueId());
        packet.setTargetName(target.getName());
        packet.setTimestamp(System.currentTimeMillis());
        return List.of(packet);
    }

    /**
     * Builds the unsolicited failure carrier the shared private-message service
     * understands ({@code reason=private_message} + {@code detail}).
     */
    private ChannelActionResponsePacket privateMessageError(PrivateMessagePacket packet,
                                                            ProxyPlayer sender,
                                                            ErrorCode code,
                                                            String detail) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, null, "", code.getCode(), code.getMessage());
        response.setRequestId(packet.getRequestId());
        response.addExtra("reason", PrivateMessageService.ERROR_REASON);
        response.addExtra("detail", detail);
        response.addExtra("senderId", sender.getUniqueId().toString());
        if (packet.getTargetName() != null) {
            response.addExtra("targetName", packet.getTargetName());
        }
        platform.logDebug("Lite private message rejected for " + sender.getName()
                + " (" + detail + ")");
        return response;
    }

    // ---------------------------------------------------------------- helpers

    /** @return the live lite channel definitions (re-read after a reload) */
    private java.util.Map<String, LiteChannelConfig> channels() {
        return config().getLiteChannels();
    }    private ProxyConfigView config() {
        return chatCore.getStates().getConfig();
    }

    private PlayerChannelState stateOf(UUID playerId) {
        return chatCore.getState(playerId);
    }

    private boolean hasChannelPermission(ProxyPlayer player, LiteChannelConfig channel) {
        String permission = channel.getPermission();
        if (permission == null || permission.isBlank()) {
            return true;
        }
        return player != null && player.hasPermission(permission);
    }

    /**
     * Forgets a player's channel membership and slow-mode windows.
     *
     * <p>Called by the platform shell on disconnect, mirroring the backend's
     * logout LEAVE eviction: a departed player must not linger in WHO results
     * or occupy channel capacity.
     *
     * @param playerId the disconnecting player
     */
    public synchronized void onPlayerDisconnect(UUID playerId) {
        if (playerId == null) {
            return;
        }
        for (Set<UUID> channelMembers : members.values()) {
            channelMembers.remove(playerId);
        }
        slowModeTracker.forgetPlayer(playerId);
        // Allow a reconnecting player to be restored again: ProxyChatCore drops
        // the state on disconnect, so without this the same session's reconnect
        // would skip the restore and the player would lose their channels.
        restoreRequested.remove(playerId);
        // The persisted membership is deliberately kept: that is the whole point
        // of the store, a reconnecting player must find their channels again.
        // Only the cache entry is dropped so it does not grow without bound.
        store.evictPlayer(playerId);
    }

    // ---------------------------------------------------------- persistence

    /**
     * Mirrors a player's current membership into the store.
     *
     * <p>The snapshot is captured here, synchronously, because the state object
     * keeps mutating after this authority call returns (the client applies
     * optimistic updates, a later JOIN changes the active channel). Only the DB
     * write is deferred, so this never blocks the caller on I/O. A {@code memory}
     * store makes the whole call a no-op.
     *
     * @param playerId the acting player
     * @param state the state to snapshot, or null when it was never created
     */
    private void persist(UUID playerId, PlayerChannelState state) {
        if (playerId == null || state == null || !store.isPersistent()) {
            return;
        }
        ProxyPlayer player = platform.getPlayer(playerId);
        store.savePlayer(new LiteChannelStore.PlayerSnapshot(
                playerId,
                player != null ? player.getName() : null,
                state.getJoinedChannels(),
                state.getActiveChannel()));
    }

    /**
     * Restores a player's persisted membership into the live state.
     *
     * <p>Queued from the player's first contact with the authority; the read
     * itself runs on the storage I/O thread and applies the result back through
     * the platform's dispatch hop, because {@link PlayerChannelState} is read by
     * the chat hot path. Channels that the configuration no longer declares are
     * dropped rather than restored, so a removed {@code lite.channels} entry does
     * not resurrect as a joinable-but-undefined channel.
     *
     * @param playerId the player to restore
     */
    private void restore(UUID playerId) {
        if (playerId == null || !store.isPersistent()) {
            return;
        }
        store.loadPlayer(playerId, snapshot -> {
            if (snapshot == null || snapshot.isEmpty()) {
                return;
            }
            platform.execute(() -> applyRestored(playerId, snapshot));
        });
    }

    /**
     * Restores a connecting player's persisted channel membership.
     *
     * <p>Called by the platform shell when a player joins the network (and again
     * from the first JOIN as a fallback). The database read is queued on the
     * storage thread and the result is applied through the platform dispatch hop,
     * so this returns immediately and never blocks the connect event. Repeated
     * calls for one player are no-ops.
     *
     * @param playerId the connecting player
     */
    public void onPlayerConnect(UUID playerId) {
        restoreOnce(playerId);
    }

    /**
     * Queues a restore only the first time this authority sees the player, so a
     * burst of packets from one client cannot queue one read per packet.
     *
     * @param playerId the player to restore
     */
    private void restoreOnce(UUID playerId) {
        if (store.isPersistent() && restoreRequested.add(playerId)) {
            restore(playerId);
        }
    }

    /**
     * Applies a restored snapshot to the live state and membership table.
     *
     * @param playerId the restored player
     * @param snapshot the persisted membership
     */
    private synchronized void applyRestored(UUID playerId, LiteChannelStore.PlayerSnapshot snapshot) {
        // The read completed asynchronously, so the player may already have left
        // this proxy: restoring then would resurrect state nobody reads.
        if (platform.getPlayer(playerId) == null) {
            return;
        }
        // The connect path normally created the state before requesting the
        // restore, but a JOIN can be the first thing the authority sees (plugin
        // messaging, a command before the server-connect event). Create the
        // default state rather than silently dropping the persisted membership.
        PlayerChannelState state = chatCore.getStates().getOrCreateState(playerId);
        Map<String, LiteChannelConfig> configured = channels();
        List<String> restored = new ArrayList<>();
        for (String channelId : snapshot.joinedChannels()) {
            if (channelId != null && configured.containsKey(channelId)) {
                restored.add(channelId);
            }
        }
        if (restored.isEmpty()) {
            return;
        }
        for (String channelId : restored) {
            members.computeIfAbsent(channelId, ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
            state.joinChannel(channelId);
        }
        String active = snapshot.activeChannel();
        if (active != null && restored.contains(active)) {
            state.setActiveChannel(active);
        }
        platform.logDebug("Lite authority restored " + restored.size()
                + " channel(s) for " + playerId + " (active=" + snapshot.activeChannel() + ")");
    }

    /**
     * @param channelId the channel id
     * @return the number of players the authority considers members
     */
    public int memberCount(String channelId) {
        Set<UUID> channelMembers = members.get(channelId);
        return channelMembers != null ? channelMembers.size() : 0;
    }

    /** Online players whose active channel is {@code channelId}. */
    private List<ProxyPlayer> activeMembers(String channelId) {
        Collection<? extends ProxyPlayer> online = platform.getOnlinePlayers();
        List<ProxyPlayer> members = new ArrayList<>();
        for (ProxyPlayer player : online) {
            PlayerChannelState state = stateOf(player.getUniqueId());
            if (state != null && channelId.equals(state.getActiveChannel())) {
                members.add(player);
            }
        }
        return members;
    }

    private ProxyPlayer findOnlineByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ProxyPlayer player : platform.getOnlinePlayers()) {
            if (player.getName() != null && player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    /**
     * Resolves the acting player. {@code ChannelCommandService} stamps
     * {@code playerId}; the server-switch path uses the historical
     * {@code player_uuid} spelling, so both are accepted.
     */
    private UUID resolvePlayerId(ChannelActionPacket packet) {
        UUID id = parseUuid(packet.getExtra("playerId"));
        if (id == null) {
            id = parseUuid(packet.getExtra("player_id"));
        }
        if (id == null) {
            id = parseUuid(packet.getExtra("player_uuid"));
        }
        if (id == null) {
            ProxyPlayer byName = findOnlineByName(playerName(packet, null));
            id = byName != null ? byName.getUniqueId() : null;
        }
        return id;
    }

    /**
     * Whether this LEAVE reports that the player is logging off the network
     * (stamped by {@code ProxyChatCore#notifyBackendDisconnect}) rather than an
     * explicit {@code /nc leave}.
     */
    private static boolean isLogout(ChannelActionPacket packet) {
        return "true".equalsIgnoreCase(packet.getExtra("logout"));
    }

    private static String playerName(ChannelActionPacket packet, ProxyPlayer player) {
        String name = packet.getExtra("playerName");
        if (name == null || name.isBlank()) {
            name = packet.getExtra("player_name");
        }
        if ((name == null || name.isBlank()) && player != null) {
            name = player.getName();
        }
        return name != null ? name : "";
    }

    private ChannelActionResponsePacket success(ChannelAction action,
                                                ChannelActionPacket request,
                                                String channelId) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, action, channelId, "", "OK");
        response.setRequestId(request.getRequestId());
        return response;
    }

    private ChannelActionResponsePacket failure(ChannelAction action,
                                                ChannelActionPacket request,
                                                ErrorCode code) {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, action, request.getChannelId(), code.getCode(), code.getMessage());
        response.setRequestId(request.getRequestId());
        return response;
    }

    /** Sends an actionable error line to the acting player, if still online. */
    private void notifySender(UUID senderId, ErrorCode code) {
        if (senderId == null || senderId.equals(NIL_UUID)) {
            return;
        }
        ProxyPlayer player = platform.getPlayer(senderId);
        if (player != null) {
            platform.sendMessage(player,
                    chatCore.getMessageFormatter().formatError(ErrorMessageFormatter.format(code)));
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
