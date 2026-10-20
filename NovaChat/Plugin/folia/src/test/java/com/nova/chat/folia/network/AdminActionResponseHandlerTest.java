package com.nova.chat.folia.network;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.command.MessageHelper;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import org.bukkit.Server;
import org.bukkit.entity.Player;
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

import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Folia {@link AdminActionResponseHandler}.
 *
 * <p>{@code /nc announce}, {@code /nc title} and {@code /nc auth} all answer
 * asynchronously on {@code AdminActionResponsePacket}. These cases pin the four
 * rendering paths — success, coded failure, the NC-403 super-admin guidance, and
 * the console sentinel — plus the region-thread hop that makes the player
 * branches legal on Folia.
 */
@DisplayName("Folia AdminActionResponsePacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminActionResponseHandlerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID REQUEST_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Mock
    private NovaChatFolia plugin;
    @Mock
    private FoliaSchedulerAdapter scheduler;
    @Mock
    private AsyncNetworkClient networkClient;
    @Mock
    private MessageHelper messageHelper;
    @Mock
    private Server server;
    @Mock
    private Player player;
    @Mock
    private Logger logger;

    private AdminActionResponseHandler handler;
    private Locale previousDefaultLocale;

    @BeforeEach
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getMessageHelper()).thenReturn(messageHelper);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.isOnline()).thenReturn(true);
        doReturn(player).when(server).getPlayer(PLAYER_ID);

        handler = new AdminActionResponseHandler(plugin, scheduler, networkClient);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    /** Records {@code requestId -> PLAYER_ID} the way a sent command would. */
    private void givenPendingRequestForPlayer() {
        when(networkClient.consumePendingAdminRequest(REQUEST_ID)).thenReturn(PLAYER_ID);
    }

    /** Stamps the response with the request id the correlation is keyed on. */
    private static AdminActionResponsePacket correlated(AdminActionResponsePacket response) {
        response.setRequestId(REQUEST_ID);
        return response;
    }

    /** Runs the region-thread task the handler scheduled. */
    private void runScheduledRegionTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runForPlayer(eq(player), task.capture());
        task.getValue().run();
    }

    @Test
    @DisplayName("a success response renders the backend success message to the requesting player")
    void successRendersToPlayer() {
        givenPendingRequestForPlayer();
        AdminActionResponsePacket response = correlated(
                AdminActionResponsePacket.success(AdminAction.STATUS, "Announcement sent"));

        handler.accept(response);
        runScheduledRegionTask();

        verify(messageHelper).sendSuccess(player, "Announcement sent");
        verify(messageHelper, never()).sendError(any(), anyString());
    }

    @Test
    @DisplayName("a success response with no message falls back to the shared success key")
    void successWithoutMessageFallsBack() {
        givenPendingRequestForPlayer();
        AdminActionResponsePacket response = correlated(
                AdminActionResponsePacket.success(AdminAction.STATUS, ""));

        handler.accept(response);
        runScheduledRegionTask();

        verify(messageHelper).sendSuccess(player, "操作成功");
    }

    @Test
    @DisplayName("a failure response renders the backend's message under its error code")
    void failureRendersErrorCodeAndMessage() {
        givenPendingRequestForPlayer();
        AdminActionResponsePacket response = correlated(AdminActionResponsePacket.failure(
                AdminAction.STATUS, NovaConstants.ERROR_NOT_FOUND, "Channel not found: trade"));

        handler.accept(response);
        runScheduledRegionTask();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendError(eq(player), sent.capture());
        assertThat(sent.getValue())
                .contains("[NC-404]")
                .contains("Channel not found: trade");
        verify(messageHelper, never()).sendSuggestion(any(), anyString());
        verify(messageHelper, never()).sendSuccess(any(), anyString());
    }

    @Test
    @DisplayName("NC-403 on STATUS renders the super-admin guidance, not a bare error code")
    void statusForbiddenRendersSuperAdminGuidance() {
        givenPendingRequestForPlayer();
        AdminActionResponsePacket response = correlated(AdminActionResponsePacket.failure(
                AdminAction.STATUS, "NC-403", "Super admin authentication required for status"));

        handler.accept(response);
        runScheduledRegionTask();

        verify(messageHelper).sendError(player, "需要超级管理员会话才能执行此操作");
        verify(messageHelper).sendSuggestion(player,
                "请先执行 /nc auth <密码> 进行超级管理员认证");
    }

    @Test
    @DisplayName("NC-403 on a non-STATUS action keeps the generic error path")
    void nonStatusForbiddenKeepsGenericError() {
        givenPendingRequestForPlayer();
        AdminActionResponsePacket response = correlated(AdminActionResponsePacket.failure(
                AdminAction.AUTH, "NC-403", "Permission denied"));

        handler.accept(response);
        runScheduledRegionTask();

        verify(messageHelper, never()).sendSuggestion(any(), anyString());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendError(eq(player), sent.capture());
        assertThat(sent.getValue()).contains("[NC-403]").contains("Permission denied");
    }

    @Test
    @DisplayName("a console-originated success logs to the server console and never touches a player")
    void consoleSuccessLogs() {
        when(networkClient.consumePendingAdminRequest(REQUEST_ID))
                .thenReturn(UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID));
        AdminActionResponsePacket response = correlated(
                AdminActionResponsePacket.success(AdminAction.STATUS, "Announcement sent"));

        handler.accept(response);

        verify(logger).info("[NovaChat console] Announcement sent");
        verify(scheduler, never()).runForPlayer(any(Player.class), any(Runnable.class));
        verify(server, never()).getPlayer(any(UUID.class));
        verify(messageHelper, never()).sendSuccess(any(), anyString());
    }

    @Test
    @DisplayName("a console-originated failure logs the error code to the server console")
    void consoleFailureLogs() {
        when(networkClient.consumePendingAdminRequest(REQUEST_ID))
                .thenReturn(UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID));
        AdminActionResponsePacket response = correlated(AdminActionResponsePacket.failure(
                AdminAction.STATUS, NovaConstants.ERROR_BAD_REQUEST, "Channel ID is required"));

        handler.accept(response);

        ArgumentCaptor<String> logged = ArgumentCaptor.forClass(String.class);
        verify(logger).warning(logged.capture());
        assertThat(logged.getValue())
                .startsWith("[NovaChat console] ")
                .contains("[NC-400]")
                .contains("Channel ID is required");
    }

    @Test
    @DisplayName("a console-originated NC-403 STATUS logs the super-admin guidance")
    void consoleForbiddenLogsGuidance() {
        when(networkClient.consumePendingAdminRequest(REQUEST_ID))
                .thenReturn(UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID));
        AdminActionResponsePacket response = correlated(AdminActionResponsePacket.failure(
                AdminAction.STATUS, "NC-403", "Super admin authentication required for status"));

        handler.accept(response);

        verify(logger).warning("[NovaChat console] 需要超级管理员会话才能执行此操作");
        verify(logger).warning("[NovaChat console] 请先执行 /nc auth <密码> 进行超级管理员认证");
    }

    @Test
    @DisplayName("the guidance predicate is scoped to NC-403 on STATUS only")
    void guidanceRequiresStatusAction() {
        assertThat(AdminActionResponseHandler.isSuperAdminRequired(
                AdminActionResponsePacket.failure(AdminAction.RELOAD, "NC-403", "Permission denied")))
                .as("non-STATUS actions keep the generic error path")
                .isFalse();
        assertThat(AdminActionResponseHandler.isSuperAdminRequired(
                AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-401", "unauthenticated")))
                .as("only NC-403 is the super-admin gate")
                .isFalse();
        assertThat(AdminActionResponseHandler.isSuperAdminRequired(
                AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-403", "no session")))
                .isTrue();
        assertThat(AdminActionResponseHandler.isSuperAdminRequired(
                AdminActionResponsePacket.success(AdminAction.STATUS, "ok")))
                .as("a success never renders guidance")
                .isFalse();
    }

    @Test
    @DisplayName("a response with no matching pending request does not throw")
    void unmatchedResponseIsIgnored() {
        when(networkClient.consumePendingAdminRequest(REQUEST_ID)).thenReturn(null);
        AdminActionResponsePacket response = correlated(
                AdminActionResponsePacket.success(AdminAction.STATUS, "Announcement sent"));

        assertThatCode(() -> handler.accept(response)).doesNotThrowAnyException();

        verify(scheduler, never()).runForPlayer(any(Player.class), any(Runnable.class));
        verify(messageHelper, never()).sendSuccess(any(), anyString());
        verify(messageHelper, never()).sendError(any(), anyString());
        verify(logger, never()).info(anyString());
        verify(logger, never()).warning(anyString());
    }

    @Test
    @DisplayName("a pending request whose player logged off is dropped quietly")
    void offlinePlayerIsDropped() {
        givenPendingRequestForPlayer();
        doReturn(null).when(server).getPlayer(PLAYER_ID);
        AdminActionResponsePacket response = correlated(
                AdminActionResponsePacket.success(AdminAction.STATUS, "Announcement sent"));

        assertThatCode(() -> handler.accept(response)).doesNotThrowAnyException();

        verify(scheduler, never()).runForPlayer(any(Player.class), any(Runnable.class));
        verify(messageHelper, never()).sendSuccess(any(), anyString());
    }
}
