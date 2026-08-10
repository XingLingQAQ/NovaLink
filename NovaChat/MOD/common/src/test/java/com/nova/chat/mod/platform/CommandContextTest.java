package com.nova.chat.mod.platform;

import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.command.ChannelCommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
/**
 * Unit tests for {@link CommandContext}: color-code conversion on send,
 * null-safety guards, services attachment, and admin flag passthrough.
 */
@DisplayName("CommandContext")
class CommandContextTest {

    private static final UUID PLAYER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Nested
    @DisplayName("sendMessage color conversion")
    class SendMessage {

        @Test
        @DisplayName("converts & color codes to section signs before delegating to the platform")
        void convertsAmpersandToSection() {
            Platform platform = mock(Platform.class);
            CommandContext ctx = new CommandContext(PLAYER, "Steve", platform, false);

            ctx.sendMessage("&aHello &bworld");

            verify(platform).sendMessage(PLAYER, "§aHello §bworld");
        }

        @Test
        @DisplayName("does not send when platform is null")
        void nullPlatformNoSend() {
            CommandContext ctx = new CommandContext(PLAYER, "Steve", null, false);
            // Should not throw
            ctx.sendMessage("hello");
        }

        @Test
        @DisplayName("does not send when playerId is null")
        void nullPlayerIdNoSend() {
            Platform platform = mock(Platform.class);
            CommandContext ctx = new CommandContext(null, "Steve", platform, false);

            ctx.sendMessage("hello");

            verify(platform, never()).sendMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("does not send when message is null")
        void nullMessageNoSend() {
            Platform platform = mock(Platform.class);
            CommandContext ctx = new CommandContext(PLAYER, "Steve", platform, false);

            ctx.sendMessage(null);

            verify(platform, never()).sendMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("passes plain text through unchanged (no color codes)")
        void plainTextUnchanged() {
            Platform platform = mock(Platform.class);
            CommandContext ctx = new CommandContext(PLAYER, "Steve", platform, false);

            ctx.sendMessage("plain text");

            verify(platform).sendMessage(PLAYER, "plain text");
        }
    }

    @Nested
    @DisplayName("services attachment")
    class Services {

        @Test
        @DisplayName("getServices returns null before withServices is called")
        void servicesNullByDefault() {
            CommandContext ctx = new CommandContext(PLAYER, "Steve", mock(Platform.class), false);
            assertThat(ctx.getServices()).isNull();
        }

        @Test
        @DisplayName("withServices attaches and returns the same context (fluent)")
        void withServicesAttachesFluently() {
            CommandContext ctx = new CommandContext(PLAYER, "Steve", mock(Platform.class), false);
            ModConfig config = new ModConfig();
            ModServices services = new ModServices(
                    config,
                    mock(NetworkClient.class),
                    mock(ChatInterceptor.class),
                    mock(ChannelCommandService.class),
                    mock(KnownChannelRegistry.class));

            CommandContext returned = ctx.withServices(services);

            assertThat(returned).isSameAs(ctx);
            assertThat(ctx.getServices()).isSameAs(services);
            assertThat(ctx.getServices().getConfig()).isSameAs(config);
        }
    }

    @Nested
    @DisplayName("identity accessors")
    class Accessors {

        @Test
        @DisplayName("getPlayerId/getPlayerName/isAdmin return constructor values")
        void accessorsReturnConstructorValues() {
            Platform platform = mock(Platform.class);
            CommandContext adminCtx = new CommandContext(PLAYER, "Alex", platform, true);
            CommandContext playerCtx = new CommandContext(PLAYER, "Bob", platform, false);

            assertThat(adminCtx.getPlayerId()).isEqualTo(PLAYER);
            assertThat(adminCtx.getPlayerName()).isEqualTo("Alex");
            assertThat(adminCtx.isAdmin()).isTrue();
            assertThat(playerCtx.isAdmin()).isFalse();
            assertThat(adminCtx.getPlatform()).isSameAs(platform);
        }
    }
}
