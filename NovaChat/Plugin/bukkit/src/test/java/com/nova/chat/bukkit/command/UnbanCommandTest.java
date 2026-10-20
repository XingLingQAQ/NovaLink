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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Bukkit {@code /nc unban} command shell.
 *
 * <p>The property worth pinning is that an unban works for a player who is not
 * online: that is the normal case, since a ban exists to keep someone out while
 * they are away, and an implementation that resolved the target only among
 * online players would fail exactly when it is needed.
 */
@DisplayName("Bukkit Unban command")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnbanCommandTest {

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

    private UnbanCommand command;
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

        bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class);
        command = new UnbanCommand(plugin);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        I18n.setDefaultLocale(previousDefaultLocale);
    }

    @Test
    @DisplayName("an offline target can be unbanned by UUID")
    void offlineTargetByUuid() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer(any(String.class))).thenReturn(null);

        command.execute(operator, new String[]{TARGET_ID.toString()});

        ChannelActionPacket packet = sentPacket();
        assertThat(packet.getAction()).isEqualTo(ChannelAction.UNBAN);
        assertThat(packet.getExtra("targetId"))
                .as("the UUID is what makes an offline unban possible")
                .isEqualTo(TARGET_ID.toString());
    }

    @Test
    @DisplayName("a name is still sent so a proxy can resolve it")
    void nameIsSentForProxyResolution() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(null);

        command.execute(operator, new String[]{"Bob"});

        ChannelActionPacket packet = sentPacket();
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("targetId")).isNull();
    }

    @Test
    @DisplayName("an online target resolves to its UUID")
    void onlineTargetResolvesToUuid() {
        Player target = org.mockito.Mockito.mock(Player.class);
        when(target.getUniqueId()).thenReturn(TARGET_ID);
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);

        command.execute(operator, new String[]{"Bob"});

        assertThat(sentPacket().getExtra("targetId")).isEqualTo(TARGET_ID.toString());
    }

    @Test
    @DisplayName("omitting the channel lifts the network-wide ban")
    void omittedChannelIsGlobal() {
        command.execute(operator, new String[]{TARGET_ID.toString()});

        assertThat(sentPacket().getChannelId()).isNull();
    }

    @Test
    @DisplayName("a channel-scoped unban carries the channel")
    void channelScopedUnban() {
        command.execute(operator, new String[]{TARGET_ID.toString(), "global"});

        assertThat(sentPacket().getChannelId()).isEqualTo("global");
    }

    @Test
    @DisplayName("console unbans carry the console sentinel")
    void consoleUsesSentinel() {
        ConsoleCommandSender console = org.mockito.Mockito.mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        command.execute(console, new String[]{TARGET_ID.toString()});

        ChannelActionPacket packet = sentPacket();
        assertThat(packet.getExtra("operatorId")).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
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
