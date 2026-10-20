package com.nova.chat.folia.command;

import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
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
 * Unit tests for the Folia {@code /nc announce} and {@code /nc title} shells.
 *
 * <p>Both go over {@link AdminActionPacket}, reusing {@link AdminAction#STATUS}
 * and distinguishing the operation in the {@code type} extra. The extras and
 * the console sentinel are the contract the backend keys off, so those are what
 * these tests pin.
 */
@DisplayName("Folia Announce/Title commands")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModerationCommandTest extends CommandTestSupport {

    private static final UUID OPERATOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatFolia plugin;
    @Mock
    private AsyncNetworkClient client;
    @Mock
    private Player operator;

    private AnnounceCommand announce;
    private TitleCommand title;

    @BeforeEach
    void setUp() {
        wireConnected(plugin, client);
        when(operator.getUniqueId()).thenReturn(OPERATOR_ID);
        when(operator.getName()).thenReturn("Mod");
        announce = new AnnounceCommand(plugin);
        title = new TitleCommand(plugin);
    }

    @Test
    @DisplayName("announce sends a STATUS packet typed ANNOUNCE with the joined content")
    void announceSendsPacket() {
        announce.execute(operator, new String[]{"global", "server", "restart", "in", "5m"});

        AdminActionPacket packet = (AdminActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(AdminAction.STATUS);
        assertThat(packet.getPlayerId()).isEqualTo(OPERATOR_ID);
        assertThat(packet.getTarget()).isEqualTo("global");
        assertThat(packet.getExtra("type")).isEqualTo("ANNOUNCE");
        assertThat(packet.getExtra("operatorName")).isEqualTo("Mod");
        assertThat(packet.getExtra("content")).isEqualTo("server restart in 5m");
        assertThat(packet.getExtra("console")).isNull();
    }

    @Test
    @DisplayName("console announce carries the sentinel UUID")
    void consoleAnnounceUsesSentinel() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        announce.execute(console, new String[]{"global", "hello"});

        AdminActionPacket packet = (AdminActionPacket) sentPacket(client);
        assertThat(packet.getPlayerId().toString()).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
    }

    @Test
    @DisplayName("announce needs a channel and a body")
    void announceValidatesArguments() {
        announce.execute(operator, new String[]{"global"});

        verifyNoPacket(client);
        assertMessageContains(operator, "/nc announce");
    }

    @Test
    @DisplayName("title sends a STATUS packet typed TITLE with title and subtitle")
    void titleSendsPacket() {
        title.execute(operator, new String[]{"global", "Welcome", "to", "the", "server"});

        AdminActionPacket packet = (AdminActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(AdminAction.STATUS);
        assertThat(packet.getPlayerId()).isEqualTo(OPERATOR_ID);
        assertThat(packet.getTarget()).isEqualTo("global");
        assertThat(packet.getExtra("type")).isEqualTo("TITLE");
        assertThat(packet.getExtra("operatorName")).isEqualTo("Mod");
        assertThat(packet.getExtra("title")).isEqualTo("Welcome");
        assertThat(packet.getExtra("subtitle")).isEqualTo("to the server");
    }

    @Test
    @DisplayName("a title without a subtitle sends an empty one")
    void titleWithoutSubtitle() {
        title.execute(operator, new String[]{"global", "Welcome"});

        assertThat(((AdminActionPacket) sentPacket(client)).getExtra("subtitle")).isEmpty();
    }

    @Test
    @DisplayName("console title carries the sentinel UUID")
    void consoleTitleUsesSentinel() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        title.execute(console, new String[]{"global", "Welcome"});

        AdminActionPacket packet = (AdminActionPacket) sentPacket(client);
        assertThat(packet.getPlayerId().toString()).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
    }

    @Test
    @DisplayName("both shells refuse a disconnected backend")
    void disconnectedBackendRefuses() {
        when(client.isConnected()).thenReturn(false);

        announce.execute(operator, new String[]{"global", "hello"});
        title.execute(operator, new String[]{"global", "Welcome"});

        verifyNoPacket(client);
        assertMessageContains(operator, "NC-500");
    }

    @Test
    @DisplayName("the permission nodes match the ones the authority checks")
    void permissionNodes() {
        assertThat(announce.getPermission()).isEqualTo("novachat.announce");
        assertThat(title.getPermission()).isEqualTo("novachat.title");
        assertThat(announce.isPlayerOnly()).isFalse();
        assertThat(title.isPlayerOnly()).isFalse();
    }
}
