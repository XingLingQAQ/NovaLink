package com.nova.chat.mod.chat;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
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
 * Unit tests for the mod-common {@link ChatInterceptor}'s ItemDisplayPacket
 * handler.
 *
 * <p>Mirrors {@link ChatInterceptorTitleTest}'s infrastructure: the handler is
 * captured from the mocked {@link NetworkClient} registration and driven with
 * an {@link ItemDisplayPacket}. The mod {@link Platform} abstraction is
 * plain-string chat (no hover), so the assertions cover the channel filter and
 * the color-parsed line rendering.
 *
 * <p>The mod layer has no send side: {@link Platform} exposes no held-item
 * accessor, so only the receive side exists here.
 */
@DisplayName("Mod ChatInterceptor ItemDisplayPacket handler")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorItemDisplayTest {

    private static final UUID PLAYER = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID SENDER = UUID.fromString("cccccccc-1111-2222-3333-444444444444");

    @Mock
    private Platform platform;
    @Mock
    private NetworkClient networkClient;

    private final List<String> sentToPlayer = new ArrayList<>();

    private ChatInterceptor interceptor;
    private Consumer<ItemDisplayPacket> itemDisplayHandler;
    private Locale previousDefaultLocale;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        sentToPlayer.clear();
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

        ArgumentCaptor<Consumer<ItemDisplayPacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ItemDisplayPacket.class), captor.capture());
        itemDisplayHandler = captor.getValue();
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    @Test
    @DisplayName("matching channel: renders a color-parsed item line with name and count")
    void matchingChannelRendersItemLine() {
        itemDisplayHandler.accept(new ItemDisplayPacket(SENDER, "Alex", "global",
                "{\"id\":\"minecraft:netherite_sword\",\"count\":2}", System.currentTimeMillis()));

        assertThat(sentToPlayer).hasSize(1);
        assertThat(sentToPlayer.get(0))
                .contains("Alex")
                .contains("Netherite Sword")
                .contains("x2")
                .contains("\u00A7")
                .doesNotContain("&7");
    }

    @Test
    @DisplayName("empty hand payload renders the localized empty placeholder")
    void emptyHandRendersPlaceholder() {
        itemDisplayHandler.accept(new ItemDisplayPacket(SENDER, "Alex", "global",
                "{\"id\":\"minecraft:air\",\"count\":0}", System.currentTimeMillis()));

        assertThat(sentToPlayer).hasSize(1);
        assertThat(sentToPlayer.get(0)).contains("\u7A7A\u624B"); // 空手
    }

    @Test
    @DisplayName("non-matching channel: nothing is sent")
    void nonMatchingChannelSkipsPlayer() {
        itemDisplayHandler.accept(new ItemDisplayPacket(SENDER, "Alex", "trade",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis()));

        verify(platform, never()).sendMessage(eq(PLAYER), anyString());
        assertThat(sentToPlayer).isEmpty();
    }
}
