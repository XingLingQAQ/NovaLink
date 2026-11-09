package com.nova.link.network;

import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.packets.AdminActionPacket;
import com.nova.link.auth.IpBanManager;
import com.nova.link.auth.PermissionManager;
import com.nova.link.auth.SuperAdminCredentials;
import com.nova.link.console.ConsoleSentinel;
import com.nova.link.database.MemoryProvider;
import com.nova.link.database.PlayerStateManager;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminClientIngressTest {
    @Test
    void networkAuthKeepsIpProtectionAndDirectAndConsoleEntryPoints() throws Exception {
        var db = new MemoryProvider();
        db.initialize();
        try {
            var permissions = new PermissionManager();
            var players = new PlayerStateManager(db);
            var handler = new AdminActionHandler(permissions, players);
            var bans = new IpBanManager(1, 60_000);
            handler.setIpBanManager(bans);
            var connection = mock(ClientConnection.class);
            when(connection.isAuthenticated()).thenReturn(true);
            when(connection.getClientId()).thenReturn("client");
            when(connection.getRemoteAddress()).thenReturn("192.0.2.10");
            UUID player = UUID.randomUUID();
            permissions.registerSuperAdmin(new SuperAdminCredentials(player, "test-hash"));
            players.getOrCreateState(player, "Admin").setClientId("client");

            var failed = handler.handleFromClient(connection, AdminActionPacket.createAuthPacket(player, "wrong"));
            assertThat(failed.getErrorCode()).isEqualTo("NC-401");
            assertThat(bans.isBanned("192.0.2.10")).isTrue();
            var valid = AdminActionPacket.createAuthPacket(player, "test-hash");
            assertThat(handler.handleFromClient(connection, valid).getErrorCode()).isEqualTo("NC-403");
            assertThat(permissions.hasSuperAdminSession(player)).isFalse();
            bans.unban("192.0.2.10");
            assertThat(handler.handleFromClient(connection, valid).isSuccess()).isTrue();

            var direct = new AdminActionHandler(permissions);
            players.removeFromCache(player, true);
            assertThat(handler.handleFromClient(connection, valid).getErrorCode()).isEqualTo("NC-403");
            assertThat(direct.handle(valid).isSuccess()).isTrue();

            var status = new AdminActionPacket();
            status.setAction(AdminAction.STATUS);
            status.setPlayerId(ConsoleSentinel.CONSOLE_SENTINEL);
            assertThat(handler.handleFromClient(connection, status).getErrorCode()).isEqualTo("NC-403");
            permissions.registerSuperAdmin(new SuperAdminCredentials(ConsoleSentinel.CONSOLE_SENTINEL, "console-test-hash"));
            permissions.authenticateSuperAdmin(ConsoleSentinel.CONSOLE_SENTINEL, "console-test-hash");
            assertThat(handler.handleFromClient(connection, status).isSuccess()).isTrue();
            when(connection.isAuthenticated()).thenReturn(false);
            assertThat(handler.handleFromClient(connection, status).getErrorCode()).isEqualTo("NC-401");
        } finally {
            db.shutdown();
        }
    }
}
