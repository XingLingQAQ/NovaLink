package com.nova.chat.folia.command;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.chat.AsyncChatInterceptor;
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
 * Unit tests for the Folia {@code /nc mute} shell.
 *
 * <p>Covers the two argument shapes (explicit channel, and the sender's active
 * channel as fallback), the cross-server path where the target is not local,
 * the console sentinel, and the duration/connection refusals.
 */
@DisplayName("Folia Mute command")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MuteCommandTest extends CommandTestSupport {

    private static final UUID OPERATOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TARGET_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Mock
    private NovaChatFolia plugin;
    @Mock
    private AsyncNetworkClient client;
    @Mock
    private AsyncChatInterceptor interceptor;
    @Mock
    private Player operator;
    @Mock
    private Player target;

    private MuteCommand command;

    @BeforeEach
    void setUp() {
        wireConnected(plugin, client);
        when(plugin.getChatInterceptor()).thenReturn(interceptor);
        when(operator.getUniqueId()).thenReturn(OPERATOR_ID);
        when(operator.getName()).thenReturn("Mod");
        command = new MuteCommand(plugin);
    }

    @Test
    @DisplayName("a mute sends the action, target and duration")
    void muteSendsPacket() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);
        when(target.getUniqueId()).thenReturn(TARGET_ID);

        command.execute(operator, new String[]{"Bob", "10m", "global"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(ChannelAction.MUTE);
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR_ID.toString());
        assertThat(packet.getExtra("operatorName")).isEqualTo("Mod");
        assertThat(packet.getExtra("targetId")).isEqualTo(TARGET_ID.toString());
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("duration")).isEqualTo("600");
    }

    @Test
    @DisplayName("an omitted channel falls back to the sender's active channel")
    void omittedChannelUsesActiveChannel() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(null);
        PlayerChannelState state = new PlayerChannelState(OPERATOR_ID, "staff", ChatMode.HYBRID);
        when(interceptor.getPlayerState(OPERATOR_ID)).thenReturn(state);

        command.execute(operator, new String[]{"Bob", "1h"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getChannelId()).isEqualTo("staff");
        assertThat(packet.getExtra("targetId")).isNull();
    }

    @Test
    @DisplayName("no channel and no active channel is refused")
    void noChannelIsRefused() {
        when(interceptor.getPlayerState(OPERATOR_ID)).thenReturn(null);

        command.execute(operator, new String[]{"Bob", "1h"});

        verifyNoPacket(client);
        assertMessageContains(operator, "频道");
    }

    @Test
    @DisplayName("an invalid duration is refused with the accepted formats")
    void invalidDurationIsRefused() {
        command.execute(operator, new String[]{"Bob", "banana", "global"});

        verifyNoPacket(client);
        assertMessageContains(operator, "NC-439");
    }

    @Test
    @DisplayName("too few arguments print usage and the duration hint")
    void tooFewArgumentsPrintUsage() {
        command.execute(operator, new String[]{"Bob"});

        verifyNoPacket(client);
        assertMessageContains(operator, "/nc mute");
    }

    @Test
    @DisplayName("console mutes carry the console sentinel")
    void consoleUsesSentinel() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        command.execute(console, new String[]{"Bob", "30s", "global"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getExtra("operatorId")).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
        assertThat(packet.getExtra("duration")).isEqualTo("30");
    }

    @Test
    @DisplayName("a send failure is reported as NC-503")
    void sendFailureIsReported() {
        when(client.isConnected()).thenReturn(true);
        // checkConnection passes, but a second getNetworkClient returns null on send
        when(plugin.getNetworkClient()).thenReturn(client, null);

        command.execute(operator, new String[]{"Bob", "1h", "global"});

        assertReportsRequestFailed(operator);
    }

    @Test
    @DisplayName("the permission node matches the one the authority checks")
    void permissionMatchesAuthority() {
        assertThat(command.getPermission()).isEqualTo("novachat.mute");
        assertThat(command.isPlayerOnly()).isFalse();
    }
}
