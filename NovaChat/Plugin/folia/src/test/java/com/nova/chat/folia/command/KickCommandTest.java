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
 * Unit tests for the Folia {@code /nc kick} shell.
 *
 * <p>A kick is inherently per-channel, so unlike a ban an omitted channel falls
 * back to the sender's active channel — and is refused when there is none.
 */
@DisplayName("Folia Kick command")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KickCommandTest extends CommandTestSupport {

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

    private KickCommand command;

    @BeforeEach
    void setUp() {
        wireConnected(plugin, client);
        when(plugin.getChatInterceptor()).thenReturn(interceptor);
        when(operator.getUniqueId()).thenReturn(OPERATOR_ID);
        when(operator.getName()).thenReturn("Mod");
        command = new KickCommand(plugin);
    }

    @Test
    @DisplayName("a kick sends the action, target and channel")
    void kickSendsPacket() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);
        when(target.getUniqueId()).thenReturn(TARGET_ID);

        command.execute(operator, new String[]{"Bob", "global"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(ChannelAction.KICK);
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR_ID.toString());
        assertThat(packet.getExtra("playerId"))
                .as("the response dispatcher keys its pending entry on playerId")
                .isEqualTo(OPERATOR_ID.toString());
        assertThat(packet.getExtra("operatorName")).isEqualTo("Mod");
        assertThat(packet.getExtra("targetId")).isEqualTo(TARGET_ID.toString());
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("duration")).isNull();
    }

    @Test
    @DisplayName("a target that is not local is kicked by name")
    void remoteTargetIsKickedByName() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(null);

        command.execute(operator, new String[]{"Bob", "staff"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getExtra("targetId")).isNull();
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getChannelId()).isEqualTo("staff");
    }

    @Test
    @DisplayName("an omitted channel falls back to the sender's active channel")
    void omittedChannelUsesActiveChannel() {
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(null);
        PlayerChannelState state = new PlayerChannelState(OPERATOR_ID, "staff", ChatMode.HYBRID);
        when(interceptor.getPlayerState(OPERATOR_ID)).thenReturn(state);

        command.execute(operator, new String[]{"Bob"});

        assertThat(((ChannelActionPacket) sentPacket(client)).getChannelId()).isEqualTo("staff");
    }

    @Test
    @DisplayName("no channel and no active channel is refused")
    void noChannelIsRefused() {
        when(interceptor.getPlayerState(OPERATOR_ID)).thenReturn(null);

        command.execute(operator, new String[]{"Bob"});

        verifyNoPacket(client);
        assertMessageContains(operator, "频道");
    }

    @Test
    @DisplayName("console kicks need an explicit channel")
    void consoleNeedsExplicitChannel() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        command.execute(console, new String[]{"Bob"});
        verifyNoPacket(client);

        command.execute(console, new String[]{"Bob", "global"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getExtra("operatorId")).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("playerId"))
                .as("console moderation carries the sentinel so the outcome is logged, not dropped")
                .isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
    }

    @Test
    @DisplayName("a missing target prints usage without sending")
    void missingTargetPrintsUsage() {
        command.execute(operator, new String[0]);

        verifyNoPacket(client);
        assertMessageContains(operator, "/nc kick");
    }

    @Test
    @DisplayName("the permission node matches the one the authority checks")
    void permissionMatchesAuthority() {
        assertThat(command.getPermission()).isEqualTo("novachat.kick");
        assertThat(command.isPlayerOnly()).isFalse();
    }
}
