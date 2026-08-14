package com.nova.chat.folia.chat;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.network.AsyncNetworkClient;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Folia {@link AsyncChatInterceptor}'s channel-prefix
 * routing (outbound, REPLACE mode) and ignore filtering (inbound chat +
 * mention). Infrastructure mirrors {@link AsyncChatInterceptorItemDisplayTest}:
 * inbound handlers are captured from the mocked {@link AsyncNetworkClient}
 * registration and the {@link FoliaSchedulerAdapter#runForPlayer} region hop
 * is asserted before rendering.
 */
@DisplayName("Folia AsyncChatInterceptor prefix routing + ignore filter")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncChatInterceptorPrefixIgnoreTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID IGNORED_SENDER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID OTHER_SENDER_ID = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb");

    @Mock
    private NovaChatFolia plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private FoliaSchedulerAdapter scheduler;
    @Mock
    private AsyncNetworkClient networkClient;
    @Mock
    private ChannelResponseTracker tracker;
    @Mock
    private Server server;
    @Mock
    private PluginManager pluginManager;
    @Mock
    private Player player;
    @Mock
    private World world;
    @Mock
    private AsyncPlayerChatEvent event;

    private KnownChannelRegistry registry;
    private IgnoreListService ignoreListService;
    private AsyncChatInterceptor interceptor;
    private Consumer<ChatMessagePacket> chatHandler;
    private Consumer<MentionPacket> mentionHandler;
    private Locale previousDefaultLocale;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        registry = new KnownChannelRegistry();
        registry.replaceAll(Set.of("global", "local"));
        ignoreListService = new IgnoreListService();
        ignoreListService.ignore(PLAYER_ID, "Viewer", "Steve");

        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(plugin.getKnownChannelRegistry()).thenReturn(registry);
        when(plugin.getIgnoreListService()).thenReturn(ignoreListService);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(networkClient.isAuthenticated()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);
        when(server.getPlayer(PLAYER_ID)).thenReturn(player);
        when(config.getDefaultChannel()).thenReturn("local");
        when(config.isReplaceVanilla()).thenReturn(true); // REPLACE mode
        when(config.getUsername()).thenReturn("folia-1");
        when(config.getChannelPrefixes()).thenReturn(Map.of("!", "global"));
        when(config.getChannelFormat(anyString())).thenReturn("{player}: {message}");
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Viewer");
        when(player.getDisplayName()).thenReturn("Viewer");
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        doReturn(List.of(player)).when(server).getOnlinePlayers();

        when(event.getPlayer()).thenReturn(player);

        interceptor = new AsyncChatInterceptor(plugin);
        interceptor.getOrCreateState(player); // active channel = "local"

        ArgumentCaptor<Consumer<ChatMessagePacket>> chatCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ChatMessagePacket.class), chatCaptor.capture());
        chatHandler = chatCaptor.getValue();

        ArgumentCaptor<Consumer<MentionPacket>> mentionCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(MentionPacket.class), mentionCaptor.capture());
        mentionHandler = mentionCaptor.getValue();
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    private ChatMessagePacket sentChatPacket() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(networkClient, atLeastOnce()).sendPacket(captor.capture());
        return captor.getAllValues().stream()
                .filter(ChatMessagePacket.class::isInstance)
                .map(ChatMessagePacket.class::cast)
                .findFirst().orElseThrow();
    }

    // --- outbound prefix routing ---

    @Test
    @DisplayName("prefixed message is redirected to the mapped channel with the prefix stripped")
    void prefixRedirectsToMappedChannel() {
        when(event.getMessage()).thenReturn("!hello everyone");

        interceptor.onPlayerChat(event);

        verify(event).setCancelled(true);
        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getContent()).isEqualTo("hello everyone");
    }

    @Test
    @DisplayName("escaped prefix sends the literal message to the active channel")
    void escapedPrefixStaysInActiveChannel() {
        when(event.getMessage()).thenReturn("\\!hello");

        interceptor.onPlayerChat(event);

        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("local");
        assertThat(packet.getContent()).isEqualTo("!hello");
    }

    @Test
    @DisplayName("prefix mapped to an unknown channel falls back to the active channel")
    void unknownChannelFallsBack() {
        registry.replaceAll(Set.of("local"));
        when(event.getMessage()).thenReturn("!hello");

        interceptor.onPlayerChat(event);

        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("local");
        assertThat(packet.getContent()).isEqualTo("!hello");
    }

    // --- inbound ignore filtering ---

    private ChatMessagePacket inboundChat(UUID senderId, String senderName) {
        return new ChatMessagePacket(senderId, senderName, "other-server", "local", "hi there");
    }

    @Test
    @DisplayName("inbound chat from an ignored sender is not rendered (no region hop)")
    void inboundChatFromIgnoredSenderSkipped() {
        chatHandler.accept(inboundChat(IGNORED_SENDER_ID, "Steve"));

        verify(scheduler, never()).runForPlayer(any(Player.class), any(Runnable.class));
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    @DisplayName("inbound chat from a non-ignored sender still renders")
    void inboundChatFromOtherSenderRenders() {
        chatHandler.accept(inboundChat(OTHER_SENDER_ID, "Alex"));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runForPlayer(eq(player), task.capture());
        task.getValue().run();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(player).sendMessage(message.capture());
        assertThat(message.getValue()).contains("Alex").contains("hi there");
    }

    @Test
    @DisplayName("mention from an ignored player: no region hop, no title")
    void mentionFromIgnoredSenderSkipped() {
        mentionHandler.accept(new MentionPacket(IGNORED_SENDER_ID, "Steve", PLAYER_ID,
                "local", "hi @Viewer", System.currentTimeMillis()));

        verify(scheduler, never()).runForPlayer(any(Player.class), any(Runnable.class));
        verify(player, never()).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("mention from a non-ignored player still notifies")
    void mentionFromOtherSenderNotifies() {
        mentionHandler.accept(new MentionPacket(OTHER_SENDER_ID, "Alex", PLAYER_ID,
                "local", "hi @Viewer", System.currentTimeMillis()));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runForPlayer(eq(player), task.capture());
        try {
            task.getValue().run();
        } catch (Throwable soundRegistryUnavailable) {
            // org.bukkit.Sound's static init needs a live server registry which
            // does not exist in unit tests; the title send asserted below
            // happens before the sound call, so the assertion is still valid.
        }

        verify(player).sendTitle(anyString(), anyString(), anyInt(), anyInt(), anyInt());
    }
}
