package com.nova.chat.bukkit.chat;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.config.NovaChatConfig;
import com.nova.chat.bukkit.network.NetworkClient;
import com.nova.chat.client.itemdisplay.ItemDisplayTokens;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
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
 * Unit tests for the Bukkit {@link ChatInterceptor}'s outbound
 * {@code [item]}/{@code [i]} token path (send side of the item display play).
 *
 * <p>Semantics under test, aligned with the Bedrock reference implementations:
 * case-insensitive token detection, the {@code novachat.feature.item}
 * permission gate, the shared per-player cooldown, the display-fields-only
 * item JSON (never full NBT), and the empty-hand air payload.
 */
@DisplayName("Bukkit ChatInterceptor [item]/[i] send side")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorItemDisplayTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private Server server;
    @Mock
    private Player player;
    @Mock
    private World world;
    @Mock
    private PlayerInventory inventory;
    @Mock
    private ItemStack mainHand;
    @Mock
    private ItemMeta meta;

    private ChatInterceptor interceptor;

    @BeforeEach
    void setUp() {
        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(plugin.getServer()).thenReturn(server);
        when(config.isReplaceVanilla()).thenReturn(false);
        when(config.getUsername()).thenReturn("bukkit-1");
        when(networkClient.isAuthenticated()).thenReturn(true);

        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Steve");
        when(player.getDisplayName()).thenReturn("Steve");
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(player.hasPermission(ItemDisplayTokens.PERMISSION_ITEM)).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(mainHand);
        when(mainHand.getType()).thenReturn(Material.NETHERITE_SWORD);
        when(mainHand.getAmount()).thenReturn(2);
        when(mainHand.getItemMeta()).thenReturn(null);

        interceptor = new ChatInterceptor(plugin);
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
    @DisplayName("[i] token sends an ItemDisplayPacket with display fields only, after the chat packet")
    void tokenSendsItemDisplayPacket() {
        interceptor.sendToChannel(player, "global", "look at this [i]");

        List<Packet> packets = sentPackets();
        assertThat(packets).hasSize(2);
        assertThat(packets.get(0)).isInstanceOf(ChatMessagePacket.class);
        assertThat(packets.get(1)).isInstanceOf(ItemDisplayPacket.class);

        ItemDisplayPacket display = (ItemDisplayPacket) packets.get(1);
        assertThat(display.getSenderId()).isEqualTo(PLAYER_ID);
        assertThat(display.getSenderName()).isEqualTo("Steve");
        assertThat(display.getChannelId()).isEqualTo("global");
        assertThat(display.getItemJson())
                .contains("\"id\":\"minecraft:netherite_sword\"")
                .contains("\"count\":2")
                .doesNotContain("nbt");
    }

    @Test
    @DisplayName("custom display name is carried in the item JSON")
    void customNameIsCarried() {
        when(mainHand.getItemMeta()).thenReturn(meta);
        when(meta.hasDisplayName()).thenReturn(true);
        when(meta.getDisplayName()).thenReturn("Excalibur");

        interceptor.sendToChannel(player, "global", "[item]");

        assertThat(sentItemDisplays()).hasSize(1);
        assertThat(sentItemDisplays().get(0).getItemJson()).contains("\"name\":\"Excalibur\"");
    }

    @Test
    @DisplayName("empty hand sends the air payload (Bedrock 'Empty' semantics)")
    void emptyHandSendsAirPayload() {
        when(inventory.getItemInMainHand()).thenReturn(null);

        interceptor.sendToChannel(player, "global", "[item]");

        List<ItemDisplayPacket> displays = sentItemDisplays();
        assertThat(displays).hasSize(1);
        assertThat(displays.get(0).getItemJson()).contains("\"id\":\"minecraft:air\"");
    }

    @Test
    @DisplayName("no token: only the chat packet is sent")
    void noTokenSendsChatOnly() {
        interceptor.sendToChannel(player, "global", "no token here [items]");

        List<Packet> packets = sentPackets();
        assertThat(packets).hasSize(1);
        assertThat(packets.get(0)).isInstanceOf(ChatMessagePacket.class);
    }

    @Test
    @DisplayName("without novachat.feature.item permission the token stays plain text")
    void withoutPermissionNoItemDisplay() {
        when(player.hasPermission(ItemDisplayTokens.PERMISSION_ITEM)).thenReturn(false);

        interceptor.sendToChannel(player, "global", "[i]");

        assertThat(sentItemDisplays()).isEmpty();
    }

    @Test
    @DisplayName("per-player cooldown suppresses a second display inside the window")
    void cooldownSuppressesSecondDisplay() {
        interceptor.sendToChannel(player, "global", "first [i]");
        interceptor.sendToChannel(player, "global", "second [i]");

        List<Packet> packets = sentPackets();
        long chatCount = packets.stream().filter(ChatMessagePacket.class::isInstance).count();
        assertThat(chatCount).isEqualTo(2); // chat itself is never suppressed
        assertThat(sentItemDisplays()).hasSize(1);
    }
}
