package com.nova.chat.bukkit.chat;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.api.event.ChannelMessageEvent;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginManager;
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

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the plugin-messaging delivery break: the plugin-message
 * callback runs on the server main thread, but {@link ChannelMessageEvent} is
 * declared async ({@code super(true)}). Paper refuses that combination with
 * {@code IllegalStateException: ChannelMessageEvent may only be triggered
 * asynchronously}, which aborted the whole downstream render.
 *
 * <p>The fix keeps the event async (third-party listeners are documented as
 * async listeners, and every existing call point already runs off-thread) and
 * hops off the main thread before firing. These tests pin both halves: the
 * event is never fired on the main thread, and the backend (Netty) path does
 * not gain an extra hop that would reorder messages.
 */
@DisplayName("Bukkit ChatInterceptor inbound event threading")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorInboundEventTest {

    private static final UUID SENDER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID VIEWER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private Server server;
    @Mock
    private BukkitScheduler scheduler;
    @Mock
    private PluginManager pluginManager;
    @Mock
    private Player viewer;

    private final Deque<Runnable> asyncTasks = new ArrayDeque<>();
    private final Deque<Runnable> mainTasks = new ArrayDeque<>();
    private ChatInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // This module runs JUnit per_class: the instance (and these queues)
        // outlive one test method, so they must be reset explicitly.
        asyncTasks.clear();
        mainTasks.clear();

        when(plugin.getServer()).thenReturn(server);
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getOnlinePlayers()).thenReturn((Collection) List.of(viewer));
        when(config.isReplaceVanilla()).thenReturn(false);

        when(viewer.getUniqueId()).thenReturn(VIEWER_ID);
        when(viewer.getName()).thenReturn("Viewer");

        // Both scheduler calls are captured, never executed: the test decides
        // which thread runs what. doAnswer rather than when(...) so the
        // per_class instance does not run the previous stub while re-stubbing.
        doAnswer(invocation -> {
            asyncTasks.add(invocation.getArgument(1));
            return null;
        }).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            mainTasks.add(invocation.getArgument(1));
            return null;
        }).when(scheduler).runTask(eq(plugin), any(Runnable.class));

        interceptor = new ChatInterceptor(plugin);
    }

    @Test
    @DisplayName("main-thread delivery hops off the thread before firing the async event")
    void mainThreadDeliveryIsHoppedOffThread() {
        when(server.isPrimaryThread()).thenReturn(true);

        interceptor.handleIncomingMessage(chat("from the proxy"), java.util.Set.of());

        verify(pluginManager, never()).callEvent(any(Event.class));
        assertThat(asyncTasks).hasSize(1);

        // The body only now fires the event, and the render hop is still queued
        // behind it (Bukkit API calls belong on the main thread).
        asyncTasks.poll().run();
        verify(pluginManager).callEvent(any(ChannelMessageEvent.class));
        assertThat(mainTasks).hasSize(1);
    }

    @Test
    @DisplayName("an already-async caller fires the event in place (no reordering hop)")
    void offThreadDeliveryFiresDirectly() {
        when(server.isPrimaryThread()).thenReturn(false);

        interceptor.handleIncomingMessage(chat("from the backend"), java.util.Set.of());

        verify(pluginManager).callEvent(any(ChannelMessageEvent.class));
        assertThat(asyncTasks).isEmpty();
    }

    @Test
    @DisplayName("a cancelled event renders nothing")
    void cancelledEventRendersNothing() {
        when(server.isPrimaryThread()).thenReturn(false);
        org.mockito.Mockito.doAnswer(invocation -> {
            ((ChannelMessageEvent) invocation.getArgument(0)).setCancelled(true);
            return null;
        }).when(pluginManager).callEvent(any(Event.class));

        interceptor.handleIncomingMessage(chat("drop me"), java.util.Set.of());

        assertThat(mainTasks).isEmpty();
    }

    @Test
    @DisplayName("the render hop skips players the proxy already served")
    void renderHopHonoursExcludedRecipients() {
        when(server.isPrimaryThread()).thenReturn(false);
        interceptor.handleIncomingMessage(chat("dedup"), java.util.Set.of(VIEWER_ID));

        assertThat(mainTasks).hasSize(1);
        mainTasks.poll().run();
        verify(viewer, never()).sendMessage(any(String.class));
    }

    @Test
    @DisplayName("the event carries the packet identity and content")
    void eventCarriesPacketFields() {
        when(server.isPrimaryThread()).thenReturn(false);
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);

        interceptor.handleIncomingMessage(chat("hello"), java.util.Set.of());

        verify(pluginManager).callEvent(captor.capture());
        ChannelMessageEvent event = (ChannelMessageEvent) captor.getValue();
        assertThat(event.getSenderId()).isEqualTo(SENDER_ID);
        assertThat(event.getSenderName()).isEqualTo("Alice");
        assertThat(event.getChannelId()).isEqualTo("global");
        assertThat(event.getMessage()).isEqualTo("hello");
        assertThat(event.isAsynchronous())
                .as("the event stays async; the caller is what hops off the main thread")
                .isTrue();
    }

    private static ChatMessagePacket chat(String content) {
        ChatMessagePacket packet = new ChatMessagePacket(SENDER_ID, "Alice", "survival-01",
                "global", content);
        packet.addPlaceholder("channel_name", "Global");
        return packet;
    }
}
