package com.nova.chat.folia.network;

import com.nova.chat.client.network.ClientConnectionConfig;
import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.chat.common.protocol.packets.ChatMessagePacket;
import com.nova.chat.folia.NovaChatFolia;
import com.nova.chat.folia.config.NovaChatConfig;
import com.nova.chat.folia.scheduler.FoliaSchedulerAdapter;
import org.bukkit.Server;
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
import static org.mockito.Mockito.when;

/**
 * Guards the Folia-side admin-action correlation.
 *
 * <p>{@link AsyncNetworkClient#sendPacket} records {@code requestId -> senderId}
 * for every {@link AdminActionPacket} before it reaches the core. Without that
 * record the response handler cannot route the outcome back to anyone, and
 * {@code /nc announce}, {@code /nc title} and {@code /nc auth} go silent again —
 * so the recording is pinned here rather than left to the handler's tests.
 */
@DisplayName("Folia AsyncNetworkClient admin-action correlation")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AsyncNetworkClientAdminCorrelationTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private NovaChatFolia plugin;
    @Mock
    private NovaChatConfig config;
    @Mock
    private FoliaSchedulerAdapter scheduler;
    @Mock
    private Server server;

    private AsyncNetworkClient client;

    @BeforeEach
    void setUp() {
        when(plugin.getServer()).thenReturn(server);
        when(server.getVersion()).thenReturn("test-server");
        when(config.toClientConnectionConfig())
                .thenReturn(ClientConnectionConfig.builder().build());
        client = new AsyncNetworkClient(plugin, config, scheduler);
    }

    @Test
    @DisplayName("sending an admin action records requestId -> senderId")
    void adminActionIsTracked() {
        AdminActionPacket packet = AdminActionPacket.createAuthPacket(PLAYER_ID, "hash");

        client.sendPacket(packet);

        assertThat(client.pendingAdminRequestCount()).isEqualTo(1);
        assertThat(client.consumePendingAdminRequest(packet.getRequestId())).isEqualTo(PLAYER_ID);
        assertThat(client.pendingAdminRequestCount())
                .as("consuming removes the entry so a duplicate response renders nothing")
                .isZero();
    }

    @Test
    @DisplayName("status packets (announce/title) are tracked the same way")
    void statusPacketIsTracked() {
        AdminActionPacket packet = new AdminActionPacket();
        packet.setAction(AdminAction.STATUS);
        packet.setPlayerId(PLAYER_ID);

        client.sendPacket(packet);

        assertThat(client.consumePendingAdminRequest(packet.getRequestId())).isEqualTo(PLAYER_ID);
    }

    @Test
    @DisplayName("non-admin packets are not tracked")
    void otherPacketsAreNotTracked() {
        ChatMessagePacket chat = new ChatMessagePacket(
                PLAYER_ID, "Alice", "srv", "global", "hi");

        client.sendPacket(chat);

        assertThat(client.pendingAdminRequestCount()).isZero();
        assertThat(client.consumePendingAdminRequest(chat.getRequestId())).isNull();
    }

    @Test
    @DisplayName("consuming an unknown or null request id returns null instead of throwing")
    void unknownRequestIdIsNull() {
        assertThat(client.consumePendingAdminRequest(UUID.randomUUID())).isNull();
        assertThat(client.consumePendingAdminRequest(null)).isNull();
    }
}
