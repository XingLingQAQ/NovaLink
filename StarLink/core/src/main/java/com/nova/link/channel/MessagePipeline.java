package com.nova.link.channel;

import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.auth.PermissionLevel;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.chat.MentionResolver;
import com.nova.link.console.ConsoleSentinel;
import com.nova.link.database.ChatMessageRecord;
import com.nova.link.filter.FilterResult;
import com.nova.link.filter.SensitiveWordFilter;
import com.nova.link.log.ChatLogger;
import com.nova.link.log.MessageLogService;
import com.nova.link.mute.MuteManager;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import com.nova.link.spy.SpyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Explicit chat processing pipeline:
 * <pre>
 *   validate → resolve channel → (optional) cross-client check
 *            → mute → sensitive-word filter
 *            → scope fan-out → spy → websocket
 * </pre>
 * <p>
 * Extracted from ad-hoc checks so every ingress path (TCP handler, REST, tests)
 * shares the same ordered stages and drop reasons.
 * <p>
 * Thread-safety: boundary enforcement is passed as a method parameter (never a
 * mutable instance flag), so concurrent calls cannot interleave and disable each
 * other's cross-client checks.
 */
public class MessagePipeline {

    private static final Logger logger = LoggerFactory.getLogger(MessagePipeline.class);

    private final ChannelManager channelManager;
    private final ServerNetworkHandler networkHandler;

    private BiPredicate<String, String> permissionChecker = (clientId, permission) -> true;
    private MuteManager muteManager;
    private BanManager banManager;
    private PermissionManager permissionManager;
    private final SlowModeTracker slowModeTracker;
    private SensitiveWordFilter sensitiveWordFilter;
    private SpyManager spyManager;
    private MessageRouter.WebSocketBroadcaster webSocketBroadcaster;
    private ChatLogger chatLogger;
    private MessageLogService messageLogService;
    private MentionResolver mentionResolver;

    // Feature switches (FeatureConfig §3.5) — volatile for hot-reload visibility.
    private volatile boolean crossServerChatEnabled = true;
    private volatile boolean messageLogEnabled = false;
    // §11.6 Proposal 05 sub-slice: backend MentionPacket emit. Default true so
    // @mention notifications reach cross-server recipients without an explicit
    // config opt-in; flip to false to suppress backend mention delivery.
    private volatile boolean mentionsEnabled = true;

    public MessagePipeline(ChannelManager channelManager, ServerNetworkHandler networkHandler) {
        this(channelManager, networkHandler, new SlowModeTracker());
    }

    /**
     * @param slowModeTracker injectable timestamp tracker (tests pass a
     *                        clock-controlled instance)
     */
    public MessagePipeline(ChannelManager channelManager, ServerNetworkHandler networkHandler,
                           SlowModeTracker slowModeTracker) {
        this.channelManager = Objects.requireNonNull(channelManager, "channelManager");
        this.networkHandler = Objects.requireNonNull(networkHandler, "networkHandler");
        this.slowModeTracker = Objects.requireNonNull(slowModeTracker, "slowModeTracker");
    }

    public void setPermissionChecker(BiPredicate<String, String> permissionChecker) {
        this.permissionChecker = permissionChecker != null ? permissionChecker : (c, p) -> true;
    }

    public void setMuteManager(MuteManager muteManager) {
        this.muteManager = muteManager;
    }

    public void setBanManager(BanManager banManager) {
        this.banManager = banManager;
    }

    /**
     * Wires the permission manager used for slow-mode admin exemption
     * (channel admin and above skip the interval check).
     */
    public void setPermissionManager(PermissionManager permissionManager) {
        this.permissionManager = permissionManager;
    }

    public SlowModeTracker getSlowModeTracker() {
        return slowModeTracker;
    }

    public void setSensitiveWordFilter(SensitiveWordFilter sensitiveWordFilter) {
        this.sensitiveWordFilter = sensitiveWordFilter;
    }

    /**
     * Exposes the wired filter so hot-apply paths (REST settings) can toggle it
     * without going through a disk reload.
     */
    public SensitiveWordFilter getSensitiveWordFilter() {
        return sensitiveWordFilter;
    }

    public void setSpyManager(SpyManager spyManager) {
        this.spyManager = spyManager;
    }

    public void setWebSocketBroadcaster(MessageRouter.WebSocketBroadcaster webSocketBroadcaster) {
        this.webSocketBroadcaster = webSocketBroadcaster;
    }

    public void setChatLogger(ChatLogger chatLogger) {
        this.chatLogger = chatLogger;
    }

    /**
     * Wires the async message persistence service. Persisting happens only
     * when {@code messageLogEnabled} is true and fan-out succeeded.
     */
    public void setMessageLogService(MessageLogService messageLogService) {
        this.messageLogService = messageLogService;
    }

    public void setCrossServerChatEnabled(boolean crossServerChatEnabled) {
        this.crossServerChatEnabled = crossServerChatEnabled;
    }

    public boolean isCrossServerChatEnabled() {
        return crossServerChatEnabled;
    }

    public void setMessageLogEnabled(boolean messageLogEnabled) {
        this.messageLogEnabled = messageLogEnabled;
    }

    /**
     * Wires the §11.6 Proposal 05 mention resolver. When non-null and
     * {@link #mentionsEnabled} is true, successful fan-out is followed by a
     * best-effort emit of backend {@code MentionPacket}s to mentioned
     * players' connections. Nullable: when unset (tests, partial wiring) the
     * mention stage is skipped entirely so existing pipeline behavior is
     * unchanged.
     */
    public void setMentionResolver(MentionResolver mentionResolver) {
        this.mentionResolver = mentionResolver;
    }

    public MentionResolver getMentionResolver() {
        return mentionResolver;
    }

    public void setMentionsEnabled(boolean mentionsEnabled) {
        this.mentionsEnabled = mentionsEnabled;
    }

    public boolean isMentionsEnabled() {
        return mentionsEnabled;
    }

    /**
     * Full pipeline for untrusted ingress (TCP game clients).
     * Always enforces the SERVER/PRIVATE sender-client boundary.
     *
     * @param message inbound message (content may be mutated by filter stage)
     * @return structured result including recipients or drop reason
     */
    public MessagePipelineResult process(ChatMessagePacket message) {
        // --- Stage 1: payload validation ---
        if (message == null) {
            return MessagePipelineResult.dropped(MessagePipelineResult.DropReason.NULL_MESSAGE, null);
        }

        String content = message.getContent();
        if (content == null || content.isBlank()) {
            logger.debug("Pipeline drop EMPTY_CONTENT from {}", message.getSenderName());
            return MessagePipelineResult.dropped(MessagePipelineResult.DropReason.EMPTY_CONTENT, message);
        }
        if (content.length() > NovaConstants.MAX_MESSAGE_LENGTH) {
            logger.warn("Pipeline drop OVERSIZED_CONTENT ({} chars) from {}",
                    content.length(), message.getSenderName());
            return MessagePipelineResult.dropped(MessagePipelineResult.DropReason.OVERSIZED_CONTENT, message);
        }
        if (message.getChannelId() == null || message.getChannelId().isBlank()) {
            logger.warn("Pipeline drop MISSING_CHANNEL_ID from {}", message.getSenderName());
            return MessagePipelineResult.dropped(MessagePipelineResult.DropReason.MISSING_CHANNEL_ID, message);
        }

        // --- Stage 2: resolve channel ---
        Channel channel = channelManager.getChannel(message.getChannelId());
        if (channel == null) {
            logger.warn("Pipeline drop CHANNEL_NOT_FOUND '{}' from {}",
                    message.getChannelId(), message.getSenderName());
            return MessagePipelineResult.dropped(MessagePipelineResult.DropReason.CHANNEL_NOT_FOUND, message);
        }

        return processForChannel(channel, message, true);
    }

    /**
     * Trusted path when the channel is already resolved (REST / internal helpers).
     * Does <em>not</em> re-check the sender-client boundary — callers are responsible
     * for constructing a trusted packet (e.g. API stamps {@code clientId} from the channel).
     */
    public MessagePipelineResult processForChannel(Channel channel, ChatMessagePacket message) {
        return processForChannel(channel, message, false);
    }

    /**
     * Runs pipeline stages from boundary/mute onward for an already-resolved channel.
     *
     * @param enforceSenderClientBoundary when true, SERVER/PRIVATE messages whose
     *                                    sender client does not own the channel are
     *                                    dropped with {@link MessagePipelineResult.DropReason#CROSS_CLIENT_DENIED}
     */
    public MessagePipelineResult processForChannel(Channel channel, ChatMessagePacket message,
                                                   boolean enforceSenderClientBoundary) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(message, "message");

        // --- Stage 3: cross-client boundary (SERVER/PRIVATE) ---
        if (enforceSenderClientBoundary
                && (channel.getScope() == ChannelScope.SERVER || channel.getScope() == ChannelScope.PRIVATE)) {
            String senderClient = message.getClientId();
            if (channel.getClientId() == null || !channel.getClientId().equals(senderClient)) {
                logger.warn("Pipeline drop CROSS_CLIENT_DENIED senderClient={} channel={} owner={}",
                        senderClient, channel.getId(), channel.getClientId());
                return MessagePipelineResult.dropped(
                        MessagePipelineResult.DropReason.CROSS_CLIENT_DENIED, message, channel);
            }
        }

        // --- Stage 4: mute ---
        if (isSenderMuted(message.getSenderId(), channel.getId())) {
            logger.debug("Pipeline drop SENDER_MUTED player={} channel={}",
                    message.getSenderId(), channel.getId());
            return MessagePipelineResult.dropped(
                    MessagePipelineResult.DropReason.SENDER_MUTED, message, channel);
        }

        // --- Stage 4b: ban ---
        if (isSenderBanned(message.getSenderId(), channel.getId())) {
            logger.debug("Pipeline drop SENDER_BANNED player={} channel={}",
                    message.getSenderId(), channel.getId());
            return MessagePipelineResult.dropped(
                    MessagePipelineResult.DropReason.SENDER_BANNED, message, channel);
        }

        // --- Stage 4c: slow mode (channel admins and console are exempt) ---
        int slowModeSeconds = channel.getSlowModeSeconds();
        if (slowModeSeconds > 0 && message.getSenderId() != null
                && !isSlowModeExempt(message.getSenderId(), channel.getId())) {
            long remaining = slowModeTracker.tryAcquire(
                    message.getSenderId(), channel.getId(), slowModeSeconds);
            if (remaining > 0) {
                logger.debug("Pipeline drop SLOW_MODE player={} channel={} remaining={}s",
                        message.getSenderId(), channel.getId(), remaining);
                return MessagePipelineResult.droppedSlowMode(message, channel, remaining);
            }
        }

        // --- Stage 5: sensitive-word filter (mutates content in place) ---
        int filterMatches = 0;
        boolean filtered = false;
        if (sensitiveWordFilter != null && sensitiveWordFilter.isEnabled()) {
            FilterResult result = sensitiveWordFilter.filter(message.getContent());
            if (result != null && result.isFiltered()) {
                message.setContent(result.getFilteredMessage());
                filterMatches = result.getMatchCount();
                filtered = true;
                logger.debug("Pipeline filtered {} match(es) from {}",
                        filterMatches, message.getSenderName());
            }
        }

        // --- Stage 6: scope fan-out ---
        Set<String> recipients = fanOut(channel, message);
        if (recipients.isEmpty()) {
            // Still mirror to spy/ws for audit visibility of muted-empty edge cases? No — no delivery.
            logger.debug("Pipeline NO_RECIPIENTS channel={} scope={}",
                    channel.getId(), channel.getScope());
            // Spy/WS only when we actually attempted delivery to game clients with content.
        }

        // Optional chat logging (FeatureConfig.messageLogEnabled) — records
        // delivered messages via the injected ChatLogger. Does not affect delivery.
        if (messageLogEnabled && chatLogger != null) {
            try {
                chatLogger.log(message.getChannelId(),
                        message.getSenderId() != null ? message.getSenderId().toString() : null,
                        message.getSenderName(),
                        message.getContent());
            } catch (Exception e) {
                logger.debug("ChatLogger failed: {}", e.getMessage());
            }
        }

        // --- Stage 7: spy + websocket (side channels; do not affect drop reason) ---
        if (!recipients.isEmpty() || channel.getScope() == ChannelScope.GLOBAL) {
            // Always mirror successfully processed (non-muted) messages for ops visibility.
            forwardToSpies(message);
            forwardToWebSocket(message);
        } else if (filtered || message.getContent() != null) {
            // Message was valid but nobody online — still useful for panel live feed.
            forwardToSpies(message);
            forwardToWebSocket(message);
        }

        if (recipients.isEmpty()) {
            return MessagePipelineResult.dropped(
                    MessagePipelineResult.DropReason.NO_RECIPIENTS, message, channel);
        }

        // --- Stage 7b: mention emit (§11.6 Proposal 05 sub-slice) ---
        // After successful fan-out, resolve @name/@all mentions and deliver
        // backend MentionPackets to the mentioned players' connections so
        // cross-server recipients get sound/title notifications. Best-effort:
        // any failure is caught and logged at debug so chat delivery is never
        // affected. Skipped when the resolver is not wired (tests) or the
        // feature switch is off.
        if (mentionsEnabled && mentionResolver != null) {
            try {
                mentionResolver.emitMentions(message, channel, recipients);
            } catch (Exception e) {
                logger.debug("Mention emit failed for channel={}: {}",
                        channel.getId(), e.getMessage());
            }
        }

        // --- Stage 8: async persistence (only after successful fan-out) ---
        if (messageLogEnabled && messageLogService != null) {
            messageLogService.logAsync(new ChatMessageRecord(
                    message.getChannelId(),
                    message.getSenderId() != null ? message.getSenderId().toString() : null,
                    message.getSenderName(),
                    message.getClientId(),
                    message.getContent(),
                    System.currentTimeMillis()
            ));
        }

        return MessagePipelineResult.delivered(message, channel, recipients, filtered, filterMatches);
    }

    private boolean isSenderMuted(UUID senderId, String channelId) {
        return muteManager != null && senderId != null && muteManager.isMuted(senderId, channelId);
    }

    private boolean isSenderBanned(UUID senderId, String channelId) {
        return banManager != null && senderId != null && banManager.isBanned(senderId, channelId);
    }

    private boolean isSlowModeExempt(UUID senderId, String channelId) {
        if (ConsoleSentinel.isConsole(senderId)) {
            return true;
        }
        return permissionManager != null
                && permissionManager.getPermissionLevel(senderId, channelId)
                        .hasAtLeast(PermissionLevel.CHANNEL_ADMIN);
    }

    private Set<String> fanOut(Channel channel, ChatMessagePacket message) {
        switch (channel.getScope()) {
            case GLOBAL:
                return fanOutGlobal(channel, message);
            case SERVER:
            case PRIVATE:
                return fanOutBoundClient(channel, message);
            default:
                return Collections.emptySet();
        }
    }

    private Set<String> fanOutGlobal(Channel channel, ChatMessagePacket message) {
        // FeatureConfig.crossServerChatEnabled: when disabled, GLOBAL fan-out is
        // suppressed so chat does not cross servers. Returns empty so the caller
        // records a NO_RECIPIENTS drop — the message is intentionally not delivered.
        if (!crossServerChatEnabled) {
            logger.debug("GLOBAL fan-out suppressed (crossServerChatEnabled=false) channel={}",
                    channel.getId());
            return Collections.emptySet();
        }
        Set<String> recipients = new HashSet<>();
        String requiredPermission = channel.getPermission();

        for (ClientConnection connection : networkHandler.getConnections()) {
            if (!connection.isAuthenticated() || !connection.isActive()) {
                continue;
            }
            String clientId = connection.getClientId();
            if (requiredPermission != null && !requiredPermission.isEmpty()) {
                if (!permissionChecker.test(clientId, requiredPermission)) {
                    logger.debug("GLOBAL fan-out skip client={} missing permission={} channel={}",
                            clientId, requiredPermission, channel.getId());
                    continue;
                }
            }
            connection.sendPacket(message);
            if (clientId != null) {
                recipients.add(clientId);
            }
        }
        return recipients;
    }

    private Set<String> fanOutBoundClient(Channel channel, ChatMessagePacket message) {
        Set<String> recipients = new HashSet<>();
        String targetClientId = channel.getClientId();
        if (targetClientId == null) {
            logger.error("Bound channel '{}' has no clientId", channel.getId());
            return recipients;
        }
        ClientConnection target = networkHandler.findByClientId(targetClientId);
        if (target != null && target.isActive()) {
            target.sendPacket(message);
            recipients.add(targetClientId);
        }
        return recipients;
    }

    private void forwardToWebSocket(ChatMessagePacket message) {
        if (webSocketBroadcaster != null) {
            webSocketBroadcaster.broadcastChatMessage(
                    message.getChannelId(),
                    message.getSenderId() != null ? message.getSenderId().toString() : null,
                    message.getSenderName(),
                    message.getContent()
            );
        }
    }

    private void forwardToSpies(ChatMessagePacket message) {
        if (spyManager != null) {
            spyManager.forwardToSpies(message);
        }
    }

    /**
     * Dry-run recipient calculation (no send, no mute/filter).
     * Used by property tests and admin previews.
     */
    public Set<String> calculateRecipients(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        Set<String> recipients = new HashSet<>();
        switch (channel.getScope()) {
            case GLOBAL:
                String requiredPermission = channel.getPermission();
                for (ClientConnection connection : networkHandler.getConnections()) {
                    if (!connection.isAuthenticated() || connection.getClientId() == null) {
                        continue;
                    }
                    String clientId = connection.getClientId();
                    if (requiredPermission != null && !requiredPermission.isEmpty()
                            && !permissionChecker.test(clientId, requiredPermission)) {
                        continue;
                    }
                    recipients.add(clientId);
                }
                break;
            case SERVER:
            case PRIVATE:
                String channelClientId = channel.getClientId();
                if (channelClientId != null) {
                    ClientConnection connection = networkHandler.findByClientId(channelClientId);
                    if (connection != null && connection.isAuthenticated()) {
                        recipients.add(channelClientId);
                    }
                }
                break;
            default:
                break;
        }
        return recipients;
    }
}
