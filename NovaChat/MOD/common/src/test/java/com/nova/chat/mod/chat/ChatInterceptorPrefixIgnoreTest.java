package com.nova.chat.mod.chat;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.ignore.IgnoreListService;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.common.protocol.packets.MentionPacket;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.Platform;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the mod-common {@link ChatInterceptor}'s channel-prefix
 * routing (outbound, REPLACE mode) and ignore filtering (inbound chat +
 * mention + item display). Infrastructure mirrors {@link ChatInterceptorTest}:
 * the platform bridge is mocked and private inbound handlers are invoked via
 * reflection.
 */
@DisplayName("mod ChatInterceptor prefix routing + ignore filter")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorPrefixIgnoreTest {

    private static final UUID VIEWER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID IGNORED_SENDER = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final UUID OTHER_SENDER = UUID.fromString("77777777-8888-9999-aaaa-bbbbbbbbbbbb");

    @Mock
    private Platform platform;
    @Mock
    private NetworkClient networkClient;

    private KnownChannelRegistry registry;
    private IgnoreListService ignoreListService;
    private ChatInterceptor interceptor;
    private final List<String> sentToViewer = new ArrayList<>();
    private Locale previousDefaultLocale;

    @BeforeEach
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        registry = new KnownChannelRegistry();
        registry.replaceAll(Set.of("global", "local"));
        ignoreListService = new IgnoreListService();
        ignoreListService.ignore(VIEWER, "Viewer", "Steve");

        ModConfig config = new ModConfig();
        config.getChat().setReplaceVanilla(true); // REPLACE mode
        config.getChat().setDefaultChannel("local");
        config.getChat().setChannelPrefixes(Map.of("!", "global"));
        config.getBackend().setUsername("mod-server");

        when(networkClient.isAuthenticated()).thenReturn(true);
        when(platform.isPlayerOnline(VIEWER)).thenReturn(true);
        when(platform.getOnlinePlayerIds()).thenReturn(List.of(VIEWER));

        sentToViewer.clear();
        org.mockito.Mockito.doAnswer(inv -> {
            if (VIEWER.equals(inv.getArgument(0))) {
                sentToViewer.add((String) inv.getArgument(1));
            }
            return null;
        }).when(platform).sendMessage(any(), anyString());

        interceptor = new ChatInterceptor(platform, networkClient, config,
                new MessageFormatter(Map.of(), "{player}: {message}"),
                registry, ignoreListService);
        interceptor.getOrCreateState(VIEWER, "Viewer"); // active channel = "local"
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    private ChatMessagePacket sentChatPacket() {
        ArgumentCaptor<ChatMessagePacket> captor = ArgumentCaptor.forClass(ChatMessagePacket.class);
        verify(networkClient).sendPacket(captor.capture());
        return captor.getValue();
    }

    private void invokeHandler(String methodName, Class<?> packetType, Object packet) throws Exception {
        java.lang.reflect.Method m = ChatInterceptor.class.getDeclaredMethod(methodName, packetType);
        m.setAccessible(true);
        m.invoke(interceptor, packet);
    }

    // --- outbound prefix routing ---

    @Test
    @DisplayName("prefixed message is redirected to the mapped channel with the prefix stripped")
    void prefixRedirectsToMappedChannel() {
        interceptor.onPlayerChat(VIEWER, "Viewer", "!hello everyone");

        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getContent()).isEqualTo("hello everyone");
    }

    @Test
    @DisplayName("escaped prefix sends the literal message to the active channel")
    void escapedPrefixStaysInActiveChannel() {
        interceptor.onPlayerChat(VIEWER, "Viewer", "\\!hello");

        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("local");
        assertThat(packet.getContent()).isEqualTo("!hello");
    }

    @Test
    @DisplayName("prefix mapped to an unknown channel falls back to the active channel")
    void unknownChannelFallsBack() {
        registry.replaceAll(Set.of("local"));

        interceptor.onPlayerChat(VIEWER, "Viewer", "!hello");

        ChatMessagePacket packet = sentChatPacket();
        assertThat(packet.getChannelId()).isEqualTo("local");
        assertThat(packet.getContent()).isEqualTo("!hello");
    }

    // --- inbound ignore filtering ---

    @Test
    @DisplayName("inbound chat from an ignored sender is not rendered")
    void inboundChatFromIgnoredSenderSkipped() throws Exception {
        invokeHandler("handleIncomingMessage", ChatMessagePacket.class,
                new ChatMessagePacket(IGNORED_SENDER, "Steve", "other", "local", "hi"));

        assertThat(sentToViewer).isEmpty();
    }

    @Test
    @DisplayName("inbound chat from a non-ignored sender still renders")
    void inboundChatFromOtherSenderRenders() throws Exception {
        invokeHandler("handleIncomingMessage", ChatMessagePacket.class,
                new ChatMessagePacket(OTHER_SENDER, "Alex", "other", "local", "hi"));

        assertThat(sentToViewer).hasSize(1);
        assertThat(sentToViewer.get(0)).contains("Alex");
    }

    @Test
    @DisplayName("mention from an ignored player is not notified")
    void mentionFromIgnoredSenderSkipped() throws Exception {
        invokeHandler("handleMention", MentionPacket.class,
                new MentionPacket(IGNORED_SENDER, "Steve", VIEWER, "local",
                        "hi @Viewer", System.currentTimeMillis()));

        assertThat(sentToViewer).isEmpty();
    }

    @Test
    @DisplayName("mention from a non-ignored player still notifies")
    void mentionFromOtherSenderNotifies() throws Exception {
        invokeHandler("handleMention", MentionPacket.class,
                new MentionPacket(OTHER_SENDER, "Alex", VIEWER, "local",
                        "hi @Viewer", System.currentTimeMillis()));

        assertThat(sentToViewer).hasSize(1);
        assertThat(sentToViewer.get(0)).contains("Alex");
    }

    @Test
    @DisplayName("item display from an ignored sender is not rendered")
    void itemDisplayFromIgnoredSenderSkipped() throws Exception {
        invokeHandler("handleItemDisplay", ItemDisplayPacket.class,
                new ItemDisplayPacket(IGNORED_SENDER, "Steve", "local",
                        "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis()));

        assertThat(sentToViewer).isEmpty();
    }

    @Test
    @DisplayName("item display from a non-ignored sender still renders")
    void itemDisplayFromOtherSenderRenders() throws Exception {
        invokeHandler("handleItemDisplay", ItemDisplayPacket.class,
                new ItemDisplayPacket(OTHER_SENDER, "Alex", "local",
                        "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis()));

        assertThat(sentToViewer).hasSize(1);
        assertThat(sentToViewer.get(0)).contains("Alex");
    }
}
