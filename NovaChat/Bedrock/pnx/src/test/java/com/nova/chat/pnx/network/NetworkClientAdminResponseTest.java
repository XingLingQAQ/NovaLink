package com.nova.chat.pnx.network;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.plugin.PluginLogger;
import cn.nukkit.scheduler.ServerScheduler;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.AdminActionResponsePacket;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.chat.MessageFormatter;
import com.nova.chat.pnx.config.NovaChatConfig;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PNX {@link NetworkClient}'s admin-action correlation.
 *
 * <p>{@code /nc announce} and {@code /nc title} send an {@link AdminActionPacket}
 * and then report nothing until the backend answers. Without the correlation
 * this class adds, that answer is dropped on the floor with no feedback at all,
 * which is the exact shape of "silently broken" this test guards.
 *
 * <p>Infrastructure mirrors {@code NetworkClientIgnoreFilterTest}: a real
 * {@link NetworkClient} facade is built with mocked platform deps, the shared
 * {@link CoreNetworkClient} is reached via reflection and its public
 * {@code handlePacket} dispatch is driven directly; main-thread scheduler hops
 * run synchronously.
 */
@DisplayName("PNX NetworkClient admin-action response")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NetworkClientAdminResponseTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatPNX plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private Server server;
    @Mock
    private ServerScheduler scheduler;
    @Mock
    private Player player;
    @Mock
    private MessageFormatter formatter;
    @Mock
    private PluginLogger logger;

    private NetworkClient client;
    private CoreNetworkClient core;
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
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getMessageFormatter()).thenReturn(formatter);
        when(formatter.colorize(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(formatter.formatSuccess(anyString()))
                .thenAnswer(inv -> "S:" + inv.getArgument(0));
        when(formatter.formatError(anyString()))
                .thenAnswer(inv -> "E:" + inv.getArgument(0));
        when(player.getUniqueId()).thenReturn(PLAYER_ID);

        // Main-thread hops run inline.
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(scheduler).scheduleTask(eq(plugin), any(Runnable.class));

        client = new NetworkClient(plugin, config);
        Field coreField = AbstractPlatformNetworkClient.class.getDeclaredField("core");
        coreField.setAccessible(true);
        core = (CoreNetworkClient) coreField.get(client);
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    @Test
    @DisplayName("a sent AdminActionPacket is recorded so its response can be routed")
    void sentAdminActionIsTracked() {
        AdminActionPacket packet = AdminActionPacket.createAuthPacket(PLAYER_ID, "hash");

        client.sendPacket(packet);

        assertThat(client.pendingAdminRequestCount()).isEqualTo(1);
        assertThat(client.consumePendingAdminRequest(packet.getRequestId())).isEqualTo(PLAYER_ID);
    }

    @Test
    @DisplayName("consume removes the entry so one response renders once")
    void consumeRemovesEntry() {
        AdminActionPacket packet = AdminActionPacket.createAuthPacket(PLAYER_ID, "hash");
        client.sendPacket(packet);

        client.consumePendingAdminRequest(packet.getRequestId());

        assertThat(client.consumePendingAdminRequest(packet.getRequestId())).isNull();
        assertThat(client.pendingAdminRequestCount()).isZero();
    }

    @Test
    @DisplayName("a successful response is rendered to the issuing player")
    void successIsRenderedToPlayer() {
        AdminActionPacket request = AdminActionPacket.createAuthPacket(PLAYER_ID, "hash");
        client.sendPacket(request);
        when(server.getPlayer(PLAYER_ID)).thenReturn(Optional.of(player));

        AdminActionResponsePacket response =
                AdminActionResponsePacket.success(AdminAction.AUTH, "Authenticated");
        response.setRequestId(request.getRequestId());
        core.handlePacket(response);

        verify(player).sendMessage("S:Authenticated");
    }

    @Test
    @DisplayName("an NC-403 STATUS response renders the /nc auth guidance, not the raw code")
    void superAdminRequiredRendersGuidance() {
        AdminActionPacket request = new AdminActionPacket();
        request.setAction(AdminAction.STATUS);
        request.setPlayerId(PLAYER_ID);
        client.sendPacket(request);
        when(server.getPlayer(PLAYER_ID)).thenReturn(Optional.of(player));

        AdminActionResponsePacket response =
                AdminActionResponsePacket.failure(AdminAction.STATUS, "NC-403",
                        "Super admin required");
        response.setRequestId(request.getRequestId());
        core.handlePacket(response);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(player, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getAllValues())
                .as("the guidance must name /nc auth, not the bare NC-403 text")
                .anySatisfy(message -> assertThat(message).contains("超级管理员"))
                .anySatisfy(message -> assertThat(message).contains("/nc auth"));
    }

    @Test
    @DisplayName("an unsolicited response renders nothing")
    void unsolicitedResponseIsIgnored() {
        AdminActionResponsePacket response =
                AdminActionResponsePacket.success(AdminAction.AUTH, "Authenticated");

        core.handlePacket(response);

        verify(player, never()).sendMessage(anyString());
    }

    @Test
    @DisplayName("a console-sentinel response is logged, not sent to a player")
    void consoleResponseGoesToLog() {
        AdminActionPacket request = new AdminActionPacket();
        request.setAction(AdminAction.STATUS);
        request.setPlayerId(UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID));
        client.sendPacket(request);

        AdminActionResponsePacket response =
                AdminActionResponsePacket.success(AdminAction.STATUS, "Announcement sent");
        response.setRequestId(request.getRequestId());
        core.handlePacket(response);

        verify(logger).info("[NovaChat console] Announcement sent");
        verify(player, never()).sendMessage(anyString());
    }
}
