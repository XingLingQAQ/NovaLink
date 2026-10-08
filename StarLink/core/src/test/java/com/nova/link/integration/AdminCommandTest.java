package com.nova.link.integration;

import com.nova.chat.common.protocol.AdminAction;
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
    @Order(1)
    @DisplayName("Super admin authentication should succeed with valid password")
    void testSuperAdminAuth() throws Exception {
        helper.registerClient("AdminClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("AdminClient", "password").get(5, TimeUnit.SECONDS);

        UUID playerId = registerSuperAdmin(UUID.randomUUID());
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

        UUID playerId = registerSuperAdmin(UUID.randomUUID());
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

        UUID playerId = registerSuperAdmin(UUID.randomUUID());
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

        UUID playerId = registerSuperAdmin(UUID.randomUUID());
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

        UUID playerId = registerSuperAdmin(UUID.randomUUID());
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
