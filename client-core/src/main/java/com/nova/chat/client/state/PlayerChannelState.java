package com.nova.chat.client.state;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Platform-agnostic per-player channel membership and chat mode state.
 *
 * <p>Tracks the active channel, the set of joined channels, chat mode
 * (including personal override), and an optional current-server hint used by
 * proxies. Cross-thread visibility is provided by {@code volatile} on the
 * scalar fields so platforms that read state from region/async threads
 * (notably Folia) see consistent values; {@link #toggleMode()} is
 * {@code synchronized} so concurrent toggles remain deterministic. The
 * {@code joinedChannels} set is confined to a single owning thread / held
 * under the same lock and exposed only as an unmodifiable view.
 *
 * <p>Requirements: 11.3
 */
public final class PlayerChannelState {

    private final UUID playerId;
    private volatile String activeChannel;
    private final Set<String> joinedChannels;
    private volatile ChatMode chatMode;
    private volatile boolean modeOverridden;
    private volatile boolean forwardingEnabled = true;
    private volatile String currentServer;

    /**
     * Creates state for a player, joining the default channel as active.
     *
     * @param playerId       player's UUID
     * @param defaultChannel initial active / joined channel (must be non-blank)
     * @param defaultMode    initial chat mode
     */
    public PlayerChannelState(UUID playerId, String defaultChannel, ChatMode defaultMode) {
        this(playerId, defaultMode);
        if (defaultChannel == null || defaultChannel.isBlank()) {
            throw new IllegalArgumentException("defaultChannel must not be blank");
        }
        this.activeChannel = defaultChannel;
        this.joinedChannels.add(defaultChannel);
    }

    private PlayerChannelState(UUID playerId, ChatMode defaultMode) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(defaultMode, "defaultMode");
        this.joinedChannels = new LinkedHashSet<>();
        this.chatMode = defaultMode;
        this.modeOverridden = false;
        this.currentServer = null;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getActiveChannel() {
        return activeChannel;
    }

    /**
     * Sets the active channel. The channel is also added to the joined set.
     *
     * @param activeChannel non-blank channel id
     */
    public void setActiveChannel(String activeChannel) {
        if (activeChannel == null || activeChannel.isBlank()) {
            throw new IllegalArgumentException("activeChannel must not be blank");
        }
        this.activeChannel = activeChannel;
        this.joinedChannels.add(activeChannel);
    }

    /**
     * Sets the active channel only when it is already present in the joined set.
     * Unlike {@link #setActiveChannel(String)}, this method never creates local
     * membership and is therefore suitable for post-LEAVE fallback selection.
     *
     * @param channelId non-blank channel id
     * @return {@code true} if the channel was joined and became active
     */
    public boolean setActiveChannelIfJoined(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId must not be blank");
        }
        if (!joinedChannels.contains(channelId)) {
            return false;
        }
        this.activeChannel = channelId;
        return true;
    }

    /**
     * Unmodifiable view of channels this player has joined.
     * Iteration order is insertion order.
     */
    public Set<String> getJoinedChannels() {
        return Collections.unmodifiableSet(joinedChannels);
    }

    /**
     * Marks a channel as joined without changing the active channel.
     *
     * @return {@code true} if the channel was not already joined
     */
    public boolean joinChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId must not be blank");
        }
        return joinedChannels.add(channelId);
    }

    /**
     * Leaves a channel. If the left channel was active, the active channel falls
     * back to the first remaining joined channel, or {@code null} if none remain.
     *
     * @return {@code true} if the player was a member of the channel
     */
    public boolean leaveChannel(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId must not be blank");
        }
        boolean removed = joinedChannels.remove(channelId);
        if (removed && channelId.equals(activeChannel)) {
            activeChannel = joinedChannels.isEmpty()
                    ? null
                    : joinedChannels.iterator().next();
        }
        return removed;
    }

    public boolean isJoined(String channelId) {
        return channelId != null && joinedChannels.contains(channelId);
    }

    public int getJoinedChannelCount() {
        return joinedChannels.size();
    }

    public ChatMode getChatMode() {
        return chatMode;
    }

    public void setChatMode(ChatMode chatMode) {
        this.chatMode = Objects.requireNonNull(chatMode, "chatMode");
    }

    public boolean isModeOverridden() {
        return modeOverridden;
    }

    public void setModeOverridden(boolean modeOverridden) {
        this.modeOverridden = modeOverridden;
    }

    public boolean isForwardingEnabled() {
        return forwardingEnabled;
    }

    public void setForwardingEnabled(boolean forwardingEnabled) {
        this.forwardingEnabled = forwardingEnabled;
    }

    public String getCurrentServer() {
        return currentServer;
    }

    public void setCurrentServer(String currentServer) {
        this.currentServer = currentServer;
    }

    /**
     * Toggles chat mode between {@link ChatMode#HYBRID} and {@link ChatMode#REPLACE}
     * and marks the mode as personally overridden.
     *
     * @return the new chat mode
     */
    public ChatMode toggleMode() {
        synchronized (this) {
            this.modeOverridden = true;
            this.chatMode = this.chatMode.toggled();
            return this.chatMode;
        }
    }

    /**
     * Resets personal mode override back to the given default.
     */
    public void resetMode(ChatMode defaultMode) {
        this.chatMode = Objects.requireNonNull(defaultMode, "defaultMode");
        this.modeOverridden = false;
    }

    /**
     * Creates an independent copy of this state. The joined-channels set is
     * duplicated so mutations to the copy do not affect the original.
     *
     * @return a new {@code PlayerChannelState} with the same field values
     */
    public PlayerChannelState copy() {
        PlayerChannelState copy = new PlayerChannelState(playerId, chatMode);
        copy.activeChannel = this.activeChannel;
        copy.joinedChannels.addAll(this.joinedChannels);
        copy.modeOverridden = this.modeOverridden;
        copy.forwardingEnabled = this.forwardingEnabled;
        copy.currentServer = this.currentServer;
        return copy;
    }

    @Override
    public String toString() {
        return "PlayerChannelState{"
                + "playerId=" + playerId
                + ", activeChannel='" + activeChannel + '\''
                + ", joinedChannels=" + joinedChannels
                + ", chatMode=" + chatMode
                + ", modeOverridden=" + modeOverridden
                + ", forwardingEnabled=" + forwardingEnabled
                + ", currentServer='" + currentServer + '\''
                + '}';
    }
}
