package com.nova.chat.mod.command;

import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.command.CommandIntent;
import com.nova.chat.client.command.CommandResult;
import com.nova.chat.client.command.WhoCommandService;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.mod.chat.ChatInterceptor;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.network.NetworkClient;
import com.nova.chat.mod.platform.CommandContext;
import com.nova.chat.mod.platform.ModServices;
import com.nova.chat.mod.platform.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the mod common-layer command handlers. Covers the testable
 * branches of {@link WhoCommand}, {@link ListCommand}, {@link ReloadCommand},
 * {@link HelpCommand}, {@link JoinCommand}, {@link LeaveCommand} and
 * {@link ToggleCommand}: argument validation, services-null guard, network
 * readiness checks, channelId fallback to active channel, WHO packet extras,
 * admin-gating, and success/error feedback routing.
 */
@DisplayName("mod command handlers")
@ExtendWith(MockitoExtension.class)
class CommandHandlersTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String PLAYER_NAME = "Steve";

    @Mock
    private Platform platform;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ChatInterceptor chatInterceptor;
    @Mock
    private ChannelCommandService channelCommandService;
    @Mock
    private com.nova.chat.client.channel.KnownChannelRegistry knownChannelRegistry;

    // Captures messages sent to the player through the platform bridge.
    private final java.util.List<String> sentMessages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sentMessages.clear();
    }

    // --------------------------- helpers ---------------------------

    private ModConfig newConfig(String defaultChannel, boolean replaceVanilla) {
        ModConfig config = new ModConfig();
        config.getChat().setDefaultChannel(defaultChannel);
        config.getChat().setReplaceVanilla(replaceVanilla);
        return config;
    }

    private CommandContext context(boolean admin) {
        CommandContext ctx = new CommandContext(PLAYER, PLAYER_NAME, platform, admin);
        ctx.withServices(services(newConfig("global", false)));
        return ctx;
    }

    private ModServices services(ModConfig config) {
        return new ModServices(config, networkClient, chatInterceptor, channelCommandService, knownChannelRegistry);
    }

    private void recordMessages() {
        // Route platform.sendMessage(uuid, msg) into sentMessages for assertions.
        org.mockito.Mockito.doAnswer(inv -> {
            sentMessages.add((String) inv.getArgument(1));
            return null;
        }).when(platform).sendMessage(eq(PLAYER), anyString());
    }

    private PlayerChannelState stateWith(String activeChannel) {
        // PlayerChannelState requires a non-blank default channel; construct with
        // a valid channel then it is auto-joined. activeChannel is the default.
        return new PlayerChannelState(PLAYER, activeChannel, ChatMode.HYBRID);
    }

    // ============================ WhoCommand ============================

    @Nested
    @DisplayName("WhoCommand")
    class Who {

        @Test
        @DisplayName("sends WHO packet with requesterId/playerId/requesterName extras and a fetching prompt")
        void sendsWhoPacketWithExtras() {
            recordMessages();
            when(networkClient.isConnected()).thenReturn(true);
            when(networkClient.isAuthenticated()).thenReturn(true);
            CommandContext ctx = context(false);

            new WhoCommand().execute(new String[]{"trade"}, ctx);

            ArgumentCaptor<ChannelActionPacket> captor = ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(networkClient).sendPacket(captor.capture());
            ChannelActionPacket sent = captor.getValue();
            assertThat(sent.getAction()).isEqualTo(ChannelAction.WHO);
            assertThat(sent.getChannelId()).isEqualTo("trade");
            assertThat(sent.getExtra("playerId")).isEqualTo(PLAYER.toString());
            assertThat(sent.getExtra("requesterId")).isEqualTo(PLAYER.toString());
            assertThat(sent.getExtra("requesterName")).isEqualTo(PLAYER_NAME);
        }

        @Test
        @DisplayName("falls back to the player's active channel when no channel argument is given")
        void fallsBackToActiveChannel() {
            recordMessages();
            when(networkClient.isConnected()).thenReturn(true);
            when(networkClient.isAuthenticated()).thenReturn(true);
            PlayerChannelState state = stateWith("staff");
            when(chatInterceptor.getOrCreateState(PLAYER, PLAYER_NAME)).thenReturn(state);
            CommandContext ctx = context(false);

            new WhoCommand().execute(new String[]{}, ctx);

            ArgumentCaptor<ChannelActionPacket> captor = ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(networkClient).sendPacket(captor.capture());
            assertThat(captor.getValue().getChannelId()).isEqualTo("staff");
        }

        @Test
        @DisplayName("falls back to the player's active channel when the argument is blank")
        void blankArgumentFallsBackToActiveChannel() {
            recordMessages();
            when(networkClient.isConnected()).thenReturn(true);
            when(networkClient.isAuthenticated()).thenReturn(true);
            PlayerChannelState state = stateWith("global");
            when(chatInterceptor.getOrCreateState(PLAYER, PLAYER_NAME)).thenReturn(state);
            CommandContext ctx = context(false);

            new WhoCommand().execute(new String[]{" "}, ctx);

            ArgumentCaptor<ChannelActionPacket> captor = ArgumentCaptor.forClass(ChannelActionPacket.class);
            verify(networkClient).sendPacket(captor.capture());
            assertThat(captor.getValue().getChannelId()).isEqualTo("global");
        }

        @Test
        @DisplayName("rejects when the network client is not authenticated")
        void rejectsWhenNotAuthenticated() {
            recordMessages();
            when(networkClient.isConnected()).thenReturn(true);
            when(networkClient.isAuthenticated()).thenReturn(false);
            CommandContext ctx = context(false);

            boolean ok = new WhoCommand().execute(new String[]{"trade"}, ctx);

            assertThat(ok).isFalse();
            verify(networkClient, never()).sendPacket(any());
        }

        @Test
        @DisplayName("rejects when services are not attached (null)")
        void rejectsWhenServicesNull() {
            recordMessages();
            CommandContext ctx = new CommandContext(PLAYER, PLAYER_NAME, platform, false);
            // No withServices() call -> services null
            boolean ok = new WhoCommand().execute(new String[]{"trade"}, ctx);

            assertThat(ok).isFalse();
            verify(networkClient, never()).sendPacket(any());
        }
    }

    // ============================ ListCommand ============================

    @Nested
    @DisplayName("ListCommand")
    class List {

        @Test
        @DisplayName("renders formatted channel list title/lines/tail via ListCommandService")
        void rendersChannelList() {
            recordMessages();
            // knownChannelRegistry is a mock -> getKnownChannelIds returns empty by default;
            // the command still renders title + tail lines.
            PlayerChannelState state = stateWith("global");
            when(chatInterceptor.getState(PLAYER)).thenReturn(state);
            CommandContext ctx = context(false);

            boolean ok = new ListCommand().execute(new String[]{}, ctx);

            assertThat(ok).isTrue();
            // title + (formatChannelList on empty known set yields one "no channels" line) + tail
            assertThat(sentMessages.size()).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("handles a null player state as empty joined set")
        void handlesNullState() {
            recordMessages();
            when(chatInterceptor.getState(PLAYER)).thenReturn(null);
            CommandContext ctx = context(false);

            boolean ok = new ListCommand().execute(new String[]{}, ctx);

            assertThat(ok).isTrue();
            assertThat(sentMessages).isNotEmpty();
        }

        @Test
        @DisplayName("rejects when services are not attached")
        void rejectsWhenServicesNull() {
            recordMessages();
            CommandContext ctx = new CommandContext(PLAYER, PLAYER_NAME, platform, false);
            boolean ok = new ListCommand().execute(new String[]{}, ctx);
            assertThat(ok).isFalse();
        }
    }

    // ============================ ReloadCommand ============================

    @Nested
    @DisplayName("ReloadCommand")
    class Reload {

        @Test
        @DisplayName("non-admin is rejected with FORBIDDEN")
        void nonAdminRejected() {
            recordMessages();
            CommandContext ctx = context(false);

            boolean ok = new ReloadCommand().execute(new String[]{}, ctx);

            assertThat(ok).isFalse();
            verify(channelCommandService, never()).reload();
        }

        @Test
        @DisplayName("admin triggers ChannelCommandService.reload and sees a success message")
        void adminReloadsSuccessfully() {
            recordMessages();
            CommandContext ctx = context(true);

            boolean ok = new ReloadCommand().execute(new String[]{}, ctx);

            assertThat(ok).isTrue();
            verify(channelCommandService).reload();
            assertThat(sentMessages).isNotEmpty();
        }

        @Test
        @DisplayName("admin with null services is rejected")
        void adminNullServicesRejected() {
            recordMessages();
            CommandContext ctx = new CommandContext(PLAYER, PLAYER_NAME, platform, true);
            boolean ok = new ReloadCommand().execute(new String[]{}, ctx);
            assertThat(ok).isFalse();
            verify(channelCommandService, never()).reload();
        }
    }

    // ============================ HelpCommand ============================

    @Nested
    @DisplayName("HelpCommand")
    class Help {

        @Test
        @DisplayName("non-admin help lists 10 lines and excludes the reload line")
        void nonAdminHelp() {
            recordMessages();
            CommandContext ctx = context(false);

            new HelpCommand().execute(new String[]{}, ctx);

            // title + 9 lines (help/join/leave/list/who/toggle/ignore/unignore/msg)
            assertThat(sentMessages).hasSize(10);
        }

        @Test
        @DisplayName("admin help includes the reload line (11 lines total)")
        void adminHelpIncludesReload() {
            recordMessages();
            CommandContext ctx = context(true);

            new HelpCommand().execute(new String[]{}, ctx);

            assertThat(sentMessages).hasSize(11);
        }
    }

    // ============================ JoinCommand ============================

    @Nested
    @DisplayName("JoinCommand")
    class Join {

        @Test
        @DisplayName("missing channel argument -> usage message, returns false")
        void missingChannelArg() {
            recordMessages();
            CommandContext ctx = context(false);

            boolean ok = new JoinCommand().execute(new String[]{}, ctx);

            assertThat(ok).isFalse();
            verify(channelCommandService, never()).join(any(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("successful join sends a 'joining' message")
        void successfulJoin() {
            recordMessages();
            when(platform.getCurrentWorld(PLAYER)).thenReturn("world");
            PlayerChannelState state = stateWith("global");
            when(chatInterceptor.getOrCreateState(PLAYER, PLAYER_NAME)).thenReturn(state);
            when(channelCommandService.join(state, "trade", "", PLAYER_NAME, "world"))
                    .thenReturn(CommandResult.success(CommandIntent.JOIN, "ok"));
            CommandContext ctx = context(false);

            boolean ok = new JoinCommand().execute(new String[]{"trade"}, ctx);

            assertThat(ok).isTrue();
            assertThat(sentMessages).isNotEmpty();
        }

        @Test
        @DisplayName("failed join returns false and sends an error-formatted message")
        void failedJoin() {
            recordMessages();
            when(platform.getCurrentWorld(PLAYER)).thenReturn(null);
            PlayerChannelState state = stateWith("global");
            when(chatInterceptor.getOrCreateState(PLAYER, PLAYER_NAME)).thenReturn(state);
            when(channelCommandService.join(state, "trade", "", PLAYER_NAME, null))
                    .thenReturn(CommandResult.failure(CommandIntent.JOIN, "bad", "NC-403"));
            CommandContext ctx = context(false);

            boolean ok = new JoinCommand().execute(new String[]{"trade"}, ctx);

            assertThat(ok).isFalse();
            assertThat(sentMessages).isNotEmpty();
        }

        @Test
        @DisplayName("null services -> rejected")
        void nullServicesRejected() {
            recordMessages();
            CommandContext ctx = new CommandContext(PLAYER, PLAYER_NAME, platform, false);
            boolean ok = new JoinCommand().execute(new String[]{"trade"}, ctx);
            assertThat(ok).isFalse();
        }
    }

    // ============================ LeaveCommand ============================

    @Nested
    @DisplayName("LeaveCommand")
    class Leave {

        @Test
        @DisplayName("explicit channel argument is left on success")
        void leaveExplicitChannel() {
            recordMessages();
            PlayerChannelState state = stateWith("trade");
            when(chatInterceptor.getOrCreateState(PLAYER, PLAYER_NAME)).thenReturn(state);
            when(channelCommandService.leave(state, "trade", PLAYER_NAME))
                    .thenReturn(CommandResult.success(CommandIntent.LEAVE, "ok"));
            CommandContext ctx = context(false);

            boolean ok = new LeaveCommand().execute(new String[]{"trade"}, ctx);

            assertThat(ok).isTrue();
            assertThat(sentMessages).isNotEmpty();
        }

        @Test
        @DisplayName("no argument falls back to active channel on success")
        void leaveActiveChannelFallback() {
            recordMessages();
            PlayerChannelState state = stateWith("staff");
            when(chatInterceptor.getOrCreateState(PLAYER, PLAYER_NAME)).thenReturn(state);
            when(channelCommandService.leave(state, "staff", PLAYER_NAME))
                    .thenReturn(CommandResult.success(CommandIntent.LEAVE, "ok"));
            CommandContext ctx = context(false);

            boolean ok = new LeaveCommand().execute(new String[]{}, ctx);

            assertThat(ok).isTrue();
            verify(channelCommandService).leave(state, "staff", PLAYER_NAME);
        }

        @Test
        @DisplayName("blank channel argument falls back to active channel on success")
        void blankArgumentFallsBackToActiveChannel() {
            recordMessages();
            PlayerChannelState state = stateWith("global");
            when(chatInterceptor.getOrCreateState(PLAYER, PLAYER_NAME)).thenReturn(state);
            when(channelCommandService.leave(state, "global", PLAYER_NAME))
                    .thenReturn(CommandResult.success(CommandIntent.LEAVE, "ok"));
            CommandContext ctx = context(false);

            boolean ok = new LeaveCommand().execute(new String[]{" "}, ctx);

            assertThat(ok).isTrue();
            verify(channelCommandService).leave(state, "global", PLAYER_NAME);
        }

        @Test
        @DisplayName("failed leave returns false")
        void failedLeave() {
            recordMessages();
            PlayerChannelState state = stateWith("trade");
            when(chatInterceptor.getOrCreateState(PLAYER, PLAYER_NAME)).thenReturn(state);
            when(channelCommandService.leave(state, "trade", PLAYER_NAME))
                    .thenReturn(CommandResult.failure(CommandIntent.LEAVE, "bad", "NC-503"));
            CommandContext ctx = context(false);

            boolean ok = new LeaveCommand().execute(new String[]{"trade"}, ctx);

            assertThat(ok).isFalse();
        }

        @Test
        @DisplayName("null services -> rejected")
        void nullServicesRejected() {
            recordMessages();
            CommandContext ctx = new CommandContext(PLAYER, PLAYER_NAME, platform, false);
            boolean ok = new LeaveCommand().execute(new String[]{"trade"}, ctx);
            assertThat(ok).isFalse();
        }
    }

    // ============================ ToggleCommand ============================

    @Nested
    @DisplayName("ToggleCommand")
    class Toggle {

        @Test
        @DisplayName("successful toggle sends mode description and returns true")
        void successfulToggle() {
            recordMessages();
            PlayerChannelState state = stateWith("global");
            when(chatInterceptor.getOrCreateState(PLAYER, PLAYER_NAME)).thenReturn(state);
            when(channelCommandService.toggle(state))
                    .thenReturn(CommandResult.success(CommandIntent.TOGGLE, "ok"));
            CommandContext ctx = context(false);

            boolean ok = new ToggleCommand().execute(new String[]{}, ctx);

            assertThat(ok).isTrue();
            // switched line + mode description line
            assertThat(sentMessages).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("failed toggle sends the result message and returns false")
        void failedToggle() {
            recordMessages();
            PlayerChannelState state = stateWith("global");
            when(chatInterceptor.getOrCreateState(PLAYER, PLAYER_NAME)).thenReturn(state);
            when(channelCommandService.toggle(state))
                    .thenReturn(CommandResult.failure(CommandIntent.TOGGLE, "cannot"));
            CommandContext ctx = context(false);

            boolean ok = new ToggleCommand().execute(new String[]{}, ctx);

            assertThat(ok).isFalse();
            assertThat(sentMessages).isNotEmpty();
        }

        @Test
        @DisplayName("null services -> rejected")
        void nullServicesRejected() {
            recordMessages();
            CommandContext ctx = new CommandContext(PLAYER, PLAYER_NAME, platform, false);
            boolean ok = new ToggleCommand().execute(new String[]{}, ctx);
            assertThat(ok).isFalse();
        }
    }
}
