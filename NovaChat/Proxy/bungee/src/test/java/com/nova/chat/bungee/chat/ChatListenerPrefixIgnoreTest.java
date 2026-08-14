package com.nova.chat.bungee.chat;

import com.nova.chat.bungee.NovaChatBungee;
import com.nova.chat.bungee.config.NovaChatConfig;
import com.nova.chat.bungee.network.NetworkClient;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Bungee {@link ChatListener}'s channel-prefix routing
 * (outbound, REPLACE mode) and ignore filtering (inbound chat + mention +
 * item display). Infrastructure mirrors {@link ChatListenerItemDisplayTest}:
 * inbound handlers are captured from the mocked {@link NetworkClient}
 * registration.
 */
@DisplayName("Bungee ChatListener prefix routing + ignore filter")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatListenerPrefixIgnoreTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID IGNORED_SENDER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID OTHER_SENDER_ID = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb");

    @Mock
    private NovaChatBungee plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ChannelResponseTracker tracker;
    @Mock
    private ProxyServer proxy;
    @Mock
    private ProxiedPlayer player;
    @Mock
    private ChatEvent event;

    private KnownChannelRegistry registry;
    private IgnoreListService ignoreListService;
    private ChatListener listener;
    private Consumer<ChatMessagePacket> chatHandler;
    private Consumer<MentionPacket> mentionHandler;
    private Consumer<ItemDisplayPacket> itemDisplayHandler;
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

        when(plugin.getPluginConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(plugin.getKnownChannelRegistry()).thenReturn(registry);
        when(plugin.getIgnoreListService()).thenReturn(ignoreListService);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(networkClient.isAuthenticated()).thenReturn(true);
        when(plugin.getProxy()).thenReturn(proxy);
        when(config.getDefaultChannel()).thenReturn("local");
        when(config.isReplaceVanilla()).thenReturn(true); // REPLACE mode
        when(config.getUsername()).thenReturn("bungee-1");
        when(config.getChannelPrefixes()).thenReturn(Map.of("!", "global"));
        when(config.getChannelFormat(anyString())).thenReturn("{player}: {message}");
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Viewer");
        when(player.getDisplayName()).thenReturn("Viewer");
        doReturn(List.of(player)).when(proxy).getPlayers();
        when(proxy.getPlayer(PLAYER_ID)).thenReturn(player);

        when(event.getSender()).thenReturn(player);
        when(event.isCommand()).thenReturn(false);
        when(event.isProxyCommand()).thenReturn(false);

        listener = new ChatListener(plugin);
        listener.getOrCreateState(player); // active channel = "local"

        ArgumentCaptor<Consumer<ChatMessagePacket>> chatCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ChatMessagePacket.class), chatCaptor.capture());
        chatHandler = chatCaptor.getValue();

        ArgumentCaptor<Consumer<MentionPacket>> mentionCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(MentionPacket.class), mentionCaptor.capture());
        mentionHandler = mentionCaptor.getValue();

        ArgumentCaptor<Consumer<ItemDisplayPacket>> itemCaptor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ItemDisplayPacket.class), itemCaptor.capture());
        itemDisplayHandler = itemCaptor.getValue();
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

        listener.onPlayerChat(event);

        verify(event).setCancelled(true);
        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getContent()).isEqualTo("hello everyone");
    }

    @Test
    @DisplayName("escaped prefix sends the literal message to the active channel")
    void escapedPrefixStaysInActiveChannel() {
        when(event.getMessage()).thenReturn("\\!hello");

        listener.onPlayerChat(event);

        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("local");
        assertThat(packet.getContent()).isEqualTo("!hello");
    }

    @Test
    @DisplayName("prefix mapped to an unknown channel falls back to the active channel")
    void unknownChannelFallsBack() {
        registry.replaceAll(Set.of("local"));
        when(event.getMessage()).thenReturn("!hello");

        listener.onPlayerChat(event);

        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("local");
        assertThat(packet.getContent()).isEqualTo("!hello");
    }

    // --- inbound ignore filtering ---

    private ChatMessagePacket inboundChat(UUID senderId, String senderName) {
        return new ChatMessagePacket(senderId, senderName, "other-server", "local", "hi there");
    }

    @Test
    @DisplayName("inbound chat from an ignored sender is not rendered")
    void inboundChatFromIgnoredSenderSkipped() {
        chatHandler.accept(inboundChat(IGNORED_SENDER_ID, "Steve"));
        verify(player, never()).sendMessage(any(BaseComponent[].class));
    }

    @Test
    @DisplayName("inbound chat from a non-ignored sender still renders")
    void inboundChatFromOtherSenderRenders() {
        chatHandler.accept(inboundChat(OTHER_SENDER_ID, "Alex"));
        verify(player).sendMessage(any(BaseComponent[].class));
    }

    @Test
    @DisplayName("mention from an ignored player: no in-chat notification")
    void mentionFromIgnoredSenderSkipped() {
        mentionHandler.accept(new MentionPacket(IGNORED_SENDER_ID, "Steve", PLAYER_ID,
                "local", "hi @Viewer", System.currentTimeMillis()));
        verify(player, never()).sendMessage(any(BaseComponent[].class));
    }

    @Test
    @DisplayName("mention from a non-ignored player still notifies")
    void mentionFromOtherSenderNotifies() {
        mentionHandler.accept(new MentionPacket(OTHER_SENDER_ID, "Alex", PLAYER_ID,
                "local", "hi @Viewer", System.currentTimeMillis()));
        verify(player).sendMessage(any(BaseComponent[].class));
    }

    @Test
    @DisplayName("item display from an ignored sender is not rendered")
    void itemDisplayFromIgnoredSenderSkipped() {
        itemDisplayHandler.accept(new ItemDisplayPacket(IGNORED_SENDER_ID, "Steve", "local",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis()));
        verify(player, never()).sendMessage(any(BaseComponent.class));
    }

    @Test
    @DisplayName("item display from a non-ignored sender still renders")
    void itemDisplayFromOtherSenderRenders() {
        itemDisplayHandler.accept(new ItemDisplayPacket(OTHER_SENDER_ID, "Alex", "local",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis()));
        verify(player).sendMessage(any(BaseComponent.class));
    }
}
