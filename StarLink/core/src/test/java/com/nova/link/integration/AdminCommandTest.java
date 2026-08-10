package com.nova.link.integration;

import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.*;
import com.nova.link.network.PacketHandler;
import com.nova.link.network.ClientConnection;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for admin command execution flow verification.
 * 
 * Requirements: 23.9 - Verify admin command execution flow
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminCommandTest {

    private static IntegrationTestHelper helper;
    private static final int TEST_PORT = 18897;
    private static final String SUPER_ADMIN_PASSWORD = "super-admin-secret";

    @BeforeAll
    static void setUp() throws Exception {
        helper = new IntegrationTestHelper();
        helper.startServer(TEST_PORT);
        
        // Register admin action handler
        helper.getNetworkHandler().registerHandler(AdminActionPacket.class, 
            new PacketHandler<AdminActionPacket>() {
                @Override
                public void handle(ClientConnection connection, AdminActionPacket packet) {
                    if (!connection.isAuthenticated()) {
                        AdminActionResponsePacket response = AdminActionResponsePacket.failure(
                            packet.getAction(), "NC-401", "Not authenticated"
                        );
                        response.setRequestId(packet.getRequestId());
                        connection.sendPacket(response);
                        return;
                    }
                    
                    AdminActionResponsePacket response;
                    switch (packet.getAction()) {
                        case AUTH:
                            String expectedHash = IntegrationTestHelper.hashPassword(SUPER_ADMIN_PASSWORD);
                            if (expectedHash.equals(packet.getPasswordHash())) {
                                connection.setSuperAdminUuid(packet.getPlayerId());
                                response = AdminActionResponsePacket.success(
                                    AdminAction.AUTH, "Super admin authenticated"
                                );
                            } else {
                                response = AdminActionResponsePacket.failure(
                                    AdminAction.AUTH, "NC-401", "Invalid super admin password"
                                );
                            }
                            break;
                        case LOGOUT:
                            connection.setSuperAdminUuid(null);
                            response = AdminActionResponsePacket.success(
                                AdminAction.LOGOUT, "Logged out"
                            );
                            break;
                        case SPY_START:
                            if (connection.getSuperAdminUuid() != null) {
                                response = AdminActionResponsePacket.success(
                                    AdminAction.SPY_START, "Spy mode started on: " + packet.getTarget()
                                );
                            } else {
                                response = AdminActionResponsePacket.failure(
                                    AdminAction.SPY_START, "NC-403", "Super admin required"
                                );
                            }
                            break;
                        case SPY_STOP:
                            response = AdminActionResponsePacket.success(
                                AdminAction.SPY_STOP, "Spy mode stopped"
                            );
                            break;
                        case RELOAD:
                            response = AdminActionResponsePacket.success(
                                AdminAction.RELOAD, "Configuration reloaded"
                            );
                            break;
                        case STATUS:
                            response = AdminActionResponsePacket.success(
                                AdminAction.STATUS, "Server status: OK, connections=1, uptime=1000"
                            );
                            break;
                        default:
                            response = AdminActionResponsePacket.failure(
                                packet.getAction(), "NC-400", "Unknown action"
                            );
                    }
                    response.setRequestId(packet.getRequestId());
                    connection.sendPacket(response);
                }
            }
        );
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (helper != null) {
            helper.stopServer();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Super admin authentication should succeed with valid password")
    void testSuperAdminAuth() throws Exception {
        helper.registerClient("AdminClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("AdminClient", "password").get(5, TimeUnit.SECONDS);
        
        UUID playerId = UUID.randomUUID();
        AdminActionPacket authPacket = AdminActionPacket.createAuthPacket(
            playerId, 
            IntegrationTestHelper.hashPassword(SUPER_ADMIN_PASSWORD)
        );
        client.sendPacket(authPacket);
        
        AdminActionResponsePacket response = client.waitForPacket(
            AdminActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(AdminAction.AUTH);
        assertThat(response.getErrorCode()).isEmpty();
        
        client.disconnect();
    }

    @Test
    @Order(2)
    @DisplayName("Super admin authentication should fail with invalid password")
    void testSuperAdminAuthFailure() throws Exception {
        helper.registerClient("AdminClient2", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("AdminClient2", "password").get(5, TimeUnit.SECONDS);
        
        UUID playerId = UUID.randomUUID();
        AdminActionPacket authPacket = AdminActionPacket.createAuthPacket(
            playerId, 
            IntegrationTestHelper.hashPassword("wrong-password")
        );
        client.sendPacket(authPacket);
        
        AdminActionResponsePacket response = client.waitForPacket(
            AdminActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getAction()).isEqualTo(AdminAction.AUTH);
        assertThat(response.getErrorCode()).isEqualTo("NC-401");
        
        client.disconnect();
    }

    @Test
    @Order(3)
    @DisplayName("Super admin logout should work")
    void testSuperAdminLogout() throws Exception {
        helper.registerClient("LogoutClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("LogoutClient", "password").get(5, TimeUnit.SECONDS);
        
        // First authenticate as super admin
        UUID playerId = UUID.randomUUID();
        AdminActionPacket authPacket = AdminActionPacket.createAuthPacket(
            playerId, 
            IntegrationTestHelper.hashPassword(SUPER_ADMIN_PASSWORD)
        );
        client.sendPacket(authPacket);
        client.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS);
        
        // Then logout
        AdminActionPacket logoutPacket = AdminActionPacket.createLogoutPacket(playerId);
        client.sendPacket(logoutPacket);
        
        AdminActionResponsePacket response = client.waitForPacket(
            AdminActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(AdminAction.LOGOUT);
        
        client.disconnect();
    }

    @Test
    @Order(4)
    @DisplayName("Spy mode should require super admin")
    void testSpyModeRequiresSuperAdmin() throws Exception {
        helper.registerClient("SpyClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("SpyClient", "password").get(5, TimeUnit.SECONDS);
        
        // Try to start spy without super admin auth
        UUID playerId = UUID.randomUUID();
        AdminActionPacket spyPacket = AdminActionPacket.createSpyStartPacket(playerId, "global");
        client.sendPacket(spyPacket);
        
        AdminActionResponsePacket response = client.waitForPacket(
            AdminActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-403");
        
        client.disconnect();
    }

    @Test
    @Order(5)
    @DisplayName("Spy mode should work for super admin")
    void testSpyModeForSuperAdmin() throws Exception {
        helper.registerClient("SuperSpyClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("SuperSpyClient", "password").get(5, TimeUnit.SECONDS);
        
        // First authenticate as super admin
        UUID playerId = UUID.randomUUID();
        AdminActionPacket authPacket = AdminActionPacket.createAuthPacket(
            playerId, 
            IntegrationTestHelper.hashPassword(SUPER_ADMIN_PASSWORD)
        );
        client.sendPacket(authPacket);
        client.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS);
        
        // Now start spy mode
        AdminActionPacket spyPacket = AdminActionPacket.createSpyStartPacket(playerId, "private-channel");
        client.sendPacket(spyPacket);
        
        AdminActionResponsePacket response = client.waitForPacket(
            AdminActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(AdminAction.SPY_START);
        
        client.disconnect();
    }

    @Test
    @Order(6)
    @DisplayName("Reload command should work")
    void testReloadCommand() throws Exception {
        helper.registerClient("ReloadClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("ReloadClient", "password").get(5, TimeUnit.SECONDS);
        
        AdminActionPacket reloadPacket = new AdminActionPacket();
        reloadPacket.setAction(AdminAction.RELOAD);
        reloadPacket.setPlayerId(UUID.randomUUID());
        reloadPacket.setPasswordHash("");
        reloadPacket.setTarget("");
        client.sendPacket(reloadPacket);
        
        AdminActionResponsePacket response = client.waitForPacket(
            AdminActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(AdminAction.RELOAD);
        
        client.disconnect();
    }

    @Test
    @Order(7)
    @DisplayName("Status command should return server info")
    void testStatusCommand() throws Exception {
        helper.registerClient("StatusClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("StatusClient", "password").get(5, TimeUnit.SECONDS);
        
        AdminActionPacket statusPacket = new AdminActionPacket();
        statusPacket.setAction(AdminAction.STATUS);
        statusPacket.setPlayerId(UUID.randomUUID());
        statusPacket.setPasswordHash("");
        statusPacket.setTarget("");
        client.sendPacket(statusPacket);
        
        AdminActionResponsePacket response = client.waitForPacket(
            AdminActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(AdminAction.STATUS);
        assertThat(response.getMessage()).contains("connections");
        assertThat(response.getMessage()).contains("uptime");
        
        client.disconnect();
    }

    @Test
    @Order(8)
    @DisplayName("Unauthenticated client should not execute admin commands")
    void testUnauthenticatedAdminCommand() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        // Do NOT authenticate
        
        AdminActionPacket statusPacket = new AdminActionPacket();
        statusPacket.setAction(AdminAction.STATUS);
        statusPacket.setPlayerId(UUID.randomUUID());
        statusPacket.setPasswordHash("");
        statusPacket.setTarget("");
        client.sendPacket(statusPacket);
        
        AdminActionResponsePacket response = client.waitForPacket(
            AdminActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-401");
        
        client.disconnect();
    }

    @Test
    @Order(9)
    @DisplayName("Channel kick action should work for admin")
    void testChannelKickAction() throws Exception {
        helper.registerClient("KickClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("KickClient", "password").get(5, TimeUnit.SECONDS);
        
        ChannelActionPacket kickAction = new ChannelActionPacket(ChannelAction.KICK, "global");
        kickAction.addExtra("targetPlayer", "BadPlayer");
        kickAction.addExtra("reason", "Spamming");
        client.sendPacket(kickAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.KICK);
        
        client.disconnect();
    }

    @Test
    @Order(10)
    @DisplayName("Channel mute action should work for admin")
    void testChannelMuteAction() throws Exception {
        helper.registerClient("MuteClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("MuteClient", "password").get(5, TimeUnit.SECONDS);
        
        ChannelActionPacket muteAction = new ChannelActionPacket(ChannelAction.MUTE, "global");
        muteAction.addExtra("targetPlayer", "NoisyPlayer");
        muteAction.addExtra("duration", "3600"); // 1 hour in seconds
        muteAction.addExtra("reason", "Excessive spam");
        client.sendPacket(muteAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.MUTE);
        
        client.disconnect();
    }

    @Test
    @Order(11)
    @DisplayName("Channel unmute action should work for admin")
    void testChannelUnmuteAction() throws Exception {
        helper.registerClient("UnmuteClient", "password");
        
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("UnmuteClient", "password").get(5, TimeUnit.SECONDS);
        
        ChannelActionPacket unmuteAction = new ChannelActionPacket(ChannelAction.UNMUTE, "global");
        unmuteAction.addExtra("targetPlayer", "FormerlyMutedPlayer");
        client.sendPacket(unmuteAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.UNMUTE);
        
        client.disconnect();
    }
}
