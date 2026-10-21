package com.nova.chat.folia.chat;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.command.MessageHelper;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.network.AsyncNetworkClient;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Folia half of the CREATE / INVITE / ACCEPT success-rendering fix.
 *
 * <p>Mirrors the Bukkit end-to-end test. The handler is captured from the mocked
 * client registration, a real {@link ChannelActionPacket} with {@code playerId}
 * is fed through the real {@link ChannelResponseTracker}, and the echoed
 * response is dispatched — so correlation and rendering are exercised together,
 * and the region-thread hop is asserted by running the task the adapter queued.
 */
@DisplayName("Folia CREATE/INVITE/ACCEPT success rendering")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncChatInterceptorCreateInviteAcceptTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private NovaChatFolia plugin;
    @Mock private NovaChatConfig config;
    @Mock private FoliaSchedulerAdapter scheduler;
    @Mock private AsyncNetworkClient networkClient;
    @Mock private Server server;
    @Mock private PluginManager pluginManager;
    @Mock private Player player;
    @Mock private MessageHelper messageHelper;

    private final ChannelResponseTracker tracker = new ChannelResponseTracker();
    private AsyncChatInterceptor interceptor;
    private Consumer<ChannelActionResponsePacket> responseHandler;
    private Locale previousDefaultLocale;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getMessageHelper()).thenReturn(messageHelper);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
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

        ArgumentCaptor<Consumer<ChannelActionResponsePacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ChannelActionResponsePacket.class), captor.capture());
        responseHandler = captor.getValue();
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    /** Tracks a request the way the command's send path would. */
    private UUID track(ChannelAction action, String channelIdOrName) {
        ChannelActionPacket request = new ChannelActionPacket(action, channelIdOrName);
        request.addExtra("playerId", PLAYER_ID.toString());
        tracker.track(request);
        return request.getRequestId();
    }

    /**
     * Runs every region-thread task the adapter scheduled. CREATE/ACCEPT queue
     * two (setActiveChannel then the render), so all of them must be drained.
     */
    private void runRegionTasks() {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, org.mockito.Mockito.atLeastOnce())
                .runForPlayer(eq(player), tasks.capture());
        tasks.getAllValues().forEach(Runnable::run);
    }

    @Test
    @DisplayName("a CREATE success renders the id and password on the creator's region thread")
    void createRendersOnRegionThread() {
        UUID requestId = track(ChannelAction.CREATE, "myroom");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.CREATE, "NC-1A2B", "", "Private channel created");
        response.setRequestId(requestId);
        response.addExtra("password", "hunter2");
        response.addExtra("passwordGenerated", "false");
        response.addExtra("displayName", "myroom");
        responseHandler.accept(response);

        // Nothing is rendered before the region hop runs.
        verify(messageHelper, never()).sendMessage(eq(player), anyString());

        runRegionTasks();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendMessage(eq(player), body.capture());
        assertThat(body.getValue()).contains("NC-1A2B").contains("hunter2");
    }

    @Test
    @DisplayName("a generated password says so")
    void generatedPasswordFlagsItself() {
        UUID requestId = track(ChannelAction.CREATE, "myroom");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.CREATE, "NC-9F8E", "", "Private channel created");
        response.setRequestId(requestId);
        response.addExtra("password", "abc123");
        response.addExtra("passwordGenerated", "true");
        responseHandler.accept(response);
        runRegionTasks();

        verify(messageHelper).sendSuggestion(eq(player), eq("该密码为系统自动生成，请妥善保存"));
    }

    @Test
    @DisplayName("an INVITE success renders the backend invite code")
    void inviteRendersCode() {
        UUID requestId = track(ChannelAction.INVITE, "NC-1A2B");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.INVITE, "NC-1A2B", "", "Invitation created");
        response.setRequestId(requestId);
        response.addExtra("code", "a1b2c3");
        responseHandler.accept(response);
        runRegionTasks();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendMessage(eq(player), body.capture());
        assertThat(body.getValue()).contains("A1B2C3");
    }

    @Test
    @DisplayName("an ACCEPT success renders the channel the invite admitted the player to")
    void acceptRendersChannel() {
        UUID requestId = track(ChannelAction.ACCEPT, "A1B2C3");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.ACCEPT, "NC-1A2B", "", "Invitation accepted");
        response.setRequestId(requestId);
        response.addExtra("code", "A1B2C3");
        responseHandler.accept(response);
        runRegionTasks();

        verify(messageHelper).sendSuccess(eq(player), eq("已通过邀请加入频道 &eNC-1A2B"));
        assertThat(interceptor.getOrCreateState(player).getActiveChannel()).isEqualTo("NC-1A2B");
    }

    @Test
    @DisplayName("a refused ACCEPT renders the error, not a joined line")
    void refusedAcceptRendersErrorOnly() {
        UUID requestId = track(ChannelAction.ACCEPT, "A1B2C3");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, ChannelAction.ACCEPT, "A1B2C3", "NC-410", "Invitation has expired");
        response.setRequestId(requestId);
        responseHandler.accept(response);
        runRegionTasks();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendError(eq(player), error.capture());
        assertThat(error.getValue()).contains("NC-410");
        verify(messageHelper, never()).sendSuccess(eq(player), anyString());
    }
}
