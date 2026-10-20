package com.nova.chat.folia.command;

import com.nova.chat.client.state.ChatMode;
import com.nova.chat.client.state.PlayerChannelState;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.chat.AsyncChatInterceptor;
import com.nova.chat.folia.network.AsyncNetworkClient;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Folia {@code /nc create}, {@code /nc invite} and
 * {@code /nc accept} shells.
 *
 * <p>These are the channel-lifecycle commands the Folia module was missing
 * alongside moderation. They are player-only, so the console gate is checked
 * here rather than through the registry.
 */
@DisplayName("Folia Create/Invite/Accept commands")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChannelLifecycleCommandTest extends CommandTestSupport {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TARGET_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Mock
    private NovaChatFolia plugin;
    @Mock
    private AsyncNetworkClient client;
    @Mock
    private AsyncChatInterceptor interceptor;
    @Mock
    private Player player;
    @Mock
    private World world;

    private CreateCommand create;
    private InviteCommand invite;
    private AcceptCommand accept;

    @BeforeEach
    void setUp() {
        wireConnected(plugin, client);
        when(plugin.getChatInterceptor()).thenReturn(interceptor);
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.getName()).thenReturn("Alice");
        when(player.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("world");
        create = new CreateCommand(plugin);
        invite = new InviteCommand(plugin);
        accept = new AcceptCommand(plugin);
    }

    @Test
    @DisplayName("create sends CREATE with the name, password and player extras")
    void createSendsPacket() {
        create.execute(player, new String[]{"staff", "hunter2"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(ChannelAction.CREATE);
        assertThat(packet.getChannelId()).isEqualTo("staff");
        assertThat(packet.getExtra("playerId")).isEqualTo(PLAYER_ID.toString());
        assertThat(packet.getExtra("playerName")).isEqualTo("Alice");
        assertThat(packet.getExtra("displayName")).isEqualTo("staff");
    }

    @Test
    @DisplayName("a channel name outside 2-16 characters is refused")
    void createRejectsBadNameLength() {
        create.execute(player, new String[]{"x"});

        verifyNoPacket(client);
        assertMessageContains(player, "NC-430");
    }

    @Test
    @DisplayName("a channel name with unsupported characters is refused")
    void createRejectsBadNameChars() {
        create.execute(player, new String[]{"bad name!"});

        verifyNoPacket(client);
        assertMessageContains(player, "NC-430");
    }

    @Test
    @DisplayName("create is player-only")
    void createIsPlayerOnly() {
        assertThat(create.isPlayerOnly()).isTrue();
        assertThat(create.getPermission()).isEqualTo("novachat.create");
    }

    @Test
    @DisplayName("invite sends INVITE with the channel and target")
    void inviteSendsPacket() {
        Player target = mock(Player.class);
        when(target.getUniqueId()).thenReturn(TARGET_ID);
        bukkit.when(() -> org.bukkit.Bukkit.getPlayer("Bob")).thenReturn(target);

        invite.execute(player, new String[]{"Bob", "staff"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(ChannelAction.INVITE);
        assertThat(packet.getChannelId()).isEqualTo("staff");
        assertThat(packet.getExtra("playerId")).isEqualTo(PLAYER_ID.toString());
        assertThat(packet.getExtra("targetId")).isEqualTo(TARGET_ID.toString());
        assertThat(packet.getExtra("targetName")).isEqualTo("Bob");
    }

    @Test
    @DisplayName("invite falls back to the active channel, and refuses without one")
    void inviteChannelFallback() {
        when(interceptor.getPlayerState(PLAYER_ID)).thenReturn(null);
        invite.execute(player, new String[]{"Bob"});
        verifyNoPacket(client);
        assertMessageContains(player, "NC-433");

        PlayerChannelState state = new PlayerChannelState(PLAYER_ID, "staff", ChatMode.HYBRID);
        when(interceptor.getPlayerState(PLAYER_ID)).thenReturn(state);
        invite.execute(player, new String[]{"Bob"});

        assertThat(((ChannelActionPacket) sentPacket(client)).getChannelId()).isEqualTo("staff");
    }

    @Test
    @DisplayName("accept sends ACCEPT with the upper-cased code and the world")
    void acceptSendsPacket() {
        accept.execute(player, new String[]{"abc123"});

        ChannelActionPacket packet = (ChannelActionPacket) sentPacket(client);
        assertThat(packet.getAction()).isEqualTo(ChannelAction.ACCEPT);
        assertThat(packet.getChannelId()).isEqualTo("ABC123");
        assertThat(packet.getExtra("playerId")).isEqualTo(PLAYER_ID.toString());
        assertThat(packet.getExtra("world")).isEqualTo("world");
    }

    @Test
    @DisplayName("a malformed invite code is refused")
    void acceptRejectsBadCode() {
        accept.execute(player, new String[]{"abc"});

        verifyNoPacket(client);
        assertMessageContains(player, "NC-430");
    }

    @Test
    @DisplayName("the lifecycle shells are player-only with the declared nodes")
    void permissionNodes() {
        assertThat(invite.isPlayerOnly()).isTrue();
        assertThat(accept.isPlayerOnly()).isTrue();
        assertThat(invite.getPermission()).isEqualTo("novachat.invite");
        assertThat(accept.getPermission()).isEqualTo("novachat.accept");
    }
}
