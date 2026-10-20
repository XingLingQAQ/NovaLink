package com.nova.link.channel;

import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.link.ban.BanManager;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.console.ConsoleSentinel;
import com.nova.link.database.MemoryProvider;
import com.nova.link.network.ClientConnection;
import com.nova.link.network.ServerNetworkHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sender-membership enforcement on the untrusted chat path.
 *
 * <p>What a kick actually changes is channel membership
 * ({@code handleKick} → {@code removeMember} + {@code leaveChannel}). Before
 * this gate existed a kicked player kept full delivery: {@code fanOutGlobal}
 * walked every connection and never asked whether the sender still belonged to
 * the channel.
 *
 * <p>Recipient selection deliberately stays scope-driven — GLOBAL still fans
 * out to every connection, and each platform renders only the lines whose
 * channel is the player's active one. These tests assert the sender rule, not
 * a change in fan-out topology.
 */
@DisplayName("MessagePipeline sender membership")
class MessagePipelineMembershipTest {

    private static final String CHANNEL = "global";

    private MemoryProvider databaseProvider;
    private ChannelManager channelManager;
    private ServerNetworkHandler networkHandler;
    private PermissionManager permissionManager;
    private BanManager banManager;
    private MessagePipeline pipeline;
    private ClientConnection connection;

    private UUID superAdminId;
    private UUID senderId;

    @BeforeEach
    void setUp() throws Exception {
        databaseProvider = new MemoryProvider();
        databaseProvider.initialize();
        channelManager = new ChannelManager();
        permissionManager = new PermissionManager();

        superAdminId = UUID.randomUUID();
        senderId = UUID.randomUUID();
        permissionManager.registerSuperAdmin(new SuperAdminCredentials(superAdminId, "hash"));
        permissionManager.authenticateSuperAdmin(superAdminId, "hash");

        banManager = new BanManager(databaseProvider, permissionManager, channelManager);

        channelManager.createChannel(ChannelConfig.builder()
                .id(CHANNEL)
                .displayName("Global")
                .scope(ChannelScope.GLOBAL)
                .build());
        channelManager.createChannel(ChannelConfig.builder()
                .id("local")
                .displayName("Local")
                .scope(ChannelScope.SERVER)
                .clientId("Survival")
                .build());

        connection = mock(ClientConnection.class);
        when(connection.getClientId()).thenReturn("Survival");
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.isActive()).thenReturn(true);
        when(connection.sendPacket(any())).thenReturn(CompletableFuture.completedFuture(null));

        networkHandler = mock(ServerNetworkHandler.class);
        when(networkHandler.getConnections()).thenReturn(Set.of(connection));
        when(networkHandler.findByClientId("Survival")).thenReturn(connection);

        pipeline = new MessagePipeline(channelManager, networkHandler);
        pipeline.setBanManager(banManager);
    }

    @AfterEach
    void tearDown() {
        databaseProvider.shutdown();
    }

    private ChatMessagePacket msg(String channelId, String content) {
        return new ChatMessagePacket(senderId, "Steve", "Survival", channelId, content);
    }

    @Test
    @DisplayName("a member's message is delivered")
    void memberDelivers() {
        channelManager.addMember(CHANNEL, senderId);

        MessagePipelineResult result = pipeline.process(msg(CHANNEL, "hello"));

        assertThat(result.isDelivered()).isTrue();
        assertThat(result.getRecipients()).containsExactly("Survival");
        verify(connection).sendPacket(any());
    }

    @Test
    @DisplayName("a non-member's message is dropped with SENDER_NOT_IN_CHANNEL")
    void nonMemberDropped() {
        MessagePipelineResult result = pipeline.process(msg(CHANNEL, "hello"));

        assertThat(result.isDelivered()).isFalse();
        assertThat(result.getDropReason())
                .isEqualTo(MessagePipelineResult.DropReason.SENDER_NOT_IN_CHANNEL);
        assertThat(result.getChannel()).isNotNull();
        verify(connection, never()).sendPacket(any());
    }

    @Test
    @DisplayName("a kicked player's subsequent message is not delivered")
    void kickedPlayerSilenced() {
        channelManager.addMember(CHANNEL, senderId);
        assertThat(pipeline.process(msg(CHANNEL, "before the kick")).isDelivered()).isTrue();

        // What handleKick does: drop the membership and the player state entry.
        channelManager.removeMember(CHANNEL, senderId);

        MessagePipelineResult after = pipeline.process(msg(CHANNEL, "after the kick"));

        assertThat(after.isDelivered()).isFalse();
        assertThat(after.getDropReason())
                .isEqualTo(MessagePipelineResult.DropReason.SENDER_NOT_IN_CHANNEL);
        // Exactly one delivery: the pre-kick line.
        verify(connection, org.mockito.Mockito.times(1)).sendPacket(any());
    }

    @Test
    @DisplayName("membership is per channel: leaving one does not silence another")
    void membershipIsPerChannel() {
        channelManager.addMember("local", senderId);

        assertThat(pipeline.process(msg("local", "still here")).isDelivered()).isTrue();
        assertThat(pipeline.process(msg(CHANNEL, "not here")).getDropReason())
                .isEqualTo(MessagePipelineResult.DropReason.SENDER_NOT_IN_CHANNEL);
    }

    @Test
    @DisplayName("a banned sender is still dropped earlier with SENDER_BANNED")
    void bannedSenderKeepsItsOwnDropReason() {
        channelManager.addMember(CHANNEL, senderId);
        banManager.banPlayer(superAdminId, senderId, CHANNEL, 60_000, "spam", "Survival");
        // The ban also ejects membership; re-add it so this test isolates the
        // ban stage rather than the membership stage that follows it.
        channelManager.addMember(CHANNEL, senderId);

        MessagePipelineResult result = pipeline.process(msg(CHANNEL, "hello"));

        assertThat(result.getDropReason())
                .isEqualTo(MessagePipelineResult.DropReason.SENDER_BANNED);
    }

    @Test
    @DisplayName("trusted callers are exempt from the membership gate")
    void trustedPathSkipsMembership() {
        // The console/panel is never a channel member; REST and announcements
        // must keep working.
        MessagePipelineResult result = pipeline.processForChannel(
                channelManager.getChannel(CHANNEL), msg(CHANNEL, "announcement"));

        assertThat(result.isDelivered()).isTrue();
        assertThat(result.getRecipients()).containsExactly("Survival");
    }

    @Test
    @DisplayName("the console sentinel passes the gate even on the untrusted path")
    void consoleSentinelIsExempt() {
        ChatMessagePacket packet = new ChatMessagePacket(
                ConsoleSentinel.CONSOLE_SENTINEL, "Console", "Survival", CHANNEL, "announcement");

        MessagePipelineResult result = pipeline.process(packet);

        assertThat(result.getDropReason())
                .isNotEqualTo(MessagePipelineResult.DropReason.SENDER_NOT_IN_CHANNEL);
        assertThat(result.isDelivered()).isTrue();
    }

    @Test
    @DisplayName("a null sender id carries no membership claim and is not gated")
    void nullSenderIsNotGated() {
        ChatMessagePacket packet = new ChatMessagePacket(null, "Anon", "Survival", CHANNEL, "hello");

        MessagePipelineResult result = pipeline.process(packet);

        assertThat(result.getDropReason())
                .isNotEqualTo(MessagePipelineResult.DropReason.SENDER_NOT_IN_CHANNEL);
    }
}
