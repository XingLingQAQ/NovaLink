package com.nova.chat.pnx.command;

import org.powernukkitx.Player;
import org.powernukkitx.command.CommandSender;
import com.nova.chat.pnx.NovaChatPNX;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Covers the tab-completion dispatcher on {@link NovaChatCommand}.
 *
 * <p>PowerNukkitX's Bedrock client drives visual completion via
 * {@code CommandParameter} overloads, but the Java-side {@code tabComplete}
 * contract is kept consistent with Nukkit/Bukkit so other dispatchers
 * (tests, custom command frontends, future PNX APIs) can route through it.
 */
@DisplayName("PNX NovaChatCommand.tabComplete dispatcher")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NovaChatCommandTabCompleteTest {

    @Mock
    private NovaChatPNX plugin;

    @Mock
    private CommandSender consoleSender;

    @Mock
    private Player player;

    private NovaChatCommand newCommand() {
        return new NovaChatCommand(plugin);
    }

    @Test
    @DisplayName("empty args returns all non-hidden sub-commands")
    void emptyArgsReturnsAllSubCommands() {
        NovaChatCommand cmd = newCommand();
        when(consoleSender.hasPermission(anyString())).thenReturn(true);

        List<String> completions = cmd.tabComplete(consoleSender, new String[]{});

        // 13 sub-commands registered; hidden ones are filtered out.
        assertThat(completions)
                .contains("help", "join", "leave", "list", "who", "toggle",
                        "ignore", "unignore", "msg", "r", "channel",
                        "reload", "debug");
    }

    @Test
    @DisplayName("first-arg prefix filters sub-command names case-insensitively")
    void firstArgPrefixFilters() {
        NovaChatCommand cmd = newCommand();
        when(consoleSender.hasPermission(anyString())).thenReturn(true);

        List<String> completions = cmd.tabComplete(consoleSender, new String[]{"J"});

        assertThat(completions).containsExactly("join");
    }

    @Test
    @DisplayName("unknown first arg returns empty list")
    void unknownFirstArgReturnsEmpty() {
        NovaChatCommand cmd = newCommand();
        when(consoleSender.hasPermission(anyString())).thenReturn(true);

        List<String> completions = cmd.tabComplete(consoleSender, new String[]{"nope", "anything"});

        assertThat(completions).isEmpty();
    }

    @Test
    @DisplayName("permission-gated entries are dropped when the sender lacks the node")
    void permissionFiltersSubCommands() {
        NovaChatCommand cmd = newCommand();
        // Deny every permission node. Commands with getPermission() == null
        // still pass; admin-gated ones (reload / debug) must be filtered out.
        when(consoleSender.hasPermission(anyString())).thenReturn(false);

        List<String> completions = cmd.tabComplete(consoleSender, new String[]{});

        assertThat(completions)
                .doesNotContain("reload", "debug")
                .contains("help", "join", "leave", "list", "who", "toggle",
                        "ignore", "unignore", "msg", "r", "channel");
    }

    @Test
    @DisplayName("routes to the matched sub-command's tabComplete when args.length > 1")
    void delegatesToSubCommandWhenArgsPresent() {
        NovaChatCommand cmd = newCommand();
        // Player types "/nc join <Tab>" -> should hit JoinCommand.tabComplete.
        // With no KnownChannelRegistry wired on the mocked plugin the fallback
        // list {global, local} is returned.
        List<String> completions = cmd.tabComplete(player, new String[]{"join", ""});

        assertThat(completions).containsExactlyInAnyOrder("global", "local");
    }

    @Test
    @DisplayName("admin-gated sub-command routes no sub-arg completions when permission denied")
    void noPermissionYieldsNoSubArgCompletions() {
        NovaChatCommand cmd = newCommand();
        when(player.hasPermission(anyString())).thenReturn(false);

        List<String> completions = cmd.tabComplete(player, new String[]{"reload", ""});

        assertThat(completions).isEmpty();
    }
}
