package com.nova.chat.bukkit.network;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.chat.ChatInterceptor;
import com.nova.chat.bukkit.command.MessageHelper;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.bukkit.error.ErrorMessageHandler;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.state.PlayerChannelState;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A refused {@code /nc join} must leave the player's local membership untouched.
 *
 * <p>{@code ChannelCommandService.join} switches the active channel optimistically
 * and {@code setActiveChannel} also <em>adds</em> to the joined set, so restoring
 * with {@code setActiveChannel(previous)} left the refused channel joined and
 * {@code /nc list} showed it with a check. The adapter now calls the shared
 * {@code PlayerChannelState.rollbackJoin}, which removes the attempted channel
 * and restores the previous one without manufacturing membership.
 */
@DisplayName("Bukkit adapter rolls a refused JOIN back out of the joined set")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BukkitJoinRollbackTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private NovaChatBukkit plugin;
    @Mock private NovaChatConfig config;
    @Mock private Server server;
    @Mock private BukkitScheduler scheduler;
    @Mock private Player player;
    @Mock private MessageHelper messageHelper;
    @Mock private ErrorMessageHandler errorHandler;

    private ChatInterceptor interceptor;
    private BukkitChannelResponseAdapter adapter;

    @BeforeEach
    void setUp() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getVersion()).thenReturn("test-server");
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(false);
        when(config.getChannelPrefixes()).thenReturn(Map.of());
        when(config.toClientConnectionConfig())
                .thenReturn(ClientConnectionConfig.builder().build());
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getMessageHelper()).thenReturn(messageHelper);
        when(plugin.getErrorHandler()).thenReturn(errorHandler);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.isOnline()).thenReturn(true);
        doReturn(player).when(server).getPlayer(PLAYER_ID);
        doReturn(List.of(player)).when(server).getOnlinePlayers();

        interceptor = new ChatInterceptor(plugin);
        when(plugin.getChatInterceptor()).thenReturn(interceptor);

        adapter = new NetworkClient(plugin, config, new KnownChannelRegistry()).adapter;
    }

    /** Runs every main-thread task the adapter scheduled. */
    private void runScheduledTasks() {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).runTask(eq(plugin), tasks.capture());
        tasks.getAllValues().forEach(Runnable::run);
    }

    @Test
    @DisplayName("a refused join drops the attempted channel and restores the previous one")
    void refusedJoinDropsMembership() {
        PlayerChannelState state = interceptor.getOrCreateState(player);
        // The optimistic switch the command performs on send success.
        state.setActiveChannel("nosuchchannel");
        assertThat(state.isJoined("nosuchchannel")).isTrue();

        adapter.rollbackJoin(PLAYER_ID, "nosuchchannel", "global");
        runScheduledTasks();

        assertThat(state.getActiveChannel()).isEqualTo("global");
        assertThat(state.getJoinedChannels())
                .as("/nc list must not mark the refused channel as joined")
                .containsExactly("global");
    }

    @Test
    @DisplayName("a manual switch made while the JOIN was in flight is not overridden")
    void manualSwitchWins() {
        PlayerChannelState state = interceptor.getOrCreateState(player);
        state.setActiveChannel("nosuchchannel");
        state.setActiveChannel("trade");

        adapter.rollbackJoin(PLAYER_ID, "nosuchchannel", "global");
        runScheduledTasks();

        assertThat(state.getActiveChannel()).isEqualTo("trade");
        // Membership is still dropped: the backend refused entry either way.
        assertThat(state.isJoined("nosuchchannel")).isFalse();
    }

    @Test
    @DisplayName("an offline player is a no-op: the hop runs but touches no state and renders nothing")
    void offlinePlayerNoOp() {
        PlayerChannelState state = interceptor.getOrCreateState(player);
        state.setActiveChannel("nosuchchannel");
        doReturn(null).when(server).getPlayer(PLAYER_ID);

        adapter.rollbackJoin(PLAYER_ID, "nosuchchannel", "global");
        runScheduledTasks();

        verify(messageHelper, never()).sendError(any(), anyString());
        // Known limitation, unchanged by this fix: the adapter resolves the
        // Player first, so a player who left before the response arrives keeps
        // the optimistic membership in their (soon-discarded) state.
        assertThat(state.isJoined("nosuchchannel")).isTrue();
    }
}
