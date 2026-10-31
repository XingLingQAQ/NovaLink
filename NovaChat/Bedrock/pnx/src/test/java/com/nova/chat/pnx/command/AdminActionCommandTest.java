package com.nova.chat.pnx.command;

import org.powernukkitx.Player;
import org.powernukkitx.Server;
import org.powernukkitx.command.ConsoleCommandSender;
import com.nova.chat.client.network.PasswordHasher;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.pnx.NovaChatPNX;
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
 * Unit tests for the PNX {@code /nc announce}, {@code /nc title} and
 * {@code /nc auth} shells.
 *
 * <p>Both broadcast shells go over {@link AdminActionPacket}, reusing
 * {@link AdminAction#STATUS} and distinguishing the operation in the
 * {@code type} extra. The extras and the console sentinel are the contract the
 * backend keys off, so those are what these tests pin. {@code auth} is covered
 * because without it the other two are rejected with NC-403 and can never
 * succeed.
 */
@DisplayName("PNX announce/title/auth commands")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminActionCommandTest extends ModerationCommandTestSupport {

    private static final UUID OPERATOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatPNX plugin;
    @Mock
    private NetworkClient client;
    @Mock
    private Server server;
    @Mock
    private Player operator;

    @BeforeEach
    void setUp() {
        wireConnected(plugin, client, server);
        when(operator.getUniqueId()).thenReturn(OPERATOR_ID);
        when(operator.getName()).thenReturn("Mod");
    }

    @Test
    @DisplayName("announce sends a STATUS packet typed ANNOUNCE with the joined content")
    void announceSendsPacket() {
        new AnnounceCommand(plugin).execute(operator,
                new String[]{"global", "server", "restart", "in", "5m"});

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

        new AnnounceCommand(plugin).execute(console, new String[]{"global", "hello"});

        AdminActionPacket packet = (AdminActionPacket) sentPacket(client);
        assertThat(packet.getPlayerId().toString()).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
    }

    @Test
    @DisplayName("announce needs a channel and a body")
    void announceValidatesArguments() {
        new AnnounceCommand(plugin).execute(operator, new String[]{"global"});

        verifyNoPacket(client);
        assertMessageContains(operator, "/nc announce");
    }

    @Test
    @DisplayName("title sends a STATUS packet typed TITLE with title and subtitle")
    void titleSendsPacket() {
        new TitleCommand(plugin).execute(operator,
                new String[]{"global", "Welcome", "to", "the", "server"});

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
        new TitleCommand(plugin).execute(operator, new String[]{"global", "Welcome"});

        assertThat(((AdminActionPacket) sentPacket(client)).getExtra("subtitle")).isEmpty();
    }

    @Test
    @DisplayName("console title carries the sentinel UUID")
    void consoleTitleUsesSentinel() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");

        new TitleCommand(plugin).execute(console, new String[]{"global", "Welcome"});

        AdminActionPacket packet = (AdminActionPacket) sentPacket(client);
        assertThat(packet.getPlayerId().toString()).isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
    }

    @Test
    @DisplayName("both shells refuse a disconnected backend")
    void disconnectedBackendRefuses() {
        when(client.isConnected()).thenReturn(false);

        new AnnounceCommand(plugin).execute(operator, new String[]{"global", "hello"});
        new TitleCommand(plugin).execute(operator, new String[]{"global", "Welcome"});

        verifyNoPacket(client);
        assertReportsRequestFailed(operator);
    }

    @Test
    @DisplayName("auth sends an AUTH packet with the SHA-256 hash and the player name")
    void authSendsHashedPassword() {
        new AuthCommand(plugin).execute(operator, new String[]{"s3cret"});

        AdminActionPacket packet = (AdminActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(AdminAction.AUTH);
        assertThat(packet.getPlayerId()).isEqualTo(OPERATOR_ID);
        assertThat(packet.getPasswordHash())
                .isEqualTo(PasswordHasher.sha256Hex("s3cret"))
                .isNotEqualTo("s3cret");
        assertThat(packet.getExtra("playerName")).isEqualTo("Mod");
    }

    @Test
    @DisplayName("auth is player-only and hidden from help")
    void authIsPlayerOnlyAndHidden() {
        AuthCommand auth = new AuthCommand(plugin);

        assertThat(auth.isPlayerOnly()).isTrue();
        assertThat(auth.isHidden()).isTrue();
        assertThat(auth.getPermission()).isEqualTo("novachat.auth");
    }

    @Test
    @DisplayName("auth without a password prints usage")
    void authWithoutPasswordShowsUsage() {
        new AuthCommand(plugin).execute(operator, new String[0]);

        verifyNoPacket(client);
        assertMessageContains(operator, "/nc auth");
    }

    @Test
    @DisplayName("auth never completes a password from tab completion")
    void authTabCompleteIsEmpty() {
        assertThat(new AuthCommand(plugin).tabComplete(operator, new String[]{""})).isEmpty();
    }
}
