package com.nova.chat.bukkit.network;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.chat.ChatInterceptor;
import com.nova.chat.bukkit.command.MessageHelper;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.network.AbstractPlatformNetworkClient;
import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.client.network.CoreNetworkClient;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.ChannelAction;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bukkit's CREATE / INVITE / ACCEPT success render arms.
 *
 * <p>Drives a real {@link NetworkClient}, so the production dispatcher, tracker
 * and adapter all run: a {@link ChannelActionPacket} is tracked exactly as the
 * command's {@code sendPacket} would track it, then the echoed response is fed
 * through the registered handler and the scheduler tasks the adapter queued are
 * executed.
 *
 * <p>The load-bearing assertions are the channel id and password on CREATE and
 * the invite code on INVITE: the backend generates all three and nothing else
 * ever showed them to the player.
 */
@DisplayName("Bukkit CREATE/INVITE/ACCEPT success rendering")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BukkitCreateInviteAcceptRenderTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock private NovaChatBukkit plugin;
    @Mock private NovaChatConfig config;
    @Mock private Server server;
    @Mock private BukkitScheduler scheduler;
    @Mock private Player player;
    @Mock private MessageHelper messageHelper;

    private CoreNetworkClient core;
    private ChannelResponseTracker tracker;
    private ChatInterceptor interceptor;
    private Locale previousLocale;

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
        when(plugin.getErrorHandler()).thenReturn(
                mock(com.nova.chat.bukkit.error.ErrorMessageHandler.class));

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.isOnline()).thenReturn(true);
        doReturn(player).when(server).getPlayer(PLAYER_ID);
        doReturn(List.of(player)).when(server).getOnlinePlayers();

        // Real interceptor so adapter state mutations land somewhere observable.
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.getChannelPrefixes()).thenReturn(java.util.Map.of());
        when(config.isReplaceVanilla()).thenReturn(false);
        interceptor = new ChatInterceptor(plugin);
        when(plugin.getChatInterceptor()).thenReturn(interceptor);

        NetworkClient client = new NetworkClient(plugin, config, new KnownChannelRegistry());
        Field coreField = AbstractPlatformNetworkClient.class.getDeclaredField("core");
        coreField.setAccessible(true);
        core = (CoreNetworkClient) coreField.get(client);
        tracker = core.getChannelResponseTracker();
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousLocale);
    }

    /** Tracks a request the way the command's send path would. */
    private UUID track(ChannelAction action, String channelIdOrName) {
        ChannelActionPacket request = new ChannelActionPacket(action, channelIdOrName);
        request.addExtra("playerId", PLAYER_ID.toString());
        tracker.track(request);
        return request.getRequestId();
    }

    /** Runs every main-thread task the adapter queued. */
    private void runScheduledTasks() {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).runTask(eq(plugin), tasks.capture());
        tasks.getAllValues().forEach(Runnable::run);
    }

    @Test
    @DisplayName("a CREATE success renders the backend channel id and password to the creator")
    void createRendersIdAndPassword() {
        UUID requestId = track(ChannelAction.CREATE, "myroom");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.CREATE, "NC-1A2B", "", "Private channel created");
        response.setRequestId(requestId);
        response.addExtra("password", "hunter2");
        response.addExtra("passwordGenerated", "false");
        response.addExtra("displayName", "myroom");
        core.handlePacket(response);
        runScheduledTasks();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendMessage(eq(player), body.capture());
        assertThat(body.getValue())
                .as("the creator cannot discover the generated id any other way")
                .contains("NC-1A2B").contains("hunter2");
        // A caller-supplied password must not be announced as generated.
        verify(messageHelper, never()).sendSuggestion(eq(player), anyString());
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
        response.addExtra("displayName", "myroom");
        core.handlePacket(response);
        runScheduledTasks();

        verify(messageHelper).sendSuggestion(eq(player), eq("该密码为系统自动生成，请妥善保存"));
    }

    @Test
    @DisplayName("a CREATE success makes the new channel the creator active channel")
    void createSetsActiveChannel() {
        UUID requestId = track(ChannelAction.CREATE, "myroom");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.CREATE, "NC-1A2B", "", "Private channel created");
        response.setRequestId(requestId);
        response.addExtra("password", "hunter2");
        response.addExtra("passwordGenerated", "false");
        core.handlePacket(response);
        runScheduledTasks();

        assertThat(interceptor.getPlayerChannel(player))
                .as("the backend made the new channel active; the client must match")
                .isEqualTo("NC-1A2B");
        assertThat(interceptor.getState(PLAYER_ID).isJoined("NC-1A2B")).isTrue();
    }

    @Test
    @DisplayName("an INVITE success renders the backend invite code")
    void inviteRendersCode() {
        UUID requestId = track(ChannelAction.INVITE, "NC-1A2B");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.INVITE, "NC-1A2B", "", "Invitation created");
        response.setRequestId(requestId);
        response.addExtra("code", "a1b2c3");
        response.addExtra("expireTime",
                String.valueOf(System.currentTimeMillis() + 24L * 60 * 60 * 1000));
        core.handlePacket(response);
        runScheduledTasks();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendMessage(eq(player), body.capture());
        assertThat(body.getValue()).contains("A1B2C3");
        // The expiry rides as a follow-up suggestion line.
        ArgumentCaptor<String> hint = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendSuggestion(eq(player), hint.capture());
        assertThat(hint.getValue()).contains("有效期");
    }

    @Test
    @DisplayName("an ACCEPT success renders the channel the invite admitted the player to")
    void acceptRendersChannel() {
        UUID requestId = track(ChannelAction.ACCEPT, "A1B2C3");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                true, ChannelAction.ACCEPT, "NC-1A2B", "", "Invitation accepted");
        response.setRequestId(requestId);
        response.addExtra("code", "A1B2C3");
        core.handlePacket(response);
        runScheduledTasks();

        verify(messageHelper).sendSuccess(eq(player), eq("已通过邀请加入频道 &eNC-1A2B"));
        assertThat(interceptor.getPlayerChannel(player)).isEqualTo("NC-1A2B");
    }

    @Test
    @DisplayName("a refused CREATE renders an error and no success line")
    void refusedCreateRendersErrorOnly() {
        UUID requestId = track(ChannelAction.CREATE, "myroom");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, ChannelAction.CREATE, "myroom", "NC-400", "Channel name already taken");
        response.setRequestId(requestId);
        core.handlePacket(response);
        runScheduledTasks();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendError(eq(player), error.capture());
        assertThat(error.getValue()).contains("NC-400");
        verify(messageHelper, never()).sendMessage(eq(player), anyString());
        verify(messageHelper, never()).sendSuccess(eq(player), anyString());
    }

    @Test
    @DisplayName("a refused ACCEPT (banned) renders the error, not a joined line")
    void refusedAcceptRendersErrorOnly() {
        UUID requestId = track(ChannelAction.ACCEPT, "A1B2C3");

        ChannelActionResponsePacket response = new ChannelActionResponsePacket(
                false, ChannelAction.ACCEPT, "A1B2C3", "NC-403", "You are banned from this channel");
        response.setRequestId(requestId);
        core.handlePacket(response);
        runScheduledTasks();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(messageHelper).sendError(eq(player), error.capture());
        assertThat(error.getValue()).contains("NC-403");
        verify(messageHelper, never()).sendSuccess(eq(player), anyString());
    }
}
