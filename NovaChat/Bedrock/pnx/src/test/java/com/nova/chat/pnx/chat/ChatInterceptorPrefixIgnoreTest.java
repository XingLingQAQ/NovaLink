package com.nova.chat.pnx.chat;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.level.Level;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.config.NovaChatConfig;
import com.nova.chat.pnx.network.NetworkClient;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PNX {@link ChatInterceptor}'s channel-prefix routing
 * (outbound, REPLACE mode) and inbound chat ignore filtering
 * ({@code displayIncomingMessage} -> {@code sendToPlayersFiltered}).
 * Infrastructure mirrors {@link ChatInterceptorTest}: platform API is mocked
 * with Mockito, the interceptor is real.
 */
@DisplayName("PNX ChatInterceptor prefix routing + ignore filter")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorPrefixIgnoreTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatPNX plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private MessageFormatter messageFormatter;
    @Mock
    private Server server;
    @Mock
    private Player player;
    @Mock
    private Level level;
    @Mock
    private PlayerChatEvent event;

    private KnownChannelRegistry registry;
    private IgnoreListService ignoreListService;
    private ChatInterceptor interceptor;
    private Locale previousDefaultLocale;

    @BeforeEach
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        registry = new KnownChannelRegistry();
        registry.replaceAll(Set.of("global", "local"));
        ignoreListService = new IgnoreListService();
        ignoreListService.ignore(PLAYER_ID, "Viewer", "Steve");

        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(plugin.getKnownChannelRegistry()).thenReturn(registry);
        when(plugin.getIgnoreListService()).thenReturn(ignoreListService);
        when(plugin.getMessageFormatter()).thenReturn(messageFormatter);
        when(plugin.getServer()).thenReturn(server);
        when(messageFormatter.formatIncomingMessage(anyString(), anyString(), anyString()))
                .thenReturn("formatted-line");
        when(networkClient.isConnected()).thenReturn(true);
        when(networkClient.isAuthenticated()).thenReturn(true);
        when(config.isReplaceVanilla()).thenReturn(true); // REPLACE mode
        when(config.getDefaultChannel()).thenReturn("local");
        when(config.getBackendUsername()).thenReturn("pnx-1");
        when(config.getChannelPrefixes()).thenReturn(Map.of("!", "global"));
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Viewer");
        when(player.getDisplayName()).thenReturn("Viewer");
        when(player.getLevel()).thenReturn(level);
        when(level.getName()).thenReturn("world");
        doReturn(Map.of(PLAYER_ID, player)).when(server).getOnlinePlayers();

        when(event.isCancelled()).thenReturn(false);
        when(event.getPlayer()).thenReturn(player);

        interceptor = new ChatInterceptor(plugin);
        interceptor.getOrCreateState(player); // active channel = "local"
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

    // --- inbound chat ignore filtering ---

    @Test
    @DisplayName("inbound chat from an ignored sender is not rendered")
    void inboundChatFromIgnoredSenderSkipped() {
        interceptor.displayIncomingMessage("Steve", "local", "hi there");

        verify(player, never()).sendMessage(anyString());
    }

    @Test
    @DisplayName("inbound chat from a non-ignored sender still renders")
    void inboundChatFromOtherSenderRenders() {
        interceptor.displayIncomingMessage("Alex", "local", "hi there");

        verify(player).sendMessage("formatted-line");
    }
}
