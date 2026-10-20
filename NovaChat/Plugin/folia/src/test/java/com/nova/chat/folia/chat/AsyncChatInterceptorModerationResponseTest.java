package com.nova.chat.folia.chat;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.NovaConstants;
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
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Folia half of the moderation-response fix: the operator who ran
 * {@code /nc mute|kick|ban|unban} must see the outcome on their region thread.
 *
 * <p>Mirrors the Bukkit end-to-end test. The handler is captured from the
 * mocked client registration, a real {@link ChannelActionPacket} with
 * {@code playerId} is fed through the real {@link ChannelResponseTracker}, and
 * the echoed response is dispatched — so both halves (correlation and
 * rendering) are exercised together.
 */
@DisplayName("Folia moderation response reaches the issuing player")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncChatInterceptorModerationResponseTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private NovaChatFolia plugin;
    @Mock private NovaChatConfig config;
    @Mock private FoliaSchedulerAdapter scheduler;
    @Mock private AsyncNetworkClient networkClient;
    @Mock private Server server;
    @Mock private PluginManager pluginManager;
    @Mock private Player player;
    @Mock private MessageHelper messageHelper;
    @Mock private Logger logger;

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
        when(plugin.getLogger()).thenReturn(logger);
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

        ArgumentCaptor<Consumer<ChannelActionResponsePacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ChannelActionResponsePacket.class), captor.capture());
        responseHandler = captor.getValue();
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    /** Tracks a request the way a moderation command would, and returns its id. */
    private UUID trackModerationRequest(ChannelAction action) {
        ChannelActionPacket request = new ChannelActionPacket(action, "global");
        request.addExtra("playerId", PLAYER_ID.toString());
        request.addExtra("operatorName", "Mod");
        tracker.track(request);
        return request.getRequestId();
    }

    /** Runs the region-thread task the adapter scheduled. */
    private void runRegionTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runForPlayer(eq(player), task.capture());
        task.getValue().run();
    }

    @Test
    @DisplayName("a successful mute renders a localized confirmation on the operator's region thread")
    void muteSuccessRenders() {
        UUID requestId = trackModerationRequest(ChannelAction.MUTE);

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.MUTE, "global", "", "Player muted");
        response.setRequestId(requestId);
        responseHandler.accept(response);
        runRegionTask();

        verify(messageHelper).sendSuccess(eq(player), eq("禁言请求已处理"));
    }

    @Test
    @DisplayName("a failed kick renders the mapped error to the operator")
    void kickFailureRenders() {
        UUID requestId = trackModerationRequest(ChannelAction.KICK);

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, ChannelAction.KICK, "global", "NC-403", "Permission denied");
        response.setRequestId(requestId);
        responseHandler.accept(response);
        runRegionTask();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendError(eq(player), text.capture());
        org.assertj.core.api.Assertions.assertThat(text.getValue()).contains("NC-403");
    }

    @Test
    @DisplayName("a ban success renders the generic acceptance line")
    void banSuccessRendersGeneric() {
        UUID requestId = trackModerationRequest(ChannelAction.BAN);

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.BAN, "global", "", "Player banned");
        response.setRequestId(requestId);
        responseHandler.accept(response);
        runRegionTask();

        verify(messageHelper).sendSuccess(eq(player), eq("操作成功"));
    }

    @Test
    @DisplayName("a console-issued moderation outcome goes to the log, not the player")
    void consoleOutcomeIsLogged() {
        ChannelActionPacket request = new ChannelActionPacket(ChannelAction.MUTE, "global");
        request.addExtra("playerId", NovaConstants.CONSOLE_SENTINEL_UUID);
        tracker.track(request);

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.MUTE, "global", "", "Player muted");
        response.setRequestId(request.getRequestId());
        responseHandler.accept(response);

        verify(logger).info(contains("禁言请求已处理"));
        verify(messageHelper, never()).sendSuccess(any(), anyString());
    }
}
