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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Folia {@code /nc ban} shell.
 *
 * <p>Mirrors the Bukkit test: the shell's job is to turn arguments into a
 * {@link ChannelActionPacket} with the extras the authority reads, and to keep
 * an omitted channel as the network-wide scope. The Folia-specific additions
 * are the connection gate and the console sentinel, both of which are what make
 * the shell usable from a Folia console.
 */
@DisplayName("Folia Ban command")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BanCommandTest extends CommandTestSupport {

    private static final UUID OPERATOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TARGET_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Mock
    private NovaChatFolia plugin;
    @Mock
    private AsyncNetworkClient client;
    @Mock
    private Player operator;
    @Mock
    private Player target;

    private BanCommand command;

    @BeforeEach
    void setUp() {
        wireConnected(plugin, client);
        when(operator.getUniqueId()).thenReturn(OPERATOR_ID);
        when(operator.getName()).thenReturn("Mod");
        command = new BanCommand(plugin);
    }

    @Test
    @DisplayName("a ban with a duration and channel sends the action packet")
    void banSendsPacket() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);
        when(target.getUniqueId()).thenReturn(TARGET_ID);

        command.execute(operator, new String[]{"Bob", "1h", "global"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(ChannelAction.BAN);
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR_ID.toString());
        assertThat(packet.getExtra("operatorName")).isEqualTo("Mod");
        assertThat(packet.getExtra("targetId")).isEqualTo(TARGET_ID.toString());
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("duration")).isEqualTo("3600");
        assertThat(packet.getExtra("console")).isNull();
    }

    @Test
    @DisplayName("a ban without a channel is a network-wide ban")
    void banWithoutChannelIsGlobal() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);
        when(target.getUniqueId()).thenReturn(TARGET_ID);

        command.execute(operator, new String[]{"Bob"});

        assertThat(((ChannelActionPacket) sentPacket(client)).getChannelId())
                .as("a null channel id is the global scope the authority reads")
                .isNull();
    }

    @Test
    @DisplayName("a channel may come before the duration")
    void channelBeforeDuration() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);
        when(target.getUniqueId()).thenReturn(TARGET_ID);

        command.execute(operator, new String[]{"Bob", "global", "1d"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getExtra("duration")).isEqualTo("86400");
    }

    @Test
    @DisplayName("a permanent ban carries no duration extra")
    void permanentBanHasNoDuration() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);
        when(target.getUniqueId()).thenReturn(TARGET_ID);

        command.execute(operator, new String[]{"Bob", "global"});

        assertThat(((ChannelActionPacket) sentPacket(client)).getExtra("duration")).isNull();
    }

    @Test
    @DisplayName("a target that is not local still sends the packet by name")
    void unknownTargetIsSentByName() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(null);

        command.execute(operator, new String[]{"Bob", "30m", "global"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getExtra("targetId")).isNull();
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("duration")).isEqualTo("1800");
    }

    @Test
    @DisplayName("an invalid duration is refused without sending")
    void invalidDurationIsRefused() {
        command.execute(operator, new String[]{"Bob", "banana", "global"});

        verifyNoPacket(client);
        assertMessageContains(operator, "NC-439");
    }

    @Test
    @DisplayName("a missing target prints usage and the duration hint")
    void missingTargetPrintsUsage() {
        command.execute(operator, new String[0]);

        verifyNoPacket(client);
        assertMessageContains(operator, "/nc ban");
    }

    @Test
    @DisplayName("console bans carry the console sentinel")
    void consoleUsesSentinel() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(null);

        command.execute(console, new String[]{"Bob", "1d"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getExtra("operatorId")).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
        assertThat(packet.getExtra("duration")).isEqualTo("86400");
        assertThat(packet.getExtra("operatorName")).isEqualTo("CONSOLE");
    }

    @Test
    @DisplayName("a disconnected client refuses before building a packet")
    void disconnectedClientRefuses() {
        when(client.isConnected()).thenReturn(false);

        command.execute(operator, new String[]{"Bob", "1h", "global"});

        verifyNoPacket(client);
        assertMessageContains(operator, "NC-500");
    }

    @Test
    @DisplayName("an unauthenticated client refuses before building a packet")
    void unauthenticatedClientRefuses() {
        when(client.isAuthenticated()).thenReturn(false);

        command.execute(operator, new String[]{"Bob", "1h", "global"});

        verifyNoPacket(client);
        assertMessageContains(operator, "NC-401");
    }

    @Test
    @DisplayName("the permission node matches the authority that checks it")
    void permissionMatchesAuthority() {
        assertThat(command.getPermission()).isEqualTo("novachat.ban");
        assertThat(command.isPlayerOnly()).isFalse();
    }
}
