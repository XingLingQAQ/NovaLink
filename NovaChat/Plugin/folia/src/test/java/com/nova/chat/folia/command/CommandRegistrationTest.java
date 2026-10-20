package com.nova.chat.folia.command;

import com.nova.chat.folia.NovaChatFolia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that every Folia sub-command class is reachable through {@code /nc}.
 *
 * <p>Mirrors the Bukkit registration test: a shell that exists, compiles and
 * passes its own unit tests is still invisible to players until it is
 * registered here. This module shipped without any moderation shells at all,
 * so the registration map is the place that would silently stay empty.
 */
@DisplayName("Folia command registration")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommandRegistrationTest {

    @Mock
    private NovaChatFolia plugin;

    @Test
    @DisplayName("every moderation sub-command is registered")
    void moderationCommandsAreRegistered() {
        NovaChatCommand command = new NovaChatCommand(plugin);

        assertThat(command.getSubCommands())
                .as("an unregistered sub-command is a command the player cannot run")
                .containsKeys("mute", "kick", "ban", "unban", "announce", "title");
    }

    @Test
    @DisplayName("every channel lifecycle sub-command is registered")
    void channelLifecycleCommandsAreRegistered() {
        NovaChatCommand command = new NovaChatCommand(plugin);

        assertThat(command.getSubCommands()).containsKeys("create", "invite", "accept");
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

    @Test
    @DisplayName("every moderation node is defined in plugin.yml, not only in code")
    void permissionsAreDeclared() {
        // Declared in src/main/resources/plugin.yml; a shell gated on an
        // undeclared node is a permission operators cannot grant deliberately.
        String[] declared = {"novachat.mute", "novachat.kick", "novachat.ban",
                "novachat.announce", "novachat.title"};

        NovaChatCommand command = new NovaChatCommand(plugin);
        assertThat(command.getSubCommands().values())
                .extracting(SubCommand::getPermission)
                .contains(declared);
    }
}
