package com.nova.chat.client.command;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;

import java.util.Objects;
import java.util.UUID;

/**
 * Platform-agnostic command intent service for channel membership and chat mode.
 *
 * <p>Depends only on {@link PlayerChannelState} and a functional
 * {@link PacketSender} — no Bukkit/Velocity APIs. Platforms call these methods
 * from their command handlers; this module is not wired into platform commands yet.
 *
 * <h2>Intent behaviour</h2>
 * <ul>
 *   <li>{@link CommandIntent#JOIN} – optimistically sets active channel, then
 *       sends {@link ChannelAction#JOIN} with optional password and
 *       {@code playerId}/{@code playerName} extras when provided.</li>
 *   <li>{@link CommandIntent#LEAVE} – optimistically leaves local membership,
 *       then sends {@link ChannelAction#LEAVE} with the same extras pattern.</li>
 *   <li>{@link CommandIntent#TOGGLE} – flips {@link ChatMode} on local state only;
 *       no network packet.</li>
 *   <li>{@link CommandIntent#RELOAD} – <strong>no-op on the wire and on state</strong>.
 *       Returns a successful result with {@link CommandIntent#RELOAD} so the
 *       platform can perform config reload / reconnect-budget reset. Documented
 *       intentionally: reload is platform-owned.</li>
 * </ul>
 *
 * <p>Optimistic local updates run only when the packet send is accepted
 * ({@link PacketSender#send} returns {@code true}). On send failure, local state
 * is left unchanged and a failure {@link CommandResult} is returned.
 */
public final class ChannelCommandService {

    private final PacketSender packetSender;

    public ChannelCommandService(PacketSender packetSender) {
        this.packetSender = Objects.requireNonNull(packetSender, "packetSender");
    }

    /**
     * Joins {@code channelId} for the given player state.
     *
     * @param state      player channel state (updated on successful send)
     * @param channelId  target channel (non-blank)
     * @param password   optional channel password; null treated as empty
     * @param playerName optional display name put in packet extras; null/blank omitted
     * @return success when the packet was accepted for send; failure otherwise
     */
    public CommandResult join(PlayerChannelState state, String channelId,
                              String password, String playerName) {
        return join(state, channelId, password, playerName, null);
    }

    /**
     * Joins {@code channelId} for the given player state, carrying the player's
     * current world so the backend can enforce world-restricted channels (NC-435)
     * and stamp {@code currentWorld} on the player state.
     *
     * @param state      player channel state (updated on successful send)
     * @param channelId  target channel (non-blank)
     * @param password   optional channel password; null treated as empty
     * @param playerName optional display name put in packet extras; null/blank omitted
     * @param world      optional player world put in packet extras; null/blank omitted
     * @return success when the packet was accepted for send; failure otherwise
     */
    public CommandResult join(PlayerChannelState state, String channelId,
                              String password, String playerName, String world) {
        Objects.requireNonNull(state, "state");
        if (channelId == null || channelId.isBlank()) {
            return CommandResult.failure(CommandIntent.JOIN, "channelId must not be blank");
        }

        ChannelActionPacket packet = new ChannelActionPacket(
                ChannelAction.JOIN, channelId, password != null ? password : "");
        addPlayerExtras(packet, state.getPlayerId(), playerName, world);

        // BUG-H2: stamp the current active channel so the async response handler
        // can roll back the optimistic setActiveChannel below if the backend
        // rejects the JOIN (wrong password, full, NC-403/404/434...). Read before
        // the optimistic switch so the pre-join value is captured exactly.
        String previousChannel = state.getActiveChannel();
        if (previousChannel != null && !previousChannel.isEmpty()) {
            packet.addExtra("previousChannel", previousChannel);
        }

        if (!packetSender.send(packet)) {
            return CommandResult.failure(CommandIntent.JOIN,
                    "Failed to send JOIN for channel '" + channelId + "'",
                    "NC-503");
        }

        // Optimistic local update after accepted send (mirrors Bukkit JoinCommand).
        state.setActiveChannel(channelId);
        return CommandResult.success(CommandIntent.JOIN,
                "Joining channel '" + channelId + "'");
    }

    /**
     * Convenience overload: join without password or player name extras beyond playerId.
     */
    public CommandResult join(PlayerChannelState state, String channelId) {
        return join(state, channelId, null, null);
    }

    /**
     * Leaves {@code channelId} for the given player state.
     *
     * <p>If {@code channelId} is null or blank, the player's current active channel
     * is used. When there is no active channel, returns failure without sending.
     *
     * @param state      player channel state (updated on successful send)
     * @param channelId  channel to leave, or null/blank to leave the active channel
     * @param playerName optional display name put in packet extras; null/blank omitted
     * @return success when the packet was accepted for send; failure otherwise
     */
    public CommandResult leave(PlayerChannelState state, String channelId, String playerName) {
        Objects.requireNonNull(state, "state");

        String target = channelId;
        if (target == null || target.isBlank()) {
            target = state.getActiveChannel();
        }
        if (target == null || target.isBlank()) {
            return CommandResult.failure(CommandIntent.LEAVE, "Not in a channel", "NC-433");
        }

        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.LEAVE, target);
        addPlayerExtras(packet, state.getPlayerId(), playerName, null);

        if (!packetSender.send(packet)) {
            return CommandResult.failure(CommandIntent.LEAVE,
                    "Failed to send LEAVE for channel '" + target + "'",
                    "NC-503");
        }

        // Optimistic local membership update after accepted send.
        state.leaveChannel(target);
        return CommandResult.success(CommandIntent.LEAVE,
                "Leaving channel '" + target + "'");
    }

    /**
     * Convenience overload: leave without player name extra.
     */
    public CommandResult leave(PlayerChannelState state, String channelId) {
        return leave(state, channelId, null);
    }

    /**
     * Toggles chat mode on local state (HYBRID ↔ REPLACE). No packet is sent.
     *
     * @param state player channel state
     * @return success with the new mode described in the message
     */
    public CommandResult toggle(PlayerChannelState state) {
        Objects.requireNonNull(state, "state");
        ChatMode newMode = state.toggleMode();
        return CommandResult.success(CommandIntent.TOGGLE,
                "Chat mode toggled to " + newMode.name());
    }

    /**
     * Signals a reload intent to the platform.
     *
     * <p><strong>No-op here:</strong> does not send a network packet and does not
     * mutate any {@link PlayerChannelState}. Platforms should interpret a successful
     * result with {@link CommandIntent#RELOAD} as permission to reload config,
     * reconnect, or reset reconnect budget.
     *
     * @return always a successful result carrying {@link CommandIntent#RELOAD}
     */
    public CommandResult reload() {
        return CommandResult.success(CommandIntent.RELOAD,
                "Reload requested; platform must handle config/reconnect");
    }

    private static void addPlayerExtras(ChannelActionPacket packet, UUID playerId,
                                         String playerName, String world) {
        if (playerId != null) {
            packet.addExtra("playerId", playerId.toString());
        }
        if (playerName != null && !playerName.isBlank()) {
            packet.addExtra("playerName", playerName);
        }
        if (world != null && !world.isBlank()) {
            packet.addExtra("world", world);
        }
    }
}
