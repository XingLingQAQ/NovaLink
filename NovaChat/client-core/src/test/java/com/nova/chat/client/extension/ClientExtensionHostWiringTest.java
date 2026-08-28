package com.nova.chat.client.extension;

import com.nova.chat.client.network.ClientLogger;
import com.nova.chat.common.extension.ExtensionEventBus;
import com.nova.chat.common.extension.events.ChatMessageEvent;
import com.nova.chat.common.extension.events.PlayerJoinChannelEvent;
import com.nova.chat.common.extension.events.PlayerLeaveChannelEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal unit tests for the {@link ClientExtensionHost} wiring.
 *
 * <p>Verifies:
 * <ul>
 *   <li>The host initializes with a non-null manager and event bus.</li>
 *   <li>An absent extensions directory yields an empty, disabled host (zero-regression).</li>
 *   <li>An empty extensions directory yields an empty, disabled host (zero-regression).</li>
 *   <li>A corrupt (metadata-less) JAR in the directory does not crash the host.</li>
 *   <li>The event bus is usable: a registered listener receives a fired event.</li>
 *   <li>The event bus is usable for ChatMessageEvent, PlayerJoinChannelEvent,
 *       PlayerLeaveChannelEvent.</li>
 * </ul>
 *
 * <p>This does not exercise real extension class-loading (that needs a compiled
 * test extension JAR); it focuses on the host wiring contract the task requires.
 */
@DisplayName("ClientExtensionHost wiring")
class ClientExtensionHostWiringTest {

    private static final class RecordingLogger implements ClientLogger {
        final List<String> infos = new ArrayList<>();
        final List<String> warns = new ArrayList<>();
        final List<String> debugs = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override public void info(String message) { infos.add(message); }
        @Override public void warn(String message) { warns.add(message); }
        @Override public void debug(String message) { debugs.add(message); }
        @Override public void error(String message) { errors.add(message); }
    }

    @Test
    @DisplayName("initialize with absent directory yields a disabled empty host")
    void absentDirectoryYieldsEmptyHost(@TempDir Path tempDir) {
        Path absent = tempDir.resolve("does-not-exist");
        assertThat(Files.exists(absent)).isFalse();

        ClientExtensionHost host = ClientExtensionHost.initialize(
                absent.toString(), new RecordingLogger());

        assertThat(host).isNotNull();
        assertThat(host.isEnabled()).isFalse();
        assertThat(host.getExtensionsDir()).isEqualTo(absent);
        assertThat(host.getExtensionManager()).isNotNull();
        assertThat(host.getEventBus()).isNotNull();
        // Directory was NOT created by the host (zero side-effects).
        assertThat(Files.exists(absent)).isFalse();
    }

    @Test
    @DisplayName("initialize with empty directory yields a disabled empty host")
    void emptyDirectoryYieldsEmptyHost(@TempDir Path tempDir) {
        Path empty = tempDir.resolve("extensions");
        // Pre-create the directory so the host takes the load path but finds no JARs.
        // The host itself must NOT create it when absent, so we create it here.
        try {
            Files.createDirectories(empty);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertThat(Files.isDirectory(empty)).isTrue();

        ClientExtensionHost host = ClientExtensionHost.initialize(
                empty.toString(), new RecordingLogger());

        assertThat(host).isNotNull();
        assertThat(host.isEnabled()).isFalse();
        assertThat(host.getExtensionManager().getExtensions()).isEmpty();
    }

    @Test
    @DisplayName("initialize with a JAR missing extension.yml stays usable (isolation)")
    void corruptJarDoesNotCrashHost(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("extensions");
        Files.createDirectories(dir);
        // Write a JAR without extension.yml — DefaultExtensionLoader logs and skips it.
        Path badJar = dir.resolve("bad.jar");
        try (java.util.jar.JarOutputStream jos =
                     new java.util.jar.JarOutputStream(new java.io.FileOutputStream(badJar.toFile()))) {
            jos.putNextEntry(new java.util.jar.JarEntry("dummy.txt"));
            jos.write("no meta here".getBytes());
            jos.closeEntry();
        }

        ClientExtensionHost host = ClientExtensionHost.initialize(
                dir.toString(), new RecordingLogger());

        // Host must stay usable; the bad JAR is isolated.
        assertThat(host).isNotNull();
        assertThat(host.isEnabled()).isFalse();
        assertThat(host.getExtensionManager().getExtensions()).isEmpty();
        assertThat(host.getEventBus()).isNotNull();
    }

    @Test
    @DisplayName("initialize with null logger does not throw")
    void nullLoggerIsSafe(@TempDir Path tempDir) {
        ClientExtensionHost host = ClientExtensionHost.initialize(
                tempDir.resolve("absent").toString(), null);
        assertThat(host).isNotNull();
        assertThat(host.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("event bus delivers ChatMessageEvent to a registered listener")
    void chatMessageEventIsDelivered() {
        ClientExtensionHost host = ClientExtensionHost.initialize(
                "absent-dir-for-test", new RecordingLogger());
        ExtensionEventBus bus = host.getEventBus();

        UUID sender = UUID.randomUUID();
        List<String> seen = new ArrayList<>();
        bus.register("test-ext", ChatMessageEvent.class, event -> {
            seen.add(event.getMessage());
            event.setMessage("rewritten");
        });

        ChatMessageEvent event = new ChatMessageEvent(sender, "Steve", "global", "hello");
        bus.fire(event);

        assertThat(seen).containsExactly("hello");
        assertThat(event.getMessage()).isEqualTo("rewritten");
        assertThat(event.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("ChatMessageEvent cancellation is honored by the bus")
    void chatMessageEventCanBeCancelled() {
        ClientExtensionHost host = ClientExtensionHost.initialize(
                "absent-dir-for-test", new RecordingLogger());
        ExtensionEventBus bus = host.getEventBus();

        bus.register("cancelling-ext", ChatMessageEvent.class, event -> {
            event.setCancelled(true);
            event.setMessage("blocked");
        });
        List<String> secondSeen = new ArrayList<>();
        bus.register("observing-ext", ChatMessageEvent.class,
                event -> secondSeen.add(event.getMessage()),
                com.nova.chat.common.extension.ExtensionEventBus.PRIORITY_LOW);

        ChatMessageEvent event = new ChatMessageEvent(
                UUID.randomUUID(), "Steve", "global", "hello");
        bus.fire(event);

        assertThat(event.isCancelled()).isTrue();
        // Lower-priority listener still fires after the cancel (documented bus behavior).
        assertThat(secondSeen).containsExactly("blocked");
    }

    @Test
    @DisplayName("event bus delivers PlayerJoinChannelEvent")
    void playerJoinChannelEventIsDelivered() {
        ClientExtensionHost host = ClientExtensionHost.initialize(
                "absent-dir-for-test", new RecordingLogger());
        ExtensionEventBus bus = host.getEventBus();

        UUID player = UUID.randomUUID();
        List<PlayerJoinChannelEvent> seen = new ArrayList<>();
        bus.register("join-ext", PlayerJoinChannelEvent.class, seen::add);

        PlayerJoinChannelEvent event = new PlayerJoinChannelEvent(player, "Steve", "trade");
        bus.fire(event);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).getChannelId()).isEqualTo("trade");
        assertThat(seen.get(0).getPlayerId()).isEqualTo(player);
    }

    @Test
    @DisplayName("event bus delivers PlayerLeaveChannelEvent")
    void playerLeaveChannelEventIsDelivered() {
        ClientExtensionHost host = ClientExtensionHost.initialize(
                "absent-dir-for-test", new RecordingLogger());
        ExtensionEventBus bus = host.getEventBus();

        UUID player = UUID.randomUUID();
        List<PlayerLeaveChannelEvent> seen = new ArrayList<>();
        bus.register("leave-ext", PlayerLeaveChannelEvent.class, seen::add);

        PlayerLeaveChannelEvent event = new PlayerLeaveChannelEvent(player, "Steve", "trade");
        bus.fire(event);

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).getChannelId()).isEqualTo("trade");
    }

    @Test
    @DisplayName("shutdown is a no-op on an empty host and can be called repeatedly")
    void shutdownIsSafeOnEmptyHost() {
        ClientExtensionHost host = ClientExtensionHost.initialize(
                "absent-dir-for-test", new RecordingLogger());
        host.shutdown();
        // Repeated shutdown must not throw.
        host.shutdown();
        assertThat(host.isEnabled()).isFalse();
    }
}
