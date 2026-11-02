package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import com.nova.chat.bukkit.network.NetworkClient;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.Packet;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TitleCommandTest {

    @Mock private NovaChatBukkit plugin;
    @Mock private NetworkClient client;
    @Mock private Player player;
    @Mock private ConsoleCommandSender console;
    private TitleCommand command;
    private final UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @BeforeEach
    void setUp() {
        when(plugin.getNetworkClient()).thenReturn(client);
        when(client.isConnected()).thenReturn(true);
        when(client.isAuthenticated()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("E2EJointJava");
        when(console.getName()).thenReturn("Console");
        command = new TitleCommand(plugin);
    }

    @Test
    void sendsPlayerIdentityAndCustomTimingThroughTheExistingAdminAction() {
        command.execute(player, new String[]{"global", "Title", "several", "subtitle", "words",
                "--fadeIn", "3", "--stay", "37", "--fadeOut", "9"});

        AdminActionPacket packet = sentPacket();
        assertThat(packet.getAction()).isEqualTo(AdminAction.STATUS);
        assertThat(packet.getPlayerId()).isEqualTo(playerId);
        assertThat(packet.getTarget()).isEqualTo("global");
        assertThat(packet.getExtra("type")).isEqualTo("TITLE");
        assertThat(packet.getExtra("operatorName")).isEqualTo("E2EJointJava");
        assertThat(packet.getExtra("console")).isNull();
        assertThat(packet.getExtra("title")).isEqualTo("Title");
        assertThat(packet.getExtra("subtitle")).isEqualTo("several subtitle words");
        assertThat(packet.getExtra("fadeIn")).isEqualTo("3");
        assertThat(packet.getExtra("stay")).isEqualTo("37");
        assertThat(packet.getExtra("fadeOut")).isEqualTo("9");
        assertThat(command.getPermission()).isEqualTo("novachat.title");
    }

    @Test
    void preservesConsoleIdentityAndLegacyDefaultTimings() {
        command.execute(console, new String[]{"local", "Title", "subtitle", "words"});

        AdminActionPacket packet = sentPacket();
        assertThat(packet.getPlayerId()).isEqualTo(UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID));
        assertThat(packet.getExtra("console")).isEqualTo("true");
        assertThat(packet.getExtra("subtitle")).isEqualTo("subtitle words");
        assertThat(packet.getExtra("fadeIn")).isEqualTo("10");
        assertThat(packet.getExtra("stay")).isEqualTo("70");
        assertThat(packet.getExtra("fadeOut")).isEqualTo("20");
        assertThat(command.isPlayerOnly()).isFalse();
    }

    @Test
    void rejectsInvalidTimingWithoutSendingAnyPacket() {
        command.execute(player, new String[]{"global", "Title", "subtitle", "--stay", "2147483648"});

        verify(client, never()).sendPacket(any(Packet.class));
        verify(player, atLeastOnce()).sendMessage(contains("--stay"));
    }

    private AdminActionPacket sentPacket() {
        ArgumentCaptor<Packet> packet = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(packet.capture());
        return (AdminActionPacket) packet.getValue();
    }
}
