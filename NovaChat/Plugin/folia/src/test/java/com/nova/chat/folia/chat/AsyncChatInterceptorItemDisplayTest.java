package com.nova.chat.folia.chat;

import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.itemdisplay.ItemDisplayTokens;
import com.nova.chat.client.network.ChannelResponseTracker;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.common.protocol.packets.ItemDisplayPacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.network.AsyncNetworkClient;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.HoverEvent;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
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
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Folia {@link AsyncChatInterceptor}'s item display play:
 * the inbound {@code ItemDisplayPacket} handler (hover line on the player's
 * region thread) and the outbound {@code [item]}/{@code [i]} token path.
 *
 * <p>Mirrors {@link AsyncChatInterceptorTitleTest}'s infrastructure: the
 * handler is captured from the mocked {@link AsyncNetworkClient} registration,
 * driven with a packet, and the {@link FoliaSchedulerAdapter#runForPlayer}
 * region hop is asserted before rendering.
 */
@DisplayName("Folia AsyncChatInterceptor item display (receive + send)")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncChatInterceptorItemDisplayTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID SENDER_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

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
    private Player.Spigot spigot;
    @Mock
    private World world;
    @Mock
    private PlayerInventory inventory;
    @Mock
    private ItemStack mainHand;
    @Mock
    private ItemMeta meta;

    private AsyncChatInterceptor interceptor;
    private Consumer<ItemDisplayPacket> itemDisplayHandler;
    private Locale previousDefaultLocale;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getNovaChatConfig()).thenReturn(config);
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.getChannelResponseTracker()).thenReturn(tracker);
        when(networkClient.isAuthenticated()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPlugin("PlaceholderAPI")).thenReturn(null);
        when(config.getDefaultChannel()).thenReturn("global");
        when(config.isReplaceVanilla()).thenReturn(false);
        when(config.getUsername()).thenReturn("folia-1");
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Steve");
        when(player.getDisplayName()).thenReturn("Steve");
        when(player.isOnline()).thenReturn(true);
        when(player.spigot()).thenReturn(spigot);
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        when(player.hasPermission(ItemDisplayTokens.PERMISSION_ITEM)).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(mainHand);
        when(mainHand.getType()).thenReturn(Material.NETHERITE_SWORD);
        when(mainHand.getAmount()).thenReturn(2);
        when(mainHand.getItemMeta()).thenReturn(null);
        doReturn(List.of(player)).when(server).getOnlinePlayers();

        interceptor = new AsyncChatInterceptor(plugin);
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
    @DisplayName("matching channel: hops to the player's region thread and sends a hoverable item line")
    void matchingChannelRendersHoverableLineOnRegionThread() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:netherite_sword\",\"count\":2}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runForPlayer(eq(player), task.capture());
        verify(spigot, never()).sendMessage(any(BaseComponent.class));

        task.getValue().run();

        ArgumentCaptor<BaseComponent> component = ArgumentCaptor.forClass(BaseComponent.class);
        verify(spigot).sendMessage(component.capture());

        String plain = component.getValue().toPlainText();
        assertThat(plain).contains("Alex").contains("Netherite Sword").contains("x2");
        assertThat(component.getValue().getHoverEvent()).isNotNull();
        assertThat(component.getValue().getHoverEvent().getAction())
                .isEqualTo(HoverEvent.Action.SHOW_TEXT);
    }

    @Test
    @DisplayName("empty hand payload renders the localized empty placeholder")
    void emptyHandRendersPlaceholder() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "global",
                "{\"id\":\"minecraft:air\",\"count\":0}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runForPlayer(eq(player), task.capture());
        task.getValue().run();

        ArgumentCaptor<BaseComponent> component = ArgumentCaptor.forClass(BaseComponent.class);
        verify(spigot).sendMessage(component.capture());
        assertThat(component.getValue().toPlainText()).contains("\u7A7A\u624B"); // 空手
    }

    @Test
    @DisplayName("non-matching channel: no region hop, nothing rendered")
    void nonMatchingChannelSkipsPlayer() {
        ItemDisplayPacket packet = new ItemDisplayPacket(SENDER_ID, "Alex", "trade",
                "{\"id\":\"minecraft:stone\",\"count\":1}", System.currentTimeMillis());

        itemDisplayHandler.accept(packet);

        verify(scheduler, never()).runForPlayer(any(Player.class), any(Runnable.class));
        verify(spigot, never()).sendMessage(any(BaseComponent.class));
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
    @DisplayName("[i] token sends an ItemDisplayPacket with display fields only")
    void tokenSendsItemDisplayPacket() {
        interceptor.sendToChannel(player, "global", "look at this [i]");

        List<ItemDisplayPacket> displays = sentItemDisplays();
        assertThat(displays).hasSize(1);
        ItemDisplayPacket display = displays.get(0);
        assertThat(display.getSenderId()).isEqualTo(PLAYER_ID);
        assertThat(display.getSenderName()).isEqualTo("Steve");
        assertThat(display.getChannelId()).isEqualTo("global");
        assertThat(display.getItemJson())
                .contains("\"id\":\"minecraft:netherite_sword\"")
                .contains("\"count\":2");
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
