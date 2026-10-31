package com.nova.chat.pnx.command;

import org.powernukkitx.Player;
import org.powernukkitx.Server;
import org.powernukkitx.command.ConsoleCommandSender;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.pnx.NovaChatPNX;
import com.nova.chat.pnx.chat.ChatInterceptor;
import com.nova.chat.pnx.network.NetworkClient;
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
 * Unit tests for the PNX moderation shells: {@code /nc mute}, {@code kick},
 * {@code ban}, {@code unban}.
 *
 * <p>Pins the contract the backend keys off — the {@link ChannelAction}, the
 * operator/target/duration extras, and the console sentinel — because getting
 * any of those wrong makes the command silently do the wrong thing rather than
 * fail loudly.
 */
@DisplayName("PNX moderation commands")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModerationCommandTest extends ModerationCommandTestSupport {

    private static final UUID OPERATOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TARGET_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final String TARGET_NAME = "Bob";

    @Mock
    private NovaChatPNX plugin;
    @Mock
    private NetworkClient client;
    @Mock
    private Server server;
    @Mock
    private ChatInterceptor interceptor;
    @Mock
    private Player operator;
    @Mock
    private Player target;

    @BeforeEach
    void setUp() {
        wireConnected(plugin, client, server);
        when(plugin.getChatInterceptor()).thenReturn(interceptor);
        when(operator.getUniqueId()).thenReturn(OPERATOR_ID);
        when(operator.getName()).thenReturn("Mod");
        when(target.getUniqueId()).thenReturn(TARGET_ID);
        when(target.getName()).thenReturn(TARGET_NAME);
    }

    // ==================== mute ====================

    @Test
    @DisplayName("/nc mute sends MUTE with operator, target and duration")
    void muteSendsPacket() {
        when(server.getPlayerExact(TARGET_NAME)).thenReturn(target);

        new MuteCommand(plugin).execute(operator, new String[]{"Bob", "10m", "global"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(ChannelAction.MUTE);
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR_ID.toString());
        assertThat(packet.getExtra("operatorName")).isEqualTo("Mod");
        assertThat(packet.getExtra("targetId")).isEqualTo(TARGET_ID.toString());
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("duration")).isEqualTo("600");
        assertThat(packet.getExtra("console")).isNull();
    }

    @Test
    @DisplayName("/nc mute falls back to the sender's active channel")
    void muteUsesActiveChannel() {
        when(server.getPlayerExact(TARGET_NAME)).thenReturn(null);
        when(interceptor.getPlayerState(OPERATOR_ID))
                .thenReturn(new PlayerChannelState(OPERATOR_ID, "staff", ChatMode.HYBRID));

        new MuteCommand(plugin).execute(operator, new String[]{"Bob", "1h"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getChannelId()).isEqualTo("staff");
        assertThat(packet.getExtra("targetId")).isNull();
        assertThat(packet.getExtra("duration")).isEqualTo("3600");
    }

    @Test
    @DisplayName("/nc mute without a channel or active channel is refused")
    void muteWithoutChannelIsRefused() {
        when(interceptor.getPlayerState(OPERATOR_ID)).thenReturn(null);

        new MuteCommand(plugin).execute(operator, new String[]{"Bob", "1h"});

        verifyNoPacket(client);
        assertMessageContains(operator, "频道");
    }

    @Test
    @DisplayName("/nc mute rejects an invalid duration before sending")
    void muteRejectsInvalidDuration() {
        new MuteCommand(plugin).execute(operator, new String[]{"Bob", "banana", "global"});

        verifyNoPacket(client);
        assertMessageContains(operator, "NC-439");
    }

    @Test
    @DisplayName("/nc mute forwards a trailing reason extra")
    void muteForwardsReason() {
        when(server.getPlayerExact(TARGET_NAME)).thenReturn(target);

        new MuteCommand(plugin).execute(operator,
                new String[]{"Bob", "10m", "global", "spamming", "the", "channel"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getExtra("reason")).isEqualTo("spamming the channel");
    }

    @Test
    @DisplayName("console mute carries the sentinel UUID and console=true")
    void consoleMuteUsesSentinel() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        when(server.getPlayerExact(TARGET_NAME)).thenReturn(null);

        new MuteCommand(plugin).execute(console, new String[]{"Bob", "10m", "global"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getExtra("operatorId")).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
        assertThat(packet.getExtra("operatorName")).isEqualTo("CONSOLE");
    }

    @Test
    @DisplayName("a disconnected backend refuses the mute with NC-503")
    void muteRequiresConnection() {
        when(client.isConnected()).thenReturn(false);

        new MuteCommand(plugin).execute(operator, new String[]{"Bob", "10m", "global"});

        verifyNoPacket(client);
        assertReportsRequestFailed(operator);
    }

    // ==================== kick ====================

    @Test
    @DisplayName("/nc kick sends KICK with the operator and target extras")
    void kickSendsPacket() {
        when(server.getPlayerExact(TARGET_NAME)).thenReturn(target);

        new KickCommand(plugin).execute(operator, new String[]{"Bob", "global"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(ChannelAction.KICK);
        assertThat(packet.getChannelId()).isEqualTo("global");
        assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR_ID.toString());
        assertThat(packet.getExtra("targetId")).isEqualTo(TARGET_ID.toString());
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("duration")).isNull();
    }

    @Test
    @DisplayName("/nc kick without arguments prints usage")
    void kickWithoutArgsShowsUsage() {
        new KickCommand(plugin).execute(operator, new String[0]);

        verifyNoPacket(client);
        assertMessageContains(operator, "/nc kick");
    }

    @Test
    @DisplayName("console kick carries the sentinel UUID")
    void consoleKickUsesSentinel() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        when(server.getPlayerExact(TARGET_NAME)).thenReturn(null);

        new KickCommand(plugin).execute(console, new String[]{"Bob", "global"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getExtra("operatorId")).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
    }

    // ==================== ban / unban ====================

    @Test
    @DisplayName("/nc ban without a channel is a network-wide ban")
    void banWithoutChannelIsGlobal() {
        when(server.getPlayerExact(TARGET_NAME)).thenReturn(target);

        new BanCommand(plugin).execute(operator, new String[]{"Bob"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(ChannelAction.BAN);
        assertThat(packet.getChannelId()).isNull();
        assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR_ID.toString());
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
        assertThat(packet.getExtra("duration")).isNull();
    }

    @Test
    @DisplayName("/nc ban accepts duration and channel in either order")
    void banAcceptsEitherArgumentOrder() {
        new BanCommand(plugin).execute(operator, new String[]{"Bob", "1d", "global"});
        ChannelActionPacket durationFirst = (ChannelActionPacket) sentPacket(client);
        assertThat(durationFirst.getChannelId()).isEqualTo("global");
        assertThat(durationFirst.getExtra("duration")).isEqualTo("86400");

        new BanCommand(plugin).execute(operator, new String[]{"Bob", "global", "1d"});
        ChannelActionPacket channelFirst = (ChannelActionPacket) sentPacket(client);
        assertThat(channelFirst.getChannelId()).isEqualTo("global");
        assertThat(channelFirst.getExtra("duration")).isEqualTo("86400");
    }

    @Test
    @DisplayName("/nc ban rejects an invalid duration")
    void banRejectsInvalidDuration() {
        new BanCommand(plugin).execute(operator, new String[]{"Bob", "banana", "global"});

        verifyNoPacket(client);
        assertMessageContains(operator, "NC-439");
    }

    @Test
    @DisplayName("/nc unban accepts a UUID and sends it as targetId")
    void unbanAcceptsUuid() {
        String uuid = TARGET_ID.toString();

        new UnbanCommand(plugin).execute(operator, new String[]{uuid});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(ChannelAction.UNBAN);
        assertThat(packet.getExtra("targetId")).isEqualTo(uuid);
        assertThat(packet.getExtra("targetName")).isEqualTo(uuid);
        assertThat(packet.getChannelId()).isNull();
    }

    @Test
    @DisplayName("/nc unban with an offline name still sends the name")
    void unbanOfflineNameStillSends() {
        when(server.getPlayerExact("Ghost")).thenReturn(null);

        new UnbanCommand(plugin).execute(operator, new String[]{"Ghost"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getExtra("targetName")).isEqualTo("Ghost");
        assertThat(packet.getExtra("targetId")).isNull();
    }

    // ==================== permissions / registration ====================

    @Test
    @DisplayName("each moderation shell declares its own permission node")
    void permissionNodesAreDistinct() {
        assertThat(new MuteCommand(plugin).getPermission()).isEqualTo("novachat.mute");
        assertThat(new KickCommand(plugin).getPermission()).isEqualTo("novachat.kick");
        assertThat(new BanCommand(plugin).getPermission()).isEqualTo("novachat.ban");
        assertThat(new UnbanCommand(plugin).getPermission()).isEqualTo("novachat.ban");
    }

    @Test
    @DisplayName("a sender without the node is rejected before any packet is built")
    void permissionRejectionBlocksExecution() {
        when(operator.hasPermission("novachat.mute")).thenReturn(false);
        NovaChatCommand command = new NovaChatCommand(plugin);

        command.execute(operator, "nc", new String[]{"mute", "Bob", "10m", "global"});

        verifyNoPacket(client);
        assertMessageContains(operator, "NC-403");
    }

    @Test
    @DisplayName("every moderation shell is reachable through /nc")
    void moderationCommandsAreRegistered() {
        NovaChatCommand command = new NovaChatCommand(plugin);

        assertThat(command.getSubCommands())
                .as("an unregistered sub-command is a command the operator cannot run")
                .containsKeys("mute", "kick", "ban", "unban", "announce", "title", "auth");
        assertThat(command.getSubCommands())
                .allSatisfy((name, sub) -> assertThat(sub.getName()).isEqualTo(name));
    }
}
