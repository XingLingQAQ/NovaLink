package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerState;
import com.nova.link.database.PlayerStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LEAVE handling of the {@code logout} marker.
 *
 * <p>A LEAVE carrying {@code logout=true} is how a proxy or game server reports
 * that the player left the whole network (as opposed to a plain {@code /nc
 * leave}, which only drops one channel). The player must then disappear from
 * the network-wide cache — otherwise {@code findPlayerByName} keeps resolving
 * them and private messages are routed to a connection that can no longer
 * render them. A plain LEAVE must leave the cache untouched.
 *
 * <p>The cache is checked through the same public surface the rest of the
 * backend uses ({@code getAllPlayerStates} / {@code getCachedState}) after the
 * database has been wiped, so a logout that merely reloads the persisted row
 * still counts as "gone".
 */
@DisplayName("ChannelActionHandler logout cache eviction")
class ChannelActionHandlerLogoutTest {

    private static final String CLIENT_ID = "velocity-proxy";

    private MemoryProvider databaseProvider;
    private ChannelManager channelManager;
    private PlayerStateManager playerStateManager;
    private ChannelActionHandler handler;
    private ClientConnection connection;

    private UUID playerId;

    @BeforeEach
    void setUp() throws Exception {
        databaseProvider = new MemoryProvider();
        databaseProvider.initialize();
        channelManager = new ChannelManager();
        playerStateManager = new PlayerStateManager(databaseProvider);

        handler = new ChannelActionHandler(
                channelManager,
                playerStateManager,
                databaseProvider,
                new PrivateChannelManager(channelManager),
                new InvitationManager(databaseProvider, channelManager),
                new PermissionManager());

        connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn(CLIENT_ID);

        channelManager.createChannel(ChannelConfig.builder()
                .id("global")
                .scope(ChannelScope.GLOBAL)
                .build());

        playerId = UUID.randomUUID();
    }

    /** JOIN then LEAVE "global", returning the leave response. */
    private ChannelActionResponsePacket joinThenLeave(boolean logout, String channelId) {
        ChannelActionPacket join = new ChannelActionPacket(ChannelAction.JOIN, channelId);
        join.addExtra("playerId", playerId.toString());
        join.addExtra("playerName", "Steve");
        ChannelActionResponsePacket joined = handler.handle(connection, join);
        assertThat(joined.isSuccess()).isTrue();

        return leave(logout, channelId);
    }

    private ChannelActionResponsePacket leave(boolean logout, String channelId) {
        ChannelActionPacket leave = new ChannelActionPacket(ChannelAction.LEAVE, channelId);
        leave.addExtra("playerId", playerId.toString());
        leave.addExtra("playerName", "Steve");
        if (logout) {
            leave.addExtra("logout", "true");
        }
        return handler.handle(connection, leave);
    }

    private void assertPlayerEvicted() {
        assertThat(playerStateManager.getCachedState(playerId))
                .as("cache entry for the logged-out player")
                .isEmpty();
        assertThat(playerStateManager.getAllPlayerStates())
                .as("network-wide online roster")
                .noneMatch(state -> state.getPlayerId().equals(playerId));
    }

    @Test
    @DisplayName("LEAVE with logout=true evicts the player from the cache")
    void logoutLeaveEvictsCache() {
        // The JOIN caches the player (it is the network-wide online roster).
        ChannelActionPacket join = new ChannelActionPacket(ChannelAction.JOIN, "global");
        join.addExtra("playerId", playerId.toString());
        join.addExtra("playerName", "Steve");
        handler.handle(connection, join);
        assertThat(playerStateManager.getCachedState(playerId)).isPresent();

        ChannelActionResponsePacket response = leave(true, "global");

        assertThat(response.isSuccess()).isTrue();
        assertPlayerEvicted();
    }

    @Test
    @DisplayName("evicted player is no longer resolvable by name")
    void evictedPlayerNotResolvableByName() {
        joinThenLeave(true, "global");

        // What PrivateMessageHandler.findPlayerByName does: scan the cache.
        assertThat(playerStateManager.getAllPlayerStates())
                .noneMatch(state -> "Steve".equalsIgnoreCase(state.getPlayerName()));
    }

    @Test
    @DisplayName("logout removes the row from the online cache even though it stays persisted")
    void evictionLeavesPersistenceIntact() throws Exception {
        joinThenLeave(true, "global");

        // saveFirst=true keeps the player's persisted row for the next login,
        // yet the online cache — the roster private messages and panel
        // listings read — must not contain the player any more.
        assertThat(databaseProvider.loadPlayerState(playerId)).isPresent();
        assertPlayerEvicted();
    }

    @Test
    @DisplayName("logout eviction saves the final state before dropping it")
    void evictionSavesFinalState() throws Exception {
        joinThenLeave(true, "global");

        // saveFirst=true: the departure (channel membership now empty) is
        // persisted, so a later restart does not resurrect the membership.
        PlayerState persisted = databaseProvider.loadPlayerState(playerId).orElseThrow();
        assertThat(persisted.getJoinedChannels()).doesNotContain("global");
        assertThat(persisted.getActiveChannel()).isNull();
        assertThat(persisted.getClientId()).isEqualTo(CLIENT_ID);
    }

    @Test
    @DisplayName("plain /nc leave (no logout marker) keeps the player cached")
    void plainLeaveKeepsCache() {
        ChannelActionResponsePacket response = joinThenLeave(false, "global");

        assertThat(response.isSuccess()).isTrue();
        assertThat(playerStateManager.getCachedState(playerId))
                .as("still online, just not in this channel")
                .isPresent();
        assertThat(playerStateManager.getJoinedChannels(playerId)).doesNotContain("global");
    }

    @Test
    @DisplayName("logout=false and other values are treated as a plain leave")
    void nonTrueLogoutValuesKeepCache() {
        ChannelActionPacket join = new ChannelActionPacket(ChannelAction.JOIN, "global");
        join.addExtra("playerId", playerId.toString());
        join.addExtra("playerName", "Steve");
        handler.handle(connection, join);

        ChannelActionPacket leave = new ChannelActionPacket(ChannelAction.LEAVE, "global");
        leave.addExtra("playerId", playerId.toString());
        leave.addExtra("logout", "false");
        handler.handle(connection, leave);

        assertThat(playerStateManager.getCachedState(playerId)).isPresent();
    }

    @Test
    @DisplayName("logout marker is matched case-insensitively and ignores padding")
    void logoutMarkerParsedTolerantly() {
        ChannelActionPacket join = new ChannelActionPacket(ChannelAction.JOIN, "global");
        join.addExtra("playerId", playerId.toString());
        join.addExtra("playerName", "Steve");
        handler.handle(connection, join);

        ChannelActionPacket leave = new ChannelActionPacket(ChannelAction.LEAVE, "global");
        leave.addExtra("playerId", playerId.toString());
        leave.addExtra("logout", " TRUE ");
        handler.handle(connection, leave);

        assertPlayerEvicted();
    }

    @Test
    @DisplayName("per-channel logout sequence evicts on each LEAVE without failing")
    void perChannelLogoutSequence() {
        // The proxies send one LEAVE per joined channel. The first one already
        // evicts the cache, so the remaining ones must still succeed rather
        // than erroring on the (now unstored) state.
        channelManager.createChannel(ChannelConfig.builder()
                .id("staff")
                .scope(ChannelScope.GLOBAL)
                .build());
        for (String channelId : new String[]{"global", "staff"}) {
            ChannelActionPacket join = new ChannelActionPacket(ChannelAction.JOIN, channelId);
            join.addExtra("playerId", playerId.toString());
            join.addExtra("playerName", "Steve");
            assertThat(handler.handle(connection, join).isSuccess()).isTrue();
        }

        assertThat(leave(true, "global").isSuccess()).isTrue();
        assertThat(leave(true, "staff").isSuccess()).isTrue();

        assertPlayerEvicted();
        assertThat(channelManager.getChannel("global").isMember(playerId)).isFalse();
        assertThat(channelManager.getChannel("staff").isMember(playerId)).isFalse();
    }
}
