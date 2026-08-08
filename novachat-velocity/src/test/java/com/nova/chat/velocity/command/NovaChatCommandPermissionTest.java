package com.nova.chat.velocity.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Regression guard for the Velocity 4.1.0 command-node-pruning bug (NC-VELOCITY).
 *
 * <p>Velocity 4.1.0 changed Brigadier dispatch: when a {@link
 * com.velocitypowered.api.command.SimpleCommand}'s {@code hasPermission} returns
 * false for a sender, Velocity prunes the command node's children from that
 * sender's dispatch tree (3.4.0 did not). An offline-mode player has NO granted
 * permissions, so the previous {@code invocation.source().hasPermission("novachat.use")}
 * check returned false, pruning the {@code nc} node's subcommand children
 * (help/join/leave/...) and making every {@code /nc <sub>} report
 * "Incorrect argument at position 3: nc <--[HERE]" — a permission denial
 * masquerading as a syntax error.
 *
 * <p>This test asserts the source-level invariant: {@code hasPermission} must
 * not gate the basic user {@code /nc} command on the {@code novachat.use}
 * permission, because that permission is default-deny under Velocity 4.1.0's
 * default permission provider and there is no plugin-container mechanism to
 * declare it default-true. The admin subcommand {@code /nc reload} still
 * independently gates on {@code novachat.admin} inside {@code handleReload}.
 *
 * <p>The assertion is deliberately source-level: instantiating {@link
 * NovaChatCommand} requires a fully wired {@link
 * com.nova.chat.velocity.NovaChatVelocity} (ProxyServer/Logger/Path) plus the
 * shared {@link com.nova.chat.client.command.ChannelCommandService}, none of
 * which belong in a fast unit test. The source guard is exact, needs no Velocity
 * API on the test runtime classpath, and prevents silent regression of this
 * shipped fix.
 */
class NovaChatCommandPermissionTest {

    private static final Path SOURCE = Paths.get(
            "src/main/java/com/nova/chat/velocity/command/NovaChatCommand.java");

    @Test
    void hasPermissionShouldDefaultAllowAllSenders() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);

        // Locate the hasPermission override body and assert it unconditionally
        // returns true. We pin on the exact shipped body so a future "cleanup"
        // that re-introduces a permission gate fails loudly with a clear diff.
        String marker = "public boolean hasPermission(Invocation invocation) {";
        int start = source.indexOf(marker);
        org.assertj.core.api.Assertions.assertThat(start)
                .as("hasPermission override must exist in NovaChatCommand.java")
                .isGreaterThan(-1);
        int end = source.indexOf('}', start);
        org.assertj.core.api.Assertions.assertThat(end)
                .as("hasPermission body must be terminated")
                .isGreaterThan(start);
        String body = source.substring(start, end + 1);

        // Must unconditionally return true (no permission consult, no branch).
        org.assertj.core.api.Assertions.assertThat(body)
                .as("hasPermission must default-allow — see class javadoc for the "
                        + "Velocity 4.1.0 node-pruning bug this guards against")
                .contains("return true;");

        // The executable body (comment-stripped) must not consult novachat.use.
        // Comments may reference the old code path for explanation, so we strip
        // // lines before checking.
        String executable = body.lines()
                .map(String::strip)
                .filter(l -> !l.startsWith("//"))
                .filter(l -> !l.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
        org.assertj.core.api.Assertions.assertThat(executable)
                .as("hasPermission must NOT consult novachat.use (default-deny on Velocity 4.1.0 "
                        + "would prune the nc node's children and break /nc <sub> for offline-mode players)")
                .doesNotContain("hasPermission(\"novachat.use\")");
        // The unconditional return must be the only executable statement.
        long returnCount = executable.lines().filter(l -> l.contains("return ")).count();
        org.assertj.core.api.Assertions.assertThat(returnCount)
                .as("hasPermission must have exactly one return statement (the unconditional true)")
                .isEqualTo(1L);
    }
}
