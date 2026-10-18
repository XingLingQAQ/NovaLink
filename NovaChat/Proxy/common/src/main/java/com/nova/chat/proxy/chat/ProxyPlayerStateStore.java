package com.nova.chat.proxy.chat;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.client.state.PlayerStateStore;
import com.nova.chat.proxy.config.ProxyConfigView;

import java.util.UUID;

/**
 * The twelve per-player state accessors shared by every NovaChat proxy plugin.
 *
 * <p>Both proxies carried the same {@code ChatListener} state surface verbatim:
 * get-or-create, get, get-alias, set, global-mode get/set, toggle, active-channel
 * get/set, plus config/mode propagation. This class owns them once, backed by the
 * thread-safe shared {@link PlayerStateStore}; the platform shells keep only the
 * event subscription and the proxy {@code Player} → UUID extraction.
 *
 * <p>Thread safety: the store is {@link java.util.concurrent.ConcurrentHashMap}
 * backed and {@link PlayerChannelState}'s scalars are volatile, so the accessors
 * are safe from the proxy event thread and the network thread at once. The global
 * mode is volatile for the same reason.
 */
public final class ProxyPlayerStateStore {

    private final PlayerStateStore states = new PlayerStateStore();

    /** Latest immutable config snapshot; volatile — event thread reads, reload writes. */
    private volatile ProxyConfigView config;

    /** Global chat mode derived from the config snapshot. */
    private volatile ChatMode globalMode;

    /**
     * @param config the initial configuration snapshot
     */
    public ProxyPlayerStateStore(ProxyConfigView config) {
        this.config = config;
        this.globalMode = config.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
    }

    /**
     * Gets or creates the player's state, using the configured default channel
     * and the current global mode for first-time creation.
     *
     * @param playerId the player UUID
     * @return the state; never null
     */
    public PlayerChannelState getOrCreateState(UUID playerId) {
        return states.getOrCreate(playerId, config.getDefaultChannel(), globalMode);
    }

    /**
     * @param playerId the player UUID
     * @return the existing state, or null
     */
    public PlayerChannelState getState(UUID playerId) {
        return states.get(playerId);
    }

    /**
     * Alias for {@link #getState(UUID)} kept for command compatibility.
     *
     * @param playerId the player UUID
     * @return the existing state, or null
     */
    public PlayerChannelState getPlayerState(UUID playerId) {
        return states.getPlayer(playerId);
    }

    /**
     * @param playerId the player UUID
     * @param state the state to store (null is ignored)
     */
    public void setPlayerState(UUID playerId, PlayerChannelState state) {
        states.set(playerId, state);
    }

    /**
     * @param playerId the player UUID
     * @return the removed state, or null
     */
    public PlayerChannelState removeState(UUID playerId) {
        return states.remove(playerId);
    }

    /**
     * @return the number of tracked players
     */
    public int size() {
        return states.size();
    }

    /**
     * @param mode the new global mode
     */
    public void setGlobalMode(ChatMode mode) {
        this.globalMode = mode;
    }

    /**
     * @return the global chat mode
     */
    public ChatMode getGlobalMode() {
        return globalMode;
    }

    /**
     * Toggles the player's personal chat mode.
     *
     * @param playerId the player UUID
     * @return the new mode
     */
    public ChatMode togglePlayerMode(UUID playerId) {
        return getOrCreateState(playerId).toggleMode();
    }

    /**
     * Sets the player's active channel.
     *
     * @param playerId the player UUID
     * @param channelId the channel id
     */
    public void setPlayerChannel(UUID playerId, String channelId) {
        getOrCreateState(playerId).setActiveChannel(channelId);
    }

    /**
     * @param playerId the player UUID
     * @return the player's active channel (creating state if needed)
     */
    public String getPlayerChannel(UUID playerId) {
        return getOrCreateState(playerId).getActiveChannel();
    }

    /**
     * Installs a new config snapshot and derives the global mode from it.
     * Called by the platform shell after a successful reload.
     *
     * @param newConfig the fresh snapshot (null is ignored)
     */
    public void setConfig(ProxyConfigView newConfig) {
        if (newConfig == null) {
            return;
        }
        this.config = newConfig;
        this.globalMode = newConfig.isReplaceVanilla() ? ChatMode.REPLACE : ChatMode.HYBRID;
    }

    /**
     * @return the current config snapshot
     */
    public ProxyConfigView getConfig() {
        return config;
    }
}
