package com.nova.chat.mod.platform.brigadier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.ModServices;
import com.nova.chat.mod.platform.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the console/RCON dispatch path added for the moderation shells, and
 * the boundary that keeps it away from every other command.
 *
 * <p>The shared registrar's default path is player-only. Letting the console
 * through unconditionally would have let it run {@code /nc msg}, {@code /nc r}
 * or {@code /nc join} with a fake sentinel identity, so only handlers that opt
 * in via {@link com.nova.chat.mod.platform.ConsoleCapableCommand} are
 * dispatched. Both sides of that boundary are asserted here.
 */
@DisplayName("console dispatch")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsoleDispatchTest {

    @Mock
    private Platform platform;
    @Mock
    private com.nova.chat.mod.network.NetworkClient networkClient;
    @Mock
    private ModServices services;

    private final List<String> sentMessages = new ArrayList<>();
    private CommandDispatcher<String> dispatcher;

    /** Console-only shim: {@link #lookupPlayer} always returns null. */
    private static final class ConsoleRegistrar
            extends AbstractBrigadierCommandRegistrar<String> {

        @Override
        public LiteralArgumentBuilder<String> literal(String name) {
            return LiteralArgumentBuilder.literal(name);
        }

        @Override
        public RequiredArgumentBuilder<String, String> argument(
                String name, ArgumentType<String> type) {
            return RequiredArgumentBuilder.argument(name, type);
        }

        @Override
        public ArgumentType<String> word() {
            return StringArgumentType.word();
        }

        @Override
        public ArgumentType<String> greedyString() {
            return StringArgumentType.greedyString();
        }

        @Override
        public String readString(CommandContext<String> ctx, String name)
                throws CommandSyntaxException {
            return StringArgumentType.getString(ctx, name);
        }

        @Override
        public ServerPlayerInfo lookupPlayer(String source) {
            return null; // console / command block / rcon
        }

        @Override
        public Platform getPlatform() {
            return null;
        }

        @Override
        public com.nova.chat.mod.platform.CommandContext createContext(
                UUID playerId, String playerName, Platform platform,
                boolean isAdmin, String source) {
            return new com.nova.chat.mod.platform.CommandContext(
                    playerId, playerName, platform, isAdmin);
        }

        @Override
        public boolean isAdmin(String source) {
            return true; // an op-level console
        }

        @Override
        public void sendSuccess(String source, String message) {
        }

        @Override
        public void sendFailure(String source, String message) {
        }

        @Override
        public ModDebugInfo debugInfo(String source) {
            return new ModDebugInfo("", 0, false, false);
        }

        @Override
        public String platformDisplayName() {
            return "Stub";
        }
    }

    @BeforeEach
    void setUp() {
        sentMessages.clear();
        when(networkClient.isConnected()).thenReturn(true);
        when(networkClient.isAuthenticated()).thenReturn(true);
        when(services.getNetworkClient()).thenReturn(networkClient);
        doAnswer(inv -> {
            sentMessages.add((String) inv.getArgument(1));
            return null;
        }).when(platform).sendMessage(any(UUID.class), anyString());

        ConsoleRegistrar registrar = new ConsoleRegistrar();
        registrar.registerSharedStateAndHandlers(new CommandManager(platform), services);
        dispatcher = new CommandDispatcher<>();
        registrar.registerNovaChatCommands(dispatcher);
    }

    private int execute(String command) {
        try {
            return dispatcher.execute(command, "console");
        } catch (CommandSyntaxException | IllegalArgumentException e) {
            throw new AssertionError("command should have parsed: " + command, e);
        }
    }

    @Test
    @DisplayName("console /nc mute reaches the handler with the sentinel identity")
    void consoleMuteRunsWithSentinel() {
        int result = execute("novachat mute Bob 1h global");

        assertThat(result).isEqualTo(1);
        ArgumentCaptor<ChannelActionPacket> captor =
                ArgumentCaptor.forClass(ChannelActionPacket.class);
        verify(networkClient).sendPacket(captor.capture());
        ChannelActionPacket packet = captor.getValue();
        assertThat(packet.getAction()).isEqualTo(ChannelAction.MUTE);
        assertThat(packet.getExtra("operatorId"))
                .isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(packet.getExtra("console")).isEqualTo("true");
        assertThat(packet.getExtra("operatorName"))
                .isEqualTo(AbstractBrigadierCommandRegistrar.CONSOLE_SENDER_NAME);
    }

    @Test
    @DisplayName("console /nc announce reaches the handler with the sentinel identity")
    void consoleAnnounceRunsWithSentinel() {
        int result = execute("novachat announce global server restart");

        assertThat(result).isEqualTo(1);
        ArgumentCaptor<com.nova.chat.common.protocol.packets.AdminActionPacket> captor =
                ArgumentCaptor.forClass(
                        com.nova.chat.common.protocol.packets.AdminActionPacket.class);
        verify(networkClient).sendPacket(captor.capture());
        assertThat(captor.getValue().getPlayerId().toString())
                .isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
        assertThat(captor.getValue().getExtra("console")).isEqualTo("true");
    }

    @Test
    @DisplayName("console cannot run a player-only command")
    void consoleRefusesPlayerOnlyCommand() {
        int result = execute("novachat help");

        assertThat(result).isZero();
        assertThat(sentMessages)
                .as("HelpCommand must not have run for a console sender")
                .isEmpty();
        verify(networkClient, never()).sendPacket(any());
    }

    @Test
    @DisplayName("the player-only refusal covers the private-message commands too")
    void consoleRefusesPrivateMessages() {
        assertThat(execute("novachat msg Bob hi")).isZero();
        assertThat(execute("novachat r hi")).isZero();
        assertThat(execute("novachat join global")).isZero();

        verify(networkClient, never()).sendPacket(any());
    }
}
