package com.nova.chat.nukkit.chat;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.inventory.PlayerInventory;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.scheduler.ServerScheduler;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.itemdisplay.ItemDisplayTokens;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.nukkit.NovaChatNukkit;
import com.nova.chat.nukkit.config.NovaChatConfig;
import com.nova.chat.nukkit.network.NetworkClient;
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
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Nukkit {@link ChatInterceptor}'s item display play:
 * the inbound {@code ItemDisplayPacket} handler (plain color-coded chat line,
 * Bedrock has no hover) and the outbound {@code [item]}/{@code [i]} token path.
 *
 * <p>Mirrors {@link ChatInterceptorTitleTest}'s infrastructure: the handler is
 * captured from the mocked {@link NetworkClient} registration, driven with a
 * packet, and the Nukkit main-thread scheduler hop is asserted before the
 * rendering.
 */
@DisplayName("Nukkit ChatInterceptor item display (receive + send)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatInterceptorItemDisplayTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SENDER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Mock
    private NovaChatNukkit plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ChannelResponseTracker tracker;
    @Mock
    private Server server;
    @Mock
    private ServerScheduler scheduler;
    @Mock
    private Player player;
    @Mock
    private Level level;
    @Mock
    private PlayerInventory inventory;
    @Mock
    private Item mainHand;

    private ChatInterceptor interceptor;
    private Consumer<ItemDisplayPacket> itemDisplayHandler;
    private Locale previousDefaultLocale;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(networkClient.isAuthenticated()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(false);
        when(config.getUsername()).thenReturn("nukkit-1");
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
        doReturn(Map.of(PLAYER_ID, player)).when(server).getOnlinePlayers();

        interceptor = new ChatInterceptor(plugin);
        // Seed the player's state; the default active channel is "global".
        interceptor.getOrCreateState(player);

        ArgumentCaptor<Consumer<ItemDisplayPacket>> captor =
                ArgumentCaptor.forClass((Class) Consumer.class);
        verify(networkClient).registerHandler(eq(ItemDisplayPacket.class), captor.capture());
        itemDisplayHandler = captor.getValue();
    }

    @AfterEach
    void tearDown() {
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    // --- receive side ---

    @Test
    @DisplayName("matching channel: hops to the main thread and sends a color-coded item line")
    void matchingChannelSendsLineViaSchedulerHop() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:netherite_sword\",\"count\":2}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleTask(eq(plugin), task.capture());
        verify(player, never()).sendMessage(anyString());

        task.getValue().run();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(player).sendMessage(sent.capture());
        // & codes are translated to § by the real MessageFormatter.
        assertThat(sent.getValue())
                .contains("Alex")
                .contains("Netherite Sword")
                .contains("x2")
                .contains("\u00A7");
    }

    @Test
    @DisplayName("empty hand payload renders the localized empty placeholder")
    void emptyHandRendersPlaceholder() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:air\",\"count\":0}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleTask(eq(plugin), task.capture());
        task.getValue().run();

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(player).sendMessage(sent.capture());
        assertThat(sent.getValue()).contains("\u7A7A\u624B"); // 空手
    }

    @Test
    @DisplayName("non-matching channel: scheduler hop happens but nothing is rendered")
    void nonMatchingChannelSkipsPlayer() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "trade",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleTask(eq(plugin), task.capture());
        task.getValue().run();

        verify(player, never()).sendMessage(anyString());
    }

    // --- send side ---

    private List<ItemDisplayPacket> sentItemDisplays() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(networkClient, atLeastOnce()).sendPacket(captor.capture());
        return captor.getAllValues().stream()
                .filter(ItemDisplayPacket.class::isInstance)
                .map(ItemDisplayPacket.class::cast)
                .toList();
    }

    @Test
    @DisplayName("[i] token sends an ItemDisplayPacket with a display-name-derived id")
    void tokenSendsItemDisplayPacket() {
        interceptor.sendToChannel(player, "global", "look at this [i]");

        List<ItemDisplayPacket> displays = sentItemDisplays();
        assertThat(displays).hasSize(1);
        ItemDisplayPacket display = displays.get(0);
        assertThat(display.getSenderId()).isEqualTo(PLAYER_ID);
        assertThat(display.getSenderName()).isEqualTo("Steve");
        assertThat(display.getChannelId()).isEqualTo("global");
        // Nukkit has no namespaced id; it is derived from the display name.
        assertThat(display.getItemJson())
                .contains("\"id\":\"minecraft:netherite_sword\"")
                .contains("\"count\":2");
    }

    @Test
    @DisplayName("custom display name is carried in the item JSON")
    void customNameIsCarried() {
        when(mainHand.hasCustomName()).thenReturn(true);
        when(mainHand.getCustomName()).thenReturn("Excalibur");

        interceptor.sendToChannel(player, "global", "[item]");

        assertThat(sentItemDisplays()).hasSize(1);
        assertThat(sentItemDisplays().get(0).getItemJson()).contains("\"name\":\"Excalibur\"");
    }

    @Test
    @DisplayName("empty hand sends the air payload (Bedrock 'Empty' semantics)")
    void emptyHandSendsAirPayload() {
        when(mainHand.isNull()).thenReturn(true);

        interceptor.sendToChannel(player, "global", "[item]");

        List<ItemDisplayPacket> displays = sentItemDisplays();
        assertThat(displays).hasSize(1);
        assertThat(displays.get(0).getItemJson()).contains("\"id\":\"minecraft:air\"");
    }

    @Test
    @DisplayName("no token / no permission: only the chat packet is sent")
    void noTokenOrNoPermissionSendsChatOnly() {
        interceptor.sendToChannel(player, "global", "no token here [items]");
        assertThat(sentItemDisplays()).isEmpty();

        when(player.hasPermission(ItemDisplayTokens.PERMISSION_ITEM)).thenReturn(false);
        interceptor.sendToChannel(player, "global", "[i]");
        assertThat(sentItemDisplays()).isEmpty();
    }

    @Test
    @DisplayName("per-player cooldown suppresses a second display inside the window")
    void cooldownSuppressesSecondDisplay() {
        interceptor.sendToChannel(player, "global", "first [i]");
        interceptor.sendToChannel(player, "global", "second [i]");

        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(networkClient, atLeastOnce()).sendPacket(captor.capture());
        long chatCount = captor.getAllValues().stream()
                .filter(ChatMessagePacket.class::isInstance).count();
        assertThat(chatCount).isEqualTo(2); // chat itself is never suppressed
        assertThat(sentItemDisplays()).hasSize(1);
    }
}
