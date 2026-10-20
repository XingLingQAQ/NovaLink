package com.nova.chat.folia.command;

import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.network.AsyncNetworkClient;
import org.bukkit.command.CommandSender;
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
 * Unit tests for the Folia {@code /nc auth} shell.
 *
 * <p>The packet contract is what the backend keys off — action {@code AUTH},
 * the SHA-256 hex hash, the sender UUID and the {@code playerName} extra — so
 * that is what these cases pin. The sibling facts (hidden from help, reachable
 * through {@code /nc}) are asserted alongside it, because a correct shell that
 * is absent from registration or shows up in help is still wrong.
 */
@DisplayName("Folia auth command")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthCommandTest extends CommandTestSupport {

    private static final UUID OPERATOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** SHA-256("secret"), lowercase hex. */
    private static final String SECRET_SHA256 =
            "2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b";

    @Mock
    private NovaChatFolia plugin;
    @Mock
    private AsyncNetworkClient client;
    @Mock
    private Player operator;

    private AuthCommand auth;

    @BeforeEach
    void setUp() {
        wireConnected(plugin, client);
        when(operator.getUniqueId()).thenReturn(OPERATOR_ID);
        when(operator.getName()).thenReturn("Admin");
        when(operator.hasPermission("novachat.auth")).thenReturn(true);
        auth = new AuthCommand(plugin);
    }

    @Test
    @DisplayName("sends an AUTH packet carrying the SHA-256 hash and the sender identity")
    void sendsAuthPacket() {
        auth.execute(operator, new String[]{"secret"});

        AdminActionPacket packet = (AdminActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(AdminAction.AUTH);
        assertThat(packet.getPlayerId()).isEqualTo(OPERATOR_ID);
        assertThat(packet.getPasswordHash()).isEqualTo(SECRET_SHA256);
        assertThat(packet.getExtra("playerName")).isEqualTo("Admin");
        assertThat(packet.getRequestId()).isNotNull();
    }

    @Test
    @DisplayName("reports progress and never echoes the password back")
    void reportsProgressWithoutEchoingPassword() {
        auth.execute(operator, new String[]{"secret"});

        assertMessageContains(operator, "正在验证超级管理员身份");
        assertThat(messagesTo(operator))
                .as("the plaintext password must never be rendered")
                .allSatisfy(message -> assertThat(message).doesNotContain("secret"));
    }

    @Test
    @DisplayName("without a password it shows the usage instead of sending a blank hash")
    void withoutPasswordShowsUsage() {
        auth.execute(operator, new String[0]);

        verifyNoPacket(client);
        assertMessageContains(operator, "/nc auth");
    }

    @Test
    @DisplayName("a disconnected backend is refused before any packet leaves")
    void disconnectedBackendRefuses() {
        when(client.isConnected()).thenReturn(false);

        auth.execute(operator, new String[]{"secret"});

        verifyNoPacket(client);
        assertMessageContains(operator, "NC-500");
    }

    @Test
    @DisplayName("a send that fails despite the connected check reports NC-503")
    void failedSendReportsRequestFailed() {
        // checkConnection passes, then the send races a disconnect.
        when(client.isConnected()).thenReturn(true, false);

        auth.execute(operator, new String[]{"secret"});

        assertReportsRequestFailed(operator);
    }

    @Test
    @DisplayName("is hidden from help but still dispatchable through /nc")
    void hiddenButRegistered() {
        NovaChatCommand dispatcher = new NovaChatCommand(plugin);

        assertThat(dispatcher.getSubCommands()).containsKey("auth");
        assertThat(dispatcher.getSubCommands().get("auth").isHidden()).isTrue();
        assertThat(dispatcher.getAvailableSubCommands(operator))
                .as("a hidden command must never appear in the help roster")
                .doesNotContain("auth");
    }

    @Test
    @DisplayName("is player-only and gated on novachat.auth")
    void playerOnlyAndPermissionGated() {
        assertThat(auth.isPlayerOnly()).isTrue();
        assertThat(auth.getPermission()).isEqualTo("novachat.auth");

        CommandSender console = mock(ConsoleCommandSender.class);
        assertThat(auth.hasPermission(operator)).isTrue();
        when(operator.hasPermission("novachat.auth")).thenReturn(false);
        assertThat(auth.hasPermission(operator)).isFalse();
        assertThat(console.hasPermission("novachat.auth")).isFalse();
    }
}
