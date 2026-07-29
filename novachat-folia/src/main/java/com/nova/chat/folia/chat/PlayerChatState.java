package com.nova.chat.folia.chat;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;

import java.util.UUID;

/**
 * Folia-specific concurrent wrapper around shared {@link PlayerChannelState}.
 *
 * <p>Delegates channel/mode semantics to a single {@link PlayerChannelState}
 * instance (the shared core type used by
 * {@link com.nova.chat.client.command.ChannelCommandService}) so the command
 * path and the chat-interceptor path observe the exact same state. Folia runs
 * chat handling across region threads, so reads are routed through
 * {@code volatile}-backed local mirrors of the active channel / chat mode and
 * {@link #toggleMode()} stays {@code synchronized}.
 *
 * <p>The shared {@link PlayerChannelState} is the source of truth for joined
 * membership; {@link #getChannelState()} exposes it so command handlers can
 * hand it straight to the shared service.
 *
 * <p>Requirements: 2.3
 */
public class PlayerChatState {

    /** Underlying shared state; single source of truth for membership. */
    private final PlayerChannelState channelState;

    /** Player UUID */
    private final UUID playerId;

    /** Mirror of the active channel for volatile reads across region threads. */
    private volatile String activeChannel;

    /** Mirror of the chat mode for volatile reads across region threads. */
    private volatile ChatMode chatMode;

    /** Mirror of the personal-override flag for volatile reads. */
    private volatile boolean modeOverridden;

    /**
     * Creates a new player chat state.
     *
     * @param playerId the player's UUID
     * @param defaultChannel the default channel to join
     * @param defaultMode the default chat mode
     */
    public PlayerChatState(UUID playerId, String defaultChannel, ChatMode defaultMode) {
        // Validate using shared core rules (non-null playerId/mode, non-blank channel)
        // and seed the single source of truth.
        this.channelState = new PlayerChannelState(playerId, defaultChannel, defaultMode);
        this.playerId = channelState.getPlayerId();
        this.activeChannel = channelState.getActiveChannel();
        this.chatMode = channelState.getChatMode();
        this.modeOverridden = channelState.isModeOverridden();
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getActiveChannel() {
        return activeChannel;
    }

    public void setActiveChannel(String activeChannel) {
        if (activeChannel == null || activeChannel.isBlank()) {
            throw new IllegalArgumentException("activeChannel must not be blank");
        }
        // Keep the shared membership set in sync with the local active-channel mirror.
        channelState.setActiveChannel(activeChannel);
        this.activeChannel = activeChannel;
    }

    public ChatMode getChatMode() {
        return chatMode;
    }

    public void setChatMode(ChatMode chatMode) {
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode");
        }
        channelState.setChatMode(chatMode);
        this.chatMode = chatMode;
    }

    public boolean isModeOverridden() {
        return modeOverridden;
    }

    public void setModeOverridden(boolean modeOverridden) {
        channelState.setModeOverridden(modeOverridden);
        this.modeOverridden = modeOverridden;
    }

    /**
     * Toggles the chat mode between HYBRID and REPLACE.
     *
     * @return the new chat mode after toggling
     */
    public synchronized ChatMode toggleMode() {
        // Delegate the toggle to the shared state so membership/mode stay coherent,
        // then refresh the local mirrors under the same lock.
        ChatMode newMode = channelState.toggleMode();
        this.chatMode = newMode;
        this.modeOverridden = channelState.isModeOverridden();
        return newMode;
    }

    /**
     * Leaves a channel on the shared membership state. When the left channel
     * was the active one, the active-channel mirror is refreshed from the
     * shared fallback (next joined channel, or {@code null}).
     *
     * @param channelId the channel to leave
     * @return {@code true} if the player was a member of the channel
     */
    public boolean leaveChannel(String channelId) {
        boolean removed = channelState.leaveChannel(channelId);
        if (removed) {
            this.activeChannel = channelState.getActiveChannel();
        }
        return removed;
    }

    /**
     * Returns the underlying shared {@link PlayerChannelState} so command
     * handlers can pass it to
     * {@link com.nova.chat.client.command.ChannelCommandService}.
     *
     * @return the shared channel state (single source of truth for membership)
     */
    public PlayerChannelState getChannelState() {
        return channelState;
    }

    /**
     * Creates a copy of this state.
     *
     * @return a new PlayerChatState with the same values
     */
    public PlayerChatState copy() {
        PlayerChatState copy = new PlayerChatState(playerId, activeChannel, chatMode);
        copy.setModeOverridden(modeOverridden);
        return copy;
    }
}
