package com.nova.chat.folia.command;

import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.network.AsyncNetworkClient;
import org.bukkit.command.Command;
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
 * Tests for {@link NovaChatCommand}'s dispatch guards.
 *
 * <p>A shell can be perfectly correct and still unusable if the dispatcher
 * lets the wrong sender through — or never reaches it. These cases pin the
 * permission gate, the player-only gate, and the unknown-command path, all of
 * which sit in front of {@code execute}.
 */
@DisplayName("Folia NovaChat dispatch")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NovaChatCommandDispatchTest extends CommandTestSupport {

    private static final UUID OPERATOR_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatFolia plugin;
    @Mock
    private AsyncNetworkClient client;
    @Mock
    private Command command;

    private NovaChatCommand dispatcher;

    @BeforeEach
    void setUp() {
        wireConnected(plugin, client);
        dispatcher = new NovaChatCommand(plugin);
    }

    @Test
    @DisplayName("without novachat.ban the ban shell is never reached")
    void missingPermissionBlocksTheShell() {
        Player sender = operator(false);

        boolean handled = dispatcher.onCommand(sender, command, "nc", new String[]{"ban", "Bob", "1h"});

        assertThat(handled).isTrue();
        verifyNoPacket(client);
        assertMessageContains(sender, "NC-403");
    }

    @Test
    @DisplayName("with novachat.ban the ban shell runs")
    void grantedPermissionReachesTheShell() {
        Player sender = operator(true);

        dispatcher.onCommand(sender, command, "nc", new String[]{"ban", "Bob", "1h"});

        verifyAnyPacket(client);
    }

    @Test
    @DisplayName("a player-only shell refuses the console instead of casting it")
    void playerOnlyShellRefusesConsole() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission("novachat.create")).thenReturn(true);
        when(console.getName()).thenReturn("CONSOLE");

        dispatcher.onCommand(console, command, "nc", new String[]{"create", "staff"});

        verifyNoPacket(client);
        assertMessageContains(console, "只能由玩家执行");
    }

    @Test
    @DisplayName("an unknown sub-command names itself and points at help")
    void unknownSubCommand() {
        CommandSender sender = operator(true);

        dispatcher.onCommand(sender, command, "nc", new String[]{"nope"});

        verifyNoPacket(client);
        assertMessageContains(sender, "nope");
    }

    @Test
    @DisplayName("no arguments shows help")
    void noArgumentsShowsHelp() {
        CommandSender sender = operator(true);

        dispatcher.onCommand(sender, command, "nc", new String[0]);

        assertThat(messagesTo(sender)).isNotEmpty();
    }

    @Test
    @DisplayName("moderation shells are hidden from the tab-completion roster only by permission")
    void tabCompletionRespectsPermissions() {
        Player allowed = operator(true);
        Player denied = operator(false);

        assertThat(dispatcher.getAvailableSubCommands(allowed))
                .contains("ban", "unban", "mute", "kick", "announce", "title");
        assertThat(dispatcher.getAvailableSubCommands(denied))
                .doesNotContain("ban", "unban", "mute", "kick", "announce", "title");
    }

    private Player operator(boolean permitted) {
        Player sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(OPERATOR_ID);
        when(sender.getName()).thenReturn("Mod");
        for (String node : new String[]{"novachat.ban", "novachat.mute", "novachat.kick",
                "novachat.announce", "novachat.title", "novachat.help"}) {
            when(sender.hasPermission(node)).thenReturn(permitted);
        }
        return sender;
    }
}
