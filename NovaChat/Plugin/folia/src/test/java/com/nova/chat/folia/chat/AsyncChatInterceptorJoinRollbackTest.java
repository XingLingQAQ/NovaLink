package com.nova.chat.folia.chat;

import com.nova.chat.client.network.ChannelResponseDispatcher;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.network.AsyncNetworkClient;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Folia half of the refused-JOIN fix.
 *
 * <p>Mirrors the Bukkit and nukkit tests: the optimistic
 * {@code setActiveChannel} also adds the channel to the joined set, so a restore
 * built on {@code setActiveChannel(previous)} left the refused channel joined and
 * {@code /nc list} showed it with a check. The adapter now calls the shared
 * {@code PlayerChannelState.rollbackJoin}, which removes it and restores the
 * previous channel without manufacturing membership.
 *
 * <p>{@code FoliaChannelResponseAdapter} is a private inner class, so it is
 * instantiated reflectively against the real interceptor (the same approach the
 * nukkit notify test uses).
 */
@DisplayName("Folia adapter rolls a refused JOIN back out of the joined set")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncChatInterceptorJoinRollbackTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private NovaChatFolia plugin;
    @Mock private NovaChatConfig config;
    @Mock private FoliaSchedulerAdapter scheduler;
    @Mock private AsyncNetworkClient networkClient;
    @Mock private Server server;
    @Mock private PluginManager pluginManager;
    @Mock private Player player;

    private AsyncChatInterceptor interceptor;
    private ChannelResponseDispatcher.ChannelResponseAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(plugin.getServer()).thenReturn(server);
        when(networkClient.getChannelResponseTracker()).thenReturn(new ChannelResponseTracker());
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(false);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.isOnline()).thenReturn(true);
        doReturn(player).when(server).getPlayer(PLAYER_ID);
        doReturn(List.of(player)).when(server).getOnlinePlayers();

        interceptor = new AsyncChatInterceptor(plugin);
        when(plugin.getChatInterceptor()).thenReturn(interceptor);
        adapter = instantiateAdapter(interceptor);
    }

    /** Runs the region-thread task the adapter scheduled. */
    private void runRegionTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runForPlayer(eq(player), task.capture());
        task.getValue().run();
    }

    @Test
    @DisplayName("a refused join drops the attempted channel and restores the previous one")
    void refusedJoinDropsMembership() {
        PlayerChannelState state = interceptor.getOrCreateState(player);
        // The optimistic switch the command performs on send success.
        state.setActiveChannel("nosuchchannel");
        assertThat(state.isJoined("nosuchchannel")).isTrue();

        adapter.rollbackJoin(PLAYER_ID, "nosuchchannel", "global");
        runRegionTask();

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
        runRegionTask();

        assertThat(state.getActiveChannel()).isEqualTo("trade");
        assertThat(state.isJoined("nosuchchannel")).isFalse();
    }

    @Test
    @DisplayName("an offline player is a no-op and schedules no region task")
    void offlinePlayerNoOp() {
        doReturn(null).when(server).getPlayer(PLAYER_ID);

        adapter.rollbackJoin(PLAYER_ID, "nosuchchannel", "global");

        verify(scheduler, never()).runForPlayer(any(Player.class), any(Runnable.class));
    }

    /**
     * Reflectively instantiates the private {@code FoliaChannelResponseAdapter}
     * inner class, bound to the real interceptor instance it reads state through.
     */
    private static ChannelResponseDispatcher.ChannelResponseAdapter instantiateAdapter(
            AsyncChatInterceptor outer) throws Exception {
        Class<?> adapterClass = Class.forName(
                "com.nova.chat.folia.chat.AsyncChatInterceptor$FoliaChannelResponseAdapter");
        Constructor<?> ctor = adapterClass.getDeclaredConstructor(AsyncChatInterceptor.class);
        ctor.setAccessible(true);
        return (ChannelResponseDispatcher.ChannelResponseAdapter) ctor.newInstance(outer);
    }
}
