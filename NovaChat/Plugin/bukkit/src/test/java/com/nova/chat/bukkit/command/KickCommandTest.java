package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.error.ErrorMessageHandler;
import com.nova.chat.bukkit.network.NetworkClient;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Bukkit {@code /nc kick} shell.
 *
 * <p>Same contract as the mute shell: a kick is per-channel, and the packet
 * must carry the requesting sender's {@code playerId} or the asynchronous
 * outcome is dropped before anything renders.
 */
@DisplayName("Bukkit Kick command")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KickCommandTest {

    private static final UUID OPERATOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TARGET_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Mock
    private NovaChatBukkit plugin;
    @Mock
    private ErrorMessageHandler errorHandler;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private Player operator;
    @Mock
    private Player target;
    @Mock
    private com.nova.chat.bukkit.chat.ChatInterceptor chatInterceptor;

    private KickCommand command;
    private org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit;
    private Locale previousDefaultLocale;

    @BeforeEach
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getErrorHandler()).thenReturn(errorHandler);
        when(plugin.getChatInterceptor()).thenReturn(chatInterceptor);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.isConnected()).thenReturn(true);
        when(networkClient.isAuthenticated()).thenReturn(true);
        when(operator.getUniqueId()).thenReturn(OPERATOR_ID);
        when(operator.getName()).thenReturn("Mod");

        bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class);
        command = new KickCommand(plugin);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    @Test
    @DisplayName("a kick sends the action, target and the sender's playerId")
    void kickSendsPacket() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);
        when(target.getUniqueId()).thenReturn(TARGET_ID);

        command.execute(operator, new String[]{"Bob", "global"});

        ChannelActionPacket packet = sentPacket();
        assertThat(packet.getAction()).isEqualTo(ChannelAction.KICK);
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR_ID.toString());
        assertThat(packet.getExtra("playerId"))
                .as("the dispatcher drops a response whose pending playerId is null")
                .isEqualTo(OPERATOR_ID.toString());
        assertThat(packet.getExtra("operatorName")).isEqualTo("Mod");
        assertThat(packet.getExtra("targetId")).isEqualTo(TARGET_ID.toString());
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
    }

    @Test
    @DisplayName("a remote target is kicked by name and still correlates to the sender")
    void remoteTargetIsKickedByName() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(null);

        command.execute(operator, new String[]{"Bob", "staff"});

        ChannelActionPacket packet = sentPacket();
        assertThat(packet.getExtra("targetId")).isNull();
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("playerId")).isEqualTo(OPERATOR_ID.toString());
    }

    @Test
    @DisplayName("a kick without a resolvable channel is refused before sending")
    void missingChannelIsRefused() {
        command.execute(operator, new String[]{"Bob"});

        verify(networkClient, org.mockito.Mockito.never())
                .sendPacket(org.mockito.ArgumentMatchers.any(Packet.class));
    }

    @Test
    @DisplayName("console kicks carry the console sentinel in both operatorId and playerId")
    void consoleUsesSentinel() {
        ConsoleCommandSender console = org.mockito.Mockito.mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        command.execute(console, new String[]{"Bob", "global"});

        ChannelActionPacket packet = sentPacket();
        assertThat(packet.getExtra("operatorId")).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("playerId"))
                .as("the sentinel is what lets the adapter log the outcome instead of dropping it")
                .isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
    }

    @Test
    @DisplayName("the permission node matches the one the authority checks")
    void permissionMatchesAuthority() {
        assertThat(command.getPermission()).isEqualTo("novachat.kick");
        assertThat(command.isPlayerOnly()).isFalse();
    }

    private ChannelActionPacket sentPacket() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(networkClient, atLeastOnce()).sendPacket(captor.capture());
        List<Packet> sent = captor.getAllValues();
        return (ChannelActionPacket) sent.get(sent.size() - 1);
    }
}
