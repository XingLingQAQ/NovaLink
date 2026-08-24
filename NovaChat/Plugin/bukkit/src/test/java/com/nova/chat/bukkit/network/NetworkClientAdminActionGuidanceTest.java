package com.nova.chat.bukkit.network;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.command.MessageHelper;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.bukkit.error.ErrorMessageHandler;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
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

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

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
 * FEATURE-003: when the backend rejects a STATUS (ANNOUNCE/TITLE) request
 * with NC-403 (no active super-admin session), the Bukkit
 * {@link NetworkClient#handleAdminActionResponse} surfaces a clear localized
 * "super-admin session required, run /nc auth" message instead of the generic
 * FORBIDDEN error text. Covers both the player and console branches.
 *
 * <p>The {@code handleAdminActionResponse} handler is registered on the shared
 * {@link CoreNetworkClient} in the facade constructor; we reach the core via
 * reflection (it is intentionally private) and drive its public
 * {@code handlePacket} dispatch, mirroring {@link NetworkClientItemDisplayTest}.
 */
@DisplayName("Bukkit NetworkClient admin-action NC-403 guidance (FEATURE-003)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NetworkClientAdminActionGuidanceTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID REQUEST_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private Server server;
    @Mock
    private BukkitScheduler scheduler;
    @Mock
    private Player player;
    @Mock
    private MessageHelper messageHelper;
    @Mock
    private ErrorMessageHandler errorHandler;

    private CoreNetworkClient core;
    private NetworkClient client;
    private Locale previousDefaultLocale;

    @BeforeEach
    void setUp() throws Exception {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getServer()).thenReturn(server);
        when(server.getVersion()).thenReturn("test-server");
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.toClientConnectionConfig())
                .thenReturn(ClientConnectionConfig.builder().build());
        when(plugin.getMessageHelper()).thenReturn(messageHelper);
        when(plugin.getErrorHandler()).thenReturn(errorHandler);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        doReturn(player).when(server).getPlayer(PLAYER_ID);

        client = new NetworkClient(plugin, config, new KnownChannelRegistry());

        Field coreField = AbstractPlatformNetworkClient.class.getDeclaredField("core");
        coreField.setAccessible(true);
        core = (CoreNetworkClient) coreField.get(client);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    /**
     * Seeds the bukkit-local {@code pendingAdminRequests} map so the handler
     * can route the async response back to the originating player. The handler
     * removes the entry on lookup, so this must be done per-test.
     */
    @SuppressWarnings("unchecked")
    private void seedPendingRequest(UUID playerId, UUID requestId) throws Exception {
        Field pendingField = NetworkClient.class.getDeclaredField("pendingAdminRequests");
        pendingField.setAccessible(true);
        ConcurrentMap<UUID, UUID> pending =
                (ConcurrentMap<UUID, UUID>) pendingField.get(client);
        pending.put(requestId, playerId);
    }

    private Runnable capturedMainThreadTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).runTask(eq(plugin), task.capture());
        return task.getValue();
    }

    @Test
    @DisplayName("NC-403 on STATUS surfaces the super-admin-required guidance, not the generic FORBIDDEN text")
    void statusForbiddenSurfacesSuperAdminGuidance() throws Exception {
        seedPendingRequest(PLAYER_ID, REQUEST_ID);
        AdminActionResponsePacket response = AdminActionResponsePacket.failure(
                AdminAction.STATUS, "NC-403", "Super admin authentication required for status");
        response.setRequestId(REQUEST_ID);

        core.handlePacket(response);
        capturedMainThreadTask().run();

        // Guidance path: localized super-admin-required error + suggestion.
        verify(messageHelper).sendError(eq(player), eq("需要超级管理员会话才能执行此操作"));
        verify(messageHelper).sendSuggestion(eq(player),
                eq("请先执行 /nc auth <密码> 进行超级管理员认证"));
        // Generic NC-403 error-from-code path is bypassed.
        verify(errorHandler, never()).sendErrorFromCode(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("NC-403 on a non-STATUS admin action still uses the generic error path")
    void nonStatusForbiddenFallsThroughToGeneric() throws Exception {
        seedPendingRequest(PLAYER_ID, REQUEST_ID);
        AdminActionResponsePacket response = AdminActionResponsePacket.failure(
                AdminAction.RELOAD, "NC-403", "Permission denied");
        response.setRequestId(REQUEST_ID);

        core.handlePacket(response);
        capturedMainThreadTask().run();

        // No super-admin guidance for non-STATUS actions.
        verify(messageHelper, never()).sendError(eq(player), anyString());
        verify(messageHelper, never()).sendSuggestion(eq(player), anyString());
        // Generic error-from-code path runs.
        verify(errorHandler).sendErrorFromCode(eq(player), eq("NC-403"), eq("Permission denied"));
    }

    @Test
    @DisplayName("NC-401 on STATUS still uses the generic error path (only NC-403 is the super-admin gate)")
    void statusUnauthorizedFallsThroughToGeneric() throws Exception {
        seedPendingRequest(PLAYER_ID, REQUEST_ID);
        AdminActionResponsePacket response = AdminActionResponsePacket.failure(
                AdminAction.STATUS, "NC-401", "Authentication failed");
        response.setRequestId(REQUEST_ID);

        core.handlePacket(response);
        capturedMainThreadTask().run();

        verify(messageHelper, never()).sendError(eq(player), anyString());
        verify(messageHelper, never()).sendSuggestion(eq(player), anyString());
        verify(errorHandler).sendErrorFromCode(eq(player), eq("NC-401"), eq("Authentication failed"));
    }

    @Test
    @DisplayName("successful STATUS response does not trigger the super-admin guidance")
    void statusSuccessSkipsGuidance() throws Exception {
        seedPendingRequest(PLAYER_ID, REQUEST_ID);
        AdminActionResponsePacket response = AdminActionResponsePacket.success(
                AdminAction.STATUS, "Announcement sent");
        response.setRequestId(REQUEST_ID);

        core.handlePacket(response);
        capturedMainThreadTask().run();

        verify(messageHelper, never()).sendError(eq(player), anyString());
        verify(messageHelper, never()).sendSuggestion(eq(player), anyString());
        verify(messageHelper).sendSuccess(eq(player), eq("Announcement sent"));
    }
}
