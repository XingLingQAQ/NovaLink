package com.nova.link.integration;

import com.nova.chat.common.protocol.AdminAction;
import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.*;
import com.nova.link.auth.SuperAdminCredentials;
import org.junit.jupiter.api.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for admin command execution flow verification.
 *
 * <p>The server is started via {@link IntegrationTestHelper} whose default
 * wiring registers the <b>production</b> {@code AdminActionHandler} — there is
 * no anonymous stub handler here. Tests that need an active super-admin
 * session first register {@link SuperAdminCredentials} on
 * {@link IntegrationTestHelper#getPermissionManager()} and then drive AUTH to
 * create the session; assertions match the real production response strings
 * (e.g. {@code "System status: OK"}).
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
        // Default: production AdminActionHandler is registered by startServer.
        helper.startServer(TEST_PORT);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (helper != null) {
            helper.stopServer();
        }
    }

    /**
     * Registers a super admin credential for the given player UUID so the
     * production {@code AdminActionHandler} will accept AUTH / SPY / STATUS
     * packets carrying that UUID. Returns the UUID for convenience.
     */
    private static UUID registerSuperAdmin(UUID playerId) {
        SuperAdminCredentials creds = new SuperAdminCredentials(
                playerId, IntegrationTestHelper.hashPassword(SUPER_ADMIN_PASSWORD));
        helper.getPermissionManager().registerSuperAdmin(creds);
        return playerId;
    }

    @Test
    void otherClientsCannotUseOrRevokeAnAdminsSession() throws Exception {
        helper.registerClient("SessionOwner", "password");
        helper.registerClient("OtherClient", "password");
        var owner = helper.createClient(PlatformType.BUKKIT);
        var other = helper.createClient(PlatformType.BUKKIT);
        try {
            owner.connect().get(5, TimeUnit.SECONDS);
            owner.authenticate("SessionOwner", "password").get(5, TimeUnit.SECONDS);
            other.connect().get(5, TimeUnit.SECONDS);
            other.authenticate("OtherClient", "password").get(5, TimeUnit.SECONDS);
            UUID playerId = registerSuperAdmin(owner.getPlayerUuid());
            joinPlayer(owner, playerId);
            var auth = AdminActionPacket.createAuthPacket(playerId, IntegrationTestHelper.hashPassword(SUPER_ADMIN_PASSWORD));
            owner.sendPacket(auth);
            assertThat(owner.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS).isSuccess()).isTrue();

            for (AdminAction action : AdminAction.values()) {
                var borrowed = new AdminActionPacket();
                borrowed.setAction(action);
                borrowed.setPlayerId(playerId);
                borrowed.setPasswordHash(IntegrationTestHelper.hashPassword(SUPER_ADMIN_PASSWORD));
                borrowed.setTarget("global");
                other.sendPacket(borrowed);
                var response = other.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS);
                assertThat(response.isSuccess()).as("%s cannot borrow another client's player", action).isFalse();
                assertThat(response.getErrorCode()).isEqualTo("NC-403");
                assertThat(response.getRequestId()).isEqualTo(borrowed.getRequestId());
                assertThat(helper.getPermissionManager().hasSuperAdminSession(playerId)).isTrue();
            }

            var status = new AdminActionPacket();
            status.setAction(AdminAction.STATUS);
            status.setPlayerId(playerId);
            owner.sendPacket(status);
            assertThat(owner.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS).isSuccess()).isTrue();

            joinPlayer(other, playerId);
            owner.sendPacket(status);
            assertThat(owner.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS).getErrorCode()).isEqualTo("NC-403");
            other.sendPacket(status);
            assertThat(other.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS).isSuccess()).isTrue();
            other.sendPacket(AdminActionPacket.createLogoutPacket(playerId));
            assertThat(other.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(helper.getPermissionManager().hasSuperAdminSession(playerId)).isFalse();

            other.sendPacket(auth);
            assertThat(other.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS).isSuccess()).isTrue();
            var logout = new ChannelActionPacket(ChannelAction.LEAVE, "global");
            logout.addExtra("playerId", playerId.toString());
            logout.addExtra("logout", "true");
            other.sendPacket(logout);
            assertThat(other.waitForPacket(ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS).isSuccess()).isTrue();
            other.sendPacket(status);
            assertThat(other.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS).getErrorCode()).isEqualTo("NC-403");
            assertThat(helper.getPermissionManager().hasSuperAdminSession(playerId)).isTrue();
        } finally {
            other.disconnect();
            owner.disconnect();
        }
    }

    private static void joinPlayer(IntegrationTestHelper.TestClient client, UUID playerId) throws Exception {
        var join = new ChannelActionPacket(ChannelAction.JOIN, "global");
        join.addExtra("playerId", playerId.toString());
        join.addExtra("playerName", "Admin");
        client.sendPacket(join);
        assertThat(client.waitForPacket(ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS).isSuccess()).isTrue();
    }

    @Test
    @Order(1)
    @DisplayName("Super admin authentication should succeed with valid password")
    void testSuperAdminAuth() throws Exception {
        helper.registerClient("AdminClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("AdminClient", "password").get(5, TimeUnit.SECONDS);

        UUID playerId = registerSuperAdmin(client.getPlayerUuid());
        joinPlayer(client, playerId);
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

        UUID playerId = registerSuperAdmin(client.getPlayerUuid());
        joinPlayer(client, playerId);
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
        // Production AdminActionHandler surfaces AuthResult code; on password
        // mismatch it is NC-401 ("Invalid password").
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

        UUID playerId = registerSuperAdmin(client.getPlayerUuid());
        joinPlayer(client, playerId);
        AdminActionPacket authPacket = AdminActionPacket.createAuthPacket(
            playerId,
            IntegrationTestHelper.hashPassword(SUPER_ADMIN_PASSWORD)
        );
        client.sendPacket(authPacket);
        client.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS);

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

        // Random UUID never registered and never AUTHed → no super admin session.
        UUID playerId = UUID.randomUUID();
        joinPlayer(client, playerId);
        AdminActionPacket spyPacket = AdminActionPacket.createSpyStartPacket(playerId, "global");
        client.sendPacket(spyPacket);

        AdminActionResponsePacket response = client.waitForPacket(
            AdminActionResponsePacket.class, 5, TimeUnit.SECONDS
        );

        assertThat(response.isSuccess()).isFalse();
        // Production AdminActionHandler.handleSpyStart → NC-403 when no session.
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

        UUID playerId = registerSuperAdmin(client.getPlayerUuid());
        joinPlayer(client, playerId);
        AdminActionPacket authPacket = AdminActionPacket.createAuthPacket(
            playerId,
            IntegrationTestHelper.hashPassword(SUPER_ADMIN_PASSWORD)
        );
        client.sendPacket(authPacket);
        client.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS);

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
    @DisplayName("Status command should return production status text")
    void testStatusCommand() throws Exception {
        helper.registerClient("StatusClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("StatusClient", "password").get(5, TimeUnit.SECONDS);

        UUID playerId = registerSuperAdmin(client.getPlayerUuid());
        joinPlayer(client, playerId);
        AdminActionPacket authPacket = AdminActionPacket.createAuthPacket(
            playerId,
            IntegrationTestHelper.hashPassword(SUPER_ADMIN_PASSWORD)
        );
        client.sendPacket(authPacket);
        client.waitForPacket(AdminActionResponsePacket.class, 5, TimeUnit.SECONDS);

        AdminActionPacket statusPacket = new AdminActionPacket();
        statusPacket.setAction(AdminAction.STATUS);
        statusPacket.setPlayerId(playerId);
        statusPacket.setPasswordHash("");
        statusPacket.setTarget("");
        client.sendPacket(statusPacket);

        AdminActionResponsePacket response = client.waitForPacket(
            AdminActionResponsePacket.class, 5, TimeUnit.SECONDS
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(AdminAction.STATUS);
        // Production AdminActionHandler.handleStatus returns the literal
        // "System status: OK" (no connections/uptime placeholders). Asserting
        // the production text here is what makes this test fail if someone
        // bypasses or regresses the real handler.
        assertThat(response.getMessage()).isEqualTo("System status: OK");

        client.disconnect();
    }

    @Test
    @Order(7)
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
}
