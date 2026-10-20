package com.nova.chat.bukkit.command;

import com.nova.chat.bukkit.NovaChatBukkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that every sub-command class is reachable through {@code /nc}.
 *
 * <p>A sub-command that exists, compiles and passes its own unit tests is still
 * invisible to players until it is registered here. The ban and unban pair
 * showed exactly that gap: both shells were written and tested while only
 * {@code ban} was wired up, so {@code /nc unban} would tell the operator the
 * command does not exist.
 */
@DisplayName("NovaChat command registration")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommandRegistrationTest {

    @Mock
    private NovaChatBukkit plugin;

    @Test
    @DisplayName("every moderation sub-command is registered")
    void moderationCommandsAreRegistered() {
        NovaChatCommand command = new NovaChatCommand(plugin);

        assertThat(command.getSubCommands())
                .as("an unregistered sub-command is a command the player cannot run")
                .containsKeys("mute", "kick", "ban", "unban", "announce", "title");
    }

    @Test
    @DisplayName("the registered shell matches the command name")
    void registeredShellsAgreeWithTheirKeys() {
        NovaChatCommand command = new NovaChatCommand(plugin);

        assertThat(command.getSubCommands())
                .allSatisfy((name, sub) -> assertThat(sub.getName())
                        .as("key '%s' must dispatch to a shell that calls itself the same", name)
                        .isEqualTo(name));
    }
}
