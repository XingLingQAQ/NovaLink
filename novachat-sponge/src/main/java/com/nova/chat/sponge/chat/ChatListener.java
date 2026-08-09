package com.nova.chat.sponge.chat;

import com.nova.chat.client.command.PlayerMessages;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.i18n.LocaleResolver;
import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.common.chat.MentionNotifier;
import com.nova.chat.sponge.NovaChatSponge;
import com.nova.chat.sponge.config.NovaChatConfig;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.message.PlayerChatEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

/**
 * Intercepts player chat events and forwards messages to the NovaLink backend.
 * Uses Sponge PlayerChatEvent for chat interception.
 * Handles Sponge permission system.
 * 
 * Requirements: 3.3
 */
public class ChatListener {
    
    private final NovaChatSponge plugin;
    private final NovaChatConfig config;
    private final MentionNotifier mentionNotifier = new MentionNotifier();
    
    /** Player chat states indexed by UUID */
    private final Map<UUID, PlayerChannelState> playerStates = new ConcurrentHashMap<>();

    /** Shared response dispatcher (DUP-3); created in {@link #registerIncomingMessageHandler()}. */
    private ChannelResponseDispatcher dispatcher;

    /**
     * UUIDs of players already shown the first-join welcome line this session
     * (UX-DESIGN §8.1). Sponge 8 does not expose a reliable hasPlayedBefore
     * here, so the welcome is gated by this memory set and cleared on
     * disconnect.
     */
    private final java.util.Set<UUID> welcomedPlayers = ConcurrentHashMap.newKeySet();
    
    /** Global chat mode from configuration */
    private ChatMode globalMode;
    
    /**
     * Creates a new ChatListener.
     *
     * @param plugin the plugin instance
     */
    public ChatListener(NovaChatSponge plugin) {
        this.plugin = plugin;
        this.config = plugin.getNovaChatConfig();
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        
        // Register handler for incoming chat messages from backend
        registerIncomingMessageHandler();
    }
    
    /**
     * Registers the handler for incoming chat messages from the backend.
     */
    private void registerIncomingMessageHandler() {
        this.dispatcher = new ChannelResponseDispatcher(
                plugin.getNetworkClient().getChannelResponseTracker(),
                new SpongeChannelResponseAdapter());
        plugin.getNetworkClient().registerHandler(ChatMessagePacket.class, this::handleIncomingMessage);
        plugin.getNetworkClient().registerHandler(ChannelActionResponsePacket.class, this::handleChannelActionResponse);
        plugin.getNetworkClient().registerHandler(MentionPacket.class, this::handleMention);
    }

    /** Legacy color prefix applied to @name mentions when rendering chat (UX-DESIGN §4.2). */
    static final String MENTION_HIGHLIGHT_COLOR = MentionNotifier.DEFAULT_HIGHLIGHT_COLOR;

    /**
     * Handles channel action responses from the backend by delegating the
     * shared "consume pending → route success/failure" skeleton to the shared
     * {@link ChannelResponseDispatcher} (DUP-3). The platform adapter owns the
     * plugin-executor hops, rendering and the §7 action-bar flash.
     */
    private void handleChannelActionResponse(ChannelActionResponsePacket packet) {
        plugin.debug("Received channel action response: " + packet);
        dispatcher.handle(packet);
    }

    /**
     * Sponge-specific {@link ChannelResponseDispatcher.ChannelResponseAdapter}.
     * Player lookup / message sending hop to the plugin executor; the KICK/MUTE
     * notice uses a title plus an action-bar reinforcement.
     */
    private final class SpongeChannelResponseAdapter implements ChannelResponseDispatcher.ChannelResponseAdapter {

        @Override
        public void setActiveChannel(UUID playerId, String channelId) {
            Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
                PlayerChannelState state = getState(playerId);
                if (state != null) {
                    state.setActiveChannel(channelId);
                }
            });
        }

        @Override
        public void rollbackJoin(UUID playerId, String attemptedChannel, String previousChannel) {
            Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
                PlayerChannelState state = getState(playerId);
                if (state == null) {
                    return;
                }
                String current = state.getActiveChannel();
                if (current != null && current.equals(attemptedChannel)) {
                    state.setActiveChannel(previousChannel);
                }
            });
        }

        @Override
        public void sendJoinSuccess(UUID playerId, String channelId) {
            Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
                Optional<ServerPlayer> opt = Sponge.server().player(playerId);
                if (opt.isEmpty()) {
                    return;
                }
                opt.get().sendMessage(plugin.getMessageFormatter().formatSuccess(
                        PlayerMessages.joined(playerId, channelId)));
            });
        }

        @Override
        public void sendLeaveSuccess(UUID playerId, String channelId) {
            Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
                Optional<ServerPlayer> opt = Sponge.server().player(playerId);
                if (opt.isEmpty()) {
                    return;
                }
                opt.get().sendMessage(plugin.getMessageFormatter().formatSuccess(
                        PlayerMessages.left(playerId, channelId, config.getDefaultChannel())));
            });
        }

        @Override
        public void sendJoinChannelStatusBar(UUID playerId, String channelId) {
            if (channelId == null || channelId.isEmpty()) {
                return;
            }
            Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
                Optional<ServerPlayer> opt = Sponge.server().player(playerId);
                if (opt.isEmpty()) {
                    return;
                }
                sendChannelStatusBar(opt.get(), channelId);
            });
        }

        @Override
        public void sendLeaveChannelStatusBar(UUID playerId) {
            Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
                Optional<ServerPlayer> opt = Sponge.server().player(playerId);
                if (opt.isEmpty()) {
                    return;
                }
                PlayerChannelState state = getState(playerId);
                String current = (state != null) ? state.getActiveChannel() : null;
                sendChannelStatusBar(opt.get(), current);
            });
        }

        @Override
        public void sendErrorMessage(UUID playerId, String text) {
            Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
                Optional<ServerPlayer> opt = Sponge.server().player(playerId);
                if (opt.isEmpty()) {
                    return;
                }
                opt.get().sendMessage(plugin.getMessageFormatter().formatError(text));
            });
        }

        @Override
        public void notifyKickMuteTarget(ChannelResponseDispatcher.KickMuteNotice notice) {
            final String operator = notice.getOperator();
            final String durationText = notice.getDurationText();
            Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
                Optional<ServerPlayer> opt = Sponge.server().player(notice.getTargetId());
                if (opt.isEmpty()) {
                    return; // not on this server
                }
                ServerPlayer target = opt.get();
                UUID targetId = target.uniqueId();
                String channelId = notice.getChannelId();
                Title.Times times = Title.Times.times(
                        Duration.ofMillis(MentionNotifier.DEFAULT_FADE_IN * 50L),
                        Duration.ofMillis(MentionNotifier.DEFAULT_STAY * 50L),
                        Duration.ofMillis(MentionNotifier.DEFAULT_FADE_OUT * 50L));
                if (notice.getAction() == ChannelAction.KICK) {
                    Component title = LegacyComponentSerializer.legacyAmpersand().deserialize(
                            I18n.tr(targetId, "chat.notice.kick_title"));
                    Component subtitle = LegacyComponentSerializer.legacyAmpersand().deserialize(
                            I18n.tr(targetId, "chat.notice.kick_subtitle", operator, channelId));
                    target.showTitle(Title.title(title, subtitle, times));
                    Component actionbar = LegacyComponentSerializer.legacyAmpersand().deserialize(
                            I18n.tr(targetId, "chat.notice.kick_actionbar", operator, channelId));
                    target.sendActionBar(actionbar);
                    return;
                }
                // MUTE
                Component title = LegacyComponentSerializer.legacyAmpersand().deserialize(
                        I18n.tr(targetId, "chat.notice.mute_title"));
                Component subtitle = LegacyComponentSerializer.legacyAmpersand().deserialize(
                        I18n.tr(targetId, "chat.notice.mute_subtitle", channelId, durationText));
                target.showTitle(Title.title(title, subtitle, times));
                Component actionbar = LegacyComponentSerializer.legacyAmpersand().deserialize(
                        I18n.tr(targetId, "chat.notice.mute_actionbar", durationText, channelId));
                target.sendActionBar(actionbar);
            });
        }
    }

    /**
     * Flashes a one-shot action bar with the player's current channel and chat
     * mode after a successful join/leave (UX-DESIGN §7). Vanilla action-bar
     * fade handles the ~3s dismissal; no polling.
     *
     * @param player the recipient
     * @param channelId the channel to display; if null/blank, nothing is sent
     */
    private void sendChannelStatusBar(ServerPlayer player, String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            return;
        }
        PlayerChannelState state = getState(player.uniqueId());
        ChatMode mode = (state != null) ? state.getChatMode() : null;
        // Shared bar template (chat.status.current_bar) carries &-color codes;
        // deserialize via the legacy ampersand serializer so Adventure renders
        // the colors. A null mode degrades to HYBRID, matching prior behavior.
        String bar = PlayerMessages.currentChannelBar(
                player.uniqueId(), channelId, mode != null ? mode : ChatMode.HYBRID);
        player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(bar));
    }

    /**
     * Handles incoming chat messages from the backend.
     *
     * @param packet the chat message packet
     */
    private void handleIncomingMessage(ChatMessagePacket packet) {
        plugin.debug("Received chat message from backend: " + packet);
        
        String channelId = packet.getChannelId();
        String senderName = packet.getSenderName();
        String content = packet.getContent();
        Map<String, String> placeholders = packet.getPlaceholders();
        
        // Get channel display name from placeholders or use channel ID
        String channelName = placeholders.getOrDefault("channel_name", channelId);
        
        // Format and send message to players in this channel
        Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
            for (ServerPlayer player : Sponge.server().onlinePlayers()) {
                // Check if player is in this channel
                PlayerChannelState state = getState(player.uniqueId());
                if (state != null && channelId.equals(state.getActiveChannel())) {
                    Component formattedMessage = plugin.getMessageFormatter().formatChatMessage(
                        player, channelId, channelName, senderName,
                        MentionNotifier.highlightMentions(content, MENTION_HIGHLIGHT_COLOR),
                        placeholders
                    );
                    player.sendMessage(formattedMessage);
                }
            }
        });
    }

    /**
     * Handles a mention notification packet by playing a sound and showing a
     * title to the mentioned player (UX-DESIGN §4.1, Requirements 11.2).
     */
    private void handleMention(MentionPacket packet) {
        UUID mentionedId = packet.getMentionedId();
        UUID mentionerId = packet.getMentionerId();
        if (mentionedId == null || mentionerId == null) {
            return;
        }
        Sponge.server().scheduler().executor(plugin.getContainer()).execute(() -> {
            Optional<ServerPlayer> opt = Sponge.server().player(mentionedId);
            if (opt.isEmpty()) {
                return;
            }
            ServerPlayer player = opt.get();
            mentionNotifier.notifyOrSkip(mentionedId, mentionerId, () -> {
                String mentioner = packet.getMentionerName() != null ? packet.getMentionerName() : "";
                String channelId = packet.getChannelId() != null ? packet.getChannelId() : "";
                Component title = Component.text(mentioner, NamedTextColor.YELLOW);
                Component subtitle = LegacyComponentSerializer.legacyAmpersand().deserialize(
                        I18n.tr(mentionedId, "chat.mention.subtitle", channelId));
                Title.Times times = Title.Times.times(
                        Duration.ofMillis(MentionNotifier.DEFAULT_FADE_IN * 50L),
                        Duration.ofMillis(MentionNotifier.DEFAULT_STAY * 50L),
                        Duration.ofMillis(MentionNotifier.DEFAULT_FADE_OUT * 50L));
                player.showTitle(Title.title(title, subtitle, times));
                playMentionSound(player);
            });
        });
    }

    /**
     * Plays the default mention notification sound to a player. Uses Adventure's
     * keyed-sound API so the exact {@code ENTITY_EXPERIENCE_ORB_PICKUP} sound is
     * resolved at play time.
     */
    private void playMentionSound(ServerPlayer player) {
        try {
            net.kyori.adventure.key.Key key =
                    net.kyori.adventure.key.Key.key("minecraft",
                            MentionNotifier.DEFAULT_SOUND.toLowerCase(java.util.Locale.ROOT));
            Sound sound = Sound.sound(key, Sound.Source.PLAYER, 1.0f, 1.0f);
            player.playSound(sound);
        } catch (Exception e) {
            plugin.debug("Failed to play mention sound: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handles player chat events.
     * In REPLACE mode, cancels the event and forwards to the channel.
     * In HYBRID mode, allows vanilla chat to proceed normally.
     *
     * @param event the chat event
     * @param player the player who sent the message
     */
    @Listener(order = Order.LAST)
    public void onPlayerChat(PlayerChatEvent event, @First ServerPlayer player) {
        UUID playerId = player.uniqueId();
        
        // Get or create player state
        PlayerChannelState state = getOrCreateState(player);
        ChatMode effectiveMode = state.isModeOverridden() ? state.getChatMode() : globalMode;
        
        plugin.debug("Player " + player.name() + " chat event, mode: " + effectiveMode + 
                    ", channel: " + state.getActiveChannel());
        
        // In HYBRID mode, let vanilla chat proceed
        if (effectiveMode == ChatMode.HYBRID) {
            plugin.debug("HYBRID mode: allowing vanilla chat for " + player.name());
            return;
        }
        
        // In REPLACE mode, cancel vanilla chat and forward to channel
        event.setCancelled(true);
        
        // Check if connected to backend
        if (!plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(formatError(I18n.tr(playerId, "chat.network.not_connected_retry")));
            return;
        }
        
        // Get message content
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        
        // Forward message to backend
        sendToChannel(player, state.getActiveChannel(), message);
    }
    
    /**
     * Handles player join events to initialize chat state.
     *
     * @param event the join event
     */
    @Listener
    public void onPlayerJoin(ServerSideConnectionEvent.Join event) {
        ServerPlayer player = event.player();
        getOrCreateState(player);

        // Capture the player's Minecraft client locale so per-player i18n
        // resolves in their language. Sponge API 8 has no locale-change event,
        // so the locale is captured once on join (a re-join re-captures it).
        // player.locale() comes from LocaleSource; LocaleResolver.parse accepts
        // null/blank and falls back to the configured default locale.
        I18n.registerPlayerLocale(player.uniqueId(), LocaleResolver.parse(player.locale().toString()));

        plugin.debug("Initialized chat state for " + player.name());

        // UX-DESIGN §8.1: push the shared welcome line once per session to
        // first-time players. Single non-intrusive chat line, no title.
        if (welcomedPlayers.add(player.uniqueId())) {
            player.sendMessage(plugin.getMessageFormatter().formatMessage(
                    com.nova.chat.client.command.WelcomeMessageService.getWelcomeLine()));
            plugin.debug("Sent first-join welcome to " + player.name());
        }
    }

    /**
     * Handles player disconnect events to clean up chat state.
     *
     * @param event the disconnect event
     */
    @Listener
    public void onPlayerDisconnect(ServerSideConnectionEvent.Disconnect event) {
        ServerPlayer player = event.player();
        playerStates.remove(player.uniqueId());
        welcomedPlayers.remove(player.uniqueId());
        // Clear the per-player locale registration so a UUID reuse never
        // inherits a stale locale.
        I18n.registerPlayerLocale(player.uniqueId(), null);
        plugin.debug("Removed chat state for " + player.name());
    }
    
    /**
     * Sends a message to a specific channel.
     *
     * @param player the sending player
     * @param channelId the target channel ID
     * @param message the message content
     */
    public void sendToChannel(ServerPlayer player, String channelId, String message) {
        if (!plugin.getNetworkClient().isAuthenticated()) {
            player.sendMessage(formatError(I18n.tr(player.uniqueId(), "chat.network.not_connected")));
            return;
        }
        
        ChatMessagePacket packet = new ChatMessagePacket(
            player.uniqueId(),
            player.name(),
            config.getUsername(), // Client ID
            channelId,
            message
        );
        
        // Add basic placeholders
        packet.addPlaceholder("player", player.name());
        packet.addPlaceholder("display_name", PlainTextComponentSerializer.plainText().serialize(player.displayName().get()));
        packet.addPlaceholder("world", player.world().key().value());
        
        plugin.getNetworkClient().sendPacket(packet);
        plugin.debug("Sent message to channel " + channelId + ": " + message);
    }
    
    /**
     * Gets or creates a player's chat state.
     *
     * @param player the player
     * @return the player's chat state
     */
    public PlayerChannelState getOrCreateState(ServerPlayer player) {
        return playerStates.computeIfAbsent(player.uniqueId(),
            uuid -> new PlayerChannelState(uuid, config.getDefaultChannel(), globalMode));
    }

    /**
     * Gets a player's chat state if it exists.
     *
     * @param playerId the player's UUID
     * @return the player's chat state, or null if not found
     */
    public PlayerChannelState getState(UUID playerId) {
        return playerStates.get(playerId);
    }

    /**
     * Gets a player's chat state if it exists.
     * Alias for getState() for command compatibility.
     *
     * @param playerId the player's UUID
     * @return the player's chat state, or null if not found
     */
    public PlayerChannelState getPlayerState(UUID playerId) {
        return playerStates.get(playerId);
    }

    /**
     * Sets a player's chat state.
     *
     * @param playerId the player's UUID
     * @param state the chat state to set
     */
    public void setPlayerState(UUID playerId, PlayerChannelState state) {
        playerStates.put(playerId, state);
    }
    
    /**
     * Sets the global chat mode.
     *
     * @param mode the new global mode
     */
    public void setGlobalMode(ChatMode mode) {
        this.globalMode = mode;
    }
    
    /**
     * Gets the global chat mode.
     *
     * @return the global chat mode
     */
    public ChatMode getGlobalMode() {
        return globalMode;
    }
    
    /**
     * Toggles a player's chat mode.
     *
     * @param player the player
     * @return the new chat mode
     */
    public ChatMode togglePlayerMode(ServerPlayer player) {
        PlayerChannelState state = getOrCreateState(player);
        return state.toggleMode();
    }
    
    /**
     * Sets a player's active channel.
     *
     * @param player the player
     * @param channelId the channel ID
     */
    public void setPlayerChannel(ServerPlayer player, String channelId) {
        PlayerChannelState state = getOrCreateState(player);
        state.setActiveChannel(channelId);
    }

    /**
     * Gets a player's active channel.
     *
     * @param player the player
     * @return the active channel ID
     */
    public String getPlayerChannel(ServerPlayer player) {
        PlayerChannelState state = getOrCreateState(player);
        return state.getActiveChannel();
    }
    
    /**
     * Reloads configuration settings.
     */
    public void reload() {
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
        plugin.debug("ChatListener reloaded, global mode: " + globalMode);
    }
    
    /**
     * Formats an error message with the plugin prefix.
     *
     * @param message the error message
     * @return the formatted message component
     */
    private Component formatError(String message) {
        return plugin.getMessageFormatter().formatError(message);
    }
}
