package com.nova.chat.mod.command;

import com.nova.chat.client.channel.KnownChannelRegistry;
import com.nova.chat.client.command.ChannelCommandService;
import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.NovaConstants;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the mod moderation shells ({@code mute}, {@code kick},
 * {@code ban}, {@code unban}, {@code announce}, {@code title}) and the hidden
 * super-admin {@code auth}.
 *
 * <p>These pin the contract the backend actually reads: the
 * {@link ChannelAction} / {@link AdminAction}, the {@code operatorId} vs
 * {@code console} split, the target extras, the seconds-valued {@code duration},
 * and the admin gate. The argument shapes and extras are taken from the Folia /
 * Bukkit reference shells, which are the newest ports of this behaviour.
 */
@DisplayName("mod moderation commands")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModerationCommandsTest {

    private static final UUID OPERATOR = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TARGET = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final String OPERATOR_NAME = "Mod";

    @Mock
    private Platform platform;
    @Mock
    private NetworkClient networkClient;
    @Mock
    private ChatInterceptor chatInterceptor;
    @Mock
    private ChannelCommandService channelCommandService;
    @Mock
    private KnownChannelRegistry knownChannelRegistry;

    private final List<String> sentMessages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sentMessages.clear();
        when(networkClient.isConnected()).thenReturn(true);
        when(networkClient.isAuthenticated()).thenReturn(true);
        org.mockito.Mockito.doAnswer(inv -> {
            sentMessages.add((String) inv.getArgument(1));
            return null;
        }).when(platform).sendMessage(any(UUID.class), anyString());
    }

    // --------------------------- helpers ---------------------------

    private ModServices services() {
        return new ModServices(new ModConfig(), networkClient, chatInterceptor,
                channelCommandService, knownChannelRegistry);
    }

    private CommandContext playerContext(boolean admin) {
        return new CommandContext(OPERATOR, OPERATOR_NAME, platform, admin).withServices(services());
    }

    private CommandContext consoleContext(boolean admin) {
        return new CommandContext(UUID.fromString(NovaConstants.CONSOLE_SENTINEL_UUID),
                "Console", platform, admin).withServices(services());
    }

    private void activeChannel(String channelId) {
        when(chatInterceptor.getState(OPERATOR))
                .thenReturn(new PlayerChannelState(OPERATOR, channelId, ChatMode.HYBRID));
    }

    private void onlineTarget() {
        when(platform.getOnlinePlayerIds()).thenReturn(List.of(TARGET));
        when(platform.getPlayerName(TARGET)).thenReturn("Bob");
    }

    private ChannelActionPacket sentChannelAction() {
        ArgumentCaptor<ChannelActionPacket> captor =
                ArgumentCaptor.forClass(ChannelActionPacket.class);
        verify(networkClient).sendPacket(captor.capture());
        return captor.getValue();
    }

    private AdminActionPacket sentAdminAction() {
        ArgumentCaptor<AdminActionPacket> captor =
                ArgumentCaptor.forClass(AdminActionPacket.class);
        verify(networkClient).sendPacket(captor.capture());
        return captor.getValue();
    }

    // ============================ MuteCommand ============================

    @Nested
    @DisplayName("MuteCommand")
    class Mute {

        @Test
        @DisplayName("sends MUTE with operator/target/duration extras")
        void sendsMutePacket() {
            onlineTarget();

            boolean ok = new MuteCommand().execute(new String[]{"Bob", "10m", "global"},
                    playerContext(true));

            assertThat(ok).isTrue();
            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getAction()).isEqualTo(ChannelAction.MUTE);
            assertThat(packet.getChannelId()).isEqualTo("global");
            assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR.toString());
            assertThat(packet.getExtra("operatorName")).isEqualTo(OPERATOR_NAME);
            assertThat(packet.getExtra("targetId")).isEqualTo(TARGET.toString());
            assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
            assertThat(packet.getExtra("duration")).isEqualTo("600");
            assertThat(packet.getExtra("console")).isNull();
        }

        @Test
        @DisplayName("omitted channel falls back to the sender's active channel")
        void omittedChannelUsesActiveChannel() {
            activeChannel("staff");
            onlineTarget();

            new MuteCommand().execute(new String[]{"Bob", "1h"}, playerContext(true));

            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getChannelId()).isEqualTo("staff");
            assertThat(packet.getExtra("duration")).isEqualTo("3600");
        }

        @Test
        @DisplayName("an unresolvable target still travels by name")
        void unresolvableTargetSentByName() {
            when(platform.getOnlinePlayerIds()).thenReturn(List.of());

            new MuteCommand().execute(new String[]{"Ghost", "30s", "global"},
                    playerContext(true));

            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getExtra("targetId")).isNull();
            assertThat(packet.getExtra("targetName")).isEqualTo("Ghost");
            assertThat(packet.getExtra("duration")).isEqualTo("30");
        }

        @Test
        @DisplayName("invalid duration is refused with NC-439 and no packet")
        void invalidDurationRefused() {
            new MuteCommand().execute(new String[]{"Bob", "banana", "global"},
                    playerContext(true));

            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("NC-439");
        }

        @Test
        @DisplayName("missing arguments print usage and no packet is sent")
        void missingArgumentsPrintUsage() {
            new MuteCommand().execute(new String[]{"Bob"}, playerContext(true));

            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("/nc mute");
        }

        @Test
        @DisplayName("console mute carries the sentinel UUID and the console flag")
        void consoleUsesSentinel() {
            onlineTarget();

            new MuteCommand().execute(new String[]{"Bob", "30s", "global"},
                    consoleContext(true));

            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getExtra("operatorId"))
                    .isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
            assertThat(packet.getExtra("console")).isEqualTo("true");
            assertThat(packet.getExtra("operatorName")).isEqualTo("Console");
        }

        @Test
        @DisplayName("a non-admin is refused with NC-403 and no packet")
        void nonAdminRefused() {
            boolean ok = new MuteCommand().execute(new String[]{"Bob", "1h", "global"},
                    playerContext(false));

            assertThat(ok).isFalse();
            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("NC-403");
        }

        @Test
        @DisplayName("the permission node matches the bukkit/folia shell")
        void permissionNodeMatches() {
            assertThat(new MuteCommand().getPermissionNode()).isEqualTo("novachat.mute");
        }
    }

    // ============================ KickCommand ============================

    @Nested
    @DisplayName("KickCommand")
    class Kick {

        @Test
        @DisplayName("sends KICK with operator/target extras and no duration")
        void sendsKickPacket() {
            onlineTarget();

            boolean ok = new KickCommand().execute(new String[]{"Bob", "global"},
                    playerContext(true));

            assertThat(ok).isTrue();
            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getAction()).isEqualTo(ChannelAction.KICK);
            assertThat(packet.getChannelId()).isEqualTo("global");
            assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR.toString());
            assertThat(packet.getExtra("operatorName")).isEqualTo(OPERATOR_NAME);
            assertThat(packet.getExtra("targetId")).isEqualTo(TARGET.toString());
            assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
            assertThat(packet.getExtra("duration")).isNull();
        }

        @Test
        @DisplayName("omitted channel falls back to the sender's active channel")
        void omittedChannelUsesActiveChannel() {
            activeChannel("trade");
            onlineTarget();

            new KickCommand().execute(new String[]{"Bob"}, playerContext(true));

            assertThat(sentChannelAction().getChannelId()).isEqualTo("trade");
        }

        @Test
        @DisplayName("no channel and no active channel is refused")
        void noChannelRefused() {
            new KickCommand().execute(new String[]{"Bob"}, playerContext(true));

            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("NC-400");
        }

        @Test
        @DisplayName("console kick carries the sentinel and needs an explicit channel")
        void consoleUsesSentinel() {
            onlineTarget();

            new KickCommand().execute(new String[]{"Bob", "global"}, consoleContext(true));

            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getExtra("operatorId"))
                    .isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
            assertThat(packet.getExtra("console")).isEqualTo("true");
        }

        @Test
        @DisplayName("a non-admin is refused")
        void nonAdminRefused() {
            assertThat(new KickCommand().execute(new String[]{"Bob", "global"},
                    playerContext(false))).isFalse();
            verify(networkClient, never()).sendPacket(any());
        }

        @Test
        @DisplayName("the permission node matches the bukkit/folia shell")
        void permissionNodeMatches() {
            assertThat(new KickCommand().getPermissionNode()).isEqualTo("novachat.kick");
        }
    }

    // ============================ BanCommand ============================

    @Nested
    @DisplayName("BanCommand")
    class Ban {

        @Test
        @DisplayName("ban with duration and channel sends BAN with extras")
        void sendsBanPacket() {
            onlineTarget();

            boolean ok = new BanCommand().execute(new String[]{"Bob", "1h", "global"},
                    playerContext(true));

            assertThat(ok).isTrue();
            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getAction()).isEqualTo(ChannelAction.BAN);
            assertThat(packet.getChannelId()).isEqualTo("global");
            assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR.toString());
            assertThat(packet.getExtra("targetId")).isEqualTo(TARGET.toString());
            assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
            assertThat(packet.getExtra("duration")).isEqualTo("3600");
        }

        @Test
        @DisplayName("an omitted channel is a network-wide ban (null channel id)")
        void omittedChannelIsGlobal() {
            onlineTarget();

            new BanCommand().execute(new String[]{"Bob"}, playerContext(true));

            assertThat(sentChannelAction().getChannelId())
                    .as("a null channel id is the global scope the backend reads")
                    .isNull();
        }

        @Test
        @DisplayName("channel may come before the duration")
        void channelBeforeDuration() {
            onlineTarget();

            new BanCommand().execute(new String[]{"Bob", "global", "1d"}, playerContext(true));

            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getChannelId()).isEqualTo("global");
            assertThat(packet.getExtra("duration")).isEqualTo("86400");
        }

        @Test
        @DisplayName("a permanent ban carries no duration extra")
        void permanentBanHasNoDuration() {
            onlineTarget();

            new BanCommand().execute(new String[]{"Bob", "global"}, playerContext(true));

            assertThat(sentChannelAction().getExtra("duration")).isNull();
        }

        @Test
        @DisplayName("invalid duration is refused with NC-439")
        void invalidDurationRefused() {
            new BanCommand().execute(new String[]{"Bob", "banana", "global"}, playerContext(true));

            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("NC-439");
        }

        @Test
        @DisplayName("missing target prints usage")
        void missingTargetPrintsUsage() {
            new BanCommand().execute(new String[0], playerContext(true));

            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("/nc ban");
        }

        @Test
        @DisplayName("console ban carries the sentinel")
        void consoleUsesSentinel() {
            onlineTarget();

            new BanCommand().execute(new String[]{"Bob", "1d"}, consoleContext(true));

            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getExtra("operatorId"))
                    .isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
            assertThat(packet.getExtra("console")).isEqualTo("true");
            assertThat(packet.getExtra("duration")).isEqualTo("86400");
        }

        @Test
        @DisplayName("a non-admin is refused")
        void nonAdminRefused() {
            assertThat(new BanCommand().execute(new String[]{"Bob", "global"},
                    playerContext(false))).isFalse();
            verify(networkClient, never()).sendPacket(any());
        }

        @Test
        @DisplayName("the permission node matches the bukkit/folia shell")
        void permissionNodeMatches() {
            assertThat(new BanCommand().getPermissionNode()).isEqualTo("novachat.ban");
        }
    }

    // ============================ UnbanCommand ============================

    @Nested
    @DisplayName("UnbanCommand")
    class Unban {

        @Test
        @DisplayName("a UUID target resolves directly and sends UNBAN")
        void uuidTargetSendsUnban() {
            boolean ok = new UnbanCommand().execute(new String[]{TARGET.toString()},
                    playerContext(true));

            assertThat(ok).isTrue();
            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getAction()).isEqualTo(ChannelAction.UNBAN);
            assertThat(packet.getExtra("targetId")).isEqualTo(TARGET.toString());
            assertThat(packet.getExtra("targetName")).isEqualTo(TARGET.toString());
            assertThat(packet.getExtra("operatorId")).isEqualTo(OPERATOR.toString());
            assertThat(packet.getExtra("operatorName")).isEqualTo(OPERATOR_NAME);
            assertThat(packet.getChannelId()).isNull();
        }

        @Test
        @DisplayName("an offline name is still sent, with the offline hint")
        void offlineNameSentWithHint() {
            when(platform.getOnlinePlayerIds()).thenReturn(List.of());

            new UnbanCommand().execute(new String[]{"Ghost"}, playerContext(true));

            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getAction()).isEqualTo(ChannelAction.UNBAN);
            assertThat(packet.getExtra("targetId")).isNull();
            assertThat(packet.getExtra("targetName")).isEqualTo("Ghost");
            assertThat(sentMessages).isNotEmpty();
        }

        @Test
        @DisplayName("a channel-scoped unban carries the channel id")
        void channelScopedUnban() {
            new UnbanCommand().execute(new String[]{TARGET.toString(), "trade"},
                    playerContext(true));

            assertThat(sentChannelAction().getChannelId()).isEqualTo("trade");
        }

        @Test
        @DisplayName("missing target prints usage")
        void missingTargetPrintsUsage() {
            new UnbanCommand().execute(new String[0], playerContext(true));

            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("/nc unban");
        }

        @Test
        @DisplayName("console unban carries the sentinel")
        void consoleUsesSentinel() {
            new UnbanCommand().execute(new String[]{TARGET.toString()}, consoleContext(true));

            ChannelActionPacket packet = sentChannelAction();
            assertThat(packet.getExtra("operatorId"))
                    .isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
            assertThat(packet.getExtra("console")).isEqualTo("true");
        }

        @Test
        @DisplayName("a non-admin is refused")
        void nonAdminRefused() {
            assertThat(new UnbanCommand().execute(new String[]{TARGET.toString()},
                    playerContext(false))).isFalse();
            verify(networkClient, never()).sendPacket(any());
        }

        @Test
        @DisplayName("unban reuses the ban permission node")
        void permissionNodeMatches() {
            assertThat(new UnbanCommand().getPermissionNode()).isEqualTo("novachat.ban");
        }
    }

    // ============================ AnnounceCommand ============================

    @Nested
    @DisplayName("AnnounceCommand")
    class Announce {

        @Test
        @DisplayName("sends STATUS typed ANNOUNCE with the joined content")
        void sendsAnnouncePacket() {
            boolean ok = new AnnounceCommand().execute(
                    new String[]{"global", "server", "restart", "in", "5m"},
                    playerContext(true));

            assertThat(ok).isTrue();
            AdminActionPacket packet = sentAdminAction();
            assertThat(packet.getAction()).isEqualTo(AdminAction.STATUS);
            assertThat(packet.getPlayerId()).isEqualTo(OPERATOR);
            assertThat(packet.getTarget()).isEqualTo("global");
            assertThat(packet.getExtra("type")).isEqualTo("ANNOUNCE");
            assertThat(packet.getExtra("operatorName")).isEqualTo(OPERATOR_NAME);
            assertThat(packet.getExtra("content")).isEqualTo("server restart in 5m");
            assertThat(packet.getExtra("console")).isNull();
        }

        @Test
        @DisplayName("console announce carries the sentinel UUID")
        void consoleUsesSentinel() {
            new AnnounceCommand().execute(new String[]{"global", "hello"}, consoleContext(true));

            AdminActionPacket packet = sentAdminAction();
            assertThat(packet.getPlayerId().toString())
                    .isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
            assertThat(packet.getExtra("console")).isEqualTo("true");
        }

        @Test
        @DisplayName("announce needs a channel and a body")
        void validatesArguments() {
            new AnnounceCommand().execute(new String[]{"global"}, playerContext(true));

            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("/nc announce");
        }

        @Test
        @DisplayName("a non-admin is refused before any packet")
        void nonAdminRefused() {
            assertThat(new AnnounceCommand().execute(new String[]{"global", "hi"},
                    playerContext(false))).isFalse();
            verify(networkClient, never()).sendPacket(any());
        }

        @Test
        @DisplayName("the permission node matches the bukkit/folia shell")
        void permissionNodeMatches() {
            assertThat(new AnnounceCommand().getPermissionNode()).isEqualTo("novachat.announce");
        }
    }

    // ============================ TitleCommand ============================

    @Nested
    @DisplayName("TitleCommand")
    class Title {

        @Test
        @DisplayName("sends STATUS typed TITLE with title and subtitle")
        void sendsTitlePacket() {
            boolean ok = new TitleCommand().execute(
                    new String[]{"global", "Welcome", "to", "the", "server"},
                    playerContext(true));

            assertThat(ok).isTrue();
            AdminActionPacket packet = sentAdminAction();
            assertThat(packet.getAction()).isEqualTo(AdminAction.STATUS);
            assertThat(packet.getPlayerId()).isEqualTo(OPERATOR);
            assertThat(packet.getTarget()).isEqualTo("global");
            assertThat(packet.getExtra("type")).isEqualTo("TITLE");
            assertThat(packet.getExtra("operatorName")).isEqualTo(OPERATOR_NAME);
            assertThat(packet.getExtra("title")).isEqualTo("Welcome");
            assertThat(packet.getExtra("subtitle")).isEqualTo("to the server");
        }

        @Test
        @DisplayName("a title without a subtitle sends an empty one")
        void withoutSubtitle() {
            new TitleCommand().execute(new String[]{"global", "Welcome"}, playerContext(true));

            assertThat(sentAdminAction().getExtra("subtitle")).isEmpty();
        }

        @Test
        @DisplayName("console title carries the sentinel UUID")
        void consoleUsesSentinel() {
            new TitleCommand().execute(new String[]{"global", "Welcome"}, consoleContext(true));

            AdminActionPacket packet = sentAdminAction();
            assertThat(packet.getPlayerId().toString())
                    .isEqualTo(NovaConstants.CONSOLE_SENTINEL_UUID);
            assertThat(packet.getExtra("console")).isEqualTo("true");
        }

        @Test
        @DisplayName("a non-admin is refused")
        void nonAdminRefused() {
            assertThat(new TitleCommand().execute(new String[]{"global", "hi"},
                    playerContext(false))).isFalse();
            verify(networkClient, never()).sendPacket(any());
        }

        @Test
        @DisplayName("the permission node matches the bukkit/folia shell")
        void permissionNodeMatches() {
            assertThat(new TitleCommand().getPermissionNode()).isEqualTo("novachat.title");
        }
    }

    // ============================ AuthCommand ============================

    @Nested
    @DisplayName("AuthCommand")
    class Auth {

        @Test
        @DisplayName("sends an AUTH packet with the SHA-256 hex hash and playerName")
        void sendsAuthPacket() {
            boolean ok = new AuthCommand().execute(new String[]{"s3cret"}, playerContext(true));

            assertThat(ok).isTrue();
            AdminActionPacket packet = sentAdminAction();
            assertThat(packet.getAction()).isEqualTo(AdminAction.AUTH);
            assertThat(packet.getPlayerId()).isEqualTo(OPERATOR);
            assertThat(packet.getPasswordHash())
                    .isEqualTo(com.nova.chat.client.network.PasswordHasher.sha256Hex("s3cret"))
                    .hasSize(64);
            assertThat(packet.getExtra("playerName")).isEqualTo(OPERATOR_NAME);
        }

        @Test
        @DisplayName("missing password prints usage")
        void missingPasswordPrintsUsage() {
            new AuthCommand().execute(new String[0], playerContext(true));

            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("/nc auth");
        }

        @Test
        @DisplayName("a non-admin is refused")
        void nonAdminRefused() {
            assertThat(new AuthCommand().execute(new String[]{"s3cret"},
                    playerContext(false))).isFalse();
            verify(networkClient, never()).sendPacket(any());
        }

        @Test
        @DisplayName("the permission node matches the bukkit/folia shell")
        void permissionNodeMatches() {
            assertThat(new AuthCommand().getPermissionNode()).isEqualTo("novachat.auth");
        }
    }

    // ============================ shared gates ============================

    @Nested
    @DisplayName("shared gates")
    class Gates {

        @Test
        @DisplayName("a disconnected backend is reported as NC-500 and nothing is sent")
        void disconnectedRefused() {
            when(networkClient.isConnected()).thenReturn(false);

            new MuteCommand().execute(new String[]{"Bob", "1h", "global"}, playerContext(true));

            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("NC-500");
        }

        @Test
        @DisplayName("an unauthenticated client is reported as NC-401")
        void unauthenticatedRefused() {
            when(networkClient.isAuthenticated()).thenReturn(false);

            new BanCommand().execute(new String[]{"Bob", "global"}, playerContext(true));

            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("NC-401");
        }

        @Test
        @DisplayName("null services is reported as not initialized")
        void nullServicesReported() {
            CommandContext ctx = new CommandContext(OPERATOR, OPERATOR_NAME, platform, true);

            boolean ok = new KickCommand().execute(new String[]{"Bob", "global"}, ctx);

            assertThat(ok).isFalse();
            verify(networkClient, never()).sendPacket(any());
            assertThat(String.join("\n", sentMessages)).contains("NovaChat not initialized");
        }

        @Test
        @DisplayName("a packet that cannot be sent is reported as NC-503")
        void sendFailureReported() {
            // connection check passes, then the connection drops before the send
            when(networkClient.isConnected()).thenReturn(true, false);
            onlineTarget();

            new KickCommand().execute(new String[]{"Bob", "global"}, playerContext(true));

            assertThat(String.join("\n", sentMessages)).contains("NC-503");
        }
    }
}
