package com.nova.chat.mod.chat;

import com.nova.chat.client.itemdisplay.ItemDisplayTokens;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Send-side tests for {@link ChatInterceptor#sendToChannel} gating the
 * {@code [item]}/{@code [i]} item-display play on
 * {@link ItemDisplayTokens#PERMISSION_ITEM} (Requirements 12.5: without
 * permission the token stays plain text and no {@link ItemDisplayPacket} is
 * emitted). Mirrors the bukkit/folia/nukkit/pnx {@code player.hasPermission(...)}
 * semantics — the mod {@link Platform#hasPermission(UUID, String)} abstraction
 * is the seam, with a permissive default that loaders override when they
 * bridge to a real permission registry.
 */
@DisplayName("Mod ChatInterceptor send-side [item] permission gate")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorItemDisplaySendTest {

    private static final UUID PLAYER = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final String ITEM_JSON = "{\"id\":\"minecraft:stone\",\"count\":1}";

    @Mock
    private Platform platform;
    @Mock
    private NetworkClient networkClient;

    private ChatInterceptor interceptor;

    @BeforeEach
    void setUp() {
        when(networkClient.isAuthenticated()).thenReturn(true);
        when(platform.getCurrentWorld(PLAYER)).thenReturn(null);
        when(platform.getOnlinePlayerIds()).thenReturn(List.of());

        ModConfig config = new ModConfig();
        config.getChat().setReplaceVanilla(false);
        config.getChat().setDefaultChannel("global");
        config.getBackend().setUsername("mod-server");

        interceptor = new ChatInterceptor(platform, networkClient, config,
                new MessageFormatter(Map.of(), "{player}: {message}"));
        interceptor.getOrCreateState(PLAYER, "Steve");
    }

    @Test
    @DisplayName("has novachat.feature.item: emits an ItemDisplayPacket after the chat packet")
    void withPermissionEmitsItemDisplay() {
        when(platform.hasPermission(PLAYER, ItemDisplayTokens.PERMISSION_ITEM)).thenReturn(true);
        when(platform.getHeldItemJson(PLAYER)).thenReturn(ITEM_JSON);

        interceptor.sendToChannel(PLAYER, "Steve", "global", "look at my [item]");

        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(networkClient, atLeastOnce()).sendPacket(captor.capture());
        List<Packet> sent = captor.getAllValues();
        assertThat(sent).anySatisfy(p -> assertThat(p).isInstanceOf(ChatMessagePacket.class));
        assertThat(sent).anySatisfy(p -> {
            assertThat(p).isInstanceOf(ItemDisplayPacket.class);
            ItemDisplayPacket idp = (ItemDisplayPacket) p;
            assertThat(idp.getSenderId()).isEqualTo(PLAYER);
            assertThat(idp.getSenderName()).isEqualTo("Steve");
            assertThat(idp.getChannelId()).isEqualTo("global");
            assertThat(idp.getItemJson()).isEqualTo(ITEM_JSON);
        });
    }

    @Test
    @DisplayName("without novachat.feature.item: token stays plain text, no ItemDisplayPacket (Requirements 12.5)")
    void withoutPermissionSkipsItemDisplay() {
        when(platform.hasPermission(PLAYER, ItemDisplayTokens.PERMISSION_ITEM)).thenReturn(false);
        when(platform.getHeldItemJson(PLAYER)).thenReturn(ITEM_JSON);

        interceptor.sendToChannel(PLAYER, "Steve", "global", "look at my [item]");

        // ChatMessagePacket still forwards; the side-channel ItemDisplayPacket is gated off.
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(networkClient, atLeastOnce()).sendPacket(captor.capture());
        List<Packet> sent = captor.getAllValues();
        assertThat(sent).anySatisfy(p -> assertThat(p).isInstanceOf(ChatMessagePacket.class));
        assertThat(sent).noneSatisfy(p -> assertThat(p).isInstanceOf(ItemDisplayPacket.class));
        // Additionally verify the item introspection was never consulted —
        // the gate runs before the platform's held-item resolver.
        verify(platform, never()).getHeldItemJson(any());
    }

    @Test
    @DisplayName("gate applies to the case-insensitive [i] short token too")
    void withoutPermissionSkipsCaseInsensitiveShortToken() {
        when(platform.hasPermission(PLAYER, ItemDisplayTokens.PERMISSION_ITEM)).thenReturn(false);
        when(platform.getHeldItemJson(PLAYER)).thenReturn(ITEM_JSON);

        interceptor.sendToChannel(PLAYER, "Steve", "global", "see [I]");

        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(networkClient, atLeastOnce()).sendPacket(captor.capture());
        assertThat(captor.getAllValues()).noneSatisfy(p -> assertThat(p).isInstanceOf(ItemDisplayPacket.class));
    }

    @Test
    @DisplayName("plain message without token does not consult the permission gate")
    void noTokenSkipsPermissionLookup() {
        interceptor.sendToChannel(PLAYER, "Steve", "global", "hello");

        verify(platform, never()).hasPermission(any(), anyString());
        verify(networkClient, never()).sendPacket(any(ItemDisplayPacket.class));
    }
}
