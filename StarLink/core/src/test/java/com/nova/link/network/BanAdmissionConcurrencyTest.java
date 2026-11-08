package com.nova.link.network;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import com.nova.link.auth.PermissionManager;
import com.nova.link.ban.BanManager;
import com.nova.link.ban.BanResult;
import com.nova.link.channel.ChannelConfig;
import com.nova.link.channel.ChannelManager;
import com.nova.link.channel.ChannelScope;
import com.nova.link.channel.InvitationManager;
import com.nova.link.channel.PrivateChannelManager;
import com.nova.link.console.ConsoleSentinel;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.management.ManagementFactory;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BanAdmissionConcurrencyTest {
    @ParameterizedTest(name = "{0}, global ban={1}")
    @CsvSource({"JOIN_GLOBAL,false", "JOIN_GLOBAL,true", "JOIN_PRIVATE,false", "JOIN_PRIVATE,true",
            "ACCEPT,false", "ACCEPT,true", "CREATE,true"})
    void completedBanCannotLeaveAnOverlappingAdmissionInTheChannel(String admission, boolean global) throws Exception {
        MemoryProvider db = new MemoryProvider();
        db.initialize();
        ChannelManager channels = new ChannelManager();
        PermissionManager permissions = new PermissionManager();
        PlayerStateManager players = new PlayerStateManager(db);
        UUID player = UUID.randomUUID();
        UUID operator = ConsoleSentinel.CONSOLE_SENTINEL;
        boolean privateChannel = !admission.equals("JOIN_GLOBAL");
        String channelId = "target";
        var config = ChannelConfig.builder().id(channelId)
                .scope(privateChannel ? ChannelScope.PRIVATE : ChannelScope.GLOBAL).password("secret");
        if (privateChannel) config.clientId("primary");
        channels.createChannel(config.build());
        InvitationManager invitations = new InvitationManager(db, channels);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch continueAdmission = new CountDownLatch(1);
        BanManager bans = new BanManager(db, permissions, channels) {
            @Override
            public boolean isBanned(UUID playerId, String target) {
                boolean banned = super.isBanned(playerId, target);
                checked.countDown();
                try {
                    if (!continueAdmission.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Admission gate timed out");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return banned;
            }
        };
        ChannelActionHandler handler = new ChannelActionHandler(channels, players, db,
                new PrivateChannelManager(channels), invitations, permissions, null, bans);
        ClientConnection connection = mock(ClientConnection.class);
        when(connection.isAuthenticated()).thenReturn(true);
        when(connection.getClientId()).thenReturn("primary");
        String target = admission.equals("ACCEPT")
                ? invitations.createInvitation(channelId, operator).getCode() : channelId;
        ChannelAction action = switch (admission) {
            case "ACCEPT" -> ChannelAction.ACCEPT;
            case "CREATE" -> ChannelAction.CREATE;
            default -> ChannelAction.JOIN;
        };
        ChannelActionPacket packet = new ChannelActionPacket(action, target);
        packet.setPassword("secret");
        packet.addExtra("playerId", player.toString());
        packet.addExtra("playerName", "Player");
        CompletableFuture<ChannelActionResponsePacket> admitted = new CompletableFuture<>();
        CompletableFuture<BanResult> banned = new CompletableFuture<>();
        Thread joiner = new Thread(() -> {
            try {
                admitted.complete(handler.handle(connection, packet));
            } catch (Throwable error) {
                admitted.completeExceptionally(error);
            }
        }, "admission-race");
        Thread banner = new Thread(() -> {
            try {
                banned.complete(bans.banPlayer(operator, player, global ? null : channelId, 0L, "race", "primary"));
            } catch (Throwable error) {
                banned.completeExceptionally(error);
            }
        }, "ban-race");
        try {
            joiner.start();
            assertThat(checked.await(5, TimeUnit.SECONDS)).isTrue();
            banner.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waitingForAdmission = false;
            var threads = ManagementFactory.getThreadMXBean();
            while (!banned.isDone() && System.nanoTime() < deadline) {
                var state = threads.getThreadInfo(banner.getId());
                if (state != null && state.getThreadState() == Thread.State.BLOCKED
                        && state.getLockOwnerId() == joiner.getId()) {
                    waitingForAdmission = true;
                    break;
                }
                Thread.yield();
            }
            assertThat(banned.isDone() || waitingForAdmission).isTrue();
            continueAdmission.countDown();
            assertThat(admitted.get(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(banned.get(5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(db.loadBans(player)).hasSize(1);
            assertThat(channels.getAllChannels()).allSatisfy(channel ->
                    assertThat(channel.isMember(player))
                            .as("a completed ban must eject an overlapping admission").isFalse());
            if (admission.equals("ACCEPT")) {
                assertThat(invitations.getInvitation(target).orElseThrow().getUsedCount()).isEqualTo(1);
            }
        } finally {
            continueAdmission.countDown();
            joiner.join(5_000);
            banner.join(5_000);
            if (joiner.isAlive()) joiner.interrupt();
            if (banner.isAlive()) banner.interrupt();
            joiner.join(5_000);
            banner.join(5_000);
            db.shutdown();
            assertThat(joiner.isAlive() || banner.isAlive()).isFalse();
        }
    }
}
