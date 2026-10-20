package com.nova.chat.bukkit.network;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.command.BanCommand;
import com.nova.chat.bukkit.command.MessageHelper;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
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
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The end-to-end correlation the relay run exposed: a moderation command's
 * outcome must reach the operator who issued it.
 *
 * <p>Two things had to be true and neither was. The request packet must carry
 * {@code playerId} (else {@code ChannelResponseTracker.track} stores a null
 * player and {@code ChannelResponseDispatcher.handle} returns early), and the
 * dispatcher must route a successful moderation response somewhere (it had
 * branches for JOIN/LEAVE/WHO only, so an accepted mute rendered nothing even
 * with a correct pending context).
 *
 * <p>These tests drive the real {@link BanCommand} packet into the real
 * {@link CoreNetworkClient} tracker and then feed the response back through the
 * registered handler, so the whole chain is exercised rather than each half.
 */
@DisplayName("Bukkit moderation response reaches the issuing player")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NetworkClientModerationResponseTest {

    private static final UUID OPERATOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private NovaChatBukkit plugin;
    @Mock private NovaChatConfig config;
    @Mock private Server server;
    @Mock private BukkitScheduler scheduler;
    @Mock private Player operator;
    @Mock private MessageHelper messageHelper;
    @Mock private Logger logger;

    private CoreNetworkClient core;
    private NetworkClient client;
    private Locale previousLocale;
    private org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit;

    @BeforeEach
    void setUp() throws Exception {
        previousLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getServer()).thenReturn(server);
        when(server.getVersion()).thenReturn("test-server");
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.toClientConnectionConfig())
                .thenReturn(ClientConnectionConfig.builder().build());
        when(plugin.getMessageHelper()).thenReturn(messageHelper);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.getErrorHandler()).thenReturn(
                org.mockito.Mockito.mock(com.nova.chat.bukkit.error.ErrorMessageHandler.class));
        when(operator.getUniqueId()).thenReturn(OPERATOR_ID);
        when(operator.getName()).thenReturn("Mod");
        doReturn(operator).when(server).getPlayer(OPERATOR_ID);

        client = new NetworkClient(plugin, config, new KnownChannelRegistry());

        Field coreField = AbstractPlatformNetworkClient.class.getDeclaredField("core");
        coreField.setAccessible(true);
        core = (CoreNetworkClient) coreField.get(client);

        bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class);
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        I18n.setDefaultLocale(previousLocale);
    }

    /**
     * Sends a real {@code /nc ban} command through the plugin so the packet is
     * built by production code, then reads back the request id the backend
     * would echo. {@code NetworkClient} is a real object here (not a mock), so
     * the id comes from the command's own packet via the shared tracker.
     *
     * @return the request's id
     */
    private UUID sendBanRequest() {
        return runModerationCommand(operator);
    }

    /**
     * Runs a real moderation command against a spy of the live client, so the
     * packet is produced by production code and recorded by the real tracker.
     * The connection gates are stubbed true because there is no socket in a
     * unit test.
     *
     * @param sender the command sender
     * @return the request id the backend would echo
     */
    private UUID runModerationCommand(org.bukkit.command.CommandSender sender) {
        java.util.concurrent.atomic.AtomicReference<Packet> sent =
                new java.util.concurrent.atomic.AtomicReference<>();
        NetworkClient spy = org.mockito.Mockito.spy(client);
        when(spy.isConnected()).thenReturn(true);
        when(spy.isAuthenticated()).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            invocation.callRealMethod();
            return null;
        }).when(spy).sendPacket(any(Packet.class));
        when(plugin.getNetworkClient()).thenReturn(spy);

        new BanCommand(plugin).execute(sender, new String[]{"Bob", "1h", "global"});
        return sent.get().getRequestId();
    }

    /**
     * Runs every main-thread task the adapter scheduled. The adapter hops once
     * per callback, and the Success/Error paths hop exactly once each.
     */
    private void runScheduledTasks() {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).runTask(eq(plugin), tasks.capture());
        tasks.getAllValues().forEach(Runnable::run);
    }

    @Test
    @DisplayName("a success response renders a success line to the player who issued the ban")
    void successRendersToIssuer() {
        UUID requestId = sendBanRequest();

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.BAN, "global", "", "Player banned");
        response.setRequestId(requestId);
        core.handlePacket(response);
        runScheduledTasks();

        verify(messageHelper).sendSuccess(eq(operator), anyString());
        verify(messageHelper, never()).sendError(eq(operator), anyString());
    }

    @Test
    @DisplayName("a failed response renders the mapped error to the player who issued the ban")
    void failureRendersErrorToIssuer() {
        UUID requestId = sendBanRequest();

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, ChannelAction.BAN, "global", "NC-403", "Permission denied");
        response.setRequestId(requestId);
        core.handlePacket(response);
        runScheduledTasks();

        verify(messageHelper).sendError(eq(operator), contains("NC-403"));
        verify(messageHelper, never()).sendSuccess(eq(operator), anyString());
    }

    @Test
    @DisplayName("a mute success carries the localized confirmation, not a bare code")
    void muteSuccessCarriesLocalizedText() {
        ChannelActionPacket request = new ChannelActionPacket(ChannelAction.MUTE, "global");
        request.addExtra("playerId", OPERATOR_ID.toString());
        request.addExtra("targetName", "Bob");
        client.sendPacket(request);

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.MUTE, "global", "", "Player muted");
        response.setRequestId(request.getRequestId());
        core.handlePacket(response);
        runScheduledTasks();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendSuccess(eq(operator), text.capture());
        assertThat(text.getValue())
                .as("the operator sees a human confirmation in their own locale")
                .isEqualTo("禁言请求已处理");
    }


    @Test
    @DisplayName("an uncorrelated response (backend push) renders nothing to the operator")
    void uncorrelatedResponseRendersNothing() {
        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.MUTE, "global", "", "Player muted");
        response.setRequestId(UUID.randomUUID());

        core.handlePacket(response);

        // No pending entry means the dispatcher returns before any adapter
        // callback, so nothing is even scheduled.
        verify(scheduler, never()).runTask(any(), any(Runnable.class));
    }

    @Test
    @DisplayName("a console-issued moderation outcome is logged instead of dropped")
    void consoleOutcomeIsLogged() {
        org.bukkit.command.ConsoleCommandSender console =
                org.mockito.Mockito.mock(org.bukkit.command.ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        UUID requestId = runModerationCommand(console);

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.BAN, "global", "", "Player banned");
        response.setRequestId(requestId);
        core.handlePacket(response);
        runScheduledTasks();

        // BAN has no per-action copy; the shared generic key is what the
        // pre-refactor handler rendered for it too.
        verify(logger).info(contains("操作成功"));
    }
}
