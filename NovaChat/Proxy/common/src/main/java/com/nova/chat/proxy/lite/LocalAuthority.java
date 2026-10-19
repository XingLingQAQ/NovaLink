package com.nova.chat.proxy.lite;

import com.nova.chat.client.error.ErrorCode;
import com.nova.chat.client.error.ErrorMessageFormatter;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.privatemsg.PrivateMessageService;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.ProtocolLimits;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
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
import com.nova.link.database.BanInfo;
import com.nova.link.database.DatabaseProvider;
import com.nova.link.database.MuteInfo;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Locale;

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
 *   <li><b>Mutes</b> — MUTE / UNMUTE over the same channel definitions, enforced
 *       on every outgoing chat line (NC-436) and persisted through
 *       {@link LiteChannelStore} so a moderation decision survives a restart.
 *       Moderation is gated on the platform command nodes ({@code novachat.mute},
 *       {@code novachat.kick}) plus the console identity; see {@link #canModerate}.
 * </ul>
 *
 * <p><b>Threading:</b> {@link #handle(Packet)} is synchronized and is invoked
 * from the async executor owned by {@link LocalAuthorityClient}; it never runs
 * on the caller's thread. Downstream delivery happens after it returns, which
 * keeps the optimistic local updates performed by {@code ChannelCommandService}
 * ordered before the asynchronous response.
 *
 * <p><b>Scope:</b> channel create/delete/invite are still absent and answer
 * {@link ErrorCode#NOT_IMPLEMENTED}. Moderation (mute, kick, ban), broadcasts
 * (title, announce) and word filtering are implemented; the last two live in
 * {@link LiteBroadcasts} and {@link LiteWordFilter} respectively.
 */
public final class LocalAuthority {

    private static final UUID NIL_UUID = new UUID(0L, 0L);

    /** The moderation node the platform mute command gates on. */
    private static final String MUTE_PERMISSION = "novachat.mute";

    /** The moderation node the platform kick command gates on. */
    private static final String KICK_PERMISSION = "novachat.kick";

    /** The moderation node the platform ban command gates on. */
    private static final String BAN_PERMISSION = "novachat.ban";

    /** Mirrors {@code DatabaseProvider.GLOBAL_KEY}; a null scope keys the global ban. */
    private static final String GLOBAL_KEY = DatabaseProvider.GLOBAL_KEY;

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
     * Optional sensitive-word filter for outgoing chat. Never null: an empty
     * filter is a no-op, so the chat path needs no null check of its own.
     */
    private volatile LiteWordFilter wordFilter = LiteWordFilter.of(null, null);

    /** Shared moderation gate (mute, kick, ban, title, announce). */
    private final LitePermissions permissions;

    /** ANNOUNCE and TITLE conversion; rendering stays in the chat core. */
    private final LiteBroadcasts broadcasts;

    /**
     * Players whose persisted membership has already been requested once, so a
     * packet burst cannot queue one database read per packet.
     */
    private final Set<UUID> restoreRequested = ConcurrentHashMap.newKeySet();

    /**
     * Live mute table: player → channel key → record.
     *
     * <p>The channel key is the channel id, or {@code DatabaseProvider.GLOBAL_KEY}
     * ({@code "__global__"}) for a mute that applies everywhere. A {@code null}
     * channel id means "all channels" on the wire, so it is normalised to that
     * key here — the same normalisation the shared provider performs when it
     * loads a player state — to keep this a flat map instead of a
     * nullable-key special case.
     *
     * <p>This table — not the database — decides enforcement: a mute write is
     * queued onto the storage I/O thread and may land after the response, and a
     * failed write must not silently reintroduce a muted player's right to speak.
     */
    private final ConcurrentMap<UUID, ConcurrentMap<String, MuteInfo>> mutes =
            new ConcurrentHashMap<>();

    /**
     * Players whose persisted mutes have already been requested once, mirroring
     * {@link #restoreRequested} so a packet burst cannot queue one read per packet.
     */
    private final Set<UUID> mutesRestoreRequested = ConcurrentHashMap.newKeySet();

    /**
     * Live ban table, keyed like {@link #mutes}: player → scope key → record.
     *
     * <p>A ban is stronger than a mute in one respect: it also evicts the
     * player from the channel and refuses a rejoin, so enforcement has to be
     * consulted on the join path as well as the chat path.
     */
    private final ConcurrentMap<UUID, ConcurrentMap<String, BanInfo>> bans =
            new ConcurrentHashMap<>();

    /** Players whose persisted bans have already been requested once. */
    private final Set<UUID> bansRestoreRequested = ConcurrentHashMap.newKeySet();

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
        this.permissions = new LitePermissions(platform);
        this.broadcasts = new LiteBroadcasts(platform, this.permissions);
    }

    /**
     * Replaces the sensitive-word filter.
     *
     * <p>Separate from the constructor because the filter is configuration-loaded
     * and hot-reloadable, while the rest of the dependency graph is fixed.
     *
     * @param filter the filter to enforce; null resets to a no-op
     */
    public void setWordFilter(LiteWordFilter filter) {
        this.wordFilter = filter != null ? filter : LiteWordFilter.of(null, null);
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
        if (packet instanceof AdminActionPacket admin) {
            return handleAdminAction(admin);
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

        // Filtering happens after the length check (so the limit applies to what
        // the player typed) and before fan-out (so no viewer ever sees the raw
        // text). The replacement is length-neutral in practice but not by
        // contract, hence the order.
        String filtered = wordFilter.filter(content);
        if (!filtered.equals(content)) {
            platform.logDebug("Lite word filter rewrote a message from " + packet.getSenderName()
                    + " in " + channelId + " (" + wordFilter.countMatches(content)
                    + " match(es))");
            packet.setContent(filtered);
            // Keep the local variable in step: the mention scan below reads it.
            content = filtered;
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

        // Mutes are checked before slow mode: a muted player must not also burn
        // their send slot, and NC-436 tells them why they were rejected.
        if (isMuted(senderId, channelId)) {
            platform.logDebug("Lite mute rejected " + packet.getSenderName()
                    + " in " + channelId);
            notifySender(senderId, ErrorCode.MUTED);
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
            case MUTE -> mute(packet);
            case UNMUTE -> unmute(packet);
            case KICK -> kick(packet);
            case BAN -> ban(packet);
            case UNBAN -> unban(packet);
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
        restoreMutesOnce(playerId);
        restoreBansOnce(playerId);

        // A ban outranks a mute for the join path: it refuses entry instead of
        // only silencing the player, and it covers the rejoin a kicked player is
        // otherwise free to perform.
        if (isBanned(playerId, channelId)) {
            platform.logDebug("Lite ban rejected " + playerName(packet, player)
                    + " joining " + channelId);
            return failure(ChannelAction.JOIN, packet, ErrorCode.FORBIDDEN);
        }

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

    // --------------------------------------------------------------- broadcasts

    /**
     * Dispatches ANNOUNCE/TITLE work to {@link LiteBroadcasts}.
     *
     * @param packet the admin action
     * @return the packets to fan out
     */
    private List<Packet> handleAdminAction(AdminActionPacket packet) {
        return broadcasts.handle(packet, channels());
    }

    // -------------------------------------------------------------- moderation

    /**
     * Handles MUTE.
     *
     * <p>Validation order and error codes mirror the backend's
     * {@code ChannelActionHandler#handleMute}: operator, target, channel,
     * channel existence, then permission. A {@code null} or blank channel id is
     * rejected here rather than treated as "global" — the backend does the same,
     * so a client that wants a network-wide mute has to say which channel it
     * means; this authority has no global-mute command of its own yet.
     */
    private ChannelActionResponsePacket mute(ChannelActionPacket packet) {
        String channelId = packet.getChannelId();
        UUID operatorId = resolveOperatorId(packet);
        if (operatorId == null) {
            return failure(ChannelAction.MUTE, packet, ErrorCode.BAD_REQUEST);
        }
        UUID targetId = resolveTargetId(packet);
        if (targetId == null) {
            return failure(ChannelAction.MUTE, packet, ErrorCode.TARGET_OFFLINE);
        }
        if (channelId == null || channelId.isBlank()) {
            return failure(ChannelAction.MUTE, packet, ErrorCode.BAD_REQUEST);
        }
        if (!channels().containsKey(channelId)) {
            return failure(ChannelAction.MUTE, packet, ErrorCode.NOT_FOUND);
        }
        if (!canModerate(operatorId, MUTE_PERMISSION)) {
            return failure(ChannelAction.MUTE, packet, ErrorCode.FORBIDDEN);
        }
        if (targetId.equals(operatorId)) {
            return failure(ChannelAction.MUTE, packet, ErrorCode.SELF_ACTION);
        }

        long durationMs = parseDurationMs(packet);
        String reason = firstNonBlank(packet.getExtra("reason"),
                packet.getExtra("muteReason"), "Muted by admin");
        // expireTime <= 0 means permanent, which is exactly how the shared
        // provider stores it ("0 means permanent" in MuteInfo, and the cleanup
        // SQL only deletes rows with expire_time > 0).
        long expireTime = durationMs > 0 ? System.currentTimeMillis() + durationMs : 0L;
        MuteInfo mute = new MuteInfo(channelId, expireTime, reason, operatorId);
        muteTable(targetId).put(channelId, mute);

        if (store.isPersistent()) {
            store.saveMute(targetId, mute);
        }
        platform.logDebug("Lite authority muted " + targetId + " in " + channelId
                + " (durationMs=" + durationMs + ") by " + operatorId);

        ChannelActionResponsePacket response = success(ChannelAction.MUTE, packet, channelId);
        response.addExtra("targetId", targetId.toString());
        response.addExtra("durationMs", String.valueOf(durationMs));
        String operatorName = packet.getExtra("operatorName");
        if (operatorName != null && !operatorName.isEmpty()) {
            response.addExtra("operatorName", operatorName);
        }
        return response;
    }

    /**
     * Handles UNMUTE: the mirror of {@link #mute}, with the same ordering. An
     * unmute for a player who is not muted still succeeds, matching the
     * backend's idempotent {@code unmutePlayer}.
     */
    private ChannelActionResponsePacket unmute(ChannelActionPacket packet) {
        String channelId = packet.getChannelId();
        UUID operatorId = resolveOperatorId(packet);
        if (operatorId == null) {
            return failure(ChannelAction.UNMUTE, packet, ErrorCode.BAD_REQUEST);
        }
        UUID targetId = resolveTargetId(packet);
        if (targetId == null) {
            return failure(ChannelAction.UNMUTE, packet, ErrorCode.TARGET_OFFLINE);
        }
        if (channelId == null || channelId.isBlank()) {
            return failure(ChannelAction.UNMUTE, packet, ErrorCode.BAD_REQUEST);
        }
        if (!channels().containsKey(channelId)) {
            return failure(ChannelAction.UNMUTE, packet, ErrorCode.NOT_FOUND);
        }
        if (!canModerate(operatorId, MUTE_PERMISSION)) {
            return failure(ChannelAction.UNMUTE, packet, ErrorCode.FORBIDDEN);
        }

        ConcurrentMap<String, MuteInfo> table = mutes.get(targetId);
        boolean removed = table != null && table.remove(channelId) != null;
        if (removed && store.isPersistent()) {
            store.deleteMute(targetId, channelId);
        }
        platform.logDebug("Lite authority unmuted " + targetId + " in " + channelId
                + " (was muted=" + removed + ")");

        ChannelActionResponsePacket response = success(ChannelAction.UNMUTE, packet, channelId);
        response.addExtra("targetId", targetId.toString());
        return response;
    }
    /**
     * Handles KICK: removes the target from the channel.
     *
     * <p>Compared with a mute this adds one precondition — the target must
     * actually be a member (NC-433 otherwise) — and drops the target's
     * membership the same way a LEAVE does, so the client's view and the
     * authority's table agree. That membership is persisted, so a kicked player
     * does not come back on reconnect.
     *
     * <p>Kicks are events, not state: there is nothing extra to persist beyond
     * the resulting membership, unlike a mute.
     */
    private ChannelActionResponsePacket kick(ChannelActionPacket packet) {
        String channelId = packet.getChannelId();
        UUID operatorId = resolveOperatorId(packet);
        if (operatorId == null) {
            return failure(ChannelAction.KICK, packet, ErrorCode.BAD_REQUEST);
        }
        UUID targetId = resolveTargetId(packet);
        if (targetId == null) {
            return failure(ChannelAction.KICK, packet, ErrorCode.TARGET_OFFLINE);
        }
        if (channelId == null || channelId.isBlank()) {
            return failure(ChannelAction.KICK, packet, ErrorCode.BAD_REQUEST);
        }
        if (!channels().containsKey(channelId)) {
            return failure(ChannelAction.KICK, packet, ErrorCode.NOT_FOUND);
        }
        if (!canModerate(operatorId, KICK_PERMISSION)) {
            return failure(ChannelAction.KICK, packet, ErrorCode.FORBIDDEN);
        }
        if (targetId.equals(operatorId)) {
            return failure(ChannelAction.KICK, packet, ErrorCode.SELF_ACTION);
        }

        Set<UUID> channelMembers = members.get(channelId);
        if (channelMembers == null || !channelMembers.remove(targetId)) {
            return failure(ChannelAction.KICK, packet, ErrorCode.NOT_IN_CHANNEL);
        }
        PlayerChannelState state = stateOf(targetId);
        if (state != null) {
            state.leaveChannel(channelId);
            persist(targetId, state);
        }
        platform.logDebug("Lite authority kicked " + targetId + " from " + channelId
                + " by " + operatorId);

        ChannelActionResponsePacket response = success(ChannelAction.KICK, packet, channelId);
        response.addExtra("targetId", targetId.toString());
        String operatorName = firstNonBlank(packet.getExtra("operatorName"),
                packet.getExtra("operator_name"));
        if (operatorName != null && !operatorName.isEmpty()) {
            response.addExtra("operatorName", operatorName);
        }
        return response;
    }

    /**
     * Handles BAN.
     *
     * <p>Differs from MUTE in two ways that matter:
     * <ul>
     *   <li>A blank channel id is legal and means a network-wide ban; a mute
     *       requires a channel.</li>
     *   <li>A ban also removes the target from the channel it covers. Otherwise
     *       the player would keep receiving that channel's traffic while being
     *       unable to speak, which is not what a ban means.</li>
     * </ul>
     *
     * <p>Rejoining is blocked separately in {@code join}; this method only
     * records the ban and applies its immediate effect.
     */
    private ChannelActionResponsePacket ban(ChannelActionPacket packet) {
        String channelId = packet.getChannelId();
        UUID operatorId = resolveOperatorId(packet);
        if (operatorId == null) {
            return failure(ChannelAction.BAN, packet, ErrorCode.BAD_REQUEST);
        }
        UUID targetId = resolveTargetId(packet);
        if (targetId == null) {
            return failure(ChannelAction.BAN, packet, ErrorCode.TARGET_OFFLINE);
        }
        boolean global = channelId == null || channelId.isBlank();
        if (!global && !channels().containsKey(channelId)) {
            return failure(ChannelAction.BAN, packet, ErrorCode.NOT_FOUND);
        }
        if (!canModerate(operatorId, BAN_PERMISSION)) {
            return failure(ChannelAction.BAN, packet, ErrorCode.FORBIDDEN);
        }
        if (targetId.equals(operatorId)) {
            return failure(ChannelAction.BAN, packet, ErrorCode.SELF_ACTION);
        }

        long durationMs = parseDurationMs(packet);
        String reason = firstNonBlank(packet.getExtra("reason"),
                packet.getExtra("banReason"), "Banned by admin");
        long expireTime = durationMs > 0 ? System.currentTimeMillis() + durationMs : 0L;
        String scope = global ? null : channelId;
        BanInfo ban = new BanInfo(scope, expireTime, reason, operatorId);
        banTable(targetId).put(banKey(scope), ban);

        List<String> evicted = new ArrayList<>();
        if (global) {
            for (Map.Entry<String, Set<UUID>> entry : members.entrySet()) {
                if (entry.getValue().remove(targetId)) {
                    evicted.add(entry.getKey());
                }
            }
        } else if (members.get(channelId) != null && members.get(channelId).remove(targetId)) {
            evicted.add(channelId);
        }
        PlayerChannelState state = stateOf(targetId);
        if (state != null && !evicted.isEmpty()) {
            for (String evictedChannel : evicted) {
                state.leaveChannel(evictedChannel);
            }
            persist(targetId, state);
        }

        if (store.isPersistent()) {
            store.saveBan(targetId, ban);
        }
        platform.logDebug("Lite authority banned " + targetId + " in "
                + describeBanScope(scope) + " (durationMs=" + durationMs
                + ", evicted=" + evicted + ") by " + operatorId);

        ChannelActionResponsePacket response = success(ChannelAction.BAN, packet, channelId);
        response.addExtra("targetId", targetId.toString());
        response.addExtra("durationMs", String.valueOf(durationMs));
        String operatorName = firstNonBlank(packet.getExtra("operatorName"),
                packet.getExtra("operator_name"));
        if (operatorName != null && !operatorName.isEmpty()) {
            response.addExtra("operatorName", operatorName);
        }
        return response;
    }

    /**
     * Handles UNBAN: the mirror of {@link #ban}. Unbanning a player who is not
     * banned still succeeds, matching the backend's idempotent unban.
     */
    private ChannelActionResponsePacket unban(ChannelActionPacket packet) {
        String channelId = packet.getChannelId();
        UUID operatorId = resolveOperatorId(packet);
        if (operatorId == null) {
            return failure(ChannelAction.UNBAN, packet, ErrorCode.BAD_REQUEST);
        }
        UUID targetId = resolveTargetId(packet);
        if (targetId == null) {
            return failure(ChannelAction.UNBAN, packet, ErrorCode.TARGET_OFFLINE);
        }
        boolean global = channelId == null || channelId.isBlank();
        if (!global && !channels().containsKey(channelId)) {
            return failure(ChannelAction.UNBAN, packet, ErrorCode.NOT_FOUND);
        }
        if (!canModerate(operatorId, BAN_PERMISSION)) {
            return failure(ChannelAction.UNBAN, packet, ErrorCode.FORBIDDEN);
        }

        String scope = global ? null : channelId;
        ConcurrentMap<String, BanInfo> table = bans.get(targetId);
        boolean removed = table != null && table.remove(banKey(scope)) != null;
        if (removed && store.isPersistent()) {
            store.deleteBan(targetId, scope);
        }
        platform.logDebug("Lite authority unbanned " + targetId + " in "
                + describeBanScope(scope) + " (was banned=" + removed + ")");

        ChannelActionResponsePacket response = success(ChannelAction.UNBAN, packet, channelId);
        response.addExtra("targetId", targetId.toString());
        return response;
    }

    /**
     * Whether the player is currently banned from this channel, either directly
     * or through a network-wide ban.
     *
     * @param playerId the player
     * @param channelId the channel they are trying to enter
     * @return {@code true} when entry must be refused
     */
    private boolean isBanned(UUID playerId, String channelId) {
        if (playerId == null) {
            return false;
        }
        ConcurrentMap<String, BanInfo> table = bans.get(playerId);
        if (table == null) {
            return false;
        }
        return isActiveBan(table.get(banKey(channelId)))
                || isActiveBan(table.get(GLOBAL_KEY));
    }

    private static boolean isActiveBan(BanInfo ban) {
        return ban != null && !ban.isExpired();
    }

    /** @return the map key for a scope; null means the global ban */
    private static String banKey(String channelId) {
        return channelId != null ? channelId : GLOBAL_KEY;
    }

    private static String describeBanScope(String scope) {
        return scope != null ? scope : "<global>";
    }

    private ConcurrentMap<String, BanInfo> banTable(UUID playerId) {
        return bans.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
    }

    /**
     * Applies persisted ban rows, dropping already-expired ones.
     *
     * @param playerId the restored player
     * @param rows the raw rows from the store
     */
    private void applyRestoredBans(UUID playerId, List<BanInfo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int applied = 0;
        for (BanInfo ban : rows) {
            if (!isActiveBan(ban)) {
                continue;
            }
            banTable(playerId).put(banKey(ban.getChannelId()), ban);
            applied++;
        }
        if (applied > 0) {
            platform.logDebug("Lite authority restored " + applied
                    + " ban(s) for " + playerId);
        }
    }

    /**
     * Queues a one-time restore of the player's persisted bans, mirroring the
     * mute restore.
     *
     * @param playerId the player to restore
     */
    private void restoreBansOnce(UUID playerId) {
        if (playerId == null || !store.isPersistent() || !bansRestoreRequested.add(playerId)) {
            return;
        }
        store.loadBans(playerId, rows -> platform.execute(() -> applyRestoredBans(playerId, rows)));
    }

    /**
     * Applies ban rows without going through the store.
     *
     * <p>Package-private test seam, the twin of {@code restoreMutesForTests}.
     *
     * @param playerId the banned player
     * @param rows the rows to apply
     */
    void restoreBansForTests(UUID playerId, List<BanInfo> rows) {
        synchronized (this) {
            applyRestoredBans(playerId, rows);
        }
    }


    /**
     * Whether this operator may moderate.
     *
     * <p>Lite has no permission hierarchy (the backend resolves a four-level
     * {@code PermissionManager} role plus a console sentinel), so this uses the
     * one moderation node the ecosystem already has: {@code novachat.mute}, the
     * same node the platform {@code MuteCommand} gates on. The console identity
    /**
     * Whether this operator may perform a moderation action.
     *
     * <p>Delegates to {@link LitePermissions}, which owns the rule.
     *
     * @param operatorId the resolved operator
     * @param permission the node that gates this action
     * @return {@code true} when the operator may perform it
     */
    private boolean canModerate(UUID operatorId, String permission) {
        return permissions.canModerate(operatorId, permission);
    }

    /**
     * Resolves the acting operator.
     *
     * <p>Moderation packets carry {@code operatorId} (see the platform
     * {@code MuteCommand}); {@code resolvePlayerId} cannot be reused because it
     * looks for {@code playerId}/{@code player_id}/{@code player_uuid}, none of
     * which the command stamps.
     *
     * @param packet the request
     * @return the operator, or null when absent/unparseable
     */
    private UUID resolveOperatorId(ChannelActionPacket packet) {
        UUID id = parseUuid(packet.getExtra("operatorId"));
        if (id == null) {
            id = parseUuid(packet.getExtra("operator_id"));
        }
        return id;
    }

    /**
     * Resolves the moderation target.
     *
     * <p>The target id is only stamped when the target happens to be on the
     * same sub-server as the operator, so on a proxy the name is usually the
     * only thing that arrives. Resolution therefore prefers the explicit id and
     * falls back to the online-player lookup by name.
     *
     * @param packet the request
     * @return the target, or null when it cannot be resolved on this proxy
     */
    private UUID resolveTargetId(ChannelActionPacket packet) {
        UUID id = parseUuid(packet.getExtra("targetId"));
        if (id == null) {
            id = parseUuid(packet.getExtra("target_id"));
        }
        if (id == null) {
            ProxyPlayer byName = findOnlineByName(packet.getExtra("targetName"));
            if (byName == null) {
                byName = findOnlineByName(packet.getExtra("target_name"));
            }
            id = byName != null ? byName.getUniqueId() : null;
        }
        return id;
    }

    /**
     * Parses the requested mute duration, mirroring the backend's
     * {@code parseDurationMs}: {@code durationMs}/{@code duration_ms} first as
     * milliseconds, then {@code duration}/{@code durationSeconds} as seconds.
     *
     * @param packet the request
     * @return the duration in milliseconds, {@code 0} for permanent
     */
    private static long parseDurationMs(ChannelActionPacket packet) {
        Long millis = parseLong(firstNonBlank(packet.getExtra("durationMs"),
                packet.getExtra("duration_ms")));
        if (millis != null) {
            return Math.max(0L, millis);
        }
        Long seconds = parseLong(firstNonBlank(packet.getExtra("duration"),
                packet.getExtra("durationSeconds")));
        if (seconds != null) {
            return seconds <= 0 ? 0L : seconds * 1000L;
        }
        return 0L;
    }

    private static Long parseLong(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * @param playerId the player
     * @return that player's mutable channel-keyed mute table
     */
    private ConcurrentMap<String, MuteInfo> muteTable(UUID playerId) {
        return mutes.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>());
    }

    /**
     * Whether the player is currently muted in this channel, either directly or
     * through a network-wide mute.
     *
     * <p>Expiry is evaluated here against {@code System.currentTimeMillis()},
     * the same clock {@code SlowModeTracker} uses, so an expired row stops
     * blocking even before the shutdown sweep deletes it.
     *
     * @param playerId the sender
     * @param channelId the channel being spoken in
     * @return {@code true} when the message must be rejected
     */
    private boolean isMuted(UUID playerId, String channelId) {
        if (playerId == null) {
            return false;
        }
        ConcurrentMap<String, MuteInfo> table = mutes.get(playerId);
        if (table == null) {
            return false;
        }
        return isActive(table.get(channelId))
                || isActive(table.get(GLOBAL_KEY));
    }

    private static boolean isActive(MuteInfo mute) {
        return mute != null && !mute.isExpired();
    }

    /**
     * Applies persisted mute rows for one player, dropping entries that have
     * already expired so a stale row cannot re-block a player after a restart.
     *
     * @param playerId the restored player
     * @param rows the raw rows from the store, expired included
     */
    private void applyRestoredMutes(UUID playerId, List<MuteInfo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int applied = 0;
        for (MuteInfo mute : rows) {
            if (!isActive(mute)) {
                continue;
            }
            String key = mute.getChannelId() != null
                    ? mute.getChannelId()
                    : GLOBAL_KEY;
            muteTable(playerId).put(key, mute);
            applied++;
        }
        if (applied > 0) {
            platform.logDebug("Lite authority restored " + applied
                    + " mute(s) for " + playerId);
        }
    }

    /**
     * Queues a one-time restore of the player's persisted mutes.
     *
     * <p>Mirrors {@link #restoreOnce}: the read runs on the storage I/O thread
     * and the result is applied through the platform dispatch hop, so this never
     * blocks the caller.
     *
     * @param playerId the player to restore
     */
    private void restoreMutesOnce(UUID playerId) {
        if (playerId == null || !store.isPersistent() || !mutesRestoreRequested.add(playerId)) {
            return;
        }
        store.loadMutes(playerId, rows -> platform.execute(() -> applyRestoredMutes(playerId, rows)));
    }

    /**
     * Applies mute rows without going through the store.
     *
     * <p>Package-private test seam: it lets a test place a row that a real
     * database could hold but the client cannot currently produce (a global
     * mute, or one whose deadline has already passed) without opening a
     * database.
     *
     * @param playerId the muted player
     * @param rows the rows to apply
     */
    void restoreMutesForTests(UUID playerId, List<MuteInfo> rows) {
        synchronized (this) {
            applyRestoredMutes(playerId, rows);
        }
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
    private Map<String, LiteChannelConfig> channels() {
        return config().getLiteChannels();
    }

    private ProxyConfigView config() {
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
        // Same for mutes: the next session must be able to re-read them, and the
        // enforcement table is dropped so a long-lived proxy does not accumulate
        // an entry per player that ever connected. The persisted rows survive.
        mutesRestoreRequested.remove(playerId);
        mutes.remove(playerId);
        bansRestoreRequested.remove(playerId);
        bans.remove(playerId);
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
        restoreMutesOnce(playerId);
        restoreBansOnce(playerId);
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
