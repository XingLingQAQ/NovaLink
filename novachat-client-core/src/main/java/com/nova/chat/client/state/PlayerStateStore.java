package com.nova.chat.client.state;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe per-player {@link PlayerChannelState} store keyed by UUID.
 *
 * <p>Absorbs the {@code Map<UUID, PlayerChannelState>} + get-or-create / get /
 * set / remove accessors that were duplicated across the six server- and
 * proxy-side platform chat interceptors (bukkit, nukkit, folia, bungee,
 * velocity, sponge). Each platform's chat interceptor now delegates its
 * state accessors here, keeping only the platform {@code Player} → UUID
 * extraction local.
 *
 * <p>Thread safety: backed by a {@link ConcurrentHashMap}. {@link #getOrCreate}
 * uses {@code computeIfAbsent} so a concurrent first-access for the same UUID
 * creates exactly one state. {@link #set} refuses null state defensively so a
 * bug that would otherwise store a null and NPE a later reader is surfaced at
 * the call site.
 *
 * <p>PNX is not driven through this store: it wraps {@link PlayerChannelState}
 * in a local {@code PlayerChatState} and hardcodes {@code ChatMode.HYBRID},
 * so migrating it is a separate follow-up.
 */
public final class PlayerStateStore {

    private final Map<UUID, PlayerChannelState> states = new ConcurrentHashMap<>();

    /**
     * Returns the state for {@code playerId}, creating it with the given default
     * channel and mode on first access.
     *
     * @param playerId        player's UUID (non-null)
     * @param defaultChannel  initial active channel used only when creating
     * @param defaultMode     initial chat mode used only when creating
     * @return the (possibly newly created) state; never null
     */
    public PlayerChannelState getOrCreate(UUID playerId, String defaultChannel, ChatMode defaultMode) {
        return states.computeIfAbsent(
                Objects.requireNonNull(playerId, "playerId"),
                uuid -> new PlayerChannelState(uuid, defaultChannel, defaultMode));
    }

    /**
     * @return the existing state for {@code playerId}, or null if none
     */
    public PlayerChannelState get(UUID playerId) {
        return states.get(playerId);
    }

    /**
     * Alias for {@link #get(UUID)} kept for command-compatibility with the
     * historical per-platform {@code getPlayerState} accessors.
     */
    public PlayerChannelState getPlayer(UUID playerId) {
        return states.get(playerId);
    }

    /**
     * Sets the state for {@code playerId}. Refuses null defensively.
     *
     * @param playerId player's UUID (non-null)
     * @param state    the state to store (non-null)
     */
    public void set(UUID playerId, PlayerChannelState state) {
        if (state != null) {
            states.put(Objects.requireNonNull(playerId, "playerId"), state);
        }
    }

    /**
     * Removes the state for {@code playerId} (e.g. on player quit).
     *
     * @return the removed state, or null if there was none
     */
    public PlayerChannelState remove(UUID playerId) {
        return states.remove(playerId);
    }

    /**
     * Clears all stored state. Used on reload / full reset (e.g. Folia).
     */
    public void clear() {
        states.clear();
    }

    /**
     * @return the number of tracked players
     */
    public int size() {
        return states.size();
    }
}
