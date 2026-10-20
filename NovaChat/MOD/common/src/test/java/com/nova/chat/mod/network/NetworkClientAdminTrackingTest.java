package com.nova.chat.mod.network;

import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.mod.config.ModConfig;
import com.nova.chat.mod.platform.ChatHandler;
import com.nova.chat.mod.platform.CommandManager;
import com.nova.chat.mod.platform.Platform;
import com.nova.chat.mod.platform.PlatformType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the admin-action correlation the mod {@link NetworkClient} added.
 *
 * <p>Without it the asynchronous {@code AdminActionResponsePacket} cannot be
 * routed back to the operator, so {@code /nc auth}, {@code /nc announce} and
 * {@code /nc title} would send and report nothing.
 *
 * <p>The send itself is a no-op here (the stub platform never connects), which
 * is exactly the point: the bookkeeping happens before the wire write, so it is
 * observable without a live socket.
 */
@DisplayName("NetworkClient admin-action tracking")
class NetworkClientAdminTrackingTest {

    private static final UUID OPERATOR = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private NetworkClient client;

    @BeforeEach
    void setUp() {
        client = new NetworkClient(new StubPlatform(), validConfig());
    }

    /** @return a config that survives {@code ModConfig#validate()} */
    private static ModConfig validConfig() {
        ModConfig config = new ModConfig();
        ModConfig.BackendConfig backend = new ModConfig.BackendConfig();
        backend.setHost("127.0.0.1");
        backend.setPort(8888);
        backend.setUsername("ModServer");
        backend.setPassword("password");
        backend.setReconnectDelay(5);
        config.setBackend(backend);
        config.getChat().setDefaultChannel("global");
        config.getChat().setLocale("en_US");
        return config;
    }

    @Test
    @DisplayName("an AdminActionPacket is recorded against its request id")
    void adminActionTracked() {
        AdminActionPacket packet = AdminActionPacket.createAuthPacket(OPERATOR, "hash");

        client.sendPacket(packet);

        assertThat(client.pendingAdminRequestCount()).isEqualTo(1);
        assertThat(client.consumePendingAdminRequest(packet.getRequestId()))
                .isEqualTo(OPERATOR);
    }

    @Test
    @DisplayName("consuming removes the entry, so a duplicate response renders nothing")
    void consumeRemovesEntry() {
        AdminActionPacket packet = AdminActionPacket.createAuthPacket(OPERATOR, "hash");
        client.sendPacket(packet);

        assertThat(client.consumePendingAdminRequest(packet.getRequestId())).isEqualTo(OPERATOR);
        assertThat(client.consumePendingAdminRequest(packet.getRequestId())).isNull();
        assertThat(client.pendingAdminRequestCount()).isZero();
    }

    @Test
    @DisplayName("a ChannelActionPacket is not tracked as an admin action")
    void channelActionNotTrackedAsAdmin() {
        ChannelActionPacket packet = new ChannelActionPacket(ChannelAction.MUTE, "global");

        client.sendPacket(packet);

        assertThat(client.pendingAdminRequestCount()).isZero();
    }

    @Test
    @DisplayName("a null request id is never tracked or consumed")
    void nullRequestIdHandled() {
        assertThat(client.consumePendingAdminRequest(null)).isNull();
        assertThat(client.pendingAdminRequestCount()).isZero();
    }

    @Test
    @DisplayName("each request id maps to its own sender")
    void distinctRequestsTrackedSeparately() {
        UUID other = UUID.fromString("22222222-3333-4444-5555-666666666666");
        AdminActionPacket first = AdminActionPacket.createAuthPacket(OPERATOR, "a");
        AdminActionPacket second = AdminActionPacket.createAuthPacket(other, "b");
        assertThat(first.getRequestId()).isNotEqualTo(second.getRequestId());

        client.sendPacket(first);
        client.sendPacket(second);

        assertThat(client.pendingAdminRequestCount()).isEqualTo(2);
        assertThat(client.consumePendingAdminRequest(first.getRequestId())).isEqualTo(OPERATOR);
        assertThat(client.consumePendingAdminRequest(second.getRequestId())).isEqualTo(other);
    }

    /** Minimal platform stub: the client is never connected in these tests. */
    private static final class StubPlatform implements Platform {
        @Override public void registerChatListener(ChatHandler handler) {}
        @Override public void registerCommands(CommandManager manager) {}
        @Override public void sendMessage(UUID playerId, Object message) {}
        @Override public void broadcastMessage(Object message) {}
        @Override public String getCurrentWorld(UUID playerId) { return "world"; }
        @Override public String getHeldItemJson(UUID playerId) { return null; }
        @Override public String getPlayerName(UUID playerId) { return null; }
        @Override public boolean isPlayerOnline(UUID playerId) { return false; }
        @Override public Collection<UUID> getOnlinePlayerIds() { return List.of(); }
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
}
