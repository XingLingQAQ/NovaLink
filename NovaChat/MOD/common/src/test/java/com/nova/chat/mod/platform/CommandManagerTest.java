package com.nova.chat.mod.platform;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CommandManager}: case-insensitive registration,
 * dispatch, unknown-command handling, and tab-completion forwarding.
 */
@DisplayName("CommandManager")
class CommandManagerTest {

    private static final UUID PLAYER = UUID.fromString("12345678-1234-1234-1234-123456789012");
    private CommandManager manager;
    private Platform platform;

    @BeforeEach
    void setUp() {
        platform = new RecordingPlatform();
        manager = new CommandManager(platform);
    }

    private CommandContext context() {
        return new CommandContext(PLAYER, "Steve", platform, false);
    }

    @Test
    @DisplayName("registerCommand stores the handler (case-insensitive name)")
    void registersHandlerCaseInsensitively() {
        RecordingHandler handler = new RecordingHandler();
        manager.registerCommand("Join", handler);

        assertThat(manager.hasCommand("join")).isTrue();
        assertThat(manager.hasCommand("JOIN")).isTrue();
        assertThat(manager.hasCommand("Join")).isTrue();
        assertThat(manager.getCommand("join")).isSameAs(handler);
    }

    @Test
    @DisplayName("executeCommand dispatches to the registered handler (case-insensitive)")
    void executesRegisteredHandler() {
        RecordingHandler handler = new RecordingHandler();
        manager.registerCommand("who", handler);

        boolean ok = manager.executeCommand("WHO", new String[]{"trade"}, context());

        assertThat(ok).isTrue();
        assertThat(handler.invoked).isTrue();
        assertThat(handler.receivedArgs).containsExactly("trade");
    }

    @Test
    @DisplayName("executeCommand returns false for an unknown command")
    void unknownCommandReturnsFalse() {
        boolean ok = manager.executeCommand("nope", new String[]{}, context());
        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("tabComplete forwards to the handler when registered")
    void tabCompleteForwardsToHandler() {
        RecordingHandler handler = new RecordingHandler(Arrays.asList("global", "trade"));
        manager.registerCommand("join", handler);

        List<String> suggestions = manager.tabComplete("join", new String[]{"g"});

        assertThat(suggestions).containsExactly("global", "trade");
    }

    @Test
    @DisplayName("tabComplete returns empty list for an unknown command")
    void tabCompleteUnknownReturnsEmpty() {
        assertThat(manager.tabComplete("nope", new String[]{})).isEmpty();
    }

    @Test
    @DisplayName("hasCommand returns false for an unregistered name")
    void hasCommandFalseForUnregistered() {
        assertThat(manager.hasCommand("unknown")).isFalse();
    }

    @Test
    @DisplayName("getCommand returns null for an unregistered name")
    void getCommandNullForUnregistered() {
        assertThat(manager.getCommand("unknown")).isNull();
    }

    @Test
    @DisplayName("getPlatform returns the constructor platform")
    void getPlatformReturnsConstructorPlatform() {
        assertThat(manager.getPlatform()).isSameAs(platform);
    }

    // --------------------------- helpers ---------------------------

    /** Minimal Platform stub that records sent messages. */
    private static final class RecordingPlatform implements Platform {
        final java.util.List<String> sent = new java.util.ArrayList<>();

        @Override public void registerChatListener(ChatHandler handler) {}
        @Override public void registerCommands(CommandManager manager) {}
        @Override public void sendMessage(UUID playerId, Object message) {
            if (message instanceof String s) sent.add(s);
        }
        @Override public void broadcastMessage(Object message) {}
        @Override public String getCurrentWorld(UUID playerId) { return "world"; }
        @Override public String getHeldItemJson(UUID playerId) { return null; }
        @Override public String getPlayerName(UUID playerId) { return "Steve"; }
        @Override public boolean isPlayerOnline(UUID playerId) { return true; }
        @Override public java.util.Collection<UUID> getOnlinePlayerIds() { return List.of(PLAYER); }
        @Override public PlatformType getPlatformType() { return PlatformType.FABRIC; }
        @Override public void runAsync(Runnable task) { task.run(); }
        @Override public void runLater(Runnable task, long delaySeconds) { task.run(); }
        @Override public void execute(Runnable task) { task.run(); }
        @Override public void logInfo(String message) {}
        @Override public void logWarn(String message) {}
        @Override public void logDebug(String message) {}
        @Override public void logError(String message) {}
        @Override public void logError(String message, Throwable cause) {}
        @Override public String getServerVersion() { return "1.21.11"; }
    }

    private static final class RecordingHandler implements CommandHandler {
        private boolean invoked = false;
        private String[] receivedArgs;
        private final List<String> completions;

        RecordingHandler() { this(Collections.emptyList()); }
        RecordingHandler(List<String> completions) { this.completions = completions; }

        @Override public boolean execute(String[] args, CommandContext context) {
            invoked = true;
            receivedArgs = args;
            return true;
        }
        @Override public String getDescription() { return "test"; }
        @Override public String getUsage() { return "/test"; }
        @Override public List<String> tabComplete(String[] args) { return completions; }
    }
}
