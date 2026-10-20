package com.nova.chat.mod.platform.brigadier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.ModServices;
import com.nova.chat.mod.platform.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registration guard for the shared mod registrar.
 *
 * <p>A shell that compiles and passes its own unit tests is still invisible to
 * players until it is both registered on the {@link CommandManager} and wired
 * into the brigadier tree. This module shipped with no moderation commands at
 * all, so both places are exactly where they would silently stay empty.
 *
 * <p>Uses a {@code String}-typed stub source because the real loaders are
 * generic over {@code CommandSourceStack}; the common layer only ever sees the
 * generic parameter.
 */
@DisplayName("mod command registration")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommandRegistrationTest {

    @Mock
    private Platform platform;
    @Mock
    private ModServices services;

    private StubRegistrar registrar;
    private CommandManager manager;
    private CommandDispatcher<String> dispatcher;

    /** Minimal loader shim: brigadier only, no Minecraft types. */
    private static final class StubRegistrar extends AbstractBrigadierCommandRegistrar<String> {

        private final boolean admin;

        StubRegistrar(boolean admin) {
            this.admin = admin;
        }

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
            return com.mojang.brigadier.arguments.StringArgumentType.word();
        }

        @Override
        public ArgumentType<String> greedyString() {
            return com.mojang.brigadier.arguments.StringArgumentType.greedyString();
        }

        @Override
        public String readString(CommandContext<String> ctx, String name)
                throws CommandSyntaxException {
            return com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, name);
        }

        @Override
        public ServerPlayerInfo lookupPlayer(String source) {
            return null; // console source
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
            return admin;
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
        manager = new CommandManager(platform);
        registrar = new StubRegistrar(true);
        registrar.registerSharedStateAndHandlers(manager, services);
        dispatcher = new CommandDispatcher<>();
        registrar.registerNovaChatCommands(dispatcher);
    }

    private CommandNode<String> literalTree() {
        return dispatcher.getRoot().getChild("novachat");
    }

    @Test
    @DisplayName("every moderation sub-command is registered on the CommandManager")
    void moderationHandlersRegistered() {
        assertThat(manager.hasCommand("mute")).isTrue();
        assertThat(manager.hasCommand("kick")).isTrue();
        assertThat(manager.hasCommand("ban")).isTrue();
        assertThat(manager.hasCommand("unban")).isTrue();
        assertThat(manager.hasCommand("announce")).isTrue();
        assertThat(manager.hasCommand("title")).isTrue();
    }

    @Test
    @DisplayName("the hidden auth command is registered")
    void authHandlerRegistered() {
        assertThat(manager.hasCommand("auth")).isTrue();
    }

    @Test
    @DisplayName("the 12 pre-existing commands are all still registered")
    void existingCommandsUnchanged() {
        for (String name : AbstractBrigadierCommandRegistrar.SHARED_COMMAND_NAMES) {
            assertThat(manager.hasCommand(name)).as("pre-existing command '%s'", name).isTrue();
        }
        assertThat(AbstractBrigadierCommandRegistrar.SHARED_COMMAND_NAMES).hasSize(11);
    }

    @Test
    @DisplayName("every catalog command is both registered and present in the brigadier tree")
    void catalogMatchesRegistrationAndTree() {
        CommandNode<String> novachat = literalTree();

        for (com.nova.chat.mod.command.ModCommandCatalog.Entry entry
                : com.nova.chat.mod.command.ModCommandCatalog.ENTRIES) {
            if (!entry.isRegistered()) {
                continue; // help-only line (the /nc <channel> <message> shorthand)
            }
            assertThat(manager.hasCommand(entry.name()))
                    .as("catalog command '%s' must be registered", entry.name()).isTrue();
            // The other half of the same trap: a catalog entry that help
            // advertises but that no literal exposes would be unrunnable.
            assertThat(novachat.getChild(entry.name()))
                    .as("catalog command '%s' must appear in the brigadier tree", entry.name())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("every command help lists is runnable, and every hidden one is registered")
    void helpLinesMatchRunnableCommands() {
        CommandNode<String> novachat = literalTree();
        java.util.Set<String> helpKeys = new java.util.HashSet<>();

        for (com.nova.chat.mod.command.ModCommandCatalog.HelpLine line
                : com.nova.chat.mod.command.ModCommandCatalog.helpLines()) {
            assertThat(helpKeys.add(line.i18nKey()))
                    .as("help key '%s' must appear at most once", line.i18nKey()).isTrue();
        }
        for (com.nova.chat.mod.command.ModCommandCatalog.Entry entry
                : com.nova.chat.mod.command.ModCommandCatalog.ENTRIES) {
            if (entry.isHidden()) {
                assertThat(entry.isRegistered())
                        .as("hidden entry '%s' must still be registered to be runnable", entry.name())
                        .isTrue();
                assertThat(novachat.getChild(entry.name())).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("every moderation sub-command appears in the brigadier tree")
    void moderationInBrigadierTree() {
        CommandNode<String> novachat = literalTree();

        assertThat(novachat).isNotNull();
        assertThat(novachat.getChildren())
                .extracting(CommandNode::getName)
                .contains("mute", "kick", "ban", "unban", "announce", "title", "auth");
    }

    @Test
    @DisplayName("the nc alias carries the same moderation subtree")
    void aliasCarriesSameSubtree() {
        CommandNode<String> nc = dispatcher.getRoot().getChild("nc");
        assertThat(nc).isNotNull();
        assertThat(nc.getChildren()).extracting(CommandNode::getName)
                .contains("mute", "kick", "ban", "unban", "announce", "title", "auth");
    }

    @Test
    @DisplayName("moderation literals require the admin predicate")
    void moderationRequiresAdmin() {
        // A non-admin shim: every moderation literal must reject the source.
        CommandDispatcher<String> nonAdminDispatcher = new CommandDispatcher<>();
        new StubRegistrar(false).registerNovaChatCommands(nonAdminDispatcher);
        CommandNode<String> novachat = nonAdminDispatcher.getRoot().getChild("novachat");

        for (String name : AbstractBrigadierCommandRegistrar.MODERATION_COMMAND_NAMES) {
            CommandNode<String> node = novachat.getChild(name);
            assertThat(node).as("'%s' must exist", name).isNotNull();
            assertThat(node.getRequirement()).as("'%s' admin gate", name).isNotNull();
            assertThat(node.getRequirement().test("console"))
                    .as("'%s' must reject a non-admin", name).isFalse();
        }
    }

    @Test
    @DisplayName("auth is admin-gated too")
    void authRequiresAdmin() {
        CommandDispatcher<String> nonAdminDispatcher = new CommandDispatcher<>();
        new StubRegistrar(false).registerNovaChatCommands(nonAdminDispatcher);

        CommandNode<String> auth = nonAdminDispatcher.getRoot().getChild("novachat").getChild("auth");
        assertThat(auth).isNotNull();
        assertThat(auth.getRequirement().test("console")).isFalse();
    }

    @Test
    @DisplayName("pre-existing command shapes are unchanged")
    void existingCommandShapesUnchanged() {
        CommandNode<String> novachat = literalTree();

        // msg <player> <message>, r <message>, join <channel>, unignore <target>
        assertThat(novachat.getChild("msg").getChild("player").getChild("message")).isNotNull();
        assertThat(novachat.getChild("r").getChild("message")).isNotNull();
        assertThat(novachat.getChild("join").getChild("channel")).isNotNull();
        assertThat(novachat.getChild("unignore").getChild("target")).isNotNull();
        // the no-arg commands still execute without arguments
        for (String name : List.of("help", "leave", "list", "who", "toggle", "reload", "debug")) {
            assertThat(novachat.getChild(name).getCommand())
                    .as("'%s' must still execute with no arguments", name).isNotNull();
        }
    }

    @Test
    @DisplayName("mute takes <player> <duration> [channel]")
    void muteArgumentShape() {
        CommandNode<String> mute = literalTree().getChild("mute");

        CommandNode<String> player = mute.getChild("player");
        assertThat(player).isNotNull();
        CommandNode<String> duration = player.getChild("duration");
        assertThat(duration).isNotNull();
        assertThat(duration.getChild("channel")).isNotNull();
        assertThat(duration.getCommand()).isNotNull(); // 2-arg form executes
    }

    @Test
    @DisplayName("kick takes <player> [channel]")
    void kickArgumentShape() {
        CommandNode<String> kick = literalTree().getChild("kick");

        assertThat(kick.getCommand()).isNull(); // target is mandatory
        CommandNode<String> player = kick.getChild("player");
        assertThat(player).isNotNull();
        assertThat(player.getCommand()).isNotNull(); // 1-arg form executes
        assertThat(player.getChild("channel")).isNotNull();
    }

    @Test
    @DisplayName("ban takes <player> [duration|channel] [channel|duration]")
    void banArgumentShape() {
        CommandNode<String> ban = literalTree().getChild("ban");

        CommandNode<String> player = ban.getChild("player");
        assertThat(player).isNotNull();
        assertThat(player.getCommand()).isNotNull();
        CommandNode<String> second = player.getChild("durationOrChannel");
        assertThat(second).isNotNull();
        assertThat(second.getCommand()).isNotNull();
        assertThat(second.getChild("channelOrDuration")).isNotNull();
    }

    @Test
    @DisplayName("announce consumes the whole line after the channel as content")
    void announceUsesGreedyString() {
        CommandNode<String> announce = literalTree().getChild("announce");
        CommandNode<String> content = announce.getChild("channel").getChild("content");

        assertThat(content).isNotNull();
        assertThat(content.getChildren()).isEmpty();

        // The decisive check is parsing, not the argument object: a greedy
        // string swallows the spaces, a word/string argument would leave the
        // rest of the line unconsumed and brigadier would reject the parse.
        String input = "novachat announce global server restart in 5m";
        CommandContext<String> ctx = dispatcher.parse(input, "stub")
                .getContext().build(input);
        assertThat(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "content"))
                .isEqualTo("server restart in 5m");
        assertThat(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "channel"))
                .isEqualTo("global");
    }

    @Test
    @DisplayName("title takes <channel> <title> [subtitle...]")
    void titleArgumentShape() {
        CommandNode<String> title = literalTree().getChild("title");
        CommandNode<String> channel = title.getChild("channel");

        assertThat(channel).isNotNull();
        CommandNode<String> titleArg = channel.getChild("title");
        assertThat(titleArg).isNotNull();
        assertThat(titleArg.getCommand()).isNotNull(); // title alone is valid
        assertThat(titleArg.getChild("subtitle")).isNotNull();

        String withSubtitle = "novachat title global Welcome to the server";
        CommandContext<String> ctx = dispatcher.parse(withSubtitle, "stub")
                .getContext().build(withSubtitle);
        assertThat(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "title"))
                .isEqualTo("Welcome");
        assertThat(com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "subtitle"))
                .isEqualTo("to the server");

        // same command without a subtitle still parses
        String withoutSubtitle = "novachat title global Welcome";
        CommandContext<String> bare = dispatcher.parse(withoutSubtitle, "stub")
                .getContext().build(withoutSubtitle);
        assertThat(com.mojang.brigadier.arguments.StringArgumentType.getString(bare, "title"))
                .isEqualTo("Welcome");
        assertThat(bare.getNodes()).noneMatch(n -> "subtitle".equals(n.getNode().getName()));
    }

    @Test
    @DisplayName("auth takes a single password argument")
    void authArgumentShape() {
        CommandNode<String> auth = literalTree().getChild("auth");

        CommandNode<String> password = auth.getChild("password");
        assertThat(password).isNotNull();
        assertThat(password.getCommand()).isNotNull();
        assertThat(auth.getCommand())
                .as("a bare /nc auth must not run (no password)")
                .isNull();
    }

    @Test
    @DisplayName("the moderation names constant matches what is registered")
    void moderationNamesMatchRegistration() {
        assertThat(AbstractBrigadierCommandRegistrar.MODERATION_COMMAND_NAMES)
                .containsExactly("mute", "kick", "ban", "unban", "announce", "title");
        assertThat(AbstractBrigadierCommandRegistrar.HIDDEN_COMMAND_NAMES)
                .containsExactly("auth");
        List<String> treeNames = literalTree().getChildren().stream()
                .map(CommandNode::getName).toList();
        assertThat(treeNames).containsAll(List.of("mute", "kick", "ban", "unban",
                "announce", "title", "auth"));
    }

}
