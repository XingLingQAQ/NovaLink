package com.nova.chat.mod.chat;

import com.nova.chat.common.protocol.packets.TitlePacket;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.Platform;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the mod-common {@link ChatInterceptor}'s TitlePacket handler.
 *
 * <p>The mod {@link Platform} abstraction has no title channel, so the handler
 * degrades to chat lines via {@link Platform#sendMessage} (same constraint as
 * the mention handler). These tests capture the handler registered on the
 * mocked {@link NetworkClient} and verify the channel filter plus the
 * color-parsed line rendering.
 */
@DisplayName("Mod ChatInterceptor TitlePacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorTitleTest {

    private static final UUID PLAYER = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID SENDER = UUID.fromString("cccccccc-1111-2222-3333-444444444444");

    @Mock
    private Platform platform;
    @Mock
    private NetworkClient networkClient;

    private final List<String> sentToPlayer = new ArrayList<>();

    private ChatInterceptor interceptor;
    private Consumer<TitlePacket> titleHandler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sentToPlayer.clear();
        // PLAT-001: NetworkClient wraps each handler with platform.execute(...).
        // Run the wrapped body synchronously so captured-handler assertions stay valid.
        doAnswer(inv -> {
            inv.<Runnable>getArgument(0).run();
            return null;
        }).when(platform).execute(any());
        doAnswer(inv -> {
            UUID id = inv.getArgument(0);
            if (id.equals(PLAYER)) {
                sentToPlayer.add(inv.getArgument(1));
            }
            return null;
        }).when(platform).sendMessage(any(), anyString());
        when(platform.getOnlinePlayerIds()).thenReturn(List.of(PLAYER));

        ModConfig config = new ModConfig();
        config.getChat().setReplaceVanilla(false);
        config.getChat().setDefaultChannel("global");
        config.getBackend().setUsername("mod-server");

        interceptor = new ChatInterceptor(platform, networkClient, config,
                new MessageFormatter(Map.of(), "{player}: {message}"));
        // Seed the player's state; the default active channel is "global".
        interceptor.getOrCreateState(PLAYER, "Steve");

        ArgumentCaptor<Consumer<TitlePacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(TitlePacket.class), captor.capture());
        titleHandler = captor.getValue();
    }

    @Test
    @DisplayName("matching channel: renders color-parsed title and subtitle as chat lines")
    void matchingChannelRendersTitleAndSubtitleLines() {
        titleHandler.accept(new TitlePacket("global", "&6Hello", "&7World", SENDER));

        assertThat(sentToPlayer).hasSize(2);
        assertThat(sentToPlayer.get(0)).contains("Hello").contains("\u00A76").doesNotContain("&6");
        assertThat(sentToPlayer.get(1)).contains("World").contains("\u00A77").doesNotContain("&7");
    }

    @Test
    @DisplayName("empty subtitle: only the title line is sent")
    void emptySubtitleSendsSingleLine() {
        titleHandler.accept(new TitlePacket("global", "&6Hello", "", SENDER));

        assertThat(sentToPlayer).hasSize(1);
        assertThat(sentToPlayer.get(0)).contains("Hello");
    }

    @Test
    @DisplayName("non-matching channel: nothing is sent")
    void nonMatchingChannelSkipsPlayer() {
        titleHandler.accept(new TitlePacket("trade", "&6Hello", "&7World", SENDER));

        verify(platform, never()).sendMessage(eq(PLAYER), anyString());
        assertThat(sentToPlayer).isEmpty();
    }

    @Test
    @DisplayName("blank title and subtitle: handler is a no-op")
    void blankTitleAndSubtitleIsNoOp() {
        TitlePacket packet = new TitlePacket();
        packet.setChannelId("global");

        titleHandler.accept(packet);

        verify(platform, never()).sendMessage(any(), anyString());
    }
}
