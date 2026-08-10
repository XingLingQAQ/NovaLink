package com.nova.chat.mod.chat;

import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.command.WelcomeMessageService;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.client.state.PlayerStateStore;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
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

    /** Player chat states indexed by UUID (shared client-core store). */
    private final PlayerStateStore playerStates = new PlayerStateStore();

    /** Shared response dispatcher; created in {@link #registerIncomingHandlers()}. */
    private ChannelResponseDispatcher dispatcher;

    private ChatMode globalMode;

    public ChatInterceptor(Platform platform, NetworkClient networkClient,
                           ModConfig config, MessageFormatter messageFormatter) {
        this.platform = platform;
        this.networkClient = networkClient;
        this.config = config;
        this.messageFormatter = messageFormatter;
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

        sendToChannel(playerId, playerName, state.getActiveChannel(), message);
    }

    @Override
    public void displayMessage(UUID playerId, String formattedMessage) {
        if (platform.isPlayerOnline(playerId)) {
            platform.sendMessage(playerId, messageFormatter.parseColors(formattedMessage));
        }
    }

    /**
     * Sends a chat message to a channel via the shared network client.
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
            PlayerChannelState state = playerStates.get(playerId);
            if (state != null && channelId != null && channelId.equals(state.getActiveChannel())) {
                String formatted = messageFormatter.formatMessage(channelName, senderName, highlighted);
                platform.sendMessage(playerId, messageFormatter.parseColors(formatted));
            }
        }
    }

    /**
     * Routes a channel-action response through the shared dispatcher.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
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
