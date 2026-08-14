package com.nova.chat.pnx.chat;

import cn.nukkit.Player;
import cn.nukkit.event.player.PlayerChatEvent;
import cn.nukkit.inventory.PlayerInventory;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import com.nova.chat.client.itemdisplay.ItemDisplayTokens;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.config.NovaChatConfig;
import com.nova.chat.pnx.network.NetworkClient;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PNX {@link ChatInterceptor}'s outbound
 * {@code [item]}/{@code [i]} token path (send side of the item display play).
 *
 * <p>Driven through {@code onPlayerChat} in REPLACE mode (the interceptor's
 * outbound path, mirroring {@link ChatInterceptorTest}'s forwarding tests).
 * Semantics under test, aligned with the Bedrock references (pmmp/endstone):
 * case-insensitive token detection, the {@code novachat.feature.item}
 * permission gate, the shared per-player cooldown, the display-fields-only
 * item JSON (never full NBT), and the empty-hand air payload.
 */
@DisplayName("PNX ChatInterceptor [item]/[i] send side")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorItemDisplayTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatPNX plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private Player player;
    @Mock
    private Level level;
    @Mock
    private PlayerInventory inventory;
    @Mock
    private Item mainHand;
    @Mock
    private PlayerChatEvent chatEvent;

    private ChatInterceptor interceptor;

    @BeforeEach
    void setUp() {
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        // REPLACE mode so onPlayerChat forwards through the outbound path.
        when(config.isReplaceVanilla()).thenReturn(true);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.getBackendUsername()).thenReturn("pnx-server");
        when(networkClient.isConnected()).thenReturn(true);
        when(networkClient.isAuthenticated()).thenReturn(true);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Steve");
        when(player.getDisplayName()).thenReturn("Steve");
        when(player.getLevel()).thenReturn(level);
        when(level.getName()).thenReturn("world");
        when(player.hasPermission(ItemDisplayTokens.PERMISSION_ITEM)).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInHand()).thenReturn(mainHand);
        when(mainHand.isNull()).thenReturn(false);
        when(mainHand.getId()).thenReturn(743);
        when(mainHand.getCount()).thenReturn(2);
        when(mainHand.getName()).thenReturn("Netherite Sword");
        when(mainHand.hasCustomName()).thenReturn(false);

        when(chatEvent.isCancelled()).thenReturn(false);
        when(chatEvent.getPlayer()).thenReturn(player);

        interceptor = new ChatInterceptor(plugin);
    }

    private void chat(String message) {
        when(chatEvent.getMessage()).thenReturn(message);
        interceptor.onPlayerChat(chatEvent);
    }

    private List<Packet> sentPackets() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(networkClient, atLeastOnce()).sendPacket(captor.capture());
        return captor.getAllValues();
    }

    private List<ItemDisplayPacket> sentItemDisplays() {
        return sentPackets().stream()
                .filter(ItemDisplayPacket.class::isInstance)
                .map(ItemDisplayPacket.class::cast)
                .toList();
    }

    @Test
    @DisplayName("[i] token sends an ItemDisplayPacket with a display-name-derived id, after the chat packet")
    void tokenSendsItemDisplayPacket() {
        chat("look at this [i]");

        List<Packet> packets = sentPackets();
        assertThat(packets).hasSize(2);
        assertThat(packets.get(0)).isInstanceOf(ChatMessagePacket.class);
        assertThat(packets.get(1)).isInstanceOf(ItemDisplayPacket.class);

        ItemDisplayPacket display = (ItemDisplayPacket) packets.get(1);
        assertThat(display.getSenderId()).isEqualTo(PLAYER_ID);
        assertThat(display.getSenderName()).isEqualTo("Steve");
        assertThat(display.getChannelId()).isEqualTo("global");
        // PNX exposes no namespaced id; it is derived from the display name.
        assertThat(display.getItemJson())
                .contains("\"id\":\"minecraft:netherite_sword\"")
                .contains("\"count\":2")
                .doesNotContain("nbt");
    }

    @Test
    @DisplayName("custom display name is carried in the item JSON")
    void customNameIsCarried() {
        when(mainHand.hasCustomName()).thenReturn(true);
        when(mainHand.getCustomName()).thenReturn("Excalibur");

        chat("[item]");

        assertThat(sentItemDisplays()).hasSize(1);
        assertThat(sentItemDisplays().get(0).getItemJson()).contains("\"name\":\"Excalibur\"");
    }

    @Test
    @DisplayName("empty hand sends the air payload (Bedrock 'Empty' semantics)")
    void emptyHandSendsAirPayload() {
        when(mainHand.isNull()).thenReturn(true);

        chat("[item]");

        List<ItemDisplayPacket> displays = sentItemDisplays();
        assertThat(displays).hasSize(1);
        assertThat(displays.get(0).getItemJson()).contains("\"id\":\"minecraft:air\"");
    }

    @Test
    @DisplayName("no token: only the chat packet is sent")
    void noTokenSendsChatOnly() {
        chat("no token here [items]");

        List<Packet> packets = sentPackets();
        assertThat(packets).hasSize(1);
        assertThat(packets.get(0)).isInstanceOf(ChatMessagePacket.class);
    }

    @Test
    @DisplayName("without novachat.feature.item permission the token stays plain text")
    void withoutPermissionNoItemDisplay() {
        when(player.hasPermission(ItemDisplayTokens.PERMISSION_ITEM)).thenReturn(false);

        chat("[i]");

        assertThat(sentItemDisplays()).isEmpty();
    }

    @Test
    @DisplayName("per-player cooldown suppresses a second display inside the window")
    void cooldownSuppressesSecondDisplay() {
        chat("first [i]");
        chat("second [i]");

        List<Packet> packets = sentPackets();
        long chatCount = packets.stream().filter(ChatMessagePacket.class::isInstance).count();
        assertThat(chatCount).isEqualTo(2); // chat itself is never suppressed
        assertThat(sentItemDisplays()).hasSize(1);
    }
}
