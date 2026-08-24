package com.nova.link.chat;

import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.link.channel.Channel;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves @mentions in a delivered chat message and emits backend
 * {@link MentionPacket}s to the mentioned players' client connections.
 *
 * <p>This closes the cross-server notification gap (§11.6 Proposal 05): the
 * client receive side (MentionNotifier.notifyOrSkip + highlightMentions) is
 * already wired on all platforms, but without this backend emit a player who
 * is mentioned while on a different game server never receives the
 * sound/title notification. The client's {@code highlightMentions} only does
 * local highlighting; cross-server notification requires the backend to
 * originate the MentionPacket.
 *
 * <p>Reuses {@link MentionNotifier} from {@code NovaChat:common} for all
 * @name parsing, self-mention exclusion, distinct, and preview truncation
 * logic — no independent parser is duplicated here.
 *
 * <p>The {@link MentionNotifier.PlayerResolver} is backed by
 * {@link PlayerStateManager#getAllPlayerStates()}: each cached online state's
 * {@link PlayerState#getPlayerName()} is matched case-insensitively to the
 * mentioned name, returning the state's {@link PlayerState#getPlayerId()}.
 *
 * <p><b>Social-relation filtering (§11.6 提案 08 item-18 Part C):</b> when
 * wired with an {@link IgnoreLookup} / {@link MentionPrefLookup} (via the
 * 5-arg constructor), {@code emitMentions} suppresses the MentionPacket
 * notification — not the chat message itself — when the mentioner ignores the
 * mentioned player (or vice-versa), or when the mentioned player has mentions
 * disabled. Per §11.6 提案 08: "关系只影响通知" / "不把 ignore 当服务端封禁"
 * (relations affect notifications only; ignore is not a server-side ban on
 * chat delivery). The chat message was already delivered to the channel by
 * the caller; only the notification ping is filtered. Both lookups are
 * non-throwing and fail-open so a persistence gap never silently suppresses
 * a mention.
 *
 * <p><b>Known limitation — cross-instance delivery:</b> a mentioned player
 * who is connected to a different NovaLink backend instance (not this one)
 * will have no {@link ClientConnection} findable via
 * {@link ServerNetworkHandler#findByClientId(String)}. In that case the
 * MentionPacket is silently skipped. Cross-instance mention delivery
 * requires a message-bus fan-out and is out of scope for this slice.
 */
public class MentionResolver {

    private static final Logger logger = LoggerFactory.getLogger(MentionResolver.class);

    private final MentionNotifier notifier;
    private final PlayerStateManager playerStateManager;
    private final ServerNetworkHandler networkHandler;
    private final IgnoreLookup ignoreLookup;
    private final MentionPrefLookup mentionPrefLookup;

    /**
     * Legacy constructor (§11.6 Proposal 05 backend emit). Delegates to the
     * 5-arg overload with null lookups, preserving the no-filter behavior
     * NovaLinkMain currently relies on (wired at NovaLinkMain @305-306).
     *
     * @param notifier           reused mention logic from NovaChat:common
     * @param playerStateManager provides the online player name→UUID map
     * @param networkHandler     routes MentionPackets to client connections
     */
    public MentionResolver(MentionNotifier notifier,
                           PlayerStateManager playerStateManager,
                           ServerNetworkHandler networkHandler) {
        this(notifier, playerStateManager, networkHandler, null, null);
    }

    /**
     * Overloaded constructor wiring social-relation and notification-preference
     * lookups (§11.6 提案 08 item-18 Part C). Both lookups are nullable; when
     * null the legacy no-filter path is preserved.
     *
     * <p>These lookups are NON-throwing {@link FunctionalInterface}s by design.
     * The real persistence API ({@code DatabaseProvider.isIgnored} /
     * {@code getNotificationPreference}) throws {@code DatabaseException}, but a
     * persistence gap must NOT break mention delivery, so the lambda adapters
     * (wired in NovaLinkMain) swallow exceptions and expose fail-open semantics
     * (ignore → {@code false} / mentionsEnabled → {@code true} → mention still
     * sent). The {@link #safeIsIgnored} / {@link #safeMentionsEnabled} helpers
     * re-guard this contract at the call site.
     *
     * <p><b>Scope note (§11.6 提案 08):</b> the chat message itself is already
     * delivered to the channel by the caller before {@code emitMentions} runs;
     * the filtering here only suppresses the {@link MentionPacket}
     * <b>notification</b>, not the chat message. "关系只影响通知" / "不把
     * ignore 当服务端封禁" — ignore is not a server-side ban on delivery.
     *
     * @param notifier           reused mention logic from NovaChat:common
     * @param playerStateManager provides the online player name→UUID map
     * @param networkHandler     routes MentionPackets to client connections
     * @param ignoreLookup       directional ignore test (nullable; null = legacy)
     * @param mentionPrefLookup  per-player mentions-enabled pref (nullable; null = legacy)
     */
    public MentionResolver(MentionNotifier notifier,
                           PlayerStateManager playerStateManager,
                           ServerNetworkHandler networkHandler,
                           IgnoreLookup ignoreLookup,
                           MentionPrefLookup mentionPrefLookup) {
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.playerStateManager = Objects.requireNonNull(playerStateManager, "playerStateManager");
        this.networkHandler = Objects.requireNonNull(networkHandler, "networkHandler");
        this.ignoreLookup = ignoreLookup;
        this.mentionPrefLookup = mentionPrefLookup;
    }

    /**
     * Resolves @name and @all mentions in the delivered message into a list
     * of {@link MentionPacket}s. Each packet carries a {@code mentionedId}
     * for downstream delivery.
     *
     * <p>@name mentions are resolved via {@link MentionNotifier#createMentionPackets}
     * using a {@link MentionNotifier.PlayerResolver} backed by
     * {@link PlayerStateManager}. @all is expanded via
     * {@link MentionNotifier#createAllMentionPackets} to every player whose
     * client received the fan-out (derived from {@code recipientClientIds}).
     *
     * <p>This method returns the full resolved packet list and applies NO
     * social-relation filtering (that happens in {@link #emitMentions}); tests
     * asserting on the resolved list are unaffected by 提案 08 filtering.
     *
     * @param message            the delivered chat message
     * @param channel            the channel the message was delivered to
     *                           (used for channelId fallback; members are
     *                           derived from recipientClientIds, not
     *                           {@link Channel#getMembers()})
     * @param recipientClientIds the set of client IDs that received the
     *                           fan-out (used to expand @all to the players
     *                           who actually got the message)
     * @return list of mention packets (never null, may be empty)
     */
    public List<MentionPacket> resolveMentions(ChatMessagePacket message,
                                              Channel channel,
                                              Set<String> recipientClientIds) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(channel, "channel");

        UUID mentionerId = message.getSenderId();
        if (mentionerId == null) {
            return List.of();
        }

        String content = message.getContent();
        if (content == null || content.isEmpty()) {
            return List.of();
        }

        String channelId = message.getChannelId() != null
                ? message.getChannelId() : channel.getId();
        String mentionerName = message.getSenderName();

        // @name mentions: resolve names -> UUIDs via cached player states.
        List<MentionPacket> packets = new ArrayList<>(
                notifier.createMentionPackets(
                        mentionerId, mentionerName, channelId, content,
                        this::resolvePlayerUUID));

        // @all: expand to every player who received the message on this
        // backend instance.
        if (notifier.hasAllMention(content)) {
            List<UUID> channelMembers = collectChannelMembers(recipientClientIds);
            if (!channelMembers.isEmpty()) {
                packets.addAll(notifier.createAllMentionPackets(
                        mentionerId, mentionerName, channelId, content, channelMembers));
            }
        }

        return packets;
    }

    /**
     * Resolves mentions and delivers each {@link MentionPacket} to the
     * mentioned player's active client connection. Best-effort: failures
     * (cross-instance, offline, stale connection) are silently skipped and
     * logged at debug level so chat delivery is never affected.
     *
     * <p>§11.6 提案 08 item-18 Part C: before delivery, each packet is checked
     * against the wired {@link IgnoreLookup} / {@link MentionPrefLookup} (when
     * non-null). The notification is suppressed when the mentioner ignores the
     * mentioned player (or vice-versa) or when the mentioned player has
     * mentions disabled. The chat message itself is already delivered to the
     * channel; this filtering only suppresses the MentionPacket notification.
     * Lookups are nullable (legacy 3-arg wiring) and fail-open.
     *
     * @param message            the delivered chat message
     * @param channel            the channel the message was delivered to
     * @param recipientClientIds the set of client IDs that received the fan-out
     */
    public void emitMentions(ChatMessagePacket message, Channel channel,
                             Set<String> recipientClientIds) {
        List<MentionPacket> packets;
        try {
            packets = resolveMentions(message, channel, recipientClientIds);
        } catch (Exception e) {
            logger.debug("Mention resolution failed for channel={}: {}",
                    channel.getId(), e.getMessage());
            return;
        }

        UUID mentionerId = message.getSenderId();
        for (MentionPacket packet : packets) {
            // §11.6 提案 08 item-18 Part C: social relations affect only the
            // MentionPacket notification, NOT the chat message itself (which
            // the caller already delivered to the channel). "关系只影响通知" /
            // "不把 ignore 当服务端封禁". Suppress the notification when the
            // mentioner ignores the mentioned player (or vice-versa) or when
            // the mentioned player has mentions disabled. Lookups are nullable
            // (legacy 3-arg wiring) and fail-open, so a persistence gap never
            // incorrectly suppresses a mention. @all expansion is filtered
            // per-packet the same way.
            if (shouldSkipMention(mentionerId, packet.getMentionedId())) {
                continue;
            }
            try {
                deliverMention(packet);
            } catch (Exception e) {
                logger.debug("Mention delivery failed for mentionedId={}: {}",
                        packet.getMentionedId(), e.getMessage());
            }
        }
    }

    /**
     * Decides whether the MentionPacket notification to {@code mentionedId}
     * should be suppressed, per §11.6 提案 08 item-18 Part C. Fail-open: any
     * null/equal id, null lookup, or thrown exception leaves the mention
     * delivered (ignore → false / mentionsEnabled → true).
     */
    private boolean shouldSkipMention(UUID mentionerId, UUID mentionedId) {
        if (ignoreLookup != null
                && (safeIsIgnored(mentionerId, mentionedId)
                        || safeIsIgnored(mentionedId, mentionerId))) {
            logger.debug("Mention skip (ignore relation) mentioner={} mentioned={}",
                    mentionerId, mentionedId);
            return true;
        }
        if (mentionPrefLookup != null && !safeMentionsEnabled(mentionedId)) {
            logger.debug("Mention skip (mentions disabled) mentioned={}", mentionedId);
            return true;
        }
        return false;
    }

    /**
     * Directional ignore test, fail-open to {@code false} (mention still sent).
     * Null/equal ids → false; null lookup → false; any exception → false.
     */
    private boolean safeIsIgnored(UUID sourceId, UUID targetId) {
        if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
            return false;
        }
        try {
            return ignoreLookup.isIgnored(sourceId, targetId);
        } catch (Exception e) {
            logger.debug("Ignore lookup failed source={} target={}: {}",
                    sourceId, targetId, e.getMessage());
            return false;
        }
    }

    /**
     * Per-player mentions-enabled pref, fail-open to {@code true} (mention sent).
     * Null id → true; null lookup → true; any exception → true.
     */
    private boolean safeMentionsEnabled(UUID playerId) {
        if (playerId == null) {
            return true;
        }
        try {
            return mentionPrefLookup.isMentionsEnabled(playerId);
        } catch (Exception e) {
            logger.debug("Mentions-pref lookup failed player={}: {}",
                    playerId, e.getMessage());
            return true;
        }
    }

    /**
     * Delivers a single mention packet to the mentioned player's connection.
     * Silently skips when the player is not on this backend instance.
     */
    private void deliverMention(MentionPacket packet) {
        UUID mentionedId = packet.getMentionedId();
        if (mentionedId == null) {
            return;
        }

        PlayerState state = playerStateManager.getCachedState(mentionedId).orElse(null);
        if (state == null) {
            // Player not in this backend's cache -> cross-instance; skip.
            logger.debug("Mention skip (not cached) mentionedId={}", mentionedId);
            return;
        }

        String clientId = state.getClientId();
        if (clientId == null) {
            return;
        }

        ClientConnection connection = networkHandler.findByClientId(clientId);
        if (connection == null || !connection.isActive()) {
            // Player's client is not connected to this backend ->
            // cross-instance; skip. (Known limitation: cross-instance
            // delivery needs a message bus.)
            logger.debug("Mention skip (no active connection) mentionedId={} clientId={}",
                    mentionedId, clientId);
            return;
        }

        connection.sendPacket(packet);
    }

    /**
     * PlayerResolver backed by {@link PlayerStateManager}: matches the given
     * name case-insensitively against all cached player states and returns
     * the matching player's UUID, or {@code null} if not found.
     * {@link MentionNotifier} already filters nulls.
     */
    private UUID resolvePlayerUUID(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        for (PlayerState state : playerStateManager.getAllPlayerStates()) {
            String name = state.getPlayerName();
            if (name != null && name.equalsIgnoreCase(playerName)) {
                return state.getPlayerId();
            }
        }
        return null;
    }

    /**
     * Collects the UUIDs of all players who received the fan-out (i.e. whose
     * client connection was a recipient). Used to expand @all to the actual
     * recipient set rather than the full channel membership roster (which
     * may include offline players).
     */
    private List<UUID> collectChannelMembers(Set<String> recipientClientIds) {
        if (recipientClientIds == null || recipientClientIds.isEmpty()) {
            return List.of();
        }
        List<UUID> members = new ArrayList<>();
        for (PlayerState state : playerStateManager.getAllPlayerStates()) {
            String clientId = state.getClientId();
            if (clientId != null && recipientClientIds.contains(clientId)) {
                members.add(state.getPlayerId());
            }
        }
        return members;
    }

    /**
     * Non-throwing directional ignore test used by {@link MentionResolver}
     * (§11.6 提案 08 item-18 Part C). Returns {@code true} iff {@code sourceId}
     * ignores {@code targetId}. Implementations MUST swallow persistence
     * errors and return {@code false} on any gap (fail-open: an exception must
     * not suppress a mention); {@link #safeIsIgnored} re-guards this.
     *
     * <p>Public so it can be referenced both from the same-package unit test
     * (lambda target typing) and from {@code NovaLinkMain} (package
     * {@code com.nova.link}) when wiring {@code dbp::isIgnored}. A private or
     * package-private nesting would make the 5-arg constructor uncallable
     * from those sites.
     */
    @FunctionalInterface
    public interface IgnoreLookup {
        /** Directional: true iff source ignores target; never throws. */
        boolean isIgnored(UUID sourceId, UUID targetId);
    }

    /**
     * Non-throwing per-player mentions-enabled preference used by
     * {@link MentionResolver} (§11.6 提案 08 item-18 Part C). Returns whether
     * the player wants @mention notifications. Implementations MUST swallow
     * persistence errors and return {@code true} on any gap (fail-open:
     * default enabled); {@link #safeMentionsEnabled} re-guards this.
     *
     * <p>Public for the same wiring reason as {@link IgnoreLookup}.
     */
    @FunctionalInterface
    public interface MentionPrefLookup {
        /** Returns whether the player has mentions enabled; never throws; defaults true on any gap. */
        boolean isMentionsEnabled(UUID playerId);
    }
}
