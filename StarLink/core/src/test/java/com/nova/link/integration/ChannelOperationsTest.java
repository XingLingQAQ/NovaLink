package com.nova.link.integration;

import com.nova.chat.common.protocol.ChannelAction;
import com.nova.chat.common.protocol.PlatformType;
import com.nova.chat.common.protocol.packets.ChannelActionPacket;
import com.nova.chat.common.protocol.packets.ChannelActionResponsePacket;
import org.junit.jupiter.api.*;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for channel join and leave flow verification.
 * 
 * Requirements: 23.8 - Verify channel join and leave flow
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChannelOperationsTest {

    private static IntegrationTestHelper helper;
    private static final int TEST_PORT = 18896;

    @BeforeAll
    static void setUp() throws Exception {
        helper = new IntegrationTestHelper();
        helper.startServer(TEST_PORT);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (helper != null) {
            helper.stopServer();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Client should successfully join a channel")
    void testChannelJoin() throws Exception {
        helper.registerClient("JoinClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("JoinClient", "password").get(5, TimeUnit.SECONDS);

        ChannelActionPacket joinAction = client.channelAction(ChannelAction.JOIN, "global");
        client.sendPacket(joinAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.JOIN);
        assertThat(response.getChannelId()).isEqualTo("global");
        assertThat(response.getErrorCode()).isEmpty();
        
        client.disconnect();
    }

    @Test
    @Order(2)
    @DisplayName("Client should successfully leave a channel")
    void testChannelLeave() throws Exception {
        helper.registerClient("LeaveClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("LeaveClient", "password").get(5, TimeUnit.SECONDS);

        // First join
        ChannelActionPacket joinAction = client.channelAction(ChannelAction.JOIN, "test-channel");
        client.sendPacket(joinAction);
        client.waitForPacket(ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS);

        // Then leave (same client, same playerId — LEAVE requires prior membership)
        ChannelActionPacket leaveAction = client.channelAction(ChannelAction.LEAVE, "test-channel");
        client.sendPacket(leaveAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.LEAVE);
        assertThat(response.getChannelId()).isEqualTo("test-channel");
        
        client.disconnect();
    }

    @Test
    @Order(3)
    @DisplayName("Client should be able to join multiple channels")
    void testJoinMultipleChannels() throws Exception {
        helper.registerClient("MultiChannelClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("MultiChannelClient", "password").get(5, TimeUnit.SECONDS);

        String[] channels = {"global", "local", "staff", "vip"};

        for (String channelId : channels) {
            ChannelActionPacket joinAction = client.channelAction(ChannelAction.JOIN, channelId);
            client.sendPacket(joinAction);
            
            ChannelActionResponsePacket response = client.waitForPacket(
                ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
            );
            
            assertThat(response.isSuccess())
                .as("Should successfully join channel: " + channelId)
                .isTrue();
            assertThat(response.getChannelId()).isEqualTo(channelId);
        }
        
        client.disconnect();
    }

    @Test
    @Order(4)
    @DisplayName("Channel join with password should work")
    void testChannelJoinWithPassword() throws Exception {
        helper.registerClient("PasswordClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("PasswordClient", "password").get(5, TimeUnit.SECONDS);

        ChannelActionPacket joinAction = client.channelAction(
            ChannelAction.JOIN,
            "private-channel",
            "channel-password"
        );
        client.sendPacket(joinAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.JOIN);
        
        client.disconnect();
    }

    @Test
    @Order(5)
    @DisplayName("Unauthenticated client should not perform channel actions")
    void testUnauthenticatedChannelAction() throws Exception {
        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        // Do NOT authenticate
        
        ChannelActionPacket joinAction = new ChannelActionPacket(ChannelAction.JOIN, "global");
        client.sendPacket(joinAction);
        
        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo("NC-401");
        
        client.disconnect();
    }

    @Test
    @Order(6)
    @DisplayName("Channel create action should work")
    void testChannelCreate() throws Exception {
        helper.registerClient("CreateClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("CreateClient", "password").get(5, TimeUnit.SECONDS);

        ChannelActionPacket createAction = client.channelAction(ChannelAction.CREATE, "new-channel");
        createAction.addExtra("displayName", "New Channel");
        client.sendPacket(createAction);

        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.CREATE);
        // CREATE always mints a fresh PRIVATE channel with an NC-XXXX id — the
        // request channelId is a display hint, not the allocated id.
        assertThat(response.getChannelId()).isNotBlank();
        assertThat(response.getExtra()).containsKey("displayName");

        client.disconnect();
    }

    @Test
    @Order(7)
    @DisplayName("Channel delete action should work")
    void testChannelDelete() throws Exception {
        helper.registerClient("DeleteClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("DeleteClient", "password").get(5, TimeUnit.SECONDS);

        // CREATE allocates a fresh PRIVATE channel — only that allocated id is
        // deletable (GLOBAL config channels are rejected by the handler).
        ChannelActionPacket createAction = client.channelAction(ChannelAction.CREATE, "temp-channel");
        client.sendPacket(createAction);
        ChannelActionResponsePacket createResponse = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        assertThat(createResponse.isSuccess()).isTrue();
        String allocatedChannelId = createResponse.getChannelId();
        assertThat(allocatedChannelId).isNotBlank();

        // Then delete the channel we just created (owner is auto-granted
        // channel-admin, so DELETE is authorised for our playerId).
        ChannelActionPacket deleteAction = client.channelAction(ChannelAction.DELETE, allocatedChannelId);
        client.sendPacket(deleteAction);

        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.DELETE);

        client.disconnect();
    }

    @Test
    @Order(8)
    @DisplayName("Channel invite action should work")
    void testChannelInvite() throws Exception {
        helper.registerClient("InviteClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("InviteClient", "password").get(5, TimeUnit.SECONDS);

        // INVITE only works on PRIVATE channels whose creator is channel-admin.
        // Spin one up so the invite has a valid scope + permission context.
        ChannelActionPacket createAction = client.channelAction(ChannelAction.CREATE, "invite-base");
        client.sendPacket(createAction);
        ChannelActionResponsePacket createResponse = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        assertThat(createResponse.isSuccess()).isTrue();
        String privateChannelId = createResponse.getChannelId();
        assertThat(privateChannelId).isNotBlank();

        ChannelActionPacket inviteAction = client.channelAction(ChannelAction.INVITE, privateChannelId);
        client.sendPacket(inviteAction);

        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.INVITE);
        // The invite code is what another player feeds into ACCEPT.
        assertThat(response.getExtra()).containsKey("code");

        client.disconnect();
    }

    @Test
    @Order(9)
    @DisplayName("Channel accept action should work")
    void testChannelAccept() throws Exception {
        helper.registerClient("AcceptClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("AcceptClient", "password").get(5, TimeUnit.SECONDS);

        // Spin up a private channel and mint an invitation code for it.
        ChannelActionPacket createAction = client.channelAction(ChannelAction.CREATE, "accept-base");
        client.sendPacket(createAction);
        ChannelActionResponsePacket createResponse = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        assertThat(createResponse.isSuccess()).isTrue();
        String privateChannelId = createResponse.getChannelId();

        ChannelActionPacket inviteAction = client.channelAction(ChannelAction.INVITE, privateChannelId);
        client.sendPacket(inviteAction);
        ChannelActionResponsePacket inviteResponse = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );
        assertThat(inviteResponse.isSuccess()).isTrue();
        String inviteCode = inviteResponse.getExtra("code");
        assertThat(inviteCode).isNotBlank();

        // ACCEPT treats packet.channelId as the invite code. Use a *different*
        // playerId than the channel owner so the flow models a real invitee.
        ChannelActionPacket acceptAction = new ChannelActionPacket(ChannelAction.ACCEPT, inviteCode);
        acceptAction.addExtra("playerId", java.util.UUID.randomUUID().toString());
        client.sendPacket(acceptAction);

        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAction()).isEqualTo(ChannelAction.ACCEPT);

        client.disconnect();
    }

    @Test
    @Order(10)
    @DisplayName("Channel create response should include server-populated extra data")
    void testChannelActionExtraData() throws Exception {
        helper.registerClient("ExtraDataClient", "password");

        IntegrationTestHelper.TestClient client = helper.createClient(PlatformType.BUKKIT);
        client.connect().get(5, TimeUnit.SECONDS);
        client.authenticate("ExtraDataClient", "password").get(5, TimeUnit.SECONDS);

        // CREATE returns a server-populated extra map (displayName / password /
        // passwordGenerated) so asserting on a known key nails down the wire
        // contract instead of the previous `isNotNull()` tautology.
        ChannelActionPacket createAction = client.channelAction(ChannelAction.CREATE, "data-channel");
        createAction.addExtra("displayName", "Data Channel");
        client.sendPacket(createAction);

        ChannelActionResponsePacket response = client.waitForPacket(
            ChannelActionResponsePacket.class, 5, TimeUnit.SECONDS
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getExtra())
                .containsKey("displayName")
                .containsKey("password")
                .containsKey("passwordGenerated");
        assertThat(response.getExtra("displayName")).isEqualTo("Data Channel");

        client.disconnect();
    }
}
