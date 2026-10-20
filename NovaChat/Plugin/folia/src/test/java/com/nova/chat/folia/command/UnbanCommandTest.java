package com.nova.chat.folia.command;

import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.network.AsyncNetworkClient;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Folia {@code /nc unban} shell.
 *
 * <p>The property worth pinning is that an unban works for a player who is not
 * online — the normal case, since a ban exists to keep someone out while they
 * are away.
 */
@DisplayName("Folia Unban command")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UnbanCommandTest extends CommandTestSupport {

    private static final UUID OPERATOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TARGET_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Mock
    private NovaChatFolia plugin;
    @Mock
    private AsyncNetworkClient client;
    @Mock
    private Player operator;

    private UnbanCommand command;

    @BeforeEach
    void setUp() {
        wireConnected(plugin, client);
        when(operator.getUniqueId()).thenReturn(OPERATOR_ID);
        when(operator.getName()).thenReturn("Mod");
        command = new UnbanCommand(plugin);
    }

    @Test
    @DisplayName("an offline target can be unbanned by UUID")
    void offlineTargetByUuid() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer(anyString())).thenReturn(null);

        command.execute(operator, new String[]{TARGET_ID.toString()});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(ChannelAction.UNBAN);
        assertThat(packet.getExtra("targetId"))
                .as("the UUID is what makes an offline unban possible")
                .isEqualTo(TARGET_ID.toString());
        assertThat(packet.getExtra("targetName")).isEqualTo(TARGET_ID.toString());
    }

    @Test
    @DisplayName("a name is still sent so the authority can resolve it")
    void nameIsSentForResolution() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(null);

        command.execute(operator, new String[]{"Bob"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("targetId")).isNull();
        assertMessageContains(operator, "Bob");
    }

    @Test
    @DisplayName("an online target resolves to its UUID")
    void onlineTargetResolvesToUuid() {
        Player target = mock(Player.class);
        when(target.getUniqueId()).thenReturn(TARGET_ID);
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);

        command.execute(operator, new String[]{"Bob"});

        assertThat(((ChannelActionPacket) sentPacket(client)).getExtra("targetId"))
                .isEqualTo(TARGET_ID.toString());
    }

    @Test
    @DisplayName("omitting the channel lifts the network-wide ban")
    void omittedChannelIsGlobal() {
        command.execute(operator, new String[]{TARGET_ID.toString()});

        assertThat(((ChannelActionPacket) sentPacket(client)).getChannelId()).isNull();
    }

    @Test
    @DisplayName("a channel-scoped unban carries the channel")
    void channelScopedUnban() {
        command.execute(operator, new String[]{TARGET_ID.toString(), "global"});

        assertThat(((ChannelActionPacket) sentPacket(client)).getChannelId()).isEqualTo("global");
    }

    @Test
    @DisplayName("console unbans carry the console sentinel")
    void consoleUsesSentinel() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        command.execute(console, new String[]{TARGET_ID.toString()});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getExtra("operatorId")).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
    }

    @Test
    @DisplayName("a missing target prints usage without sending")
    void missingTargetPrintsUsage() {
        command.execute(operator, new String[0]);

        verifyNoPacket(client);
        assertMessageContains(operator, "/nc unban");
    }

    @Test
    @DisplayName("the permission node matches the one the authority checks")
    void permissionMatchesAuthority() {
        assertThat(command.getPermission()).isEqualTo("novachat.ban");
    }
}
