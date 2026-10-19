package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.error.ErrorMessageHandler;
import com.nova.chat.bukkit.network.NetworkClient;
import com.nova.chat.client.i18n.I18n;
import com.nova.chat.client.state.PlayerChannelState;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Bukkit {@code /nc ban} command shell.
 *
 * <p>The command's job is narrow: turn arguments into a {@link ChannelActionPacket}
 * with the extras the authority reads, and get the global-versus-channel scope
 * right. That scope is the part worth pinning — omitting the channel means a
 * network-wide ban, and a shell that always fills in the sender's active channel
 * would silently narrow every global ban to one channel.
 */
@DisplayName("Bukkit Ban command")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BanCommandTest {

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

    private BanCommand command;
    private org.mockito.MockedStatic<org.bukkit.Bukkit> bukkit;
    private Locale previousDefaultLocale;
    @BeforeEach
    void setUp() {
        previousDefaultLocale = I18n.getDefaultLocale();
        I18n.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);

        when(plugin.getErrorHandler()).thenReturn(errorHandler);
        when(plugin.getNetworkClient()).thenReturn(networkClient);
        when(networkClient.isConnected()).thenReturn(true);
        when(networkClient.isAuthenticated()).thenReturn(true);
        when(operator.getUniqueId()).thenReturn(OPERATOR_ID);
        when(operator.getName()).thenReturn("Mod");

        // parsePlayer goes through the static Bukkit registry, so the mock is
        // opened here and closed in tearDown.
        bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class);

        command = new BanCommand(plugin);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    @Test
    @DisplayName("a ban with a duration and channel sends the action packet")
    void banSendsPacket() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);
        when(target.getUniqueId()).thenReturn(TARGET_ID);
        when(target.getName()).thenReturn("Bob");

        command.execute(operator, new String[]{"Bob", "1h", "global"});

        ChannelActionPacket packet = sentPacket();
        assertThat(packet.getAction()).isEqualTo(ChannelAction.BAN);
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR_ID.toString());
        assertThat(packet.getExtra("targetId")).isEqualTo(TARGET_ID.toString());
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("duration")).isEqualTo("3600");
    }

    @Test
    @DisplayName("a ban without a channel is a network-wide ban")
    void banWithoutChannelIsGlobal() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);
        when(target.getUniqueId()).thenReturn(TARGET_ID);

        command.execute(operator, new String[]{"Bob"});

        ChannelActionPacket packet = sentPacket();
        assertThat(packet.getChannelId())
                .as("a null channel id is the global scope the authority reads")
                .isNull();
    }

    @Test
    @DisplayName("a permanent ban carries no duration extra")
    void permanentBanHasNoDuration() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);
        when(target.getUniqueId()).thenReturn(TARGET_ID);

        command.execute(operator, new String[]{"Bob", "global"});

        ChannelActionPacket packet = sentPacket();
        assertThat(packet.getExtra("duration")).isNull();
    }

    @Test
    @DisplayName("a target that is not local still sends the packet by name")
    void unknownTargetIsSentByName() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(null);

        command.execute(operator, new String[]{"Bob", "30m", "global"});

        ChannelActionPacket packet = sentPacket();
        assertThat(packet.getExtra("targetId")).isNull();
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("duration")).isEqualTo("1800");
    }

    @Test
    @DisplayName("an invalid duration is refused without sending")
    void invalidDurationIsRefused() {
        command.execute(operator, new String[]{"Bob", "banana", "global"});

        verify(networkClient, never()).sendPacket(any(Packet.class));
    }

    @Test
    @DisplayName("console bans carry the console sentinel")
    void consoleUsesSentinel() {
        ConsoleCommandSender console = org.mockito.Mockito.mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        command.execute(console, new String[]{"Bob", "1d"});

        ChannelActionPacket packet = sentPacket();
        assertThat(packet.getExtra("operatorId")).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
        assertThat(packet.getExtra("duration")).isEqualTo("86400");
    }

    @Test
    @DisplayName("the permission node matches the one the authority checks")
    void permissionMatchesAuthority() {
        assertThat(command.getPermission()).isEqualTo("novachat.ban");
    }

    private ChannelActionPacket sentPacket() {
        ArgumentCaptor<Packet> captor = ArgumentCaptor.forClass(Packet.class);
        verify(networkClient, atLeastOnce()).sendPacket(captor.capture());
        List<Packet> sent = captor.getAllValues();
        return (ChannelActionPacket) sent.get(sent.size() - 1);
    }

}
