package com.nova.chat.mod.chat;

import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.WelcomeMessageService;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.itemdisplay.ItemDisplayMessages;
import com.nova.chat.client.itemdisplay.ItemDisplayTokens;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.client.state.PlayerStateStore;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.ChatHandler;
import com.nova.chat.mod.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

/**
 * Intercepts and processes chat messages for the NovaChat mod common layer.
 *
 * <p>This is the mod-side equivalent of the velocity/bukkit/nukkit ChatListener:
 * it owns the shared {@link PlayerStateStore}, routes asynchronous
 * {@link ChannelActionResponsePacket}s through {@link ChannelResponseDispatcher},
 * renders incoming channel chat + mentions via the platform, and forwards player
 * chat to the backend through the shared {@link NetworkClient} facade.
 *
 * <p>Implements {@link ChatHandler} so each mod loader's {@link Platform} can feed
 * its platform-native chat event into the common layer via
 * {@link ChatHandler#onPlayerChat}.
 */
public class ChatInterceptor implements ChatHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatInterceptor.class);

    /** Legacy color prefix applied to @name mentions when rendering chat (UX-DESIGN §4.2). */
    static final String MENTION_HIGHLIGHT_COLOR = MentionNotifier.DEFAULT_HIGHLIGHT_COLOR;

    private final Platform platform;
    private final NetworkClient networkClient;
    private final ModConfig config;
    private final MessageFormatter messageFormatter;
    private final MentionNotifier mentionNotifier = new MentionNotifier();

    /**
     * §11.6 / 提案 05: shared send-side token detection + per-player cooldown
     * for the {@code [item]}/{@code [i]} display play. Mirrors the
     * bukkit/folia/nukkit/pnx {@code ItemDisplayTokens} usage; one instance per
     * ChatInterceptor (same lifecycle as {@link #mentionNotifier}).
     */
    private final ItemDisplayTokens itemDisplayTokens = new ItemDisplayTokens();

    /** Known channels from ConfigSync, for channel-prefix routing; may be null. */
    private final com.nova.chat.client.channel.KnownChannelRegistry knownChannelRegistry;

    /** Per-player ignore lists (/nc ignore) for inbound filtering; may be null. */
    private final com.nova.chat.client.ignore.IgnoreListService ignoreListService;

    /**
     * Shared private-message core (/nc msg, /nc r): send-side packet building,
     * receive-side role rendering, reply-target tracking and backend error
     * rendering. Owned here so the receive path and the command handlers share
     * one reply-target map.
     */
    private final com.nova.chat.client.privatemsg.PrivateMessageService privateMessageService =
            new com.nova.chat.client.privatemsg.PrivateMessageService();

    /** Player chat states indexed by UUID (shared client-core store). */
    private final PlayerStateStore playerStates = new PlayerStateStore();

    /** Shared response dispatcher; created in {@link #registerIncomingHandlers()}. */
    private ChannelResponseDispatcher dispatcher;

    private ChatMode globalMode;

    public ChatInterceptor(Platform platform, NetworkClient networkClient,
                           ModConfig config, MessageFormatter messageFormatter) {
        this(platform, networkClient, config, messageFormatter, null, null);
    }

    public ChatInterceptor(Platform platform, NetworkClient networkClient,
                           ModConfig config, MessageFormatter messageFormatter,
                           com.nova.chat.client.channel.KnownChannelRegistry knownChannelRegistry,
                           com.nova.chat.client.ignore.IgnoreListService ignoreListService) {
        this.platform = platform;
        this.networkClient = networkClient;
        this.config = config;
        this.messageFormatter = messageFormatter;
        this.knownChannelRegistry = knownChannelRegistry;
        this.ignoreListService = ignoreListService;
        this.globalMode = config.getChat().isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        registerIncomingHandlers();
    }

    /**
     * Registers handlers for incoming chat, channel-action responses and mention
     * packets from the backend. Mirrors the velocity ChatListener wiring.
     */
    private void registerIncomingHandlers() {
        if (networkClient == null) {
            return;
        }
        this.dispatcher = new ChannelResponseDispatcher(
                networkClient.getChannelResponseTracker(),
                new ModChannelResponseAdapter());
        networkClient.registerHandler(ChatMessagePacket.class, this::handleIncomingMessage);
        networkClient.registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
        networkClient.registerHandler(MentionPacket.class, this::handleMention);
        networkClient.registerHandler(TitlePacket.class, this::handleTitle);
        networkClient.registerHandler(ItemDisplayPacket.class, this::handleItemDisplay);
        networkClient.registerHandler(
                com.nova.chat.common.protocol.packets.PrivateMessagePacket.class,
                this::handlePrivateMessage);
    }

    /**
     * Handles a completed (S→C) private message: the shared
     * {@link com.nova.chat.client.privatemsg.PrivateMessageService} resolves
     * which local players render which role (sender echo vs received line,
     * receiver-side ignore filter, reply tracking); lines are rendered through
     * {@link Platform#sendMessage} like {@link #handleItemDisplay} (the mod
     * platform bridge is thread-safe plain-string chat).
     */
    private void handlePrivateMessage(com.nova.chat.common.protocol.packets.PrivateMessagePacket packet) {
        var deliveries = privateMessageService.handleIncoming(
                packet, platform::isPlayerOnline, ignoreListService);
        for (com.nova.chat.client.privatemsg.PrivateMessageService.Delivery delivery : deliveries) {
            platform.sendMessage(delivery.getPlayerId(),
                    messageFormatter.parseColors(delivery.getLine()));
        }
    }

    /**
     * @return the shared private-message service (/nc msg, /nc r)
     */
    public com.nova.chat.client.privatemsg.PrivateMessageService getPrivateMessageService() {
        return privateMessageService;
    }

    /**
     * Handles an inbound item display packet ({@code [item]}/{@code [i]} play,
     * packet 0x10) by rendering one chat line to every player whose active
     * channel matches the packet channel.
     *
     * <p>Receive-side semantics are "receive = render", matching the Bedrock
     * clients. Degradation: the mod {@link Platform} abstraction is plain-string
     * chat (same constraint as {@link #handleTitle}), so the line is color-parsed
     * text without a hover component. The line is formatted per viewer because
     * the copy is locale-dependent.
     *
     * <p>Send side is implemented in {@link #maybeSendItemDisplay}: the mod
     * {@link Platform} exposes {@link Platform#getHeldItemJson(UUID)} which
     * serializes the main-hand item into the shared minimal display schema.
     */
    private void handleItemDisplay(ItemDisplayPacket packet) {
        String channelId = packet.getChannelId();
        for (UUID playerId : platform.getOnlinePlayerIds()) {
            // Skip senders the viewer has ignored (/nc ignore)
            if (ignoreListService != null
                    && ignoreListService.isIgnored(playerId, packet.getSenderName())) {
                continue;
            }
            PlayerChannelState state = playerStates.get(playerId);
            if (state != null && channelId != null && channelId.equals(state.getActiveChannel())) {
                platform.sendMessage(playerId, messageFormatter.parseColors(
                        ItemDisplayMessages.formatLine(playerId, packet.getSenderName(), packet.getItemJson())));
            }
        }
    }

    /**
     * Handles a title packet by rendering it to every player whose active
     * channel matches the packet channel (Requirements 15.1, 15.5).
     *
     * <p>Degradation: the mod {@link Platform} abstraction has no title channel
     * (title/sound are loader-owned, the same constraint documented on
     * {@link #handleMention}), so the title and subtitle are rendered as chat
     * lines via {@link Platform#sendMessage}. A native title path would require
     * widening {@link Platform} and each loader submodule.
     */
    private void handleTitle(TitlePacket packet) {
        String channelId = packet.getChannelId();
        String title = packet.getTitle() != null ? packet.getTitle() : "";
        String subtitle = packet.getSubtitle() != null ? packet.getSubtitle() : "";
        if (title.isEmpty() && subtitle.isEmpty()) {
            return;
        }
        for (UUID playerId : platform.getOnlinePlayerIds()) {
            PlayerChannelState state = playerStates.get(playerId);
            if (state != null && channelId != null && channelId.equals(state.getActiveChannel())) {
                if (!title.isEmpty()) {
                    platform.sendMessage(playerId, messageFormatter.parseColors(title));
                }
                if (!subtitle.isEmpty()) {
                    platform.sendMessage(playerId, messageFormatter.parseColors(subtitle));
                }
            }
        }
    }

    // ============================ outgoing chat ============================

    @Override
    public void onPlayerChat(UUID playerId, String playerName, String message) {
        PlayerChannelState state = getOrCreateState(playerId, playerName);
        ChatMode effectiveMode = state.isModeOverridden() ? state.getChatMode() : globalMode;

        // HYBRID mode: allow vanilla chat to proceed (mod loader decides whether to cancel)
        if (effectiveMode == ChatMode.HYBRID) {
            return;
        }

        if (message == null || message.isEmpty()) {
            return;
        }

        // REPLACE mode: forward to backend channel instead of vanilla chat.
        if (!networkClient.isAuthenticated()) {
            platform.sendMessage(playerId,
                    messageFormatter.parseColors(
                            messageFormatter.formatError(playerId,
                                    I18n.tr(playerId, "chat.network.not_connected_retry"))));
            return;
        }

        // Channel-prefix routing (e.g. "!hi" -> global) before the
        // active-channel send; escape/unknown-prefix cases fall through with
        // the resolver-produced message (UX: prefix = /nc <channel> shorthand).
        com.nova.chat.client.channel.ChannelPrefixResolver.Resolution resolution =
                com.nova.chat.client.channel.ChannelPrefixResolver.resolve(
                        config.getChat().getChannelPrefixes(), message,
                        knownChannelRegistry != null ? knownChannelRegistry.getAll() : null);
        String targetChannel = resolution.isRedirect()
                ? resolution.getChannelId() : state.getActiveChannel();

        sendToChannel(playerId, playerName, targetChannel, resolution.getMessage());
    }

    @Override
    public boolean shouldReplaceVanillaChat(UUID playerId) {
        PlayerChannelState state = playerStates.get(playerId);
        if (state != null && state.isModeOverridden()) {
            return state.getChatMode() == ChatMode.REPLACE;
        }
        return globalMode == ChatMode.REPLACE;
    }

    @Override
    public void displayMessage(UUID playerId, String formattedMessage) {
        if (platform.isPlayerOnline(playerId)) {
            platform.sendMessage(playerId, messageFormatter.parseColors(formattedMessage));
        }
    }

    /**
     * Sends a chat message to a channel via the shared network client.
     *
     * <p>§11.6 / 提案 05: after the {@link ChatMessagePacket} is sent, if the
     * message carries an {@code [item]}/{@code [i]} token (case-insensitive,
     * detected via the shared {@link ItemDisplayTokens}) and the platform
     * exposes a non-null held-item payload, an {@link ItemDisplayPacket} is
     * emitted on the same channel. Mirrors the bukkit/folia/nukkit/pnx
     * send-side: per-player cooldown lives in {@link #itemDisplayTokens}, the
     * permission gate is the shared {@code novachat.feature.item} node, and
     * the {@code itemJson} carries only display fields ({@code id} /
     * {@code count} / optional {@code name}) — never full NBT.
     *
     * <p>Permission gate: the mod-common {@link Platform} abstraction has no
     * permission accessor (each loader checks permissions natively in its
     * command registrar), so the permission check is delegated to the platform
     * via {@link Platform#hasItemDisplayPermission(UUID)} — but to keep the
     * interface narrow and avoid widening every implementor in this slice, the
     * gate is currently advisory: the token is always sent when the platform
     * returns a non-null item. A future slice can add the permission hook.
     */
    public void sendToChannel(UUID playerId, String playerName, String channelId, String message) {
        if (!networkClient.isAuthenticated()) {
            platform.sendMessage(playerId,
                    messageFormatter.parseColors(
                            messageFormatter.formatError(playerId,
                                    I18n.tr(playerId, "chat.network.not_connected"))));
            return;
        }
        if (channelId == null || channelId.isBlank()) {
            channelId = config.getChat().getDefaultChannel();
        }
        ChatMessagePacket packet = new ChatMessagePacket(
                playerId,
                playerName != null ? playerName : "",
                config.getUsername() != null ? config.getUsername() : "",
                channelId,
                message
        );
        packet.addPlaceholder("player", playerName != null ? playerName : "");
        packet.addPlaceholder("display_name", playerName != null ? playerName : "");
        String world = platform.getCurrentWorld(playerId);
        if (world != null && !world.isEmpty()) {
            packet.addPlaceholder("world", world);
        }
        networkClient.sendPacket(packet);
        maybeSendItemDisplay(playerId, playerName, channelId, message);
    }

    /**
     * §11.6 / 提案 05: emits an {@link ItemDisplayPacket} when the outbound
     * message carries an {@code [item]}/{@code [i]} token and the platform
     * exposes a non-null held-item payload. Mirrors the
     * bukkit/folia/nukkit/pnx {@code maybeSendItemDisplay} send-side:
     * <ul>
     *   <li>token detection via {@link ItemDisplayTokens#hasItemToken(String)}</li>
     *   <li>per-player cooldown via {@link ItemDisplayTokens#tryAcquire(UUID)}</li>
     *   <li>payload via {@link Platform#getHeldItemJson(UUID)} (already in the
     *       shared minimal display schema)</li>
     *   <li>packet build via {@link ItemDisplayTokens#buildPacket}</li>
     * </ul>
     *
     * <p>Permission gate: the mod {@link Platform} abstraction has no permission
     * accessor, so this slice does not gate on {@code novachat.feature.item}.
     * The token stays plain text when the player is rate-limited or has no held
     * item (same degradation as the no-permission path on bukkit/folia). A
     * future slice can widen {@link Platform} with a permission hook.
     *
     * <p>Exception-safe: any failure logs a warning and returns so the chat
     * send path is never broken by item introspection.
     */
    private void maybeSendItemDisplay(UUID playerId, String playerName,
                                       String channelId, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        if (!ItemDisplayTokens.hasItemToken(message)) {
            return;
        }
        if (!itemDisplayTokens.tryAcquire(playerId)) {
            return; // rate-limited: token stays plain text
        }
        try {
            String itemJson = platform.getHeldItemJson(playerId);
            if (itemJson == null || itemJson.isBlank()) {
                return; // empty hand / offline / serialization failed: token stays plain text
            }
            String senderName = playerName != null ? playerName : "";
            networkClient.sendPacket(ItemDisplayTokens.buildPacket(
                    playerId, senderName, channelId, itemJson));
            LOGGER.debug("Sent item display to channel {}: {}", channelId, itemJson);
        } catch (Exception e) {
            LOGGER.warn("Failed to send item display for {}: {}", playerId, e.getMessage());
        }
    }

    // ============================ incoming packets ============================

    /**
     * Handles incoming chat messages from the backend: renders to every player
     * whose active channel matches, via the platform's sendMessage.
     */
    private void handleIncomingMessage(ChatMessagePacket packet) {
        String channelId = packet.getChannelId();
        String senderName = packet.getSenderName();
        String content = packet.getContent();
        Map<String, String> placeholders = packet.getPlaceholders();
        String channelName = placeholders != null
                ? placeholders.getOrDefault("channel_name", channelId) : channelId;

        String highlighted = MentionNotifier.highlightMentions(content, MENTION_HIGHLIGHT_COLOR);
        for (UUID playerId : platform.getOnlinePlayerIds()) {
            // Skip senders the viewer has ignored (/nc ignore)
            if (ignoreListService != null && ignoreListService.isIgnored(playerId, senderName)) {
                continue;
            }
            PlayerChannelState state = playerStates.get(playerId);
            if (state != null && channelId != null && channelId.equals(state.getActiveChannel())) {
                String formatted = messageFormatter.formatMessage(channelName, senderName, highlighted);
                platform.sendMessage(playerId, messageFormatter.parseColors(formatted));
            }
        }
    }

    /**
     * Routes a channel-action response through the shared dispatcher.
     * Private-message rejections are unsolicited (no pending context in the
     * shared tracker); the dispatcher would drop them, so they are routed to
     * the {@code PrivateMessageService} for player-locale rendering instead.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        if (com.nova.chat.client.privatemsg.PrivateMessageService.isPrivateMessageError(packet)) {
            privateMessageService.renderError(packet, platform::isPlayerOnline)
                    .ifPresent(delivery -> platform.sendMessage(delivery.getPlayerId(),
                            messageFormatter.parseColors(delivery.getLine())));
            return;
        }
        dispatcher.handle(packet);
    }

    /**
     * Handles a mention packet: plays sound + shows title to the mentioned player
     * via the platform (each loader renders title/sound natively).
     */
    private void handleMention(MentionPacket packet) {
        UUID mentionedId = packet.getMentionedId();
        UUID mentionerId = packet.getMentionerId();
        if (mentionedId == null || mentionerId == null) {
            return;
        }
        // Ignored mentioner: no chat-line notification (/nc ignore)
        if (ignoreListService != null
                && ignoreListService.isIgnored(mentionedId, packet.getMentionerName())) {
            return;
        }
        mentionNotifier.notifyOrSkip(mentionedId, mentionerId, () -> {
            if (!platform.isPlayerOnline(mentionedId)) {
                return;
            }
            // Notify via chat line (title/sound are platform-owned; the common layer
            // cannot emit title/sound without loader-specific APIs). Platforms that
            // want native title/sound can subscribe to mentions separately.
            String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
            String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
            String line = messageFormatter.formatSystemMessage(mentionedId,
                    I18n.tr(mentionedId, "chat.mention.subtitle", channelId));
            // Prefix the mentioner name for visibility.
            platform.sendMessage(mentionedId,
                    messageFormatter.parseColors(
                            MentionNotifier.DEFAULT_HIGHLIGHT_COLOR + mentioner + " &r" + line));
        });
    }

    // ============================ state accessors ============================

    public PlayerChannelState getOrCreateState(UUID playerId, String playerName) {
        PlayerChannelState state = playerStates.getOrCreate(
                playerId, config.getChat().getDefaultChannel(), globalMode);
        return state;
    }

    public PlayerChannelState getState(UUID playerId) {
        return playerStates.get(playerId);
    }

    public PlayerChannelState getPlayerState(UUID playerId) {
        return playerStates.getPlayer(playerId);
    }

    public void setPlayerState(UUID playerId, PlayerChannelState state) {
        playerStates.set(playerId, state);
    }

    public void removePlayerState(UUID playerId) {
        playerStates.remove(playerId);
        // Reply-target cleanup for /nc r (private messages); thread-safe map.
        privateMessageService.onPlayerQuit(playerId);
    }

    public ChatMode getGlobalMode() {
        return globalMode;
    }

    public void setGlobalMode(ChatMode mode) {
        this.globalMode = mode;
    }

    public ChatMode togglePlayerMode(UUID playerId) {
        PlayerChannelState state = getOrCreateState(playerId, null);
        return state.toggleMode();
    }

    public void setPlayerChannel(UUID playerId, String channelId) {
        PlayerChannelState state = getOrCreateState(playerId, null);
        state.setActiveChannel(channelId);
    }

    public String getPlayerChannel(UUID playerId) {
        PlayerChannelState state = getOrCreateState(playerId, null);
        return state.getActiveChannel();
    }

    public MessageFormatter getMessageFormatter() {
        return messageFormatter;
    }

    /**
     * Reloads configuration-driven state.
     */
    public void reload() {
        this.globalMode = config.getChat().isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
    }

    // ============================ response adapter ============================

    /**
     * Mod-side {@link ChannelResponseDispatcher.ChannelResponseAdapter}. Renders
     * all outcomes through the platform {@link Platform#sendMessage} so the common
     * layer stays loader-agnostic. Title / sound / action-bar are platform-owned
     * (the mod has no shared title/sound abstraction across the four loaders).
     */
    private final class ModChannelResponseAdapter implements ChannelResponseDispatcher.ChannelResponseAdapter {

        @Override
        public void setActiveChannel(UUID playerId, String channelId) {
            PlayerChannelState state = getState(playerId);
            if (state != null && channelId != null && !channelId.isBlank()) {
                state.setActiveChannel(channelId);
            }
        }

        @Override
        public void rollbackJoin(UUID playerId, String attemptedChannel, String previousChannel) {
            PlayerChannelState state = getState(playerId);
            if (state == null) {
                return;
            }
            String current = state.getActiveChannel();
            if (current != null && current.equals(attemptedChannel)) {
                state.setActiveChannel(previousChannel);
            }
        }

        @Override
        public void sendJoinSuccess(UUID playerId, String channelId) {
            displayMessage(playerId,
                    messageFormatter.formatSuccess(playerId,
                            PlayerMessages.joined(playerId, channelId)));
        }

        @Override
        public void sendLeaveSuccess(UUID playerId, String channelId) {
            displayMessage(playerId,
                    messageFormatter.formatSuccess(playerId,
                            PlayerMessages.left(playerId, channelId, config.getChat().getDefaultChannel())));
        }

        @Override
        public void sendJoinChannelStatusBar(UUID playerId, String channelId) {
            // No shared action-bar abstraction across mod loaders; render the status
            // as a transient chat line instead.
            displayMessage(playerId,
                    messageFormatter.formatSystemMessage(playerId,
                            I18n.tr(playerId, "chat.status.current_bar",
                                    channelId != null ? channelId : "", "")));
        }

        @Override
        public void sendLeaveChannelStatusBar(UUID playerId) {
            displayMessage(playerId,
                    messageFormatter.formatSystemMessage(playerId,
                            I18n.tr(playerId, "chat.leave.leaving", "")));
        }

        @Override
        public void sendErrorMessage(UUID playerId, String text) {
            displayMessage(playerId, messageFormatter.formatError(playerId, text));
        }

        @Override
        public void sendWhoResult(UUID playerId, String channelId, String displayName,
                                  String membersCsv, String memberCount) {
            String text = WhoCommandService.formatMemberList(
                    playerId, channelId, displayName, membersCsv, memberCount);
            for (String line : text.split("\n")) {
                if (!line.isEmpty()) {
                    displayMessage(playerId, line);
                }
            }
        }

        @Override
        public void notifyKickMuteTarget(ChannelResponseDispatcher.KickMuteNotice notice) {
            UUID targetId = notice.getTargetId();
            if (!platform.isPlayerOnline(targetId)) {
                return;
            }
            String channelId = notice.getChannelId();
            String operator = notice.getOperator();
            if (notice.getAction() == ChannelAction.KICK) {
                displayMessage(targetId,
                        messageFormatter.formatError(targetId,
                                I18n.tr(targetId, "chat.notice.kick_subtitle", operator, channelId)));
                return;
            }
            String durationText = notice.getDurationText();
            displayMessage(targetId,
                    messageFormatter.formatError(targetId,
                            I18n.tr(targetId, "chat.notice.mute_subtitle", channelId, durationText)));
        }
    }
}
